//! Dolby Vision profile 5: reshaped IPTPQc2 with no HDR10 picture underneath, undone with the
//! RPU each frame carries the way libplacebo does it.

use dolby_vision::rpu::dovi_rpu::DoviRpu;
use dolby_vision::rpu::rpu_data_header::RpuDataHeader;
use dolby_vision::rpu::rpu_data_mapping::{DoviReshapingCurve, RpuDataMapping};
use dolby_vision::rpu::vdr_dm_data::VdrDmData;

use crate::scale::pq_eotf;

/// BT.2020-referred Hunt-Pointer-Estevez LMS to BT.2020 RGB, libplacebo's constant.
const LMS_TO_RGB: [[f32; 3]; 3] = [
    [3.064_418_8, -2.165_976_8, 0.101_558_18],
    [-0.656_121_1, 1.785_541_2, -0.129_437_49],
    [0.017_363_21, -0.047_251_54, 1.030_042_5],
];

enum Piece {
    /// Coefficients of 1, s and s².
    Poly([f32; 3]),
    /// Per order: the 3 samples' powers, then their 4 products' powers.
    Mmr {
        constant: f32,
        orders: Vec<[f32; 7]>,
    },
}

struct Curve {
    /// Pivots between pieces, without the first and last.
    inner: Vec<f32>,
    lo: f32,
    hi: f32,
    pieces: Vec<Piece>,
}

/// One frame's reshaping, from coded samples to IPT.
pub struct Reshape {
    curves: [Curve; 3],
    /// The highest MMR order of any piece.
    orders: usize,
}

/// What turns reshaped samples into light: the same for most of a file.
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Matrices {
    nonlinear: [[f32; 3]; 3],
    offset: [f32; 3],
    /// The RPU's RGB to LMS, then LMS to BT.2020.
    linear: [[f32; 3]; 3],
}

pub struct Rpu {
    pub reshape: std::sync::Arc<Reshape>,
    pub matrices: Matrices,
}

fn mul(a: &[[f32; 3]; 3], b: &[[f32; 3]; 3]) -> [[f32; 3]; 3] {
    std::array::from_fn(|i| std::array::from_fn(|j| (0..3).map(|k| a[i][k] * b[k][j]).sum()))
}

fn apply(m: &[[f32; 3]; 3], v: [f32; 3]) -> [f32; 3] {
    std::array::from_fn(|i| m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2])
}

fn coefficient(header: &RpuDataHeader, int: Option<i64>, fraction: u64) -> f32 {
    if header.coefficient_data_type == 1 {
        f32::from_bits(fraction as u32)
    } else {
        int.unwrap_or(0) as f32 + fraction as f32 / (1u64 << header.coefficient_log2_denom) as f32
    }
}

impl Curve {
    fn new(header: &RpuDataHeader, curve: &DoviReshapingCurve) -> Option<Curve> {
        let max = ((1u32 << (header.bl_bit_depth_minus8 + 8)) - 1) as f32;
        // Coded as differences from the previous pivot.
        let pivots: Vec<f32> = curve
            .pivots
            .iter()
            .scan(0u32, |sum, &d| {
                *sum = (*sum + d as u32).min(u16::MAX as u32);
                Some(*sum as f32 / max)
            })
            .collect();
        let n = pivots.len().checked_sub(1).filter(|&n| n > 0)?;
        let c = |int: Option<&i64>, fraction: &u64| coefficient(header, int.copied(), *fraction);
        let pieces = if let Some(p) = &curve.polynomial {
            (0..n)
                .map(|i| {
                    let (ints, fractions) = (p.poly_coef_int.get(i), p.poly_coef.get(i)?);
                    let mut k = [0.0; 3];
                    for (j, f) in fractions.iter().enumerate().take(3) {
                        k[j] = c(ints.and_then(|v| v.get(j)), f);
                    }
                    Some(Piece::Poly(k))
                })
                .collect::<Option<Vec<_>>>()?
        } else {
            let m = curve.mmr.as_ref()?;
            (0..n)
                .map(|i| {
                    let fractions = m.mmr_coef.get(i)?;
                    let ints = m.mmr_coef_int.get(i);
                    let orders = (0..fractions.len())
                        .map(|j| {
                            std::array::from_fn(|k| {
                                c(ints.and_then(|v| v.get(j)?.get(k)), &fractions[j][k])
                            })
                        })
                        .collect();
                    let constant = c(m.mmr_constant_int.get(i), m.mmr_constant.get(i)?);
                    Some(Piece::Mmr { constant, orders })
                })
                .collect::<Option<Vec<_>>>()?
        };
        Some(Curve {
            inner: pivots[1..n].to_vec(),
            lo: pivots[0],
            hi: pivots[n],
            pieces,
        })
    }
}

