use crate::decode::Frame;
use crate::dovi::{Matrices, Reshape};

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Transfer {
    Sdr,
    Pq,
    Hlg,
}

const GRID: usize = 33;

/// Source Y'CbCr (Y' 0..1, chroma -0.5..0.5) to the JPEG's full-range BT.601 YCbCr.
pub struct Color {
    kr: f32,
    kb: f32,
    pub full: bool,
    transfer: Transfer,
    bt2020: bool,
    sdr: [[f32; 3]; 3],
    lut: Vec<[u8; 3]>,
    /// Dolby Vision profile 5, whose samples are reshaped IPT rather than Y'CbCr.
    dovi: Option<Matrices>,
}

/// In nits.
pub(crate) fn pq_eotf(e: f32) -> f32 {
    let (m1, m2) = (2610.0 / 16384.0, 2523.0 / 4096.0 * 128.0);
    let (c1, c2, c3) = (
        3424.0 / 4096.0,
        2413.0 / 4096.0 * 32.0,
        2392.0 / 4096.0 * 32.0,
    );
    let p = e.max(0.0).powf(1.0 / m2);
    10000.0 * ((p - c1).max(0.0) / (c2 - c3 * p)).powf(1.0 / m1)
}

fn hlg_inverse_oetf(e: f32) -> f32 {
    let a = 0.178_832_77_f32;
    let (b, c) = (1.0 - 4.0 * a, 0.5 - a * (4.0 * a).ln());
    if e <= 0.5 {
        e * e / 3.0
    } else {
        (((e - c) / a).exp() + b) / 12.0
    }
}

fn srgb(l: f32) -> f32 {
    let l = l.clamp(0.0, 1.0);
    if l <= 0.003_130_8 {
        12.92 * l
    } else {
        1.055 * l.powf(1.0 / 2.4) - 0.055
    }
}

/// R'G'B' (0..1) to JPEG YCbCr (0..255).
fn jpeg_ycbcr([r, g, b]: [f32; 3]) -> [f32; 3] {
    [
        255.0 * (0.299 * r + 0.587 * g + 0.114 * b),
        128.0 + 255.0 * (-0.168_736 * r - 0.331_264 * g + 0.5 * b),
        128.0 + 255.0 * (0.5 * r - 0.418_688 * g - 0.081_312 * b),
    ]
}

/// Rounded; `as` saturates out-of-range values.
fn byte(v: f32) -> u8 {
    (v + 0.5) as u8
}

