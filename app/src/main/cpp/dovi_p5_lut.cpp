#include "dovi_p5_lut.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace dovi_p5 {
namespace {

// SMPTE ST 2084 constants.
constexpr double kPqM1 = 2610.0 / 16384.0;
constexpr double kPqM2 = 2523.0 / 4096.0 * 128.0;
constexpr double kPqC1 = 3424.0 / 4096.0;
constexpr double kPqC2 = 2413.0 / 4096.0 * 32.0;
constexpr double kPqC3 = 2392.0 / 4096.0 * 32.0;

// Media3's BT.2020 full-range YUV -> RGB matrix (DefaultShaderProgram), row-major here, and the
// offset its HDR input shader subtracts first (fragment_shader_transformation_external_yuv_es3).
constexpr double kMedia3YuvToRgb[9] = {
    1.0, 0.0, 1.4746,
    1.0, -0.1646, -0.5714,
    1.0, 1.8814, 0.0,
};
constexpr double kMedia3YuvOffset[3] = {0.0625, 0.5, 0.5};

inline double clamp01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }

void mul3(const double m[9], const double v[3], double out[3]) {
    const double x = v[0], y = v[1], z = v[2];
    out[0] = m[0] * x + m[1] * y + m[2] * z;
    out[1] = m[3] * x + m[4] * y + m[5] * z;
    out[2] = m[6] * x + m[7] * y + m[8] * z;
}

bool invert3(const double m[9], double out[9]) {
    const double a = m[0], b = m[1], c = m[2];
    const double d = m[3], e = m[4], f = m[5];
    const double g = m[6], h = m[7], i = m[8];
    const double A = e * i - f * h;
    const double B = -(d * i - f * g);
    const double C = d * h - e * g;
    const double det = a * A + b * B + c * C;
    if (std::fabs(det) < 1e-12) return false;
    const double inv = 1.0 / det;
    out[0] = A * inv;
    out[1] = -(b * i - c * h) * inv;
    out[2] = (b * f - c * e) * inv;
    out[3] = B * inv;
    out[4] = (a * i - c * g) * inv;
    out[5] = -(a * f - c * d) * inv;
    out[6] = C * inv;
    out[7] = -(a * h - b * g) * inv;
    out[8] = (a * e - b * d) * inv;
    return true;
}

int pieceIndex(const Curve& curve, double x) {
    const int n = std::max(1, std::min(curve.numPieces, kMaxPieces));
    for (int i = 0; i < n - 1; ++i) {
        if (x < curve.pivots[i + 1]) return i;
    }
    return n - 1;
}

double evalPoly(const Piece& piece, double x) {
    double value = 0.0;
    double power = 1.0;
    const int order = std::max(0, std::min(piece.polyOrder, kMaxPolyOrder));
    for (int k = 0; k <= order; ++k) {
        value += piece.poly[k] * power;
        power *= x;
    }
    return value;
}

double evalMmr(const Piece& piece, const double s[3]) {
    const double terms[7] = {
        s[0], s[1], s[2],
        s[0] * s[1], s[0] * s[2], s[1] * s[2],
        s[0] * s[1] * s[2],
    };
    double value = piece.mmrConstant;
    double powers[7] = {1, 1, 1, 1, 1, 1, 1};
    const int order = std::max(0, std::min(piece.mmrOrder, kMaxMmrOrder));
    for (int o = 0; o < order; ++o) {
        for (int j = 0; j < 7; ++j) {
            powers[j] *= terms[j];
            value += piece.mmrCoef[o][j] * powers[j];
        }
    }
    return value;
}

struct Hasher {
    uint64_t h = 1469598103934665603ULL;
    void bytes(const void* data, size_t len) {
        const auto* p = static_cast<const uint8_t*>(data);
        for (size_t i = 0; i < len; ++i) {
            h ^= p[i];
            h *= 1099511628211ULL;
        }
    }
    void d(double v) { bytes(&v, sizeof(v)); }
    void i(int v) { bytes(&v, sizeof(v)); }
};

}  // namespace

double pqEotf(double v) {
    const double p = std::pow(clamp01(v), 1.0 / kPqM2);
    const double num = std::max(p - kPqC1, 0.0);
    const double den = kPqC2 - kPqC3 * p;
    return std::pow(num / den, 1.0 / kPqM1);
}

double pqOetf(double v) {
    const double p = std::pow(clamp01(v), kPqM1);
    return std::pow((kPqC1 + kPqC2 * p) / (1.0 + kPqC3 * p), kPqM2);
}

uint64_t hashParams(const Params& params) {
    // Fields one by one: the structs have padding, which must not reach the hash.
    Hasher hasher;
    for (const Curve& curve : params.curves) {
        const int n = std::max(1, std::min(curve.numPieces, kMaxPieces));
        hasher.i(n);
        for (int i = 0; i <= n; ++i) hasher.d(curve.pivots[i]);
        for (int i = 0; i < n; ++i) {
            const Piece& piece = curve.pieces[i];
            hasher.i(piece.mmr ? 1 : 0);
            if (piece.mmr) {
                hasher.i(piece.mmrOrder);
                hasher.d(piece.mmrConstant);
                for (int o = 0; o < kMaxMmrOrder; ++o) {
                    for (int j = 0; j < 7; ++j) hasher.d(piece.mmrCoef[o][j]);
                }
            } else {
                hasher.i(piece.polyOrder);
                for (int k = 0; k <= kMaxPolyOrder; ++k) hasher.d(piece.poly[k]);
            }
        }
    }
    for (double v : params.nonlinear) hasher.d(v);
    for (double v : params.nonlinearOffset) hasher.d(v);
    for (double v : params.rgbToLms) hasher.d(v);
    return hasher.h == 0 ? 1 : hasher.h;
}

