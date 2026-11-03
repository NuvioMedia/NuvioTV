//! Splits an HEVC intra picture of independent, row-aligned slices into one small picture per slice.
//! Prediction never crosses a slice, so with loop filters off each strip decodes to the same samples.

const START: [u8; 4] = [0, 0, 0, 1];

/// The RBSP of a NAL payload, and the payload offset of each RBSP byte.
fn unescape(ebsp: &[u8]) -> (Vec<u8>, Vec<usize>) {
    let mut out = Vec::with_capacity(ebsp.len());
    let mut from = Vec::with_capacity(ebsp.len());
    let mut zeros = 0;
    for (i, &b) in ebsp.iter().enumerate() {
        if zeros >= 2 && b == 3 {
            zeros = 0;
            continue;
        }
        zeros = if b == 0 { zeros + 1 } else { 0 };
        out.push(b);
        from.push(i);
    }
    (out, from)
}

fn escape(rbsp: &[u8], out: &mut Vec<u8>) {
    let mut zeros = 0;
    for &b in rbsp {
        if zeros >= 2 && b <= 3 {
            out.push(3);
            zeros = 0;
        }
        zeros = if b == 0 { zeros + 1 } else { 0 };
        out.push(b);
    }
}

struct Reader<'a> {
    b: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(b: &'a [u8]) -> Self {
        Reader { b, pos: 0 }
    }
    fn bit(&mut self) -> Option<u32> {
        let byte = *self.b.get(self.pos >> 3)?;
        let v = (byte >> (7 - (self.pos & 7))) & 1;
        self.pos += 1;
        Some(v as u32)
    }
    fn u(&mut self, n: u32) -> Option<u32> {
        let mut v = 0;
        for _ in 0..n {
            v = (v << 1) | self.bit()?;
        }
        Some(v)
    }
    fn skip(&mut self, n: usize) -> Option<()> {
        self.pos += n;
        (self.pos <= self.b.len() * 8).then_some(())
    }
    fn ue(&mut self) -> Option<u32> {
        let mut zeros = 0;
        while self.bit()? == 0 {
            zeros += 1;
            if zeros > 31 {
                return None;
            }
        }
        Some(((1u64 << zeros) - 1 + self.u(zeros)? as u64) as u32)
    }
    fn se(&mut self) -> Option<i32> {
        let k = self.ue()? as i64;
        Some(if k & 1 == 1 {
            ((k + 1) / 2) as i32
        } else {
            -(k / 2) as i32
        })
    }
}

#[derive(Default)]
struct Writer {
    b: Vec<u8>,
    n: usize,
}

impl Writer {
    fn bit(&mut self, v: u32) {
        if self.n & 7 == 0 {
            self.b.push(0);
        }
        if v != 0 {
            *self.b.last_mut().unwrap() |= 0x80 >> (self.n & 7);
        }
        self.n += 1;
    }
    fn u(&mut self, n: u32, v: u32) {
        for i in (0..n).rev() {
            self.bit((v >> i) & 1);
        }
    }
    fn ue(&mut self, v: u32) {
        let x = v as u64 + 1;
        let len = 64 - x.leading_zeros();
        self.u(len - 1, 0);
        for i in (0..len).rev() {
            self.bit(((x >> i) & 1) as u32);
        }
    }
    /// Copies bits [from, to) of `src`.
    fn copy(&mut self, src: &[u8], from: usize, to: usize) {
        for p in from..to {
            self.bit(((src[p >> 3] >> (7 - (p & 7))) & 1) as u32);
        }
    }
    /// rbsp_stop_one_bit or byte_alignment().
    fn align(&mut self) {
        self.bit(1);
        while self.n & 7 != 0 {
            self.bit(0);
        }
    }
}

fn ceil_log2(v: u32) -> u32 {
    if v <= 1 {
        0
    } else {
        32 - (v - 1).leading_zeros()
    }
}

fn scaling_list_data(r: &mut Reader) -> Option<()> {
    for size in 0..4 {
        let mut matrix = 0;
        while matrix < 6 {
            if r.bit()? == 0 {
                r.ue()?;
            } else {
                let coefs = 64.min(1 << (4 + (size << 1)));
                if size > 1 {
                    r.se()?;
                }
                for _ in 0..coefs {
                    r.se()?;
                }
            }
            matrix += if size == 3 { 3 } else { 1 };
        }
    }
    Some(())
}

