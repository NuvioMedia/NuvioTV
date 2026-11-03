#[derive(Debug, Clone, Copy, PartialEq)]
pub enum TrackKind {
    Video,
    Audio,
    Subtitle,
    Other,
}

/// A track of the file; `codec` uses Matroska codec IDs for MP4 tracks too.
#[derive(Debug, Clone)]
pub struct Track {
    pub number: u64,
    pub kind: TrackKind,
    pub codec: String,
    pub private: Vec<u8>,
    pub name: Option<String>,
    pub language: Option<String>,
    pub enabled: bool,
    pub default: bool,
    pub forced: bool,
    pub hearing_impaired: bool,
    pub commentary: bool,
    /// Bytes every frame of the track begins with, stripped from its blocks.
    pub header_strip: Vec<u8>,
    pub video: Option<Video>,
}

/// Colour codes are ITU-T H.273; 2 is unspecified.
#[derive(Debug, Clone)]
pub struct Video {
    pub width: u64,
    pub height: u64,
    pub display_width: u64,
    pub display_height: u64,
    pub matrix: u64,
    pub range: u64,
    pub transfer: u64,
    pub primaries: u64,
    pub dovi_profile: Option<u8>,
    pub dovi_compatibility: Option<u8>,
}

impl Track {
    pub(crate) fn new(number: u64, kind: TrackKind, codec: String) -> Track {
        Track {
            number,
            kind,
            codec,
            private: Vec::new(),
            name: None,
            language: None,
            enabled: true,
            default: true,
            forced: false,
            hearing_impaired: false,
            commentary: false,
            header_strip: Vec::new(),
            video: None,
        }
    }
}

impl Video {
    pub(crate) fn new(width: u64, height: u64) -> Video {
        Video {
            width,
            height,
            display_width: width,
            display_height: height,
            matrix: 2,
            range: 0,
            transfer: 2,
            primaries: 2,
            dovi_profile: None,
            dovi_compatibility: None,
        }
    }

    /// Dolby Vision configuration record (dvcC/dvvC).
    pub(crate) fn set_dovi(&mut self, record: &[u8]) {
        if record.len() >= 5 {
            self.dovi_profile = Some(record[2] >> 1);
            self.dovi_compatibility = Some(record[4] >> 4);
        }
    }
}