void reshape(const Params& params, const double s[3], double outIpt[3]) {
    for (int c = 0; c < 3; ++c) {
        const Curve& curve = params.curves[c];
        const int n = std::max(1, std::min(curve.numPieces, kMaxPieces));
        // The piece is picked by this component's own value, clamped into the pivot range.
        const double x = std::min(std::max(s[c], curve.pivots[0]), curve.pivots[n]);
        const Piece& piece = curve.pieces[pieceIndex(curve, x)];
        const double value = piece.mmr ? evalMmr(piece, s) : evalPoly(piece, x);
        outIpt[c] = clamp01(value);
    }
}

namespace {

struct Prepared {
    double media3RgbToYuv[9];
    double lmsToRgb[9];
};

bool prepare(const Params& params, Prepared& out) {
    return invert3(kMedia3YuvToRgb, out.media3RgbToYuv) && invert3(params.rgbToLms, out.lmsToRgb);
}

void mapPixelPrepared(const Params& params, const Prepared& prepared, const double rgbPq[3],
                      double outRgbPq[3]) {
    // 1. Undo Media3's YUV -> RGB: recover the decoded base-layer values.
    const double rgb[3] = {clamp01(rgbPq[0]), clamp01(rgbPq[1]), clamp01(rgbPq[2])};
    double yuv[3];
    mul3(prepared.media3RgbToYuv, rgb, yuv);
    for (int c = 0; c < 3; ++c) yuv[c] = clamp01(yuv[c] + kMedia3YuvOffset[c]);
    // Media3 clamps everything darker than its 0.0625 luma offset to black, so black stands for
    // that whole range: take its bottom (true black) rather than lift black to the offset.
    if (rgb[0] <= 0.0 && rgb[1] <= 0.0 && rgb[2] <= 0.0) yuv[0] = 0.0;

    // 2. Reshape with the RPU's curves: base layer -> IPT-PQ-c2.
    double ipt[3];
    reshape(params, yuv, ipt);

    // 3. IPT -> nonlinear LMS -> linear LMS -> linear BT.2020 RGB.
    const double centered[3] = {
        ipt[0] - params.nonlinearOffset[0],
        ipt[1] - params.nonlinearOffset[1],
        ipt[2] - params.nonlinearOffset[2],
    };
    double lmsPq[3];
    mul3(params.nonlinear, centered, lmsPq);
    const double lms[3] = {pqEotf(lmsPq[0]), pqEotf(lmsPq[1]), pqEotf(lmsPq[2])};
    double linear[3];
    mul3(prepared.lmsToRgb, lms, linear);

    // 4. PQ-encode for HDR10.
    for (int c = 0; c < 3; ++c) outRgbPq[c] = pqOetf(linear[c]);
}

}  // namespace

void mapPixel(const Params& params, const double rgbPq[3], double outRgbPq[3]) {
    Prepared prepared{};
    if (!prepare(params, prepared)) {
        for (int c = 0; c < 3; ++c) outRgbPq[c] = clamp01(rgbPq[c]);
        return;
    }
    mapPixelPrepared(params, prepared, rgbPq, outRgbPq);
}

void buildLut(const Params& params, int size, uint16_t* out) {
    if (size < 2 || out == nullptr) return;
    Prepared prepared{};
    const bool ok = prepare(params, prepared);
    const double step = 1.0 / static_cast<double>(size - 1);
    const uint16_t one = floatToHalf(1.0f);
    size_t index = 0;
    for (int b = 0; b < size; ++b) {
        for (int g = 0; g < size; ++g) {
            for (int r = 0; r < size; ++r) {
                const double in[3] = {r * step, g * step, b * step};
                double mapped[3] = {in[0], in[1], in[2]};
                if (ok) mapPixelPrepared(params, prepared, in, mapped);
                out[index++] = floatToHalf(static_cast<float>(mapped[0]));
                out[index++] = floatToHalf(static_cast<float>(mapped[1]));
                out[index++] = floatToHalf(static_cast<float>(mapped[2]));
                out[index++] = one;
            }
        }
    }
}

uint16_t floatToHalf(float value) {
    uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    const uint32_t sign = (bits >> 16) & 0x8000u;
    const int32_t exponent = static_cast<int32_t>((bits >> 23) & 0xFFu) - 127 + 15;
    uint32_t mantissa = bits & 0x7FFFFFu;
    if (exponent <= 0) {
        if (exponent < -10) return static_cast<uint16_t>(sign);
        mantissa |= 0x800000u;
        const uint32_t shift = static_cast<uint32_t>(14 - exponent);
        uint32_t half = mantissa >> shift;
        if ((mantissa >> (shift - 1)) & 1u) half += 1;  // round
        return static_cast<uint16_t>(sign | half);
    }
    if (exponent >= 31) return static_cast<uint16_t>(sign | 0x7C00u);  // inf (inputs are in [0,1])
    uint32_t half = sign | (static_cast<uint32_t>(exponent) << 10) | (mantissa >> 13);
    if (mantissa & 0x1000u) half += 1;  // round to nearest
    return static_cast<uint16_t>(half);
}

}  // namespace dovi_p5