/// Parses st_ref_pic_set(idx) and returns its NumDeltaPocs.
fn st_ref_pic_set(r: &mut Reader, idx: usize, count: usize, deltas: &[u32]) -> Option<u32> {
    let inter = idx != 0 && r.bit()? == 1;
    if inter {
        let delta_idx = if idx == count {
            r.ue()? as usize + 1
        } else {
            1
        };
        r.bit()?;
        r.ue()?;
        let reference = idx.checked_sub(delta_idx)?;
        let mut n = 0;
        for _ in 0..=*deltas.get(reference)? {
            let used = r.bit()?;
            let use_delta = if used == 0 { r.bit()? } else { 1 };
            n += used | use_delta;
        }
        Some(n)
    } else {
        let neg = r.ue()?;
        let pos = r.ue()?;
        for _ in 0..neg + pos {
            r.ue()?;
            r.bit()?;
        }
        Some(neg + pos)
    }
}

struct Sps {
    id: u32,
    rbsp: Vec<u8>,
    /// Bit offsets of pic_width_in_luma_samples and of whatever follows the conformance window.
    at_size: usize,
    after_window: usize,
    width: u32,
    height: u32,
    window: [u32; 4],
    chroma_shift: (u32, u32),
    ctb: u32,
    separate_planes: bool,
    chroma_array_type: u32,
    poc_lsb_bits: u32,
    st_rps_count: usize,
    st_rps_deltas: Vec<u32>,
    long_term: bool,
    lt_count: u32,
    temporal_mvp: bool,
    sao: bool,
}

fn parse_sps(rbsp: Vec<u8>) -> Option<Sps> {
    let mut r = Reader::new(&rbsp);
    r.skip(16)?; // NAL header
    r.u(4)?;
    let max_sub_layers = r.u(3)?;
    r.u(1)?;
    // profile_tier_level(1, max_sub_layers)
    r.skip(96)?;
    let mut present = Vec::new();
    for _ in 0..max_sub_layers {
        present.push((r.bit()?, r.bit()?));
    }
    if max_sub_layers > 0 {
        r.skip(2 * (8 - max_sub_layers as usize))?;
    }
    for (profile, level) in present {
        r.skip(if profile == 1 { 88 } else { 0 } + if level == 1 { 8 } else { 0 })?;
    }
    let id = r.ue()?;
    let chroma_format = r.ue()?;
    let separate_planes = chroma_format == 3 && r.bit()? == 1;
    let at_size = r.pos;
    let width = r.ue()?;
    let height = r.ue()?;
    let mut window = [0; 4];
    if r.bit()? == 1 {
        for w in &mut window {
            *w = r.ue()?;
        }
    }
    let after_window = r.pos;
    r.ue()?;
    r.ue()?;
    let poc_lsb_bits = r.ue()? + 4;
    let ordering_all = r.bit()? == 1;
    for _ in (if ordering_all { 0 } else { max_sub_layers })..=max_sub_layers {
        r.ue()?;
        r.ue()?;
        r.ue()?;
    }
    let min_cb = r.ue()? + 3;
    let ctb = 1 << (min_cb + r.ue()?);
    r.ue()?;
    r.ue()?;
    r.ue()?;
    r.ue()?;
    if r.bit()? == 1 && r.bit()? == 1 {
        scaling_list_data(&mut r)?;
    }
    r.bit()?; // amp
    let sao = r.bit()? == 1;
    if r.bit()? == 1 {
        r.u(8)?;
        r.ue()?;
        r.ue()?;
        r.bit()?;
    }
    let st_rps_count = r.ue()? as usize;
    let mut st_rps_deltas = Vec::with_capacity(st_rps_count);
    for i in 0..st_rps_count {
        let n = st_ref_pic_set(&mut r, i, st_rps_count, &st_rps_deltas)?;
        st_rps_deltas.push(n);
    }
    let long_term = r.bit()? == 1;
    let mut lt_count = 0;
    if long_term {
        lt_count = r.ue()?;
        for _ in 0..lt_count {
            r.u(poc_lsb_bits)?;
            r.bit()?;
        }
    }
    let temporal_mvp = r.bit()? == 1;
    let chroma_shift = match chroma_format {
        1 => (1, 1),
        2 => (1, 0),
        _ => (0, 0),
    };
    Some(Sps {
        id,
        rbsp,
        at_size,
        after_window,
        width,
        height,
        window,
        chroma_shift,
        ctb,
        separate_planes,
        chroma_array_type: if separate_planes { 0 } else { chroma_format },
        poc_lsb_bits,
        st_rps_count,
        st_rps_deltas,
        long_term,
        lt_count,
        temporal_mvp,
        sao,
    })
}

