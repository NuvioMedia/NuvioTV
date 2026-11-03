// JNI side of the Dolby Vision Profile 5 -> HDR10 mapping (see dovi_p5_lut.h).
//
// The extractor hands each RPU NAL (type 62) to nativeIngestRpu, which parses it with libdovi
// and returns a hash of its mapping; identical mappings (usually a whole scene) share one hash.
// The video effect then asks nativeBuildLut for the LUT of the hash a frame needs.

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>

#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "dovi_p5_lut.h"

#ifndef DOVI_REAL_LINKED
#define DOVI_REAL_LINKED 0
#endif

#if DOVI_REAL_LINKED
#include <libdovi/rpu_parser.h>
#endif

#define P5_LOG_TAG "DoviP5Native"
#define P5_LOGW(...) __android_log_print(ANDROID_LOG_WARN, P5_LOG_TAG, __VA_ARGS__)

namespace {

#if DOVI_REAL_LINKED

constexpr size_t kMaxCachedMappings = 64;

std::mutex gMutex;
std::unordered_map<uint64_t, dovi_p5::Params> gParams;
std::deque<uint64_t> gOrder;
// The matrices of the last RPU that carried them, for RPUs that leave them out.
dovi_p5::Params gLastMatrices;
uint64_t gLastHash = 0;

double fixedPoint(uint8_t dataType, uint64_t denom, int64_t intPart, uint64_t fracPart) {
    if (dataType == 1) {
        // coefficient_data_type 1: IEEE float bits in the fraction field.
        const auto bits = static_cast<uint32_t>(fracPart);
        float value;
        std::memcpy(&value, &bits, sizeof(value));
        return value;
    }
    const double scale = denom >= 63 ? 0.0 : 1.0 / static_cast<double>(1ULL << denom);
    return static_cast<double>(intPart) + static_cast<double>(fracPart) * scale;
}

const DoviI64Data* listAt(const DoviI64Data2D& data, size_t index) {
    return index < data.len && data.list != nullptr ? data.list[index] : nullptr;
}

const DoviU64Data* listAt(const DoviU64Data2D& data, size_t index) {
    return index < data.len && data.list != nullptr ? data.list[index] : nullptr;
}

int64_t valueAt(const DoviI64Data* data, size_t index) {
    return data != nullptr && data->data != nullptr && index < data->len ? data->data[index] : 0;
}

uint64_t valueAt(const DoviU64Data* data, size_t index) {
    return data != nullptr && data->data != nullptr && index < data->len ? data->data[index] : 0;
}

bool readCurve(const DoviReshapingCurve& src, const DoviRpuDataHeader& header, dovi_p5::Curve& out) {
    const uint64_t numPivots = src.num_pivots_minus2 + 2;
    const int numPieces = static_cast<int>(numPivots) - 1;
    if (numPieces < 1 || numPieces > dovi_p5::kMaxPieces) return false;
    if (src.pivots.data == nullptr || src.pivots.len < numPivots) return false;

    const double maxCode = static_cast<double>((1ULL << (header.bl_bit_depth_minus8 + 8)) - 1);
    // The bitstream codes pivots as deltas from the previous one.
    double pivot = 0.0;
    for (uint64_t i = 0; i < numPivots; ++i) {
        pivot += src.pivots.data[i];
        out.pivots[i] = pivot / maxCode;
    }
    out.numPieces = numPieces;

    const uint8_t type = header.coefficient_data_type;
    const uint64_t denom = header.coefficient_log2_denom;
    for (int p = 0; p < numPieces; ++p) {
        dovi_p5::Piece& piece = out.pieces[p];
        if (src.mapping_idc == 0 && src.polynomial != nullptr) {
            const DoviPolynomialCurve& poly = *src.polynomial;
            piece.mmr = false;
            piece.polyOrder = static_cast<int>(valueAt(&poly.poly_order_minus1, p)) + 1;
            if (piece.polyOrder > dovi_p5::kMaxPolyOrder) return false;
            const DoviI64Data* ints = listAt(poly.poly_coef_int, p);
            const DoviU64Data* fracs = listAt(poly.poly_coef, p);
            for (int k = 0; k <= dovi_p5::kMaxPolyOrder; ++k) {
                piece.poly[k] = k <= piece.polyOrder
                    ? fixedPoint(type, denom, valueAt(ints, k), valueAt(fracs, k))
                    : 0.0;
            }
        } else if (src.mapping_idc == 1 && src.mmr != nullptr) {
            const DoviMMRCurve& mmr = *src.mmr;
            piece.mmr = true;
            if (mmr.mmr_order_minus1.data == nullptr || static_cast<size_t>(p) >= mmr.mmr_order_minus1.len) return false;
            piece.mmrOrder = static_cast<int>(mmr.mmr_order_minus1.data[p]) + 1;
            if (piece.mmrOrder > dovi_p5::kMaxMmrOrder) return false;
            piece.mmrConstant = fixedPoint(
                type, denom, valueAt(&mmr.mmr_constant_int, p), valueAt(&mmr.mmr_constant, p));
            const DoviI64Data2D* ints2d =
                static_cast<size_t>(p) < mmr.mmr_coef_int.len && mmr.mmr_coef_int.list != nullptr
                    ? mmr.mmr_coef_int.list[p] : nullptr;
            const DoviU64Data2D* fracs2d =
                static_cast<size_t>(p) < mmr.mmr_coef.len && mmr.mmr_coef.list != nullptr
                    ? mmr.mmr_coef.list[p] : nullptr;
            if (ints2d == nullptr || fracs2d == nullptr) return false;
            for (int o = 0; o < dovi_p5::kMaxMmrOrder; ++o) {
                const DoviI64Data* ints = o < piece.mmrOrder ? listAt(*ints2d, o) : nullptr;
                const DoviU64Data* fracs = o < piece.mmrOrder ? listAt(*fracs2d, o) : nullptr;
                for (int j = 0; j < 7; ++j) {
                    piece.mmrCoef[o][j] = o < piece.mmrOrder
                        ? fixedPoint(type, denom, valueAt(ints, j), valueAt(fracs, j))
                        : 0.0;
                }
            }
        } else {
            return false;
        }
    }
    return true;
}

void readMatrices(const DoviVdrDmData& dm, dovi_p5::Params& out) {
    const int16_t ycc[9] = {dm.ycc_to_rgb_coef0, dm.ycc_to_rgb_coef1, dm.ycc_to_rgb_coef2,
                            dm.ycc_to_rgb_coef3, dm.ycc_to_rgb_coef4, dm.ycc_to_rgb_coef5,
                            dm.ycc_to_rgb_coef6, dm.ycc_to_rgb_coef7, dm.ycc_to_rgb_coef8};
    const int16_t lms[9] = {dm.rgb_to_lms_coef0, dm.rgb_to_lms_coef1, dm.rgb_to_lms_coef2,
                            dm.rgb_to_lms_coef3, dm.rgb_to_lms_coef4, dm.rgb_to_lms_coef5,
                            dm.rgb_to_lms_coef6, dm.rgb_to_lms_coef7, dm.rgb_to_lms_coef8};
    for (int i = 0; i < 9; ++i) {
        out.nonlinear[i] = ycc[i] / 8192.0;
        out.rgbToLms[i] = lms[i] / 16384.0;
    }
    const uint32_t offsets[3] = {dm.ycc_to_rgb_offset0, dm.ycc_to_rgb_offset1, dm.ycc_to_rgb_offset2};
    for (int i = 0; i < 3; ++i) out.nonlinearOffset[i] = offsets[i] / static_cast<double>(1u << 28);
}

void copyMatrices(const dovi_p5::Params& from, dovi_p5::Params& to) {
    std::memcpy(to.nonlinear, from.nonlinear, sizeof(to.nonlinear));
    std::memcpy(to.nonlinearOffset, from.nonlinearOffset, sizeof(to.nonlinearOffset));
    std::memcpy(to.rgbToLms, from.rgbToLms, sizeof(to.rgbToLms));
}

void remember(uint64_t hash, const dovi_p5::Params& params) {
    if (gParams.find(hash) != gParams.end()) return;
    gParams.emplace(hash, params);
    gOrder.push_back(hash);
    while (gOrder.size() > kMaxCachedMappings) {
        gParams.erase(gOrder.front());
        gOrder.pop_front();
    }
}

// Results of ingest() other than a mapping hash (which is always > 0).
constexpr int64_t kRpuUnreadable = -1;
constexpr int64_t kMappingUnreadable = -2;
constexpr int64_t kOtherProfileBase = -100;  // -100 - guessed profile

/** A positive, non-zero form of [hash], so it never collides with the error results. */
int64_t positiveHash(uint64_t hash) {
    const auto value = static_cast<int64_t>(hash & 0x7FFFFFFFFFFFFFFFULL);
    return value == 0 ? 1 : value;
}

/**
 * Parses one RPU NAL. Returns its mapping's hash (> 0), or kRpuUnreadable, kMappingUnreadable, or
 * kOtherProfileBase - profile for an RPU that is not Profile 5.
 */
int64_t ingest(const uint8_t* nal, size_t len) {
    DoviRpuOpaque* rpu = dovi_parse_unspec62_nalu(nal, len);
    if (rpu == nullptr) return kRpuUnreadable;
    int64_t result = kRpuUnreadable;
    const char* error = dovi_rpu_get_error(rpu);
    if (error == nullptr || error[0] == '\0') {
        const DoviRpuDataHeader* header = dovi_rpu_get_header(rpu);
        if (header != nullptr) {
            if (header->guessed_profile != 5) {
                result = kOtherProfileBase - header->guessed_profile;
            } else if (header->use_prev_vdr_rpu_flag) {
                // Same mapping as before.
                std::lock_guard<std::mutex> lock(gMutex);
                result = gLastHash != 0 ? positiveHash(gLastHash) : kMappingUnreadable;
            } else {
                const DoviRpuDataMapping* mapping = dovi_rpu_get_data_mapping(rpu);
                const DoviVdrDmData* dm = dovi_rpu_get_vdr_dm_data(rpu);
                dovi_p5::Params params;
                bool ok = mapping != nullptr;
                for (int c = 0; ok && c < 3; ++c) ok = readCurve(mapping->curves[c], *header, params.curves[c]);
                std::lock_guard<std::mutex> lock(gMutex);
                result = kMappingUnreadable;
                if (ok) {
                    if (dm != nullptr) {
                        readMatrices(*dm, params);
                        copyMatrices(params, gLastMatrices);
                    } else {
                        copyMatrices(gLastMatrices, params);
                    }
                    const uint64_t hash = dovi_p5::hashParams(params);
                    result = positiveHash(hash);
                    remember(static_cast<uint64_t>(result), params);
                    gLastHash = static_cast<uint64_t>(result);
                }
                if (dm != nullptr) dovi_rpu_free_vdr_dm_data(dm);
                if (mapping != nullptr) dovi_rpu_free_data_mapping(mapping);
            }
            dovi_rpu_free_header(header);
        }
    }
    dovi_rpu_free(rpu);
    return result;
}

#endif  // DOVI_REAL_LINKED

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_core_player_DolbyVisionP5Native_nativeIsSupported(JNIEnv* /* env */, jclass /* clazz */) {
    return DOVI_REAL_LINKED ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_tv_core_player_DolbyVisionP5Native_nativeIngestRpu(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray data,
    jint offset,
    jint length
) {
#if DOVI_REAL_LINKED
    if (data == nullptr || offset < 0 || length < 3) return kRpuUnreadable;
    if (offset + length > env->GetArrayLength(data)) return kRpuUnreadable;
    std::vector<uint8_t> nal(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte*>(nal.data()));
    return static_cast<jlong>(ingest(nal.data(), nal.size()));
#else
    return -1;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_core_player_DolbyVisionP5Native_nativeBuildLut(
    JNIEnv* env,
    jclass /* clazz */,
    jlong hash,
    jint size,
    jshortArray out
) {
#if DOVI_REAL_LINKED
    if (out == nullptr || size < 2 || size > 65) return JNI_FALSE;
    const jsize needed = size * size * size * 4;
    if (env->GetArrayLength(out) < needed) return JNI_FALSE;
    dovi_p5::Params params;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        auto it = gParams.find(static_cast<uint64_t>(hash));
        if (it == gParams.end()) return JNI_FALSE;
        params = it->second;
    }
    std::vector<uint16_t> lut(static_cast<size_t>(needed));
    dovi_p5::buildLut(params, size, lut.data());
    env->SetShortArrayRegion(out, 0, needed, reinterpret_cast<const jshort*>(lut.data()));
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/**
 * Marks the buffers [surface] gets from now on as HDR10 (BT.2020, PQ, full range), so the system
 * shows what GL draws into it as HDR10 even where EGL has no BT.2020 PQ colour space. Needs
 * Android 9 (ANativeWindow_setBuffersDataSpace), looked up at run time for older systems.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_core_player_DolbyVisionP5Native_nativeSetHdr10Dataspace(
    JNIEnv* env,
    jclass /* clazz */,
    jobject surface
) {
    using SetDataSpace = int32_t (*)(ANativeWindow*, int32_t);
    static SetDataSpace setDataSpace = []() -> SetDataSpace {
        void* android = dlopen("libandroid.so", RTLD_NOW);
        return android != nullptr
            ? reinterpret_cast<SetDataSpace>(dlsym(android, "ANativeWindow_setBuffersDataSpace"))
            : nullptr;
    }();
    if (setDataSpace == nullptr || surface == nullptr) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return JNI_FALSE;
    // ADATASPACE_BT2020_PQ: STANDARD_BT2020 | TRANSFER_ST2084 | RANGE_FULL.
    constexpr int32_t kDataSpaceBt2020Pq = (6 << 16) | (7 << 22) | (1 << 27);
    const int32_t result = setDataSpace(window, kDataSpaceBt2020Pq);
    ANativeWindow_release(window);
    if (result != 0) P5_LOGW("ANativeWindow_setBuffersDataSpace failed: %d", result);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_DolbyVisionP5Native_nativeReset(JNIEnv* /* env */, jclass /* clazz */) {
#if DOVI_REAL_LINKED
    std::lock_guard<std::mutex> lock(gMutex);
    gParams.clear();
    gOrder.clear();
    gLastMatrices = dovi_p5::Params();
    gLastHash = 0;
#endif
}
