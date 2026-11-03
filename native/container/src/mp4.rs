use crate::error::{Error, Result};
use crate::http::RangeReader;
use crate::keyframes::{Container, Keyframe, Keyframes};
use crate::track::{Track, TrackKind, Video};

pub(crate) fn u16be(b: &[u8], i: usize) -> Option<u64> {
    Some(u16::from_be_bytes(b.get(i..i + 2)?.try_into().ok()?) as u64)
}

pub(crate) fn u32be(b: &[u8], i: usize) -> Option<u64> {
    Some(u32::from_be_bytes(b.get(i..i + 4)?.try_into().ok()?) as u64)
}

pub(crate) fn u64be(b: &[u8], i: usize) -> Option<u64> {
    Some(u64::from_be_bytes(b.get(i..i + 8)?.try_into().ok()?))
}

/// (type, body) of each box in `b`.
pub fn boxes(b: &[u8]) -> impl Iterator<Item = ([u8; 4], &[u8])> {
    let mut i = 0;
    std::iter::from_fn(move || {
        let size = u32be(b, i)?;
        let kind: [u8; 4] = b.get(i + 4..i + 8)?.try_into().ok()?;
        let (head, size) = match size {
            1 => (16, u64be(b, i + 8)?),
            0 => (8, (b.len() - i) as u64),
            n => (8, n),
        };
        let end = i.checked_add(usize::try_from(size).ok()?)?;
        if size < head || end > b.len() {
            return None;
        }
        let body = &b[i + head as usize..end];
        i = end;
        Some((kind, body))
    })
}

pub fn child<'a>(b: &'a [u8], kind: &[u8; 4]) -> Option<&'a [u8]> {
    boxes(b).find(|(k, _)| k == kind).map(|(_, v)| v)
}

pub(crate) fn path<'a>(b: &'a [u8], kinds: &[&[u8; 4]]) -> Option<&'a [u8]> {
    kinds.iter().try_fold(b, |b, k| child(b, k))
}

/// The moov box's body, before or after the media data.
pub fn moov(r: &mut RangeReader) -> Result<Vec<u8>> {
    let mut pos = 0u64;
    loop {
        let head = r.read(pos, 16)?;
        if head.len() < 8 {
            return Err(Error::Malformed("MP4 without moov"));
        }
        let kind = &head[4..8];
        let size = match u32be(&head, 0).unwrap() {
            1 => u64be(&head, 8).ok_or(Error::Malformed("box header"))?,
            0 => return Err(Error::Malformed("MP4 without moov")),
            n => n,
        };
        if pos == 0 && kind != b"ftyp" {
            return Err(Error::Unsupported("not MP4".into()));
        }
        if kind == b"moov" {
            let mut body = r.read(pos, size)?;
            body.drain(..if u32be(&body, 0) == Some(1) { 16 } else { 8 });
            return Ok(body);
        }
        if kind == b"moof" {
            return Err(Error::Unsupported("fragmented MP4".into()));
        }
        pos += size.max(8);
    }
}

pub fn keyframes(r: &mut RangeReader) -> Result<Keyframes> {
    let moov = moov(r)?;
    if child(&moov, b"mvex").is_some() {
        return Err(Error::Unsupported("fragmented MP4".into()));
    }
    for (kind, trak) in boxes(&moov) {
        if &kind != b"trak" {
            continue;
        }
        let Some(mdia) = child(trak, b"mdia") else {
            continue;
        };
        if child(mdia, b"hdlr").and_then(|h| h.get(8..12)) == Some(b"vide") {
            let number = child(trak, b"tkhd")
                .and_then(|t| u32be(t, if t[0] == 1 { 20 } else { 12 }))
                .unwrap_or(1);
            return video_keyframes(number, mdia);
        }
    }
    Err(Error::Unsupported("no video track".into()))
}

