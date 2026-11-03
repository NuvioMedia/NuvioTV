use crate::ebml::{UNKNOWN, children, find, float, header, id, string, uint, vint};
use crate::error::{Error, Result};
use crate::http::RangeReader;
use crate::keyframes::{Container, Keyframe, Keyframes};
use crate::track::{Track, TrackKind, Video};

/// The first read of a file, which usually covers everything before the first cluster.
pub const HEAD: u64 = 256 * 1024;

pub struct Segment {
    /// File offset that SeekHead and Cues positions count from.
    pub data_start: u64,
    pub scale_ns: u64,
    pub duration_ms: u64,
    pub tracks: Vec<Track>,
    seeks: Vec<(u32, u64)>,
}

#[derive(Debug, Clone, Copy)]
pub struct CuePoint {
    /// In segment ticks; see [`Segment::ms`].
    pub time: u64,
    pub track: u64,
    /// File offset of the cluster.
    pub cluster: u64,
    /// Offset of the block inside the cluster's body.
    pub relative: Option<u64>,
    pub duration: Option<u64>,
}

fn read_element(r: &mut RangeReader, pos: u64) -> Result<(u32, Vec<u8>)> {
    let head = r.read(pos, 64 * 1024)?;
    let (id, h, size) = header(&head, 0).ok_or(Error::Malformed("element header"))?;
    if size == UNKNOWN {
        return Err(Error::Unsupported("unknown-size element".into()));
    }
    let total = h as u64 + size;
    if total <= head.len() as u64 {
        return Ok((id, head[h..total as usize].to_vec()));
    }
    let mut body = head[h..].to_vec();
    body.extend(r.read(pos + head.len() as u64, total - head.len() as u64)?);
    Ok((id, body))
}

fn seek_entries(body: &[u8], data_start: u64) -> impl Iterator<Item = (u32, u64)> + '_ {
    children(body)
        .filter(|(i, _)| *i == id::SEEK)
        .filter_map(move |(_, s)| {
            Some((
                uint(find(s, id::SEEK_ID)?) as u32,
                data_start + uint(find(s, id::SEEK_POSITION)?),
            ))
        })
}

fn flag(entry: &[u8], element: u32, default: bool) -> bool {
    find(entry, element).map_or(default, |v| uint(v) != 0)
}

fn parse_track(entry: &[u8]) -> Option<Track> {
    let kind = match find(entry, id::TRACK_TYPE).map(uint)? {
        1 => TrackKind::Video,
        2 => TrackKind::Audio,
        17 => TrackKind::Subtitle,
        _ => TrackKind::Other,
    };
    let mut t = Track::new(
        find(entry, id::TRACK_NUMBER).map(uint)?,
        kind,
        string(find(entry, id::CODEC_ID)?),
    );
    t.private = find(entry, id::CODEC_PRIVATE)
        .map(<[u8]>::to_vec)
        .unwrap_or_default();
    t.name = find(entry, id::NAME).map(string);
    t.language = find(entry, id::LANGUAGE_IETF)
        .or_else(|| find(entry, id::LANGUAGE))
        .map(string);
    t.enabled = flag(entry, id::FLAG_ENABLED, true);
    t.default = flag(entry, id::FLAG_DEFAULT, true);
    t.forced = flag(entry, id::FLAG_FORCED, false);
    t.hearing_impaired = flag(entry, id::FLAG_HEARING_IMPAIRED, false);
    t.commentary = flag(entry, id::FLAG_COMMENTARY, false);
    if let Some(encodings) = find(entry, id::CONTENT_ENCODINGS) {
        for (_, encoding) in children(encodings).filter(|(i, _)| *i == id::CONTENT_ENCODING) {
            if let Some(comp) = find(encoding, id::CONTENT_COMPRESSION)
                && find(comp, id::CONTENT_COMP_ALGO).map(uint) == Some(3)
            {
                t.header_strip = find(comp, id::CONTENT_COMP_SETTINGS)
                    .map(<[u8]>::to_vec)
                    .unwrap_or_default();
            }
        }
    }
    if let Some(video) = find(entry, id::VIDEO) {
        let mut v = Video::new(
            find(video, id::PIXEL_WIDTH).map_or(0, uint),
            find(video, id::PIXEL_HEIGHT).map_or(0, uint),
        );
        v.display_width = find(video, id::DISPLAY_WIDTH).map_or(v.width, uint);
        v.display_height = find(video, id::DISPLAY_HEIGHT).map_or(v.height, uint);
        if let Some(c) = find(video, id::COLOUR) {
            v.matrix = find(c, id::MATRIX_COEFFICIENTS).map_or(2, uint);
            v.range = find(c, id::RANGE).map_or(0, uint);
            v.transfer = find(c, id::TRANSFER_CHARACTERISTICS).map_or(2, uint);
            v.primaries = find(c, id::PRIMARIES).map_or(2, uint);
        }
        for (_, map) in children(entry).filter(|(i, _)| *i == id::BLOCK_ADDITION_MAPPING) {
            if matches!(
                find(map, id::BLOCK_ADD_ID_TYPE).map(uint),
                Some(0x64766343 | 0x64767643)
            ) && let Some(record) = find(map, id::BLOCK_ADD_ID_EXTRA_DATA)
            {
                v.set_dovi(record);
            }
        }
        t.video = Some(v);
    }
    Some(t)
}