impl Color {
    /// Codes are ITU-T H.273.
    pub fn new(matrix: i32, range: i32, transfer: i32, primaries: i32, height: i32) -> Color {
        let (kr, kb) = match matrix {
            1 => (0.2126, 0.0722),
            9 | 10 => (0.2627, 0.0593),
            5 | 6 => (0.299, 0.114),
            _ if primaries == 9 => (0.2627, 0.0593),
            _ if height > 576 => (0.2126, 0.0722),
            _ => (0.299, 0.114),
        };
        let transfer = match transfer {
            16 => Transfer::Pq,
            18 => Transfer::Hlg,
            _ => Transfer::Sdr,
        };
        let mut c = Color {
            kr,
            kb,
            full: range == 2,
            transfer,
            bt2020: primaries == 9 || matrix == 9,
            sdr: [[0.0; 3]; 3],
            lut: Vec::new(),
            dovi: None,
        };
        // Both steps are linear for SDR, so one matrix does them; columns are the unit samples.
        for (i, unit) in [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
            .into_iter()
            .enumerate()
        {
            let out = jpeg_ycbcr(c.rgb(unit));
            for (row, v) in out.into_iter().enumerate() {
                c.sdr[row][i] = v - if row == 0 { 0.0 } else { 128.0 };
            }
        }
        if transfer != Transfer::Sdr {
            c.build_lut();
        }
        c
    }

    /// For Dolby Vision profile 5, whose frames' reshaping [`Accum::ycbcr`] is given.
    pub fn dovi(matrices: Matrices) -> Color {
        let mut c = Color::new(9, 2, 16, 9, 2160);
        c.dovi = Some(matrices);
        c.build_lut();
        c
    }

    /// Indexed by Y'CbCr, or by IPT with P and T centred like chroma.
    fn build_lut(&mut self) {
        let step = 1.0 / (GRID - 1) as f32;
        self.lut = (0..GRID * GRID * GRID)
            .map(|i| {
                let (y, cb, cr) = (i / (GRID * GRID), i / GRID % GRID, i % GRID);
                let sample = [
                    y as f32 * step,
                    cb as f32 * step - 0.5,
                    cr as f32 * step - 0.5,
                ];
                jpeg_ycbcr(self.hdr(sample).map(srgb)).map(byte)
            })
            .collect();
    }

    pub fn transfer(&self) -> Transfer {
        self.transfer
    }

    fn rgb(&self, [y, cb, cr]: [f32; 3]) -> [f32; 3] {
        let (kr, kb) = (self.kr, self.kb);
        let r = y + 2.0 * (1.0 - kr) * cr;
        let b = y + 2.0 * (1.0 - kb) * cb;
        [r, (y - kr * r - kb * b) / (1.0 - kr - kb), b]
    }

    /// An HDR sample to linear SDR light.
    fn hdr(&self, sample: [f32; 3]) -> [f32; 3] {
        let light = match self.dovi {
            Some(m) => m
                .light([sample[0], sample[1] + 0.5, sample[2] + 0.5])
                .map(|v| v.max(0.0) / 100.0),
            None => self.light(self.rgb(sample)),
        };
        self.tone_map(light)
    }

    /// HDR R'G'B' to display light in units of SDR white (100 nits).
    fn light(&self, rgb: [f32; 3]) -> [f32; 3] {
        let [mut r, mut g, mut b] = rgb.map(|v| {
            let e = v.clamp(0.0, 1.0);
            match self.transfer {
                Transfer::Pq => pq_eotf(e) / 100.0,
                _ => hlg_inverse_oetf(e) * 10.0,
            }
        });
        if self.transfer == Transfer::Hlg {
            // HLG system gamma 1.2 at 1000 nits.
            let f = ((0.2627 * r + 0.678 * g + 0.0593 * b).max(1e-6) / 10.0).powf(0.2);
            (r, g, b) = (r * f, g * f, b * f);
        }
        [r, g, b]
    }

    /// Display light to linear SDR light.
    fn tone_map(&self, [mut r, mut g, mut b]: [f32; 3]) -> [f32; 3] {
        if self.bt2020 {
            (r, g, b) = (
                1.6605 * r - 0.5876 * g - 0.0728 * b,
                -0.1246 * r + 1.1329 * g - 0.0083 * b,
                -0.0182 * r - 0.1006 * g + 1.1187 * b,
            );
        }
        // Extended Reinhard on the brightest channel, 1000-nit peak.
        let m = r.max(g).max(b);
        if m > 0.0 {
            let s = (1.0 + m / 100.0) / (1.0 + m);
            (r, g, b) = (r * s, g * s, b * s);
        }
        [r, g, b]
    }

    fn convert(&self, y: f32, cb: f32, cr: f32, out: &mut [u8; 3]) {
        if self.lut.is_empty() {
            self.matrix(y, cb, cr, out)
        } else {
            self.table(y, cb, cr, out)
        }
    }

    #[inline(always)]
    fn matrix(&self, y: f32, cb: f32, cr: f32, out: &mut [u8; 3]) {
        let m = &self.sdr;
        out[0] = byte(m[0][0] * y + m[0][1] * cb + m[0][2] * cr);
        out[1] = byte(128.0 + m[1][0] * y + m[1][1] * cb + m[1][2] * cr);
        out[2] = byte(128.0 + m[2][0] * y + m[2][1] * cb + m[2][2] * cr);
    }

    #[inline(always)]
    fn table(&self, y: f32, cb: f32, cr: f32, out: &mut [u8; 3]) {
        let s = (GRID - 1) as f32;
        let at = |v: f32| {
            let f = v.clamp(0.0, 1.0) * s;
            let i = (f as usize).min(GRID - 2);
            (i, f - i as f32)
        };
        let ((iy, fy), (ib, fb), (ir, fr)) = (at(y), at(cb + 0.5), at(cr + 0.5));
        let base = (iy * GRID + ib) * GRID + ir;
        let corner =
            |dy: usize, db: usize, dr: usize| &self.lut[base + (dy * GRID + db) * GRID + dr];
        let (c000, c001, c010, c011) = (
            corner(0, 0, 0),
            corner(0, 0, 1),
            corner(0, 1, 0),
            corner(0, 1, 1),
        );
        let (c100, c101, c110, c111) = (
            corner(1, 0, 0),
            corner(1, 0, 1),
            corner(1, 1, 0),
            corner(1, 1, 1),
        );
        let lerp = |a: f32, b: f32, t: f32| a + (b - a) * t;
        for k in 0..3 {
            let (v00, v01) = (c000[k] as f32, c001[k] as f32);
            let (v10, v11) = (c010[k] as f32, c011[k] as f32);
            let (w00, w01) = (c100[k] as f32, c101[k] as f32);
            let (w10, w11) = (c110[k] as f32, c111[k] as f32);
            let near = lerp(lerp(v00, v01, fr), lerp(v10, v11, fr), fb);
            let far = lerp(lerp(w00, w01, fr), lerp(w10, w11, fr), fb);
            out[k] = byte(lerp(near, far, fy));
        }
    }

    /// The JPEG YCbCr of a source sample without the lookup table, for checking it.
    pub fn exact(&self, y: f32, cb: f32, cr: f32) -> [u8; 3] {
        if self.transfer == Transfer::Sdr {
            let rgb = self.rgb([y, cb, cr]);
            return jpeg_ycbcr(rgb.map(|v| v.clamp(0.0, 1.0))).map(byte);
        }
        jpeg_ycbcr(self.hdr([y, cb, cr]).map(srgb)).map(byte)
    }

    pub fn in_gamut(&self, y: f32, cb: f32, cr: f32) -> bool {
        self.rgb([y, cb, cr])
            .iter()
            .all(|v| (0.0..=1.0).contains(v))
    }

    pub fn fast(&self, y: f32, cb: f32, cr: f32) -> [u8; 3] {
        let mut out = [0u8; 3];
        self.convert(y, cb, cr, &mut out);
        out
    }
}

/// `n` evenly spread taps inside each output cell along one axis, flattened, shifted by `offset`.
fn taps(src: usize, out: usize, n: usize, offset: usize) -> Vec<u32> {
    let mut v = Vec::with_capacity(out * n);
    for o in 0..out {
        let a = o * src / out;
        let w = ((o + 1) * src / out).max(a + 1) - a;
        for t in 0..n {
            v.push((offset + a + (2 * t + 1) * w / (2 * n)) as u32);
        }
    }
    v
}

struct Plane {
    xs: Vec<u32>,
    ys: Vec<u32>,
    n: usize,
    shift_y: u32,
    sums: Vec<u32>,
}

/// A thumbnail built strip by strip: each decoded strip adds the taps that fall inside it.
pub struct Accum {
    ow: usize,
    oh: usize,
    bits: i32,
    /// Colour codes the bitstream signals: matrix, range, transfer, primaries.
    pub signalled: Option<[i32; 4]>,
    planes: [Plane; 3],
}

fn add_plane<T: Copy + Into<u32>, const N: usize>(
    p: &mut Plane,
    ow: usize,
    data: *const u8,
    stride: usize,
    first: usize,
    rows: usize,
) {
    let lo = p.ys.partition_point(|&y| (y as usize) < first);
    let hi = p.ys.partition_point(|&y| (y as usize) < first + rows);
    for k in lo..hi {
        let oy = k / N;
        let row = unsafe { data.add((p.ys[k] as usize - first) * stride) as *const T };
        let sums = &mut p.sums[oy * ow..(oy + 1) * ow];
        for (sum, cell) in sums.iter_mut().zip(p.xs.as_chunks::<N>().0) {
            let mut s = 0u32;
            for &x in cell {
                s += unsafe { (*row.add(x as usize)).into() };
            }
            *sum += s;
        }
    }
}

type AddPlane = fn(&mut Plane, usize, *const u8, usize, usize, usize);

fn add_for(wide: bool, n: usize) -> AddPlane {
    match (wide, n) {
        (false, 1) => add_plane::<u8, 1>,
        (false, 2) => add_plane::<u8, 2>,
        (false, 3) => add_plane::<u8, 3>,
        (false, _) => add_plane::<u8, 4>,
        (true, 1) => add_plane::<u16, 1>,
        (true, 2) => add_plane::<u16, 2>,
        (true, 3) => add_plane::<u16, 3>,
        (true, _) => add_plane::<u16, 4>,
    }
}

/// The shown part of a picture, in coded luma samples, and its chroma subsampling shifts.
#[derive(Clone, Copy)]
pub struct Area {
    pub width: usize,
    pub height: usize,
    pub left: usize,
    pub top: usize,
    pub chroma: (u32, u32),
}

impl Accum {
    /// Samples about `samples` taps per cell edge.
    pub fn new(area: Area, ow: usize, oh: usize, samples: usize) -> Accum {
        let n_taps = |src: usize, out: usize| samples.clamp(1, 4).min((src / out).max(1));
        let plane = |sx: u32, sy: u32| {
            let (pw, ph) = (
                (area.width + (1 << sx) - 1) >> sx,
                (area.height + (1 << sy) - 1) >> sy,
            );
            let n = n_taps(pw, ow).min(n_taps(ph, oh));
            Plane {
                xs: taps(pw, ow, n, area.left >> sx),
                ys: taps(ph, oh, n, area.top >> sy),
                n,
                shift_y: sy,
                sums: vec![0; ow * oh],
            }
        };
        let (sx, sy) = area.chroma;
        Accum {
            ow,
            oh,
            bits: 8,
            signalled: None,
            planes: [plane(0, 0), plane(sx, sy), plane(sx, sy)],
        }
    }

