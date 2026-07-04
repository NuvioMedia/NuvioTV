#include <jni.h>
#include <android/log.h>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "DoviBridgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifndef DOVI_REAL_LINKED
#define DOVI_REAL_LINKED 0
#endif

#if DOVI_REAL_LINKED
extern "C" {
typedef struct DoviRpuOpaque DoviRpuOpaque;
typedef struct DoviData {
    const uint8_t* data;
    size_t len;
} DoviData;

DoviRpuOpaque* dovi_parse_unspec62_nalu(const uint8_t* buf, size_t len);
DoviRpuOpaque* dovi_parse_rpu(const uint8_t* buf, size_t len);
const char* dovi_rpu_get_error(const DoviRpuOpaque* ptr);
void dovi_rpu_free(DoviRpuOpaque* ptr);
int32_t dovi_convert_rpu_with_mode(DoviRpuOpaque* ptr, uint8_t mode);
const DoviData* dovi_write_unspec62_nalu(DoviRpuOpaque* ptr);
void dovi_data_free(const DoviData* data);
}

static inline bool dovi_has_error(const DoviRpuOpaque* rpu, std::string* out_error) {
    if (rpu == nullptr) {
        if (out_error != nullptr) {
            *out_error = "null-rpu";
        }
        return true;
    }

    const char* error = dovi_rpu_get_error(rpu);
    if (error == nullptr || error[0] == '\0') {
        return false;
    }

    if (out_error != nullptr) {
        *out_error = error;
    }
    return true;
}

static inline DoviRpuOpaque* dovi_parse_any_rpu(const std::vector<uint8_t>& payload, std::string* out_error) {
    if (payload.empty()) {
        if (out_error != nullptr) {
            *out_error = "empty-input";
        }
        return nullptr;
    }

    std::string parse_error;
    DoviRpuOpaque* parsed = dovi_parse_unspec62_nalu(payload.data(), payload.size());
    if (parsed != nullptr && !dovi_has_error(parsed, &parse_error)) {
        return parsed;
    }
    if (parsed != nullptr) {
        dovi_rpu_free(parsed);
    }

    parsed = dovi_parse_rpu(payload.data(), payload.size());
    if (parsed != nullptr && !dovi_has_error(parsed, &parse_error)) {
        return parsed;
    }
    if (parsed != nullptr) {
        dovi_rpu_free(parsed);
    }

    if (out_error != nullptr) {
        *out_error = parse_error.empty() ? "failed-parse-rpu" : parse_error;
    }
    return nullptr;
}