impl Segment {
    pub fn open(r: &mut RangeReader) -> Result<Segment> {
        let head = r.read(0, HEAD)?;
        let (ebml, h, size) = header(&head, 0).ok_or(Error::Malformed("EBML header"))?;
        if ebml != id::EBML {
            return Err(Error::Unsupported("not Matroska".into()));
        }
        let mut i = h + size as usize;
        let (segment, h, _) = header(&head, i).ok_or(Error::Malformed("segment"))?;
        if segment != id::SEGMENT {
            return Err(Error::Malformed("segment"));
        }
        i += h;
        let data_start = i as u64;
        let (mut info, mut tracks, mut seeks) = (None, None, Vec::new());
        while let Some((element, h, size)) = header(&head, i) {
            if element == id::CLUSTER || size == UNKNOWN {
                break;
            }
            let end = i + h + size as usize;
            let body = if end <= head.len() {
                head[i + h..end].to_vec()
            } else if matches!(element, id::INFO | id::TRACKS | id::SEEK_HEAD) {
                read_element(r, i as u64)?.1
            } else {
                break;
            };
            match element {
                id::SEEK_HEAD => seeks.extend(seek_entries(&body, data_start)),
                id::INFO => info = Some(body),
                id::TRACKS => tracks = Some(body),
                _ => {}
            }
            i = end;
        }
        // A SeekHead may point at a second one, often at the end of the file.
        let more: Vec<u64> = seeks
            .iter()
            .filter(|(e, _)| *e == id::SEEK_HEAD)
            .map(|&(_, p)| p)
            .collect();
        for pos in more {
            if let Ok((id::SEEK_HEAD, body)) = read_element(r, pos) {
                seeks.extend(seek_entries(&body, data_start));
            }
        }
        let mut segment = Segment {
            data_start,
            scale_ns: 1_000_000,
            duration_ms: 0,
            tracks: Vec::new(),
            seeks,
        };
        let info = match info {
            Some(b) => b,
            None => {
                read_element(
                    r,
                    segment
                        .position(id::INFO)
                        .ok_or(Error::Malformed("segment info"))?,
                )?
                .1
            }
        };
        let tracks = match tracks {
            Some(b) => b,
            None => {
                read_element(
                    r,
                    segment
                        .position(id::TRACKS)
                        .ok_or(Error::Malformed("tracks"))?,
                )?
                .1
            }
        };
        segment.scale_ns = find(&info, id::TIMESTAMP_SCALE).map_or(1_000_000, uint);
        segment.duration_ms = find(&info, id::DURATION)
            .map_or(0.0, |d| float(d) * segment.scale_ns as f64 / 1e6)
            as u64;
        segment.tracks = children(&tracks)
            .filter(|(e, _)| *e == id::TRACK_ENTRY)
            .filter_map(|(_, e)| parse_track(e))
            .collect();
        Ok(segment)
    }

    /// File offset of a top-level element the SeekHead lists.
    pub fn position(&self, element: u32) -> Option<u64> {
        self.seeks
            .iter()
            .find(|(e, _)| *e == element)
            .map(|&(_, p)| p)
    }

    pub fn ms(&self, ticks: u64) -> u64 {
        (ticks as u128 * self.scale_ns as u128 / 1_000_000) as u64
    }

    /// Every cue of every track, in file order.
    pub fn cues(&self, r: &mut RangeReader) -> Result<Vec<CuePoint>> {
        let (_, cues) = read_element(r, self.position(id::CUES).ok_or(Error::NoIndex)?)?;
        let mut out = Vec::new();
        for (_, point) in children(&cues).filter(|(e, _)| *e == id::CUE_POINT) {
            let time = find(point, id::CUE_TIME).map_or(0, uint);
            for (_, pos) in children(point).filter(|(e, _)| *e == id::CUE_TRACK_POSITIONS) {
                let (Some(track), Some(cluster)) = (
                    find(pos, id::CUE_TRACK),
                    find(pos, id::CUE_CLUSTER_POSITION),
                ) else {
                    continue;
                };
                out.push(CuePoint {
                    time,
                    track: uint(track),
                    cluster: self.data_start + uint(cluster),
                    relative: find(pos, id::CUE_RELATIVE_POSITION).map(uint),
                    duration: find(pos, id::CUE_DURATION).map(uint),
                });
            }
        }
        Ok(out)
    }