impl Reshape {
    fn new(header: &RpuDataHeader, mapping: &RpuDataMapping) -> Option<Reshape> {
        let [y, u, v] = &mapping.curves;
        let curves = [
            Curve::new(header, y)?,
            Curve::new(header, u)?,
            Curve::new(header, v)?,
        ];
        let orders = curves
            .iter()
            .flat_map(|c| &c.pieces)
            .map(|p| match p {
                Piece::Mmr { orders, .. } => orders.len(),
                Piece::Poly(_) => 0,
            })
            .max()
            .unwrap_or(0);
        (orders <= 3).then_some(Reshape { curves, orders })
    }

    /// Coded samples, each 0..1 of its full range, to IPT.
    pub fn apply(&self, coded: [f32; 3]) -> [f32; 3] {
        let [y, u, v] = coded.map(|v| v.clamp(0.0, 1.0));
        // The MMR terms of each order, shared by both chroma curves.
        let mut terms = [[y, u, v, y * u, y * v, u * v, y * u * v]; 3];
        for j in 1..self.orders {
            terms[j] = std::array::from_fn(|k| terms[j - 1][k] * terms[0][k]);
        }
        std::array::from_fn(|c| {
            let curve = &self.curves[c];
            let s = terms[0][c];
            let piece = curve.inner.iter().take_while(|&&p| s >= p).count();
            let out = match &curve.pieces[piece] {
                Piece::Poly(k) => (k[2] * s + k[1]) * s + k[0],
                Piece::Mmr { constant, orders } => {
                    orders
                        .iter()
                        .zip(&terms)
                        .map(|(w, t)| w.iter().zip(t).map(|(w, t)| w * t).sum::<f32>())
                        .sum::<f32>()
                        + constant
                }
            };
            out.clamp(curve.lo, curve.hi)
        })
    }
}

impl Matrices {
    fn new(dm: &VdrDmData) -> Matrices {
        let m = |c: [i16; 9], scale: f32| -> [[f32; 3]; 3] {
            std::array::from_fn(|i| std::array::from_fn(|j| c[i * 3 + j] as f32 / scale))
        };
        let nonlinear = m(
            [
                dm.ycc_to_rgb_coef0,
                dm.ycc_to_rgb_coef1,
                dm.ycc_to_rgb_coef2,
                dm.ycc_to_rgb_coef3,
                dm.ycc_to_rgb_coef4,
                dm.ycc_to_rgb_coef5,
                dm.ycc_to_rgb_coef6,
                dm.ycc_to_rgb_coef7,
                dm.ycc_to_rgb_coef8,
            ],
            (1 << 13) as f32,
        );
        let lms = m(
            [
                dm.rgb_to_lms_coef0,
                dm.rgb_to_lms_coef1,
                dm.rgb_to_lms_coef2,
                dm.rgb_to_lms_coef3,
                dm.rgb_to_lms_coef4,
                dm.rgb_to_lms_coef5,
                dm.rgb_to_lms_coef6,
                dm.rgb_to_lms_coef7,
                dm.rgb_to_lms_coef8,
            ],
            (1 << 14) as f32,
        );
        let offset = [
            dm.ycc_to_rgb_offset0,
            dm.ycc_to_rgb_offset1,
            dm.ycc_to_rgb_offset2,
        ]
        .map(|o| o as f32 / (1u32 << 28) as f32);
        Matrices {
            nonlinear,
            offset,
            linear: mul(&LMS_TO_RGB, &lms),
        }
    }

    /// Linear BT.2020 light in nits from IPT.
    pub fn light(&self, ipt: [f32; 3]) -> [f32; 3] {
        let centred = std::array::from_fn(|i| ipt[i] - self.offset[i]);
        let lms = apply(&self.nonlinear, centred).map(|e| pq_eotf(e.min(1.0)));
        apply(&self.linear, lms)
    }
}

impl Rpu {
    /// The RPU in a packet's UNSPEC62 NAL unit; a frame that refers to an earlier one's
    /// reshaping or matrices takes them from `previous`.
    pub fn find(packet: &[u8], previous: Option<&Rpu>) -> Option<Rpu> {
        let nal = crate::hevc::nal_units(packet)
            .into_iter()
            .find(|n| n.len() > 2 && n[0] >> 1 == 62)?;
        // The parser panics on curves it doesn't implement, which no encoder is known to use.
        let rpu = std::panic::catch_unwind(|| DoviRpu::parse_unspec62_nalu(nal))
            .ok()?
            .ok()?;
        let reshape = rpu
            .rpu_data_mapping
            .as_ref()
            .and_then(|m| Reshape::new(&rpu.header, m))
            .map(std::sync::Arc::new)
            .or_else(|| previous.map(|p| p.reshape.clone()))?;
        let matrices = rpu
            .vdr_dm_data
            .as_ref()
            .map(Matrices::new)
            .or_else(|| previous.map(|p| p.matrices))?;
        Some(Rpu { reshape, matrices })
    }
}
