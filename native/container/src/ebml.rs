pub mod id {
    pub const EBML: u32 = 0x1A45DFA3;
    pub const SEGMENT: u32 = 0x18538067;
    pub const SEEK_HEAD: u32 = 0x114D9B74;
    pub const SEEK: u32 = 0x4DBB;
    pub const SEEK_ID: u32 = 0x53AB;
    pub const SEEK_POSITION: u32 = 0x53AC;
    pub const INFO: u32 = 0x1549A966;
    pub const TIMESTAMP_SCALE: u32 = 0x2AD7B1;
    pub const DURATION: u32 = 0x4489;
    pub const TRACKS: u32 = 0x1654AE6B;
    pub const TRACK_ENTRY: u32 = 0xAE;
    pub const TRACK_NUMBER: u32 = 0xD7;
    pub const TRACK_TYPE: u32 = 0x83;
    pub const FLAG_ENABLED: u32 = 0xB9;
    pub const FLAG_DEFAULT: u32 = 0x88;
    pub const FLAG_FORCED: u32 = 0x55AA;
    pub const FLAG_HEARING_IMPAIRED: u32 = 0x55AB;
    pub const FLAG_COMMENTARY: u32 = 0x55AF;
    pub const NAME: u32 = 0x536E;
    pub const LANGUAGE: u32 = 0x22B59C;
    pub const LANGUAGE_IETF: u32 = 0x22B59D;
    pub const CODEC_ID: u32 = 0x86;
    pub const CODEC_PRIVATE: u32 = 0x63A2;
    pub const VIDEO: u32 = 0xE0;
    pub const PIXEL_WIDTH: u32 = 0xB0;
    pub const PIXEL_HEIGHT: u32 = 0xBA;
    pub const DISPLAY_WIDTH: u32 = 0x54B0;
    pub const DISPLAY_HEIGHT: u32 = 0x54BA;
    pub const COLOUR: u32 = 0x55B0;
    pub const MATRIX_COEFFICIENTS: u32 = 0x55B1;
    pub const RANGE: u32 = 0x55B9;
    pub const TRANSFER_CHARACTERISTICS: u32 = 0x55BA;
    pub const PRIMARIES: u32 = 0x55BB;
    pub const CONTENT_ENCODINGS: u32 = 0x6D80;
    pub const CONTENT_ENCODING: u32 = 0x6240;
    pub const CONTENT_COMPRESSION: u32 = 0x5034;
    pub const CONTENT_COMP_ALGO: u32 = 0x4254;
    pub const CONTENT_COMP_SETTINGS: u32 = 0x4255;
    pub const BLOCK_ADDITION_MAPPING: u32 = 0x41E4;
    pub const BLOCK_ADD_ID_TYPE: u32 = 0x41E7;
    pub const BLOCK_ADD_ID_EXTRA_DATA: u32 = 0x41ED;
    pub const CUES: u32 = 0x1C53BB6B;
    pub const CUE_POINT: u32 = 0xBB;
    pub const CUE_TIME: u32 = 0xB3;
    pub const CUE_TRACK_POSITIONS: u32 = 0xB7;
    pub const CUE_TRACK: u32 = 0xF7;
    pub const CUE_CLUSTER_POSITION: u32 = 0xF1;
    pub const CUE_RELATIVE_POSITION: u32 = 0xF0;
    pub const CUE_DURATION: u32 = 0xB2;
    pub const CLUSTER: u32 = 0x1F43B675;
    pub const CLUSTER_TIMESTAMP: u32 = 0xE7;
    pub const SIMPLE_BLOCK: u32 = 0xA3;
    pub const BLOCK_GROUP: u32 = 0xA0;
    pub const BLOCK: u32 = 0xA1;
}

pub const UNKNOWN: u64 = u64::MAX;

/// The value at `i` and the offset after it; `marker` keeps the length bit, as element IDs do.
pub fn vint(b: &[u8], i: usize, marker: bool) -> Option<(u64, usize)> {
    let first = *b.get(i)?;
    if first == 0 {
        return None;
    }
    let len = first.leading_zeros() as usize + 1;
    if i + len > b.len() {
        return None;
    }
    let mut v = if marker {
        first as u64
    } else {
        first as u64 & (0xFF >> len)
    };
    let mut all_ones = v == (0xFF >> len) as u64;
    for k in 1..len {
        v = (v << 8) | b[i + k] as u64;
        all_ones &= b[i + k] == 0xFF;
    }
    if !marker && all_ones {
        return Some((UNKNOWN, i + len));
    }
    Some((v, i + len))
}

/// (id, header length, body length) of the element at `i`.
pub fn header(b: &[u8], i: usize) -> Option<(u32, usize, u64)> {
    let (id, j) = vint(b, i, true)?;
    let (size, k) = vint(b, j, false)?;
    Some((id as u32, k - i, size))
}

/// Child elements, stopping at one the end of `b` cuts off.
pub fn children(b: &[u8]) -> impl Iterator<Item = (u32, &[u8])> {
    let mut i = 0;
    std::iter::from_fn(move || {
        let (id, h, size) = header(b, i)?;
        let start = i + h;
        let end = start.checked_add(usize::try_from(size).ok()?)?;
        if end > b.len() {
            return None;
        }
        i = end;
        Some((id, &b[start..end]))
    })
}

pub fn find(b: &[u8], id: u32) -> Option<&[u8]> {
    children(b).find(|(i, _)| *i == id).map(|(_, v)| v)
}

pub fn uint(b: &[u8]) -> u64 {
    b.iter().fold(0, |v, &x| (v << 8) | x as u64)
}

pub fn float(b: &[u8]) -> f64 {
    match b.len() {
        4 => f32::from_be_bytes(b.try_into().unwrap()) as f64,
        8 => f64::from_be_bytes(b.try_into().unwrap()),
        _ => 0.0,
    }
}

pub fn string(b: &[u8]) -> String {
    String::from_utf8_lossy(b)
        .trim_end_matches('\0')
        .to_string()
}
