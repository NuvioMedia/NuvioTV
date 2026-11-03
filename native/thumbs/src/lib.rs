pub mod codec;
pub mod decode;
pub mod dovi;
pub mod hevc;
mod lazy;
pub mod pool;
pub mod priority;
pub mod scale;
mod session;

pub use decode::{ffmpeg_version, library_dirs};
pub use lazy::{Event, Thumbnails};
pub use session::Session;

use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;

use aiostreams_container::{Keyframes, Options, RangeReader, Video};

#[derive(Debug)]
pub enum Error {
    Container(aiostreams_container::Error),
    Decode(String),
}

impl From<aiostreams_container::Error> for Error {
    fn from(e: aiostreams_container::Error) -> Self {
        Error::Container(e)
    }
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        match self {
            Error::Container(e) => e.fmt(f),
            Error::Decode(e) => write!(f, "decode: {e}"),
        }
    }
}

impl std::error::Error for Error {}

impl Error {
    /// A request that failed and may work later, rather than a file without thumbnails.
    pub fn is_transient(&self) -> bool {
        matches!(self, Error::Container(aiostreams_container::Error::Http(_)))
    }
}

pub type Result<T> = std::result::Result<T, Error>;

/// A named thread, so profiles tell the stages apart.
fn spawn<T: Send + 'static>(
    name: &str,
    f: impl FnOnce() -> T + Send + 'static,
) -> std::thread::JoinHandle<T> {
    std::thread::Builder::new()
        .name(name.into())
        .spawn(f)
        .expect("spawning a thread")
}

#[derive(Clone)]
pub struct Settings {
    pub width: usize,
    /// Decoders for the strips of a split picture, and slice threads otherwise.
    pub workers: usize,
    pub samples: usize,
    pub quality: u8,
    /// How long a [`Session`] keeps its decoders after the last thumbnail.
    pub keep_decoders: Duration,
    /// Thumbnails a [`Session`] keeps for keyframes asked for again.
    pub cache: usize,
    /// Keyframes a [`Session`] fetches at once, so a link slow to answer keeps up with the pointer.
    pub fetches: usize,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            width: 320,
            workers: 2,
            samples: 2,
            quality: 75,
            keep_decoders: Duration::from_secs(10),
            cache: 256,
            fetches: 3,
        }
    }
}

pub struct Thumbnail {
    pub time_ms: u64,
    pub width: usize,
    pub height: usize,
    pub jpeg: Vec<u8>,
}

/// Matrix, range, transfer and primaries: what the bitstream signals, else what the container says.
pub fn color_codes(signalled: Option<[i32; 4]>, container: [u64; 4]) -> [i32; 4] {
    let [m, r, t, p] = signalled.unwrap_or([2, 0, 2, 2]);
    let known = |code: i32, fallback: u64| {
        if code != 0 && code != 2 {
            code
        } else {
            fallback as i32
        }
    };
    [
        known(m, container[0]),
        if r != 0 { r } else { container[1] as i32 },
        known(t, container[2]),
        known(p, container[3]),
    ]
}

/// The keyframes of one file as decoder packets.
pub struct Source {
    reader: RangeReader,
    keyframes: Arc<Keyframes>,
    kind: codec::Kind,
    config: codec::Config,
    guess: u64,
    sizes: VecDeque<u64>,
}

impl Source {
    pub fn open(url: &str, options: Options) -> Result<Source> {
        let mut reader = RangeReader::new(url, options)?;
        let keyframes = aiostreams_container::keyframes(&mut reader)?;
        let track = &keyframes.track;
        let kind = codec::Kind::from_codec_id(&track.codec)
            .ok_or_else(|| Error::Decode(format!("codec {}", track.codec)))?;
        let config = codec::Config::new(kind, &track.private).map_err(Error::Decode)?;
        if track.video.is_none() {
            return Err(Error::Decode("no video dimensions".into()));
        }
        let byterate = reader.size.unwrap_or(0) / (keyframes.duration_ms / 1000).max(1);
        let guess = (byterate / 12).clamp(96 * 1024, 2 << 20);
        Ok(Source {
            reader,
            keyframes: Arc::new(keyframes),
            kind,
            config,
            guess,
            sizes: VecDeque::new(),
        })
    }

    pub fn keyframes(&self) -> &Arc<Keyframes> {
        &self.keyframes
    }

    /// Another source of the same keyframes, for fetching several at once.
    pub fn fork(&self) -> Source {
        Source {
            reader: self.reader.fork(),
            keyframes: self.keyframes.clone(),
            kind: self.kind,
            config: self.config.clone(),
            guess: self.guess,
            sizes: self.sizes.clone(),
        }
    }

    pub fn packet(&mut self, index: usize) -> Result<Vec<u8>> {
        let frame = self.keyframes.frame(&mut self.reader, index, self.guess)?;
        self.learn(frame.len() as u64);
        self.config.packet(&frame).map_err(Error::Decode)
    }

    /// Sizes the next read from recent keyframes: rarely a second request, little read in vain.
    fn learn(&mut self, size: u64) {
        if self.sizes.len() == 32 {
            self.sizes.pop_front();
        }
        self.sizes.push_back(size);
        if self.sizes.len() < 4 {
            self.guess = self.guess.max(size * 5 / 4);
            return;
        }
        let mut sorted: Vec<u64> = self.sizes.iter().copied().collect();
        sorted.sort_unstable();
        self.guess = sorted[sorted.len() * 3 / 4] * 7 / 5;
    }

