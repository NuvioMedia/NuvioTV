use std::time::{Duration, Instant};

use crate::mkv::Segment;
use crate::mp4::{boxes, child, path, u16be, u32be, u64be};
use crate::{Error, Options, RangeReader, Result, TrackKind, ebml, mkv, mp4};

/// How long a line whose index entry has no duration shows, at most, before the next.
const UNTIMED_MS: u64 = 4_000;
/// A PGS entry this short clears the line before it.
const PGS_CLEAR_MS: u64 = 100;
/// Lines outside these are gaps or silences a PGS labelling took for lines.
const PGS_SHORTEST_MS: u64 = 250;
const PGS_LONGEST_MS: u64 = 12_000;
/// What a labelling pays for an odd line, a line replaced without a clear, and a doubled clear.
const PGS_ODD_LINE: u32 = 4;
const PGS_REPLACED: u32 = 1;
const PGS_CLEARED_TWICE: u32 = 3;

/// A few requests for the head, the tracks and the index, or the players' own reading takes over.
pub fn index_options() -> Options {
    Options {
        timeout: Duration::from_secs(4),
        connect_timeout: Duration::from_secs(3),
        max_bytes: Some(24 << 20),
        max_requests: Some(16),
        deadline: Some(Instant::now() + Duration::from_secs(7)),
        ..Options::default()
    }
}

/// One of the file's subtitle tracks and when its lines show, in milliseconds.
#[derive(Debug, Clone)]
pub struct SubtitleTimeline {
    pub number: u64,
    pub codec: String,
    pub language: Option<String>,
    pub lines: Vec<(u64, u64)>,
}

/// Every subtitle track of a Matroska or MP4 file in file order, with the lines its index lists.
/// A track the index leaves out comes back empty.
pub fn subtitle_timelines(r: &mut RangeReader) -> Result<Vec<SubtitleTimeline>> {
    let head = r.read(0, mkv::HEAD)?;
    if head.starts_with(&ebml::id::EBML.to_be_bytes()) {
        matroska(r)
    } else {
        mp4_timelines(r)
    }
}

/// Muxers index every subtitle frame, most with its duration.
fn matroska(r: &mut RangeReader) -> Result<Vec<SubtitleTimeline>> {
    let segment = Segment::open(r)?;
    let mut tracks: Vec<SubtitleTimeline> = segment
        .tracks
        .iter()
        .filter(|t| t.kind == TrackKind::Subtitle)
        .map(|t| SubtitleTimeline {
            number: t.number,
            codec: t.codec.clone(),
            language: t.language.clone(),
            lines: Vec::new(),
        })
        .collect();
    if tracks.is_empty() {
        return Ok(tracks);
    }
    let cues = segment.cues(r)?;
    for track in &mut tracks {
        let mut points: Vec<(u64, Option<u64>)> = cues
            .iter()
            .filter(|c| c.track == track.number)
            .map(|c| (segment.ms(c.time), c.duration.map(|d| segment.ms(d))))
            .collect();
        points.sort_by_key(|p| p.0);
        points.dedup_by_key(|p| p.0);
        track.lines = if track.codec == "S_HDMV/PGS" {
            pgs_lines(&points)
        } else {
            timed_lines(&points)
        };
    }
    Ok(tracks)
}

fn timed_lines(points: &[(u64, Option<u64>)]) -> Vec<(u64, u64)> {
    points
        .iter()
        .enumerate()
        .filter_map(|(i, &(start, duration))| {
            let end = match duration {
                Some(duration) => start + duration,
                None => (start + UNTIMED_MS).min(points.get(i + 1).map_or(u64::MAX, |n| n.0)),
            };
            (end > start).then_some((start, end))
        })
        .collect()
}

/// PGS indexes the display set that shows each line and the one that clears it. Some muxers give
/// the clearing ones a frame's duration; otherwise each entry is labelled one or the other, the
/// labelling with the fewest odd lines and replaced or doubled clears winning.
fn pgs_lines(points: &[(u64, Option<u64>)]) -> Vec<(u64, u64)> {
    let clears = points
        .iter()
        .filter(|p| p.1.is_some_and(|d| d <= PGS_CLEAR_MS))
        .count();
    if clears * 10 >= points.len() * 3 {
        return points
            .iter()
            .filter_map(|&(start, d)| d.filter(|&d| d > PGS_CLEAR_MS).map(|d| (start, start + d)))
            .collect();
    }
    let t: Vec<u64> = points.iter().map(|p| p.0).collect();
    let n = t.len();
    if n < 2 {
        return Vec::new();
    }
    let line = |i: usize| match t.get(i + 1) {
        Some(next) if !(PGS_SHORTEST_MS..=PGS_LONGEST_MS).contains(&(next - t[i])) => PGS_ODD_LINE,
        _ => 0,
    };
    // The cheapest labelling of entries up to i with entry i showing a line or clearing one, and
    // whether the entry before it showed one.
    let (mut show, mut clear) = (vec![0u32; n], vec![0u32; n]);
    let (mut show_after_show, mut clear_after_show) = (vec![false; n], vec![false; n]);
    show[0] = line(0);
    clear[0] = PGS_CLEARED_TWICE;
    for i in 1..n {
        let replaced = show[i - 1] + PGS_REPLACED;
        (show[i], show_after_show[i]) = if replaced < clear[i - 1] {
            (replaced, true)
        } else {
            (clear[i - 1], false)
        };
        show[i] += line(i);
        let cleared_twice = clear[i - 1] + PGS_CLEARED_TWICE;
        (clear[i], clear_after_show[i]) = if show[i - 1] <= cleared_twice {
            (show[i - 1], true)
        } else {
            (cleared_twice, false)
        };
    }
    let mut shows = vec![false; n];
    let mut is_show = show[n - 1] < clear[n - 1];
    for i in (0..n).rev() {
        shows[i] = is_show;
        is_show = if is_show {
            show_after_show[i]
        } else {
            clear_after_show[i]
        };
    }
    (0..n - 1)
        .filter(|&i| shows[i])
        .map(|i| (t[i], t[i + 1]))
        .collect()
}