    pub fn whole(f: &Frame, ow: usize, oh: usize, samples: usize) -> Result<Accum, String> {
        let area = Area {
            width: f.width as usize,
            height: f.height as usize,
            left: 0,
            top: 0,
            chroma: (f.log2_chroma_w as u32, f.log2_chroma_h as u32),
        };
        let mut a = Accum::new(area, ow, oh, samples);
        a.add(f, 0, f.height as usize)?;
        Ok(a)
    }

    /// Adds the first `rows` luma rows of a decoded strip whose first row is coded luma row `y`.
    pub fn add(&mut self, f: &Frame, y: usize, rows: usize) -> Result<(), String> {
        if f.planar_yuv == 0 {
            return Err(format!("unsupported pixel format {}", f.pix_fmt()));
        }
        self.bits = f.bits;
        self.signalled
            .get_or_insert([f.colorspace, f.color_range, f.color_trc, f.color_primaries]);
        let ow = self.ow;
        for (i, p) in self.planes.iter_mut().enumerate() {
            let shift = if i == 0 { 0 } else { f.log2_chroma_h as u32 };
            debug_assert_eq!(shift, p.shift_y);
            let rows = (rows.min(f.height as usize) + (1 << shift) - 1) >> shift;
            add_for(f.bits > 8, p.n)(p, ow, f.data[i], f.linesize[i] as usize, y >> shift, rows);
        }
        Ok(())
    }