    /// Requests made and bytes read so far.
    pub fn reads(&self) -> (u32, u64) {
        (self.reader.requests, self.reader.bytes)
    }

    pub fn renderer(&self, settings: Settings) -> Renderer {
        Renderer::new(
            self.kind,
            self.keyframes.track.video.clone().unwrap(),
            settings,
        )
    }
}

#[derive(Clone, Copy, PartialEq)]
enum ColorKey {
    Codes([i32; 4]),
    Dovi(dovi::Matrices),
}

/// Decodes packets into JPEG thumbnails, keeping its decoders until released.
pub struct Renderer {
    kind: codec::Kind,
    video: Video,
    settings: Settings,
    height: usize,
    decoder: Option<decode::Decoder>,
    strips: Option<pool::Pool>,
    color: Option<(ColorKey, scale::Color)>,
    /// The last Dolby Vision profile 5 RPU, for frames that refer back to it.
    dovi: Option<dovi::Rpu>,
}

impl Renderer {
    pub fn new(kind: codec::Kind, video: Video, settings: Settings) -> Renderer {
        let height = ((settings.width as u64 * video.display_height / video.display_width.max(1))
            as usize)
            .div_ceil(2)
            * 2;
        Renderer {
            kind,
            video,
            settings,
            height,
            decoder: None,
            strips: None,
            color: None,
            dovi: None,
        }
    }

    /// The JPEG of one keyframe packet, `width` x `height`.
    pub fn render(&mut self, packet: &[u8]) -> Result<Vec<u8>> {
        let (w, h, s) = (self.settings.width, self.height, self.settings.samples);
        if self.kind == codec::Kind::Hevc
            && self.video.dovi_profile == Some(5)
            && let Some(rpu) = dovi::Rpu::find(packet, self.dovi.as_ref())
        {
            self.dovi = Some(rpu);
        }
        let acc = match (self.kind == codec::Kind::Hevc && self.settings.workers > 1)
            .then(|| hevc::split(packet))
            .flatten()
        {
            Some(split) => {
                let workers = self.settings.workers;
                let pool = self.strips.get_or_insert_with(|| {
                    pool::Pool::new(workers, codec::Kind::Hevc.decoder(), true)
                });
                pool.run(split, w, h, s).map_err(Error::Decode)?
            }
            None => {
                if self.decoder.is_none() {
                    let options = decode::Options {
                        threads: self.settings.workers as i32,
                        skip_loop_filter: true,
                        fast: false,
                    };
                    self.decoder = Some(
                        decode::Decoder::open(self.kind.decoder(), &options)
                            .map_err(Error::Decode)?,
                    );
                }
                let f = self
                    .decoder
                    .as_mut()
                    .unwrap()
                    .decode(packet)
                    .map_err(Error::Decode)?;
                scale::Accum::whole(f, w, h, s).map_err(Error::Decode)?
            }
        };
        let v = &self.video;
        let key = match &self.dovi {
            Some(rpu) => ColorKey::Dovi(rpu.matrices),
            None => ColorKey::Codes(color_codes(
                acc.signalled,
                [v.matrix, v.range, v.transfer, v.primaries],
            )),
        };
        if self.color.as_ref().is_none_or(|(k, _)| *k != key) {
            let color = match key {
                ColorKey::Dovi(m) => scale::Color::dovi(m),
                ColorKey::Codes([m, r, t, p]) => scale::Color::new(m, r, t, p, v.height as i32),
            };
            self.color = Some((key, color));
        }
        let color = &self.color.as_ref().unwrap().1;
        let reshape = self.dovi.as_ref().map(|r| &*r.reshape);
        Ok(scale::jpeg(
            &acc.ycbcr(color, reshape),
            w,
            h,
            self.settings.quality,
        ))
    }

    pub fn width(&self) -> usize {
        self.settings.width
    }

    pub fn height(&self) -> usize {
        self.height
    }

    /// Frees the decoders until the next thumbnail.
    pub fn release(&mut self) {
        self.decoder = None;
        self.strips = None;
    }
}

/// Fetches and renders one keyframe at a time on the caller's thread.
pub struct Thumbnailer {
    source: Source,
    renderer: Renderer,
}

impl Thumbnailer {
    pub fn open(url: &str, options: Options, settings: Settings) -> Result<Thumbnailer> {
        let source = Source::open(url, options)?;
        let renderer = source.renderer(settings);
        Ok(Thumbnailer { source, renderer })
    }

    pub fn keyframes(&self) -> &Keyframes {
        self.source.keyframes()
    }

    /// The thumbnail of the keyframe at or before `ms`.
    pub fn get(&mut self, ms: u64) -> Result<Thumbnail> {
        let index = self.source.keyframes().at(ms);
        let packet = self.source.packet(index)?;
        let jpeg = self.renderer.render(&packet)?;
        Ok(Thumbnail {
            time_ms: self.source.keyframes().entries[index].time_ms,
            width: self.renderer.width(),
            height: self.renderer.height(),
            jpeg,
        })
    }

    pub fn release(&mut self) {
        self.renderer.release();
    }
}