static inline uint8_t map_conversion_mode(jint mode) {
    switch (mode) {
        case 0:
        case 1:
        case 2:
        case 3:
        case 4:
            return static_cast<uint8_t>(mode);
        case 5:
            return 4U;
        default:
            return 2U;
    }
}
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeGetBridgeVersion(JNIEnv* env, jclass /* clazz */) {
#if DOVI_REAL_LINKED
    return env->NewStringUTF("dovi-bridge-libdovi-capi-0.2");
#else
    return env->NewStringUTF("dovi-bridge-stub-0.1");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeIsConversionPathReady(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
#if DOVI_REAL_LINKED
    LOGI("native conversion path: libdovi linked (real mode)");
    return JNI_TRUE;
#else
    LOGI("native conversion path not linked (stub mode)");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeConvertDv7RpuToDv81(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray payload,
    jint mode
) {
#if DOVI_REAL_LINKED
    if (payload == nullptr) return nullptr;
    const jsize len = env->GetArrayLength(payload);
    if (len <= 0) return nullptr;

    std::vector<uint8_t> input(static_cast<size_t>(len));
    env->GetByteArrayRegion(payload, 0, len, reinterpret_cast<jbyte*>(input.data()));

    std::string parse_error;
    DoviRpuOpaque* rpu = dovi_parse_any_rpu(input, &parse_error);
    if (rpu == nullptr) {
        LOGW("libdovi parse failed: %s", parse_error.c_str());
        return nullptr;
    }

    const uint8_t conversion_mode = map_conversion_mode(mode);
    if (dovi_convert_rpu_with_mode(rpu, conversion_mode) < 0) {
        std::string convert_error;
        dovi_has_error(rpu, &convert_error);
        LOGW(
            "libdovi convert failed (mode=%u): %s",
            static_cast<unsigned int>(conversion_mode),
            convert_error.empty() ? "unknown" : convert_error.c_str()
        );
        dovi_rpu_free(rpu);
        return nullptr;
    }

    const DoviData* out_data = dovi_write_unspec62_nalu(rpu);
    if (out_data == nullptr || out_data->data == nullptr || out_data->len == 0U) {
        std::string write_error;
        dovi_has_error(rpu, &write_error);
        LOGW("libdovi write failed: %s", write_error.empty() ? "unknown" : write_error.c_str());
        if (out_data != nullptr) {
            dovi_data_free(out_data);
        }
        dovi_rpu_free(rpu);
        return nullptr;
    }

    if (out_data->len > static_cast<size_t>(INT32_MAX)) {
        LOGW("libdovi output too large: %zu", out_data->len);
        dovi_data_free(out_data);
        dovi_rpu_free(rpu);
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(out_data->len));
    if (out == nullptr) {
        dovi_data_free(out_data);
        dovi_rpu_free(rpu);
        return nullptr;
    }

    env->SetByteArrayRegion(
        out,
        0,
        static_cast<jsize>(out_data->len),
        reinterpret_cast<const jbyte*>(out_data->data)
    );
    dovi_data_free(out_data);
    dovi_rpu_free(rpu);
    // No per-frame log here: it runs once per frame and floods logcat.
    return out;
#else
    LOGI("nativeConvertDv7RpuToDv81 called in stub mode; returning null");
    return nullptr;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeConvertDv7RpuToDv81NonAllocating(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray sample,
    jint offset,
    jint len,
    jbyteArray outBuffer,
    jint mode
) {
#if DOVI_REAL_LINKED
    if (sample == nullptr || outBuffer == nullptr || len <= 0 || offset < 0) return 0;

    // 1. RPU NALs are tiny (hundreds of bytes). Copy the bytes out of the JVM heap under a
    //    MINIMAL critical section, then run the (relatively expensive) libdovi parse OUTSIDE
    //    it. Holding GetPrimitiveArrayCritical across the parse suspends the GC for every
    //    thread and is the main source of per-frame micro-stutter. A thread_local buffer keeps
    //    this allocation-free in steady state.
    thread_local std::vector<uint8_t> input;
    input.resize(static_cast<size_t>(len));
    {
        void* dataPtr = env->GetPrimitiveArrayCritical(sample, nullptr);
        if (dataPtr == nullptr) return 0;
        const jsize sampleLen = env->GetArrayLength(sample);
        if (static_cast<jsize>(offset) + static_cast<jsize>(len) > sampleLen) {
            env->ReleasePrimitiveArrayCritical(sample, dataPtr, JNI_ABORT);
            return 0;
        }
        std::memcpy(
            input.data(),
            reinterpret_cast<const uint8_t*>(dataPtr) + offset,
            static_cast<size_t>(len)
        );
        env->ReleasePrimitiveArrayCritical(sample, dataPtr, JNI_ABORT);
    }

    // 2. Parse. Remember which parser succeeded so steady-state streams skip the wasted
    //    first attempt on every frame.
    static int preferredParser = 0; // 0 unknown, 1 unspec62, 2 raw rpu
    DoviRpuOpaque* rpu = nullptr;
    if (preferredParser != 2) {
        rpu = dovi_parse_unspec62_nalu(input.data(), input.size());
        if (rpu != nullptr && !dovi_has_error(rpu, nullptr)) {
            preferredParser = 1;
        } else if (rpu != nullptr) {
            dovi_rpu_free(rpu);
            rpu = nullptr;
        }
    }
    if (rpu == nullptr) {
        rpu = dovi_parse_rpu(input.data(), input.size());
        if (rpu != nullptr && !dovi_has_error(rpu, nullptr)) {
            preferredParser = 2;
        } else if (rpu != nullptr) {
            dovi_rpu_free(rpu);
            rpu = nullptr;
        }
    }
    if (rpu == nullptr) return 0;

    // 3. Convert RPU
    const uint8_t conversion_mode = map_conversion_mode(mode);
    if (dovi_convert_rpu_with_mode(rpu, conversion_mode) < 0) {
        dovi_rpu_free(rpu);
        return 0;
    }

    // 4. Write converted RPU
    const DoviData* out_data = dovi_write_unspec62_nalu(rpu);
    if (out_data == nullptr || out_data->data == nullptr || out_data->len == 0U) {
        if (out_data != nullptr) dovi_data_free(out_data);
        dovi_rpu_free(rpu);
        return 0;
    }

    const jsize maxOutLen = env->GetArrayLength(outBuffer);
    const jsize outLen = static_cast<jsize>(out_data->len);
    if (outLen > maxOutLen) {
        // Never silently truncate (a clipped RPU corrupts the frame). Signal the required
        // size with a negative return; Kotlin grows the reusable buffer and retries once.
        dovi_data_free(out_data);
        dovi_rpu_free(rpu);
        return -outLen;
    }

    // 5. Copy output to the Java reusable buffer FIRST, then normalize the 2-byte NAL header
    //    on the OUTPUT buffer. Never mutate libdovi-owned memory (that was undefined behaviour).
    env->SetByteArrayRegion(outBuffer, 0, outLen, reinterpret_cast<const jbyte*>(out_data->data));
    if (outLen >= 2) {
        jbyte hdr[2] = {
            static_cast<jbyte>(out_data->data[0] & 0xFE),
            static_cast<jbyte>(out_data->data[1] & 0x07)
        };
        env->SetByteArrayRegion(outBuffer, 0, 2, hdr);
    }

    dovi_data_free(out_data);
    dovi_rpu_free(rpu);

    return outLen; // bytes written
#else
    (void)env;
    (void)sample;
    (void)offset;
    (void)len;
    (void)outBuffer;
    (void)mode;
    return 0;
#endif
}

// ── Native optimized HEVC/DolbyVision/HDR10+ Sample Processing ──

static std::vector<uint8_t> unescape_rbsp(const uint8_t* data, size_t nalOffset, size_t nalSize) {
    std::vector<uint8_t> rbsp;
    if (nalSize < 3) {
        for (size_t i = 0; i < nalSize; ++i) rbsp.push_back(data[nalOffset + i]);
        return rbsp;
    }
    rbsp.reserve(nalSize);
    rbsp.push_back(data[nalOffset]);
    rbsp.push_back(data[nalOffset + 1]);
    size_t i = nalOffset + 2;
    size_t end = nalOffset + nalSize;
    while (i < end) {
        if (i + 2 < end && data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x03) {
            rbsp.push_back(0x00);
            rbsp.push_back(0x00);
            i += 3;
        } else {
            rbsp.push_back(data[i]);
            i++;
        }
    }
    return rbsp;
}

static std::vector<uint8_t> escape_rbsp(const std::vector<uint8_t>& rbsp) {
    std::vector<uint8_t> escaped;
    if (rbsp.size() <= 2) return rbsp;
    escaped.reserve(rbsp.size() * 3 / 2);
    escaped.push_back(rbsp[0]);
    escaped.push_back(rbsp[1]);

    int consecutiveZeros = 0;
    if (rbsp[0] == 0) consecutiveZeros++;
    if (rbsp[1] == 0) {
        consecutiveZeros = (consecutiveZeros == 1) ? 2 : 1;
    } else {
        consecutiveZeros = 0;
    }

    for (size_t j = 2; j < rbsp.size(); ++j) {
        uint8_t b = rbsp[j];
        if (consecutiveZeros == 2 && b <= 3) {
            escaped.push_back(0x03);
            consecutiveZeros = 0;
        }
        escaped.push_back(b);
        if (b == 0) {
            consecutiveZeros++;
        } else {
            consecutiveZeros = 0;
        }
    }
    return escaped;
}

static bool matchesSignature(const std::vector<uint8_t>& rbspData, size_t payloadStart, const uint8_t* sig, size_t sigLen) {
    if (payloadStart + sigLen > rbspData.size()) return false;
    for (size_t i = 0; i < sigLen; ++i) {
        if (rbspData[payloadStart + i] != sig[i]) return false;
    }
    return true;
}

static bool filterSeiNal(const uint8_t* data, size_t nalOffset, size_t nalSize, std::vector<uint8_t>& filteredNal) {
    if (nalSize < 3) return false;

    std::vector<uint8_t> rbspData = unescape_rbsp(data, nalOffset, nalSize);
    size_t rbspEnd = rbspData.size();

    std::vector<uint8_t> outRbsp;
    outRbsp.push_back(rbspData[0]);
    outRbsp.push_back(rbspData[1]);

    size_t pos = 2;
    bool hasHdr10Plus = false;
    const uint8_t HDR10_PLUS_SIG[] = {0xB5, 0x00, 0x3C, 0x00, 0x01};
    const size_t HDR10_PLUS_SIG_LEN = 5;

    while (pos < rbspEnd) {
        if (rbspEnd - pos == 1 && rbspData[pos] == 0x80) break;

        size_t msgStart = pos;

        uint32_t payloadType = 0;
        while (pos < rbspEnd) {
            uint8_t b = rbspData[pos++];
            payloadType += b;
            if (b != 0xFF) break;
        }

        uint32_t payloadSize = 0;
        while (pos < rbspEnd) {
            uint8_t b = rbspData[pos++];
            payloadSize += b;
            if (b != 0xFF) break;
        }

        if (pos + payloadSize > rbspEnd) return false;
        size_t payloadStart = pos;
        pos += payloadSize;
        size_t msgEnd = pos;

        if (payloadType == 4 && 
            payloadSize >= HDR10_PLUS_SIG_LEN &&
            matchesSignature(rbspData, payloadStart, HDR10_PLUS_SIG, HDR10_PLUS_SIG_LEN)
        ) {
            hasHdr10Plus = true;
        } else {
            outRbsp.insert(outRbsp.end(), rbspData.begin() + msgStart, rbspData.begin() + msgEnd);
        }
    }

    if (!hasHdr10Plus) return false;
    if (outRbsp.size() <= 2) {
        filteredNal.clear();
        return true;
    }

    outRbsp.push_back(0x80);
    filteredNal = escape_rbsp(outRbsp);
    return true;
}

static int findStartCode(const uint8_t* data, int from, int limit) {
    int i = from;
    while (i + 2 < limit) {
        if (data[i] == 0 && data[i + 1] == 0) {
            if (data[i + 2] == 1) return i;
            if (i + 3 < limit && data[i + 2] == 0 && data[i + 3] == 1) return i;
        }
        i++;
    }
    return -1;
}

static int startCodeLength(const uint8_t* data, int offset, int limit) {
    if (offset + 3 < limit &&
        data[offset] == 0 &&
        data[offset + 1] == 0 &&
        data[offset + 2] == 0 &&
        data[offset + 3] == 1
    ) return 4;
    return 3;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeProcessVideoSample(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray sample,
    jint sampleLen,
    jint nalFormat, // 0 for Annex-B, 1 for Length-Delimited
    jint nalLengthFieldLength,
    jbyteArray outBuffer,
    jboolean convertDovi,
    jint doviMode,
    jint doviProfile,
    jboolean stripDoviRpu,
    jboolean stripHdr10Plus
) {
    if (sample == nullptr || outBuffer == nullptr || sampleLen <= 0) return 0;

    thread_local std::vector<uint8_t> sampleBuffer;
    thread_local std::vector<uint8_t> outputBuffer;

    sampleBuffer.resize(sampleLen);
    {
        void* samplePtr = env->GetPrimitiveArrayCritical(sample, nullptr);
        if (samplePtr == nullptr) return 0;
        std::memcpy(sampleBuffer.data(), samplePtr, sampleLen);
        env->ReleasePrimitiveArrayCritical(sample, samplePtr, JNI_ABORT);
    }

    bool changed = false;
    outputBuffer.clear();

    if (nalFormat == 1) { // Length-Delimited
        int pos = 0;
        while (pos + nalLengthFieldLength <= sampleLen) {
            int nalSize = 0;
            for (int i = 0; i < nalLengthFieldLength; ++i) {
                nalSize = (nalSize << 8) | (sampleBuffer[pos + i] & 0xFF);
            }
            int nalStart = pos + nalLengthFieldLength;
            if (nalSize <= 0 || nalStart + nalSize > sampleLen) {
                return 0; // abort processing, format malformed
            }

            uint8_t nalHeader = sampleBuffer[nalStart];
            int nalType = (nalHeader >> 1) & 0x3F;
            int layerId = 0;
            if (nalStart + 1 < sampleLen) {
                layerId = ((nalHeader & 0x01) << 5) | ((sampleBuffer[nalStart + 1] >> 3) & 0x1F);
            }

            bool shouldDrop = false;
            bool processed = false;
            std::vector<uint8_t> processedNal;

            if (convertDovi || stripDoviRpu) {
                bool isRpu = (nalType == 62);
                bool isEl = (layerId > 0);
                if (isRpu) {
                    if (stripDoviRpu) {
                        shouldDrop = true;
                    } else if (convertDovi) {
#if DOVI_REAL_LINKED
                        DoviRpuOpaque* rpu = dovi_parse_unspec62_nalu(sampleBuffer.data() + nalStart, nalSize);
                        if (rpu == nullptr || dovi_has_error(rpu, nullptr)) {
                            if (rpu != nullptr) dovi_rpu_free(rpu);
                            rpu = dovi_parse_rpu(sampleBuffer.data() + nalStart, nalSize);
                            if (rpu != nullptr && dovi_has_error(rpu, nullptr)) {
                                dovi_rpu_free(rpu);
                                rpu = nullptr;
                            }
                        }
                        if (rpu != nullptr) {
                            uint8_t conversion_mode = map_conversion_mode(doviMode);
                            if (dovi_convert_rpu_with_mode(rpu, conversion_mode) >= 0) {
                                const DoviData* out_data = dovi_write_unspec62_nalu(rpu);
                                if (out_data != nullptr && out_data->data != nullptr && out_data->len > 0U) {
                                    processed = true;
                                    processedNal.resize(out_data->len);
                                    std::memcpy(processedNal.data(), out_data->data, out_data->len);
                                    if (processedNal.size() >= 2) {
                                        processedNal[0] &= 0xFE;
                                        processedNal[1] &= 0x07;
                                    }
                                    dovi_data_free(out_data);
                                }
                            }
                            dovi_rpu_free(rpu);
                        }
#endif
                        if (!processed) {
                            processed = true;
                            processedNal.resize(nalSize);
                            std::memcpy(processedNal.data(), sampleBuffer.data() + nalStart, nalSize);
                            if (processedNal.size() >= 2) {
                                processedNal[0] &= 0xFE;
                                processedNal[1] &= 0x07;
                            }
                        }
                    }
                } else if (isEl) {
                    shouldDrop = true;
                }
            }

            if (!shouldDrop && !processed && stripHdr10Plus) {
                if (nalType == 39 || nalType == 40) {
                    std::vector<uint8_t> filtered;
                    if (filterSeiNal(sampleBuffer.data(), nalStart, nalSize, filtered)) {
                        processed = true;
                        processedNal = std::move(filtered);
                        if (processedNal.empty()) {
                            shouldDrop = true;
                        }
                    }
                }
            }

            if (shouldDrop) {
                changed = true;
            } else if (processed) {
                changed = true;
                int outNalSize = processedNal.size();
                for (int i = nalLengthFieldLength - 1; i >= 0; --i) {
                    outputBuffer.push_back((outNalSize >> (i * 8)) & 0xFF);
                }
                outputBuffer.insert(outputBuffer.end(), processedNal.begin(), processedNal.end());
            } else {
                for (int i = nalLengthFieldLength - 1; i >= 0; --i) {
                    outputBuffer.push_back((nalSize >> (i * 8)) & 0xFF);
                }
                outputBuffer.insert(outputBuffer.end(), sampleBuffer.begin() + nalStart, sampleBuffer.begin() + nalStart + nalSize);
            }
            pos = nalStart + nalSize;
        }
    } else { // Annex-B
        int scan = 0;
        while (scan < sampleLen) {
            int startCode = findStartCode(sampleBuffer.data(), scan, sampleLen);
            if (startCode < 0) {
                outputBuffer.insert(outputBuffer.end(), sampleBuffer.begin() + scan, sampleBuffer.begin() + sampleLen);
                break;
            }
            int scLen = startCodeLength(sampleBuffer.data(), startCode, sampleLen);
            int nalBegin = startCode + scLen;
            int nextStartCode = findStartCode(sampleBuffer.data(), nalBegin + 2, sampleLen);
            int nalEnd = (nextStartCode < 0) ? sampleLen : nextStartCode;

            if (startCode > scan) {
                outputBuffer.insert(outputBuffer.end(), sampleBuffer.begin() + scan, sampleBuffer.begin() + startCode);
            }

            if (nalBegin < nalEnd) {
                int nalSize = nalEnd - nalBegin;
                uint8_t nalHeader = sampleBuffer[nalBegin];
                int nalType = (nalHeader >> 1) & 0x3F;
                int layerId = 0;
                if (nalBegin + 1 < nalEnd) {
                    layerId = ((nalHeader & 0x01) << 5) | ((sampleBuffer[nalBegin + 1] >> 3) & 0x1F);
                }

                bool shouldDrop = false;
                bool processed = false;
                std::vector<uint8_t> processedNal;

                if (convertDovi || stripDoviRpu) {
                    bool isRpu = (nalType == 62);
                    bool isEl = (layerId > 0);
                    if (isRpu) {
                        if (stripDoviRpu) {
                            shouldDrop = true;
                        } else if (convertDovi) {
#if DOVI_REAL_LINKED
                            DoviRpuOpaque* rpu = dovi_parse_unspec62_nalu(sampleBuffer.data() + nalBegin, nalSize);
                            if (rpu == nullptr || dovi_has_error(rpu, nullptr)) {
                                if (rpu != nullptr) dovi_rpu_free(rpu);
                                rpu = dovi_parse_rpu(sampleBuffer.data() + nalBegin, nalSize);
                                if (rpu != nullptr && dovi_has_error(rpu, nullptr)) {
                                    dovi_rpu_free(rpu);
                                    rpu = nullptr;
                                }
                            }
                            if (rpu != nullptr) {
                                uint8_t conversion_mode = map_conversion_mode(doviMode);
                                if (dovi_convert_rpu_with_mode(rpu, conversion_mode) >= 0) {
                                    const DoviData* out_data = dovi_write_unspec62_nalu(rpu);
                                    if (out_data != nullptr && out_data->data != nullptr && out_data->len > 0U) {
                                        processed = true;
                                        processedNal.resize(out_data->len);
                                        std::memcpy(processedNal.data(), out_data->data, out_data->len);
                                        if (processedNal.size() >= 2) {
                                            processedNal[0] &= 0xFE;
                                            processedNal[1] &= 0x07;
                                        }
                                        dovi_data_free(out_data);
                                    }
                                }
                                dovi_rpu_free(rpu);
                            }
#endif
                            if (!processed) {
                                processed = true;
                                processedNal.resize(nalSize);
                                std::memcpy(processedNal.data(), sampleBuffer.data() + nalBegin, nalSize);
                                if (processedNal.size() >= 2) {
                                    processedNal[0] &= 0xFE;
                                    processedNal[1] &= 0x07;
                                }
                            }
                        }
                    } else if (isEl) {
                        shouldDrop = true;
                    }
                }

                if (!shouldDrop && !processed && stripHdr10Plus) {
                    if (nalType == 39 || nalType == 40) {
                        std::vector<uint8_t> filtered;
                        if (filterSeiNal(sampleBuffer.data(), nalBegin, nalSize, filtered)) {
                            processed = true;
                            processedNal = std::move(filtered);
                            if (processedNal.empty()) {
                                shouldDrop = true;
                            }
                        }
                    }
                }

                if (shouldDrop) {
                    changed = true;
                } else if (processed) {
                    changed = true;
                    outputBuffer.insert(outputBuffer.end(), sampleBuffer.begin() + startCode, sampleBuffer.begin() + nalBegin);
                    outputBuffer.insert(outputBuffer.end(), processedNal.begin(), processedNal.end());
                } else {
                    outputBuffer.insert(outputBuffer.end(), sampleBuffer.begin() + startCode, sampleBuffer.begin() + nalEnd);
                }
            }
            scan = nalEnd;
        }
    }

    if (!changed) return 0;

    jsize maxOutLen = env->GetArrayLength(outBuffer);
    jsize outLen = static_cast<jsize>(outputBuffer.size());
    if (outLen > maxOutLen) {
        return -outLen;
    }

    env->SetByteArrayRegion(outBuffer, 0, outLen, reinterpret_cast<const jbyte*>(outputBuffer.data()));
    return outLen;
}