    /// The thumbnail as interleaved JPEG YCbCr; Dolby Vision profile 5 needs its frame's reshaping.
    pub fn ycbcr(&self, color: &Color, reshape: Option<&Reshape>) -> Vec<u8> {
        if color.lut.is_empty() {
            self.convert(color, reshape, Color::matrix)
        } else {
            self.convert(color, reshape, Color::table)
        }
    }

    /// As [`Accum::ycbcr`] without the lookup table, for checking it.
    pub fn ycbcr_exact(&self, color: &Color, reshape: Option<&Reshape>) -> Vec<u8> {
        self.convert(color, reshape, |c, y, u, v, px| {
            px.copy_from_slice(&c.exact(y, u, v))
        })
    }

    fn convert(
        &self,
        color: &Color,
        reshape: Option<&Reshape>,
        one: impl Fn(&Color, f32, f32, f32, &mut [u8; 3]),
    ) -> Vec<u8> {
        if let Some(r) = reshape {
            return self.convert_reshaped(color, r, one);
        }
        let scale = (1u32 << (self.bits - 8)) as f32;
        let (y0, y1, c1) = if color.full {
            (0.0, 255.0, 255.0)
        } else {
            (16.0, 219.0, 224.0)
        };
        let affine = |p: &Plane, offset: f32, span: f32| {
            let a = 1.0 / ((p.n * p.n) as f32 * span * scale);
            (a, -offset / span)
        };
        let [py, pu, pv] = &self.planes;
        let (ya, yb) = affine(py, y0, y1);
        let (ua, ub) = affine(pu, 128.0, c1);
        let (va, vb) = affine(pv, 128.0, c1);
        let mut out = vec![0u8; self.ow * self.oh * 3];
        for (px, ((&y, &u), &v)) in out
            .as_chunks_mut::<3>()
            .0
            .iter_mut()
            .zip(py.sums.iter().zip(&pu.sums).zip(&pv.sums))
        {
            one(
                color,
                y as f32 * ya + yb,
                u as f32 * ua + ub,
                v as f32 * va + vb,
                px,
            );
        }
        out
    }

    /// Reshaped after scaling, which is close enough at this size.
    fn convert_reshaped(
        &self,
        color: &Color,
        reshape: &Reshape,
        one: impl Fn(&Color, f32, f32, f32, &mut [u8; 3]),
    ) -> Vec<u8> {
        let max = ((1u32 << self.bits) - 1) as f32;
        let [py, pu, pv] = &self.planes;
        let [ya, ua, va] = [py, pu, pv].map(|p| 1.0 / ((p.n * p.n) as f32 * max));
        let mut out = vec![0u8; self.ow * self.oh * 3];
        for (px, ((&y, &u), &v)) in out
            .as_chunks_mut::<3>()
            .0
            .iter_mut()
            .zip(py.sums.iter().zip(&pu.sums).zip(&pv.sums))
        {
            let [i, p, t] = reshape.apply([y as f32 * ya, u as f32 * ua, v as f32 * va]);
            one(color, i, p - 0.5, t - 0.5, px);
        }
        out
    }
}

pub fn jpeg(ycbcr: &[u8], w: usize, h: usize, quality: u8) -> Vec<u8> {
    let mut out = Vec::with_capacity(16 * 1024);
    jpeg_encoder::Encoder::new(&mut out, quality)
        .encode(ycbcr, w as u16, h as u16, jpeg_encoder::ColorType::Ycbcr)
        .expect("jpeg");
    out
}
