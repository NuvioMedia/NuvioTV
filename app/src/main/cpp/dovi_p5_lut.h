// Dolby Vision Profile 5 -> HDR10 colour mapping, baked into a 3D lookup table.
//
// Profile 5 carries no HDR10 base layer: its pixels are Dolby's IPT-PQ-c2 signal, which only
// looks right after the per-scene reshaping and colour matrices in the RPU are applied. Every
// step from the decoded pixel to the HDR10 output depends only on that pixel's three values and
// on the RPU, so the whole chain is a 3D -> 3D function. It is evaluated once per distinct RPU
// mapping on the CPU into a small LUT; the GPU then does one lookup per pixel.
//
// Pure maths, no JNI and no libdovi, so it can be unit tested on the host.

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace dovi_p5 {

constexpr int kMaxPieces = 8;   // num_pivots is at most 9
constexpr int kMaxPolyOrder = 2; // poly_order_minus1 is at most 1
constexpr int kMaxMmrOrder = 3;  // mmr_order_minus1 is at most 2

/** One reshaping piece of one component. */
struct Piece {
    // Polynomial (luma): value = sum(poly[k] * x^k), k = 0..polyOrder.
    int polyOrder = 1;
    double poly[kMaxPolyOrder + 1] = {0.0, 1.0, 0.0};
    // MMR (chroma): value = constant + sum over orders of coef[o][j] * term_j^(o + 1).
    bool mmr = false;
    int mmrOrder = 0;
    double mmrConstant = 0.0;
    double mmrCoef[kMaxMmrOrder][7] = {};
};

/** The reshaping curve of one component: pieces split at pivots (normalized to [0, 1]). */
struct Curve {
    int numPieces = 1;
    double pivots[kMaxPieces + 1] = {0.0, 1.0};
    Piece pieces[kMaxPieces];
};

/** Everything of one RPU that the mapping depends on. */
struct Params {
    Curve curves[3];
    // IPT -> nonlinear LMS: lms' = nonlinear * (ipt - nonlinearOffset), row-major. The
    // defaults are the usual Profile 5 values (RPU fixed point: /8192 and /2^28), used only
    // until an RPU that carries its own arrives.
    double nonlinear[9] = {8192 / 8192.0, 799 / 8192.0, 1681 / 8192.0,
                           8192 / 8192.0, -933 / 8192.0, 1091 / 8192.0,
                           8192 / 8192.0, 267 / 8192.0, -5545 / 8192.0};
    double nonlinearOffset[3] = {0.0, 0.5, 0.5};
    // Linear BT.2020 RGB -> linear LMS, row-major (RPU fixed point: /16384). Inverted when the
    // LUT is built.
    double rgbToLms[9] = {7222 / 16384.0, 8771 / 16384.0, 390 / 16384.0,
                          2654 / 16384.0, 12430 / 16384.0, 1300 / 16384.0,
                          0 / 16384.0, 422 / 16384.0, 15962 / 16384.0};
};

/** Stable 64-bit hash of [params], so identical mappings share one LUT. Never returns 0. */
uint64_t hashParams(const Params& params);

/** Applies the reshaping to a normalized base-layer pixel [s] (each in [0, 1]); writes IPT. */
void reshape(const Params& params, const double s[3], double outIpt[3]);

/**
 * Maps one pixel as Media3 hands it to a video effect, to HDR10.
 *
 * [rgbPq] is the PQ-encoded BT.2020 RGB that Media3's HDR input shader computed from the decoded
 * base-layer values with its BT.2020 full-range YUV -> RGB matrix and (0.0625, 0.5, 0.5) offset.
 * That step is inverted to recover the decoded values, which are then reshaped, converted from
 * IPT-PQ-c2 to linear BT.2020 and PQ-encoded again. Output is PQ BT.2020 RGB in [0, 1].
 */
void mapPixel(const Params& params, const double rgbPq[3], double outRgbPq[3]);

/**
 * Builds a [size]^3 RGBA half-float LUT (R fastest, then G, then B), indexed by Media3's
 * PQ-encoded RGB, holding the HDR10 PQ RGB for that input. [out] gets size^3 * 4 halves.
 */
void buildLut(const Params& params, int size, uint16_t* out);

uint16_t floatToHalf(float value);

double pqEotf(double v);
double pqOetf(double v);

}  // namespace dovi_p5