    pub fn keyframes(self, r: &mut RangeReader) -> Result<Keyframes> {
        let video = self
            .tracks
            .iter()
            .find(|t| t.kind == TrackKind::Video)
            .cloned()
            .ok_or(Error::Unsupported("no video track".into()))?;
        let mut entries: Vec<Keyframe> = self
            .cues(r)?
            .into_iter()
            .filter(|c| c.track == video.number)
            .map(|c| Keyframe {
                time_ms: self.ms(c.time),
                offset: c.cluster,
                relative: c.relative,
                size: None,
            })
            .collect();
        entries.sort_by_key(|k| k.time_ms);
        entries.dedup_by_key(|k| k.time_ms);
        if entries.is_empty() {
            return Err(Error::NoIndex);
        }
        Ok(Keyframes {
            container: Container::Matroska,
            track: video,
            duration_ms: self.duration_ms,
            scale_ns: self.scale_ns,
            entries,
        })
    }
}

/// The frame in the block at `at` of `buf`, read from `base`, completing a short read.
fn block(
    k: &Keyframes,
    r: &mut RangeReader,
    base: u64,
    mut buf: Vec<u8>,
    at: usize,
) -> Result<Vec<u8>> {
    let (element, h, size) = header(&buf, at).ok_or(Error::Malformed("block header"))?;
    let end = at + h + size as usize;
    if end > buf.len() {
        let have = buf.len() as u64;
        buf.extend(r.read(base + have, end as u64 - have)?);
    }
    let body = &buf[at + h..end];
    let block = match element {
        id::SIMPLE_BLOCK => body,
        id::BLOCK_GROUP => find(body, id::BLOCK).ok_or(Error::Malformed("block group"))?,
        _ => return Err(Error::Malformed("block")),
    };
    let (track, i) = vint(block, 0, false).ok_or(Error::Malformed("block"))?;
    if track != k.track.number {
        return Err(Error::Malformed("block of another track"));
    }
    if block.get(i + 2).ok_or(Error::Malformed("block"))? & 0x06 != 0 {
        return Err(Error::Unsupported("laced video block".into()));
    }
    let mut frame = k.track.header_strip.clone();
    frame.extend_from_slice(&block[i + 3..]);
    Ok(frame)
}

pub(crate) fn keyframe(
    k: &Keyframes,
    r: &mut RangeReader,
    e: &Keyframe,
    guess: u64,
) -> Result<Vec<u8>> {
    let Some(rel) = e.relative else {
        return scan_cluster(k, r, e);
    };
    // A cluster header is at most 12 bytes; read it with the block when they are close.
    let near = rel < 256 * 1024;
    let buf = r.read(e.offset, if near { 12 + rel + guess } else { 12 })?;
    let (element, h, _) = header(&buf, 0).ok_or(Error::Malformed("cluster"))?;
    if element != id::CLUSTER {
        return Err(Error::Malformed("cluster"));
    }
    if near {
        return block(k, r, e.offset, buf, h + rel as usize);
    }
    let base = e.offset + h as u64 + rel;
    let buf = r.read(base, guess)?;
    block(k, r, base, buf, 0)
}

fn scan_cluster(k: &Keyframes, r: &mut RangeReader, e: &Keyframe) -> Result<Vec<u8>> {
    let buf = r.read(e.offset, 512 * 1024)?;
    let (element, h, _) = header(&buf, 0).ok_or(Error::Malformed("cluster"))?;
    if element != id::CLUSTER {
        return Err(Error::Malformed("cluster"));
    }
    let (mut i, mut ts) = (h, 0u64);
    while let Some((element, eh, size)) = header(&buf, i) {
        let start = i + eh;
        match element {
            id::CLUSTER_TIMESTAMP if start + size as usize <= buf.len() => {
                ts = uint(&buf[start..start + size as usize])
            }
            id::SIMPLE_BLOCK | id::BLOCK_GROUP => {
                let first = if element == id::SIMPLE_BLOCK {
                    start
                } else {
                    start + header(&buf, start).map_or(0, |x| x.1)
                };
                if let Some((track, j)) = vint(&buf, first, false)
                    && track == k.track.number
                    && j + 2 <= buf.len()
                {
                    let rel = i16::from_be_bytes([buf[j], buf[j + 1]]) as i64;
                    let t = (ts as i64 + rel).max(0) as u64 * k.scale_ns / 1_000_000;
                    if t + 1 >= e.time_ms {
                        return block(k, r, e.offset, buf, i);
                    }
                }
            }
            _ => {}
        }
        i = start + size as usize;
        if i >= buf.len() {
            break;
        }
    }
    Err(Error::Unsupported(
        "keyframe not near its cluster start".into(),
    ))
}