impl Sps {
    /// The same SPS for a picture `height` luma rows tall, conformance window dropped.
    fn with_height(&self, height: u32) -> Vec<u8> {
        let mut w = Writer::default();
        w.copy(&self.rbsp, 0, self.at_size);
        w.ue(self.width);
        w.ue(height);
        w.bit(0);
        let stop = last_one_bit(&self.rbsp);
        w.copy(&self.rbsp, self.after_window, stop);
        w.align();
        w.b
    }
}

fn last_one_bit(rbsp: &[u8]) -> usize {
    let mut end = rbsp.len();
    while end > 0 && rbsp[end - 1] == 0 {
        end -= 1;
    }
    (end - 1) * 8 + (7 - rbsp[end - 1].trailing_zeros() as usize)
}

struct Pps {
    id: u32,
    sps: u32,
    dependent_slices: bool,
    output_flag: bool,
    extra_bits: u32,
    chroma_qp_offsets: bool,
    tiles_or_wpp: bool,
    loop_filter_across_slices: bool,
    deblock_override: bool,
    deblock_disabled: bool,
    header_extension: bool,
    chroma_qp_offset_list: bool,
}

fn parse_pps(rbsp: &[u8]) -> Option<Pps> {
    let mut r = Reader::new(rbsp);
    r.skip(16)?;
    let id = r.ue()?;
    let sps = r.ue()?;
    let dependent_slices = r.bit()? == 1;
    let output_flag = r.bit()? == 1;
    let extra_bits = r.u(3)?;
    r.bit()?;
    r.bit()?;
    r.ue()?;
    r.ue()?;
    r.se()?;
    r.bit()?;
    let transform_skip = r.bit()? == 1;
    if r.bit()? == 1 {
        r.ue()?;
    }
    r.se()?;
    r.se()?;
    let chroma_qp_offsets = r.bit()? == 1;
    r.bit()?;
    r.bit()?;
    r.bit()?;
    let tiles = r.bit()? == 1;
    let wpp = r.bit()? == 1;
    if tiles {
        return Some(Pps {
            id,
            sps,
            dependent_slices,
            output_flag,
            extra_bits,
            chroma_qp_offsets,
            tiles_or_wpp: true,
            loop_filter_across_slices: false,
            deblock_override: false,
            deblock_disabled: false,
            header_extension: false,
            chroma_qp_offset_list: false,
        });
    }
    let loop_filter_across_slices = r.bit()? == 1;
    let (mut deblock_override, mut deblock_disabled) = (false, false);
    if r.bit()? == 1 {
        deblock_override = r.bit()? == 1;
        deblock_disabled = r.bit()? == 1;
        if !deblock_disabled {
            r.se()?;
            r.se()?;
        }
    }
    if r.bit()? == 1 {
        scaling_list_data(&mut r)?;
    }
    r.bit()?;
    r.ue()?;
    let header_extension = r.bit()? == 1;
    let mut chroma_qp_offset_list = false;
    if r.bit()? == 1 {
        let range = r.bit()? == 1;
        let multilayer = r.bit()? == 1;
        let three_d = r.bit()? == 1;
        let scc = r.bit()? == 1;
        r.u(4)?;
        if multilayer || three_d || scc {
            return None;
        }
        if range {
            if transform_skip {
                r.ue()?;
            }
            r.bit()?;
            chroma_qp_offset_list = r.bit()? == 1;
        }
    }
    Some(Pps {
        id,
        sps,
        dependent_slices,
        output_flag,
        extra_bits,
        chroma_qp_offsets,
        tiles_or_wpp: wpp,
        loop_filter_across_slices,
        deblock_override,
        deblock_disabled,
        header_extension,
        chroma_qp_offset_list,
    })
}

