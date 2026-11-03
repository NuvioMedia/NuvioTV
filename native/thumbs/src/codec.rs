#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Kind {
    H264,
    Hevc,
    Av1,
    Vp9,
    Mpeg2,
}

impl Kind {
    /// From a Matroska codec ID, which MP4 tracks use too.
    pub fn from_codec_id(id: &str) -> Option<Kind> {
        Some(match id {
            "V_MPEG4/ISO/AVC" => Kind::H264,
            "V_MPEGH/ISO/HEVC" => Kind::Hevc,
            "V_AV1" => Kind::Av1,
            "V_VP9" => Kind::Vp9,
            "V_MPEG2" | "V_MPEG1" => Kind::Mpeg2,
            _ => return None,
        })
    }

    pub fn decoder(self) -> &'static str {
        match self {
            Kind::H264 => "h264",
            Kind::Hevc => "hevc",
            Kind::Av1 => "libdav1d",
            Kind::Vp9 => "vp9",
            Kind::Mpeg2 => "mpeg2video",
        }
    }
}

/// Turns container frames into packets that carry their parameter sets in band.
#[derive(Clone)]
pub struct Config {
    nal_len: usize,
    prefix: Vec<u8>,
}

const START: [u8; 4] = [0, 0, 0, 1];

fn u16be(b: &[u8], i: usize) -> Option<usize> {
    Some(u16::from_be_bytes([*b.get(i)?, *b.get(i + 1)?]) as usize)
}

impl Config {
    pub fn new(kind: Kind, private: &[u8]) -> Result<Config, String> {
        let mut prefix = Vec::new();
        let mut nal_len = 0;
        match kind {
            Kind::H264 if private.len() > 6 && private[0] == 1 => {
                nal_len = (private[4] & 3) as usize + 1;
                let mut i = 5;
                for group in 0..2 {
                    let count = if group == 0 {
                        private[i] & 0x1F
                    } else {
                        private[i]
                    } as usize;
                    i += 1;
                    for _ in 0..count {
                        let len = u16be(private, i).ok_or("short avcC")?;
                        prefix.extend_from_slice(&START);
                        prefix.extend_from_slice(
                            private.get(i + 2..i + 2 + len).ok_or("short avcC")?,
                        );
                        i += 2 + len;
                    }
                    if i >= private.len() {
                        break;
                    }
                }
            }
            // Without arrays when the parameter sets travel in band (hev1, dvhe).
            Kind::Hevc if private.len() >= 23 => {
                nal_len = (private[21] & 3) as usize + 1;
                let arrays = private[22] as usize;
                let mut i = 23;
                for _ in 0..arrays {
                    let count = u16be(private, i + 1).ok_or("short hvcC")?;
                    i += 3;
                    for _ in 0..count {
                        let len = u16be(private, i).ok_or("short hvcC")?;
                        prefix.extend_from_slice(&START);
                        prefix.extend_from_slice(
                            private.get(i + 2..i + 2 + len).ok_or("short hvcC")?,
                        );
                        i += 2 + len;
                    }
                }
            }
            // av1C is 4 bytes, then the sequence header OBU(s).
            Kind::Av1 if private.len() > 4 => prefix.extend_from_slice(&private[4..]),
            Kind::Mpeg2 => prefix.extend_from_slice(private),
            _ => {}
        }
        Ok(Config { nal_len, prefix })
    }

    pub fn packet(&self, frame: &[u8]) -> Result<Vec<u8>, String> {
        let mut out = Vec::with_capacity(self.prefix.len() + frame.len() + 64);
        out.extend_from_slice(&self.prefix);
        if self.nal_len == 0 {
            out.extend_from_slice(frame);
            return Ok(out);
        }
        let mut i = 0;
        while i + self.nal_len <= frame.len() {
            let len = frame[i..i + self.nal_len]
                .iter()
                .fold(0usize, |v, &b| (v << 8) | b as usize);
            i += self.nal_len;
            let nal = frame
                .get(i..i + len)
                .ok_or("NAL past the end of the frame")?;
            out.extend_from_slice(&START);
            out.extend_from_slice(nal);
            i += len;
        }
        Ok(out)
    }
}