fn video_keyframes(number: u64, mdia: &[u8]) -> Result<Keyframes> {
    let bad = || Error::Malformed("sample tables");
    let mdhd = child(mdia, b"mdhd").ok_or_else(bad)?;
    let (timescale, duration) = if mdhd[0] == 1 {
        (u32be(mdhd, 20), u64be(mdhd, 24))
    } else {
        (u32be(mdhd, 12), u32be(mdhd, 16))
    };
    let timescale = timescale.filter(|&t| t > 0).ok_or_else(bad)?;
    let stbl = path(mdia, &[b"minf", b"stbl"]).ok_or_else(bad)?;
    let (entry_kind, entry) = boxes(
        child(stbl, b"stsd")
            .and_then(|s| s.get(8..))
            .ok_or_else(bad)?,
    )
    .next()
    .ok_or_else(bad)?;
    let codec = match &entry_kind {
        b"avc1" | b"avc3" => "V_MPEG4/ISO/AVC",
        b"hvc1" | b"hev1" | b"dvh1" | b"dvhe" => "V_MPEGH/ISO/HEVC",
        b"av01" => "V_AV1",
        b"vp09" => "V_VP9",
        other => {
            return Err(Error::Unsupported(format!(
                "video codec {}",
                String::from_utf8_lossy(other)
            )));
        }
    };
    let mut track = Track::new(number, TrackKind::Video, codec.to_string());
    let mut video = Video::new(u16be(entry, 24).unwrap_or(0), u16be(entry, 26).unwrap_or(0));
    // A visual sample entry's own fields take 78 bytes.
    for (kind, b) in boxes(entry.get(78..).unwrap_or(&[])) {
        match &kind {
            b"avcC" | b"hvcC" | b"av1C" => track.private = b.to_vec(),
            b"colr" if b.get(..4) == Some(b"nclx") => {
                video.primaries = u16be(b, 4).unwrap_or(2);
                video.transfer = u16be(b, 6).unwrap_or(2);
                video.matrix = u16be(b, 8).unwrap_or(2);
                video.range = if b.get(10).is_some_and(|f| f & 0x80 != 0) {
                    2
                } else {
                    1
                };
            }
            b"pasp" => {
                let (h, v) = (u32be(b, 0).unwrap_or(1), u32be(b, 4).unwrap_or(1));
                if h > 0 && v > 0 {
                    video.display_width = video.width * h / v;
                }
            }
            b"dvcC" | b"dvvC" => video.set_dovi(b),
            _ => {}
        }
    }
    track.video = Some(video);

    let stsz = child(stbl, b"stsz").ok_or_else(bad)?;
    let fixed = u32be(stsz, 4).ok_or_else(bad)?;
    let count = u32be(stsz, 8).ok_or_else(bad)? as usize;
    let size = |s: usize| {
        if fixed != 0 {
            Some(fixed)
        } else {
            u32be(stsz, 12 + 4 * s)
        }
    };
    let chunks: Vec<u64> = if let Some(stco) = child(stbl, b"stco") {
        (0..u32be(stco, 4).ok_or_else(bad)? as usize)
            .map(|i| u32be(stco, 8 + 4 * i))
            .collect::<Option<_>>()
            .ok_or_else(bad)?
    } else {
        let co64 = child(stbl, b"co64").ok_or_else(bad)?;
        (0..u32be(co64, 4).ok_or_else(bad)? as usize)
            .map(|i| u64be(co64, 8 + 8 * i))
            .collect::<Option<_>>()
            .ok_or_else(bad)?
    };
    let stsc = child(stbl, b"stsc").ok_or_else(bad)?;
    let runs: Vec<(u64, u64)> = (0..u32be(stsc, 4).ok_or_else(bad)? as usize)
        .map(|i| Some((u32be(stsc, 8 + 12 * i)?, u32be(stsc, 12 + 12 * i)?)))
        .collect::<Option<_>>()
        .ok_or_else(bad)?;
    let stts = child(stbl, b"stts").ok_or_else(bad)?;
    let ctts = child(stbl, b"ctts");
    // Without stss every sample is a sync sample.
    let sync: Option<Vec<u64>> = child(stbl, b"stss").map(|s| {
        (0..u32be(s, 4).unwrap_or(0) as usize)
            .filter_map(|i| u32be(s, 8 + 4 * i).map(|n| n - 1))
            .collect()
    });

    let mut entries = Vec::new();
    let (mut sample, mut run, mut dts) = (0usize, 0usize, 0u64);
    let (mut stts_i, mut stts_left, mut stts_delta) = (0usize, 0u64, 0u64);
    let (mut ctts_i, mut ctts_left, mut ctts_offset) = (0usize, 0u64, 0i64);
    let mut next_sync = 0;
    for (chunk, &offset) in chunks.iter().enumerate() {
        while run + 1 < runs.len() && runs[run + 1].0 <= chunk as u64 + 1 {
            run += 1;
        }
        let mut at = offset;
        for _ in 0..runs.get(run).ok_or_else(bad)?.1 {
            if sample >= count {
                break;
            }
            while stts_left == 0 {
                stts_left = u32be(stts, 8 + 8 * stts_i).ok_or_else(bad)?;
                stts_delta = u32be(stts, 12 + 8 * stts_i).ok_or_else(bad)?;
                stts_i += 1;
            }
            if let Some(ctts) = ctts {
                while ctts_left == 0 {
                    ctts_left = u32be(ctts, 8 + 8 * ctts_i).ok_or_else(bad)?;
                    ctts_offset =
                        u32be(ctts, 12 + 8 * ctts_i).ok_or_else(bad)? as u32 as i32 as i64;
                    ctts_i += 1;
                }
                ctts_left -= 1;
            }
            let len = size(sample).ok_or_else(bad)?;
            let is_sync = match &sync {
                Some(list) => {
                    let hit = list.get(next_sync) == Some(&(sample as u64));
                    next_sync += hit as usize;
                    hit
                }
                None => true,
            };
            if is_sync {
                let pts = (dts as i64 + ctts_offset).max(0) as u64;
                entries.push(Keyframe {
                    time_ms: pts * 1000 / timescale,
                    offset: at,
                    relative: None,
                    size: Some(len),
                });
            }
            at += len;
            dts += stts_delta;
            stts_left -= 1;
            sample += 1;
        }
    }
    entries.sort_by_key(|k| k.time_ms);
    entries.dedup_by_key(|k| k.time_ms);
    if entries.is_empty() {
        return Err(Error::NoIndex);
    }
    Ok(Keyframes {
        container: Container::Mp4,
        track,
        duration_ms: duration.unwrap_or(dts) * 1000 / timescale,
        scale_ns: 1_000_000,
        entries,
    })
}
