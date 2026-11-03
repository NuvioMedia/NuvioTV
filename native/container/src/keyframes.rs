use crate::error::Result;
use crate::http::RangeReader;
use crate::track::Track;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Container {
    Matroska,
    Mp4,
}

/// A video keyframe: Matroska gives its cluster (and the block's place in it), MP4 its exact range.
#[derive(Debug, Clone, Copy)]
pub struct Keyframe {
    pub time_ms: u64,
    pub offset: u64,
    pub relative: Option<u64>,
    pub size: Option<u64>,
}

pub struct Keyframes {
    pub container: Container,
    pub track: Track,
    pub duration_ms: u64,
    pub(crate) scale_ns: u64,
    pub entries: Vec<Keyframe>,
}

impl Keyframes {
    /// The keyframe at or before `ms`.
    pub fn at(&self, ms: u64) -> usize {
        self.entries
            .partition_point(|k| k.time_ms <= ms)
            .saturating_sub(1)
    }

    /// The frame data of a keyframe, reading `guess` bytes when its size isn't known up front.
    pub fn frame(&self, r: &mut RangeReader, index: usize, guess: u64) -> Result<Vec<u8>> {
        let e = self.entries[index];
        match e.size {
            Some(size) => r.read(e.offset, size),
            None => crate::mkv::keyframe(self, r, &e, guess),
        }
    }
}
