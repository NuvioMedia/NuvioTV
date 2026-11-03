//! Checks Dolby Vision profile 5 thumbnails against a full-size BT.2020 PQ render of the same
//! keyframe (gbrp16le), such as libplacebo's.

use std::path::Path;

use aiostreams_thumbs::{decode, dovi, scale};

use crate::{Res, load};

fn pq_oetf(nits: f32) -> f32 {
    let (m1, m2) = (2610.0 / 16384.0, 2523.0 / 4096.0 * 128.0);
    let (c1, c2, c3) = (
        3424.0 / 4096.0,
        2413.0 / 4096.0 * 32.0,
        2392.0 / 4096.0 * 32.0,
    );
    let y = (nits / 10000.0).max(0.0).powf(m1);
    ((c1 + c2 * y) / (1.0 + c3 * y)).powf(m2)
}

fn report(name: &str, mut d: Vec<f32>) {
    d.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let at = |p: f64| d[((d.len() - 1) as f64 * p) as usize];
    println!(
        "{name}: mean {:.2}, p50 {:.2}, p99 {:.2}, max {:.2}",
        d.iter().sum::<f32>() / d.len() as f32,
        at(0.5),
        at(0.99),
        at(1.0)
    );
}

pub fn run(dir: &Path, kf: usize, raw: &Path, out: Option<&String>) -> Res<()> {
    let (_, packets) = load(dir)?;
    let packet = packets.get(kf).ok_or("no such keyframe")?;
    let rpu = dovi::Rpu::find(packet, None).ok_or("no RPU")?;
    let reference = std::fs::read(raw).map_err(|e| e.to_string())?;
    let options = |skip| decode::Options {
        threads: 4,
        skip_loop_filter: skip,
        fast: false,
    };

    let mut full = decode::Decoder::open("hevc", &options(false))?;
    let f = full.decode(packet)?;
    let (w, h) = (f.width as usize, f.height as usize);
    if reference.len() != w * h * 6 {
        return Err(format!("reference is not {w}x{h} gbrp16le"));
    }
    // gbrp16le planes are G, B, R.
    let refpx = |i: usize| -> [f32; 3] {
        let at = |k: usize| {
            let o = (k * w * h + i) * 2;
            u16::from_le_bytes([reference[o], reference[o + 1]]) as f32 / 65535.0
        };
        [at(2), at(0), at(1)]
    };
    let max = ((1 << f.bits) - 1) as f32;
    let sample = |plane: usize, x: usize, y: usize| unsafe {
        *(f.data[plane].add(y * f.linesize[plane] as usize) as *const u16).add(x) as f32 / max
    };
    let mut diffs = [Vec::new(), Vec::new(), Vec::new()];
    for y in (0..h).step_by(2) {
        for x in (0..w).step_by(2) {
            let coded = [
                sample(0, x, y),
                sample(1, x / 2, y / 2),
                sample(2, x / 2, y / 2),
            ];
            let pq = rpu.matrices.light(rpu.reshape.apply(coded)).map(pq_oetf);
            let r = refpx(y * w + x);
            for c in 0..3 {
                diffs[c].push((pq[c] - r[c]).abs() * 1023.0);
            }
        }
    }
    for (c, d) in ["R", "G", "B"].iter().zip(diffs) {
        report(&format!("full size PQ {c} (10-bit steps)"), d);
    }

    let mut thumb = decode::Decoder::open("hevc", &options(true))?;
    let f = thumb.decode(packet)?;
    let (ow, oh) = (320, (320 * h / w).div_ceil(2) * 2);
    let acc = scale::Accum::whole(f, ow, oh, 2)?;
    let got = acc.ycbcr(&scale::Color::dovi(rpu.matrices), Some(&rpu.reshape));
    let hdr10 = scale::Color::new(9, 2, 16, 9, h as i32);
    let mut expected = vec![0u8; ow * oh * 3];
    for oy in 0..oh {
        for ox in 0..ow {
            let (x0, x1) = (ox * w / ow, (ox + 1) * w / ow);
            let (y0, y1) = (oy * h / oh, (oy + 1) * h / oh);
            let mut sum = [0.0f32; 3];
            for y in y0..y1 {
                for x in x0..x1 {
                    let p = refpx(y * w + x);
                    (0..3).for_each(|c| sum[c] += p[c]);
                }
            }
            let [r, g, b] = sum.map(|s| s / ((x1 - x0) * (y1 - y0)) as f32);
            let luma = 0.2627 * r + 0.678 * g + 0.0593 * b;
            let px = hdr10.exact(luma, (b - luma) / 1.8814, (r - luma) / 1.4746);
            expected[(oy * ow + ox) * 3..][..3].copy_from_slice(&px);
        }
    }
    let mut d = [Vec::new(), Vec::new(), Vec::new()];
    for (g, e) in got.chunks(3).zip(expected.chunks(3)) {
        for c in 0..3 {
            d[c].push((g[c] as f32 - e[c] as f32).abs());
        }
    }
    for (c, d) in ["Y", "Cb", "Cr"].iter().zip(d) {
        report(&format!("thumbnail JPEG {c} (8-bit steps)"), d);
    }
    if let Some(path) = out {
        let mut both = got;
        both.extend_from_slice(&expected);
        std::fs::write(path, scale::jpeg(&both, ow, oh * 2, 90)).map_err(|e| e.to_string())?;
    }
    Ok(())
}