pub struct Piece {
    /// First luma row of the strip in the full picture, and how many rows it decodes.
    pub y: u32,
    pub rows: u32,
    pub packet: Vec<u8>,
}

pub struct Split {
    pub pieces: Vec<Piece>,
    pub area: crate::scale::Area,
}

pub(crate) fn nal_units(packet: &[u8]) -> Vec<&[u8]> {
    let mut units = Vec::new();
    let mut i = 0;
    let mut start = None;
    while i + 3 <= packet.len() {
        if packet[i] == 0 && packet[i + 1] == 0 && packet[i + 2] == 1 {
            if let Some(s) = start {
                let mut end = i;
                while end > s && packet[end - 1] == 0 {
                    end -= 1;
                }
                units.push(&packet[s..end]);
            }
            i += 3;
            start = Some(i);
        } else {
            i += 1;
        }
    }
    if let Some(s) = start {
        units.push(&packet[s..]);
    }
    units
}

/// Splits an Annex B intra picture into per-slice pieces, or None when it can't be split safely.
pub fn split(packet: &[u8]) -> Option<Split> {
    let units = nal_units(packet);
    let mut vps = Vec::new();
    let mut spss: Vec<(Sps, &[u8])> = Vec::new();
    let mut ppss: Vec<(Pps, &[u8])> = Vec::new();
    let mut slices = Vec::new();
    for u in &units {
        // Base layer only (nuh_layer_id 0).
        if u.len() < 3 || (u[0] & 1) != 0 || (u[1] >> 3) != 0 {
            continue;
        }
        match (u[0] >> 1) & 0x3F {
            32 => vps.push(*u),
            33 => spss.push((parse_sps(unescape(u).0)?, *u)),
            34 => ppss.push((parse_pps(&unescape(u).0)?, *u)),
            t @ 16..=21 => slices.push((t, *u)),
            0..=15 => return None,
            _ => {}
        }
    }
    if slices.len() < 2 {
        return None;
    }
    let mut pieces = Vec::with_capacity(slices.len());
    let mut starts = Vec::with_capacity(slices.len());
    let mut sps_used: Option<&Sps> = None;
    for &(nal_type, unit) in &slices {
        // An I slice header is a few dozen bytes; the slice data is copied as it is.
        let (rbsp, from) = unescape(&unit[..unit.len().min(1024)]);
        let mut r = Reader::new(&rbsp);
        r.skip(16)?;
        let first = r.bit()? == 1;
        r.bit()?; // no_output_of_prior_pics_flag (IRAP)
        let pps_id = r.ue()?;
        let (pps, pps_raw) = ppss.iter().find(|(p, _)| p.id == pps_id)?;
        let (sps, _) = spss.iter().find(|(s, _)| s.id == pps.sps)?;
        if pps.tiles_or_wpp || sps.separate_planes || sps_used.is_some_and(|s| s.id != sps.id) {
            return None;
        }
        sps_used = Some(sps);
        let width_ctbs = sps.width.div_ceil(sps.ctb);
        let size_ctbs = width_ctbs * sps.height.div_ceil(sps.ctb);
        let mut address = 0;
        if !first {
            if pps.dependent_slices && r.bit()? == 1 {
                return None;
            }
            address = r.u(ceil_log2(size_ctbs))?;
        }
        if address % width_ctbs != 0 {
            return None;
        }
        let rest = r.pos;
        // The rest of an I slice header, to find where the slice data starts.
        r.u(pps.extra_bits)?;
        if r.ue()? != 2 {
            return None;
        }
        if pps.output_flag {
            r.bit()?;
        }
        if nal_type != 19 && nal_type != 20 {
            r.u(sps.poc_lsb_bits)?;
            if r.bit()? == 0 {
                st_ref_pic_set(
                    &mut r,
                    sps.st_rps_count,
                    sps.st_rps_count,
                    &sps.st_rps_deltas,
                )?;
            } else if sps.st_rps_count > 1 {
                r.u(ceil_log2(sps.st_rps_count as u32))?;
            }
            if sps.long_term {
                let lt_sps = if sps.lt_count > 0 { r.ue()? } else { 0 };
                let lt_pics = r.ue()?;
                for i in 0..lt_sps + lt_pics {
                    if i < lt_sps {
                        if sps.lt_count > 1 {
                            r.u(ceil_log2(sps.lt_count))?;
                        }
                    } else {
                        r.u(sps.poc_lsb_bits)?;
                        r.bit()?;
                    }
                    if r.bit()? == 1 {
                        r.ue()?;
                    }
                }
            }
            if sps.temporal_mvp {
                r.bit()?;
            }
        }
        let (mut sao_luma, mut sao_chroma) = (false, false);
        if sps.sao {
            sao_luma = r.bit()? == 1;
            if sps.chroma_array_type != 0 {
                sao_chroma = r.bit()? == 1;
            }
        }
        r.se()?;
        if pps.chroma_qp_offsets {
            r.se()?;
            r.se()?;
        }
        if pps.chroma_qp_offset_list {
            r.bit()?;
        }
        let mut deblock_disabled = pps.deblock_disabled;
        if pps.deblock_override && r.bit()? == 1 {
            deblock_disabled = r.bit()? == 1;
            if !deblock_disabled {
                r.se()?;
                r.se()?;
            }
        }
        if pps.loop_filter_across_slices && (sao_luma || sao_chroma || !deblock_disabled) {
            r.bit()?;
        }
        if pps.header_extension {
            let n = r.ue()?;
            r.skip(n as usize * 8)?;
        }
        let header_end = r.pos;
        // byte_alignment(): the data starts at the next byte boundary after the one bit.
        let data = (header_end + 1).div_ceil(8);
        let data_ebsp = *from.get(data)?;
        let mut w = Writer::default();
        w.copy(&rbsp, 0, 16);
        w.bit(1);
        w.copy(&rbsp, 17, 18);
        w.ue(pps_id);
        w.copy(&rbsp, rest, header_end);
        w.align();
        let mut slice = Vec::with_capacity(unit.len());
        escape(&w.b, &mut slice);
        slice.extend_from_slice(&unit[data_ebsp..]);
        starts.push(address / width_ctbs * sps.ctb);
        pieces.push((pps_raw, slice));
    }
    let sps = sps_used?;
    // Whole-row strips share the tallest height so decoders keep their buffers; the last keeps
    // its own, as CTUs crossing the bottom edge are coded differently.
    let n = pieces.len();
    let tallest = (0..n - 1)
        .map(|i| starts[i + 1] - starts[i])
        .max()
        .unwrap_or(0);
    let mut out = Vec::with_capacity(n);
    for (i, (pps_raw, slice)) in pieces.into_iter().enumerate() {
        let y = starts[i];
        let end = starts.get(i + 1).copied().unwrap_or(sps.height);
        if end <= y {
            return None;
        }
        let height = if i + 1 < n { tallest } else { end - y };
        let mut packet = Vec::with_capacity(slice.len() + 256);
        for v in &vps {
            packet.extend_from_slice(&START);
            packet.extend_from_slice(v);
        }
        packet.extend_from_slice(&START);
        escape(&sps.with_height(height), &mut packet);
        packet.extend_from_slice(&START);
        packet.extend_from_slice(pps_raw);
        packet.extend_from_slice(&START);
        packet.extend_from_slice(&slice);
        out.push(Piece {
            y,
            rows: end - y,
            packet,
        });
    }
    let (sw, sh) = (1 << sps.chroma_shift.0, 1 << sps.chroma_shift.1);
    Some(Split {
        pieces: out,
        area: crate::scale::Area {
            width: (sps.width - sw * (sps.window[0] + sps.window[1])) as usize,
            height: (sps.height - sh * (sps.window[2] + sps.window[3])) as usize,
            left: (sw * sps.window[0]) as usize,
            top: (sh * sps.window[2]) as usize,
            chroma: sps.chroma_shift,
        },
    })
}
