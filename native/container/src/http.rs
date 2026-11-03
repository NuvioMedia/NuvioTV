use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::error::{Error, Result};

#[derive(Clone)]
pub struct Options {
    pub headers: Vec<(String, String)>,
    pub timeout: Duration,
    pub connect_timeout: Duration,
    pub max_bytes: Option<u64>,
    pub max_requests: Option<u32>,
    pub deadline: Option<Instant>,
    /// Trusted roots as PEM, replacing the bundled ones.
    pub ca_pem: Option<Vec<u8>>,
}

impl Default for Options {
    fn default() -> Self {
        Options {
            headers: Vec::new(),
            timeout: Duration::from_secs(30),
            connect_timeout: Duration::from_secs(10),
            max_bytes: None,
            max_requests: None,
            deadline: None,
            ca_pem: None,
        }
    }
}

/// Range reads of one URL, or of a local path.
pub struct RangeReader {
    agent: ureq::Agent,
    url: String,
    /// The URL given, which `url` follows redirects from.
    origin: String,
    options: Options,
    last: Option<(u64, Vec<u8>)>,
    pub size: Option<u64>,
    pub requests: u32,
    pub bytes: u64,
    pub waited: Duration,
}

impl RangeReader {
    pub fn new(url: &str, options: Options) -> Result<RangeReader> {
        let mut config = ureq::Agent::config_builder()
            .timeout_global(Some(options.timeout))
            .timeout_connect(Some(options.connect_timeout))
            .http_status_as_error(false);
        if let Some(pem) = &options.ca_pem {
            let certs: Vec<_> = ureq::tls::parse_pem(pem)
                .filter_map(|item| match item {
                    Ok(ureq::tls::PemItem::Certificate(c)) => Some(c),
                    _ => None,
                })
                .collect();
            if certs.is_empty() {
                return Err(Error::Malformed("CA bundle"));
            }
            let roots = ureq::tls::RootCerts::Specific(Arc::new(certs));
            config = config.tls_config(ureq::tls::TlsConfig::builder().root_certs(roots).build());
        }
        Ok(RangeReader {
            agent: config.build().into(),
            url: url.to_string(),
            origin: url.to_string(),
            options,
            last: None,
            size: None,
            requests: 0,
            bytes: 0,
            waited: Duration::ZERO,
        })
    }

    /// Another reader of the same file, on this one's connections.
    pub fn fork(&self) -> RangeReader {
        RangeReader {
            agent: self.agent.clone(),
            url: self.url.clone(),
            origin: self.origin.clone(),
            options: self.options.clone(),
            last: None,
            size: self.size,
            requests: 0,
            bytes: 0,
            waited: Duration::ZERO,
        }
    }

    /// Up to `len` bytes from `start`; fewer only at the end of the file.
    pub fn read(&mut self, start: u64, len: u64) -> Result<Vec<u8>> {
        let mut len = len;
        if let Some(size) = self.size {
            if start >= size {
                return Ok(Vec::new());
            }
            len = len.min(size - start);
        }
        if let Some((at, buf)) = &self.last
            && start >= *at
            && start + len <= at + buf.len() as u64
        {
            let from = (start - at) as usize;
            return Ok(buf[from..from + len as usize].to_vec());
        }
        let o = &self.options;
        if o.max_requests.is_some_and(|m| self.requests >= m)
            || o.max_bytes.is_some_and(|m| self.bytes + len > m)
            || o.deadline.is_some_and(|d| Instant::now() >= d)
        {
            return Err(Error::Budget);
        }
        let t = Instant::now();
        let body = if self.url.starts_with("http://") || self.url.starts_with("https://") {
            self.fetch(start, len)?
        } else {
            self.read_file(start, len)?
        };
        self.requests += 1;
        self.bytes += body.len() as u64;
        self.waited += t.elapsed();
        self.last = Some((start, body.clone()));
        Ok(body)
    }

    /// A redirect's target can expire, so a failed read of one starts again from the URL given.
    fn fetch(&mut self, start: u64, len: u64) -> Result<Vec<u8>> {
        match self.fetch_from(start, len) {
            Err(_) if self.url != self.origin => {
                self.url = self.origin.clone();
                self.fetch_from(start, len)
            }
            read => read,
        }
    }

    fn fetch_from(&mut self, start: u64, len: u64) -> Result<Vec<u8>> {
        let mut request = self
            .agent
            .get(&self.url)
            .header("Range", format!("bytes={start}-{}", start + len - 1));
        for (k, v) in &self.options.headers {
            request = request.header(k, v);
        }
        let response = request.call().map_err(|e| Error::Http(e.to_string()))?;
        if response.status() != 206 {
            return Err(Error::Http(format!("range answered {}", response.status())));
        }
        if self.size.is_none() {
            self.size = response
                .headers()
                .get("content-range")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.rsplit('/').next())
                .and_then(|v| v.parse().ok());
        }
        let final_url = ureq::ResponseExt::get_uri(&response).to_string();
        let body = response
            .into_body()
            .into_with_config()
            // The reader fails on the read that finds the end, so a body of exactly `len` needs one more.
            .limit(len + 1)
            .read_to_vec()
            .map_err(|e| Error::Http(e.to_string()))?;
        self.url = final_url;
        Ok(body)
    }

    fn read_file(&mut self, start: u64, len: u64) -> Result<Vec<u8>> {
        use std::io::{Read, Seek, SeekFrom};
        let io = |e: std::io::Error| Error::Http(e.to_string());
        let mut f = std::fs::File::open(&self.url).map_err(io)?;
        self.size = Some(f.metadata().map_err(io)?.len());
        f.seek(SeekFrom::Start(start)).map_err(io)?;
        let mut body = Vec::with_capacity(len as usize);
        f.take(len).read_to_end(&mut body).map_err(io)?;
        Ok(body)
    }
}
