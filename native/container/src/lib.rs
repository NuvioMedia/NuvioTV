pub mod ebml;
mod error;
pub mod http;
mod keyframes;
pub mod mkv;
pub mod mp4;
mod subtitles;
mod track;

pub use error::{Error, Result};
pub use http::{Options, RangeReader};
pub use keyframes::{Container, Keyframe, Keyframes};
pub use subtitles::{SubtitleTimeline, index_options, subtitle_timelines};
pub use track::{Track, TrackKind, Video};

/// The video keyframes of a Matroska/WebM or MP4/MOV file.
pub fn keyframes(r: &mut RangeReader) -> Result<Keyframes> {
    let head = r.read(0, mkv::HEAD)?;
    if head.starts_with(&ebml::id::EBML.to_be_bytes()) {
        mkv::Segment::open(r)?.keyframes(r)
    } else {
        mp4::keyframes(r)
    }
}
