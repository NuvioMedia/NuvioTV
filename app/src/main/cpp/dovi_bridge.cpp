#include <jni.h>
#include <android/log.h>
#include <cstddef>
#include <cstdint>
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

    LOGI(
        "nativeConvertDv7RpuToDv81 converted %d bytes -> %d bytes (mode=%u)",
        static_cast<int>(len),
        static_cast<int>(env->GetArrayLength(out)),
        static_cast<unsigned int>(conversion_mode)
    );
    return out;
#else
    LOGI("nativeConvertDv7RpuToDv81 called in stub mode; returning null");
    return nullptr;
#endif
}