/// The sample tables list every subtitle sample, and the gaps between lines as empty ones.
fn mp4_timelines(r: &mut RangeReader) -> Result<Vec<SubtitleTimeline>> {
    let moov = mp4::moov(r)?;
    if child(&moov, b"mvex").is_some() {
        return Err(Error::Unsupported("fragmented MP4".into()));
    }
    let movie_scale = child(&moov, b"mvhd")
        .and_then(|h| u32be(h, if h.first() == Some(&1) { 20 } else { 12 }))
        .filter(|&s| s > 0)
        .unwrap_or(1000);
    let traks: Vec<&[u8]> = boxes(&moov)
        .filter(|(kind, _)| kind == b"trak")
        .map(|(_, body)| body)
        .collect();
    // Players list chapter titles as chapters, not subtitles.
    let chapters: Vec<u64> = traks
        .iter()
        .filter_map(|trak| child(trak, b"tref"))
        .flat_map(|tref| boxes(tref).filter(|(kind, _)| kind == b"chap"))
        .flat_map(|(_, ids)| (0..ids.len() / 4).filter_map(|i| u32be(ids, 4 * i)))
        .collect();
    let mut tracks = Vec::new();
    for trak in traks {
        let Some(mdia) = child(trak, b"mdia") else {
            continue;
        };
        let handler = child(mdia, b"hdlr").and_then(|h| h.get(8..12));
        if !matches!(handler, Some(b"sbtl" | b"text" | b"subt" | b"subp")) {
            continue;
        }
        let number = child(trak, b"tkhd")
            .and_then(|t| u32be(t, if t.first() == Some(&1) { 20 } else { 12 }))
            .unwrap_or(0);
        if chapters.contains(&number) {
            continue;
        }
        let entry = path(mdia, &[b"minf", b"stbl", b"stsd"])
            .and_then(|s| s.get(8..))
            .and_then(|s| boxes(s).next())
            .map(|(kind, _)| kind);
        // An empty text sample is its two length bytes, an empty WebVTT one its 8-byte vtte box.
        let empty = match entry.as_ref() {
            Some(b"tx3g" | b"text") => Some(2),
            Some(b"wvtt") => Some(8),
            _ => None,
        };
        let mdhd = child(mdia, b"mdhd");
        tracks.push(SubtitleTimeline {
            number,
            codec: entry.map_or_else(String::new, |k| String::from_utf8_lossy(&k).into_owned()),
            language: mdhd.and_then(language),
            lines: empty
                .and_then(|empty| sample_lines(trak, mdia, movie_scale, empty))
                .unwrap_or_default(),
        });
    }
    Ok(tracks)
}

/// The packed ISO 639-2 code of an mdhd box.
fn language(mdhd: &[u8]) -> Option<String> {
    let packed = u16be(mdhd, if mdhd.first() == Some(&1) { 32 } else { 20 })?;
    let code: String = [10, 5, 0]
        .iter()
        .map(|shift| char::from(((packed >> shift) & 0x1f) as u8 + 0x60))
        .collect();
    (code.chars().all(|c| c.is_ascii_lowercase()) && code != "und").then_some(code)
}

fn sample_lines(trak: &[u8], mdia: &[u8], movie_scale: u64, empty: u64) -> Option<Vec<(u64, u64)>> {
    let mdhd = child(mdia, b"mdhd")?;
    let scale = u32be(mdhd, if mdhd[0] == 1 { 20 } else { 12 }).filter(|&s| s > 0)?;
    let stbl = path(mdia, &[b"minf", b"stbl"])?;
    let stsz = child(stbl, b"stsz")?;
    let (fixed, count) = (u32be(stsz, 4)?, u32be(stsz, 8)?);
    let size = |s: u64| {
        if fixed != 0 {
            Some(fixed)
        } else {
            u32be(stsz, 12 + 4 * s as usize)
        }
    };
    let stts = child(stbl, b"stts")?;
    // An edit list delays the track by its empty edits and starts it at its first edit's media time.
    let (mut delay_ms, mut first) = (0, 0);
    if let Some(elst) = path(trak, &[b"edts", b"elst"]) {
        let wide = elst.first() == Some(&1);
        let entry = if wide { 20 } else { 12 };
        for i in 0..u32be(elst, 4)? as usize {
            let at = 8 + entry * i;
            let (length, media) = if wide {
                (u64be(elst, at)?, u64be(elst, at + 8)? as i64)
            } else {
                (u32be(elst, at)?, u32be(elst, at + 4)? as u32 as i32 as i64)
            };
            if media >= 0 {
                first = media as u64;
                break;
            }
            delay_ms += length * 1000 / movie_scale;
        }
    }
    let ms = |t: u64| delay_ms + t.saturating_sub(first) * 1000 / scale;
    let mut lines = Vec::new();
    let (mut sample, mut dts) = (0u64, 0u64);
    for i in 0..u32be(stts, 4)? as usize {
        let (run, delta) = (u32be(stts, 8 + 8 * i)?, u32be(stts, 12 + 8 * i)?);
        for _ in 0..run {
            if sample >= count {
                break;
            }
            if size(sample)? > empty && dts + delta > first {
                lines.push((ms(dts), ms(dts + delta)));
            }
            dts += delta;
            sample += 1;
        }
    }
    Some(lines)
}
