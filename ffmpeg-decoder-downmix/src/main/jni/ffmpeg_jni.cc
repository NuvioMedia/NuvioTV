/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This modified decoder module is distributed with NuvioTV under GPL-3.0-only.
 * It is based on AndroidX Media3 decoder_ffmpeg; the original Apache-2.0 notice
 * is preserved below. Additional downmix behavior was adapted from Kodi
 * (GPL-2.0-or-later). See this module's NOTICE.md for provenance details.
 */
/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/cpu.h>
#include <libavutil/downmix_info.h>
#include <libavutil/error.h>
#include <libavutil/mathematics.h>
#include <libavutil/opt.h>
#include <libavutil/pixfmt.h>
#include <libswresample/swresample.h>
#include <libavutil/audio_fifo.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define LIBRARY_FUNC(RETURN_TYPE, NAME, ...)                               \
  extern "C" {                                                             \
  JNIEXPORT RETURN_TYPE                                                    \
  Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(JNIEnv* env,    \
                                                           jobject thiz,   \
                                                           ##__VA_ARGS__); \
  }                                                                        \
  JNIEXPORT RETURN_TYPE                                                    \
  Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(                \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define AUDIO_DECODER_FUNC(RETURN_TYPE, NAME, ...)               \
  extern "C" {                                                   \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                 \
  }                                                              \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define VIDEO_DECODER_FUNC(RETURN_TYPE, NAME, ...)               \
  extern "C" {                                                   \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                 \
  }                                                              \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define ERROR_STRING_BUFFER_LENGTH 256

// Output formats corresponding to Android PCM encodings. Downmix-off uses
// 16-bit PCM to keep behavior aligned with the standard Media3 FFmpeg decoder.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;
// Default center level when no downmix metadata is present (-3 dB).
static const double DEFAULT_CENTER_MIX_LEVEL = M_SQRT1_2;

// LINT.IfChange
static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java)

// LINT.IfChange
static const int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
static const int VIDEO_DECODER_ERROR_OTHER = -2;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegVideoDecoder.java)

static const int kImageFormatYV12 = 0x32315659;
static const int kColorspaceUnknown = 0;
static const int kColorspaceBT601 = 1;
static const int kColorspaceBT709 = 2;
static const int kColorspaceBT2020 = 3;

struct VideoDecoderContext {
  AVCodecContext* codec_context;
  AVFrame* frame;
  AVPacket* packet;
  ANativeWindow* window;
  int window_width;
  int window_height;
  int window_hw_ready;
};

#ifndef NATIVE_WINDOW_API_CPU
#define NATIVE_WINDOW_API_CPU 2
#endif
#ifndef NATIVE_WINDOW_TIMESTAMP_AUTO
#define NATIVE_WINDOW_TIMESTAMP_AUTO (-1LL)
#endif

// ADATASPACE_* from android/data_space.h (API 28). Hardcoded so minSdk < 28 still builds.
static const int32_t kDataSpaceBt709 = 281083904;    // STANDARD_BT709 | TRANSFER_SMPTE_170M | RANGE_LIMITED
static const int32_t kDataSpaceBt601 = 281149440;    // STANDARD_BT601_525 | TRANSFER_SMPTE_170M | RANGE_LIMITED
static const int32_t kDataSpaceBt2020 = 147193858;   // ADATASPACE_BT2020

// AHARDWAREBUFFER_USAGE_* from android/hardware_buffer.h
static const uint64_t kUsageCpuWriteOften = 3ULL << 4;
static const uint64_t kUsageGpuSampledImage = 1ULL << 8;
static const uint64_t kUsageComposerOverlay = 1ULL << 11;

typedef int (*NwSetUsageFn)(ANativeWindow*, uint64_t);
typedef int (*NwSetBufferCountFn)(ANativeWindow*, size_t);
typedef int (*NwSetTimestampFn)(ANativeWindow*, int64_t);
typedef int (*NwApiDisconnectFn)(ANativeWindow*, int);
typedef int32_t (*NwSetDataSpaceFn)(ANativeWindow*, int32_t);
typedef int32_t (*NwSetFrameRateFn)(ANativeWindow*, float, int8_t);

static NwSetUsageFn g_nwSetUsage;
static NwSetBufferCountFn g_nwSetBufferCount;
static NwSetTimestampFn g_nwSetTimestamp;
static NwApiDisconnectFn g_nwApiDisconnect;
static NwSetDataSpaceFn g_nwSetDataSpace;
static NwSetFrameRateFn g_nwSetFrameRate;
static int g_nwExtLoaded;

static void loadNativeWindowExt() {
  if (g_nwExtLoaded) {
    return;
  }
  g_nwExtLoaded = 1;
  void* handle = dlopen("libnativewindow.so", RTLD_NOW);
  if (!handle) {
    handle = dlopen("libandroid.so", RTLD_NOW);
  }
  if (!handle) {
    return;
  }
  g_nwSetUsage = (NwSetUsageFn)dlsym(handle, "native_window_set_usage");
  g_nwSetBufferCount = (NwSetBufferCountFn)dlsym(handle, "native_window_set_buffer_count");
  g_nwSetTimestamp = (NwSetTimestampFn)dlsym(handle, "native_window_set_buffers_timestamp");
  g_nwApiDisconnect = (NwApiDisconnectFn)dlsym(handle, "native_window_api_disconnect");
  g_nwSetDataSpace = (NwSetDataSpaceFn)dlsym(handle, "ANativeWindow_setBuffersDataSpace");
  g_nwSetFrameRate = (NwSetFrameRateFn)dlsym(handle, "ANativeWindow_setFrameRate");
}

static void disconnectCpuWindow(ANativeWindow* window) {
  loadNativeWindowExt();
  if (window && g_nwApiDisconnect) {
    g_nwApiDisconnect(window, NATIVE_WINDOW_API_CPU);
  }
}

static jmethodID videoInitForYuvFrameMethod;
static jmethodID videoInitForPrivateFrameMethod;
static jfieldID videoYuvPlanesField;
static jfieldID videoYuvStridesField;
static jfieldID videoModeField;
static jfieldID videoWidthField;
static jfieldID videoHeightField;
static jfieldID videoDecoderPrivateField;
static bool videoJniReady = false;

// LINT.IfChange(decodeLoadLevel)
static const int DECODE_LOAD_NORMAL = 0;
static const int DECODE_LOAD_NON_REFERENCE = 1;
static const int DECODE_LOAD_AGGRESSIVE = 2;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegVideoDecoder.java:decodeLoadLevel)

static const int VIDEO_OUTPUT_MODE_YUV = 0;
static const int VIDEO_OUTPUT_MODE_SURFACE_YUV = 1;

struct DecoderContext {
  AVCodecContext* codec_context;
  SwrContext* resample_context;
  AVSampleFormat output_sample_format;
  AVChannelLayout input_layout;
  AVChannelLayout output_layout;
  jint requested_output_channel_count;
  char* requested_output_layout_name;
  double center_mix_level;
  bool downmix_normalization_enabled;
  bool has_input_layout;
  bool has_output_layout;
  bool has_center_mix_level;

  // AC3 Encoder fields
  bool transcode_to_ac3;
  AVCodecContext* encoder_context;
  AVAudioFifo* fifo;
  AVFrame* encoder_frame;
  AVPacket* encoder_packet;
  bool encoder_initialized;
};

static jmethodID growOutputBufferMethod;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
const AVCodec* getCodecByName(JNIEnv* env, jstring codecName);

/**
 * Allocates and opens a new decoder context for the specified codec.
 */
DecoderContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData,
                              jint rawSampleRate, jint rawChannelCount,
                              jint outputChannelCount,
                              jstring requestedOutputLayoutName,
                              jboolean outputFloat,
                              jboolean transcodeToAc3);

struct GrowOutputBufferCallback {
  uint8_t* operator()(int requiredSize) const;

  JNIEnv* env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative AUDIO_DECODER_ERROR constant value in the case of an
 * error.
 */
int decodePacket(DecoderContext* decoderContext, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 jint userCenterMixLevelDb,
                 jboolean downmixNormalizationEnabled,
                 GrowOutputBufferCallback growBuffer);

/**
 * Configures or recreates the resampler for the current frame.
 */
int configureResampler(DecoderContext* decoderContext, AVFrame* frame,
                       jint userCenterMixLevelDb,
                       jboolean downmixNormalizationEnabled);

/**
 * Transforms ffmpeg AVERROR into a negative AUDIO_DECODER_ERROR constant value.
 */
int transformError(int errorNumber);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char* functionName, int errorNumber);

/**
 * Releases the specified decoder context.
 */
void releaseContext(DecoderContext* decoderContext);

void clearResampler(DecoderContext* decoderContext);

bool copyChannelLayout(const AVChannelLayout* source, AVChannelLayout* destination);

bool channelLayoutsEqual(const AVChannelLayout* left, const AVChannelLayout* right);

bool getInputChannelLayout(AVCodecContext* codecContext, AVFrame* frame,
                           AVChannelLayout* inputLayout);

bool getOutputChannelLayout(DecoderContext* decoderContext,
                            const AVChannelLayout* inputLayout,
                            AVChannelLayout* outputLayout);

bool applyRequestedOutputLayout(DecoderContext* decoderContext,
                                AVChannelLayout* outputLayout);

double getAdjustedCenterMixLevel(AVFrame* frame, jint userCenterMixLevelDb,
                                 bool isDownmixActive);

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: GetEnv failed");
    return -1;
  }
  jclass clazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed");
    return -1;
  }
  growOutputBufferMethod =
      env->GetMethodID(clazz, "growOutputBuffer",
                       "(Landroidx/media3/decoder/"
                       "SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
  if (!growOutputBufferMethod) {
    LOGE("JNI_OnLoad: GetMethodID failed");
    return -1;
  }
  return JNI_VERSION_1_6;
}

LIBRARY_FUNC(jstring, ffmpegGetVersion) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

LIBRARY_FUNC(jint, ffmpegGetInputBufferPaddingSize) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

LIBRARY_FUNC(jboolean, ffmpegHasDecoder, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

AUDIO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName,
                   jbyteArray extraData,
                   jint rawSampleRate, jint rawChannelCount,
                   jint outputChannelCount,
                   jstring requestedOutputLayoutName,
                   jboolean outputFloat,
                   jboolean transcodeToAc3) {
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  return (jlong)createContext(env, codec, extraData, rawSampleRate,
                              rawChannelCount, outputChannelCount,
                              requestedOutputLayoutName, outputFloat,
                              transcodeToAc3);
}

AUDIO_DECODER_FUNC(jint, ffmpegDecode, jlong context, jobject inputData,
                   jint inputSize, jobject decoderOutputBuffer,
                   jobject outputData, jint outputSize,
                   jint userCenterMixLevelDb,
                   jboolean downmixNormalizationEnabled) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  if (!inputData || !decoderOutputBuffer || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return -1;
  }
  if (inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return -1;
  }
  if (outputSize < 0) {
    LOGE("Invalid output buffer length: %d", outputSize);
    return -1;
  }
  uint8_t* inputBuffer = (uint8_t*)env->GetDirectBufferAddress(inputData);
  uint8_t* outputBuffer = (uint8_t*)env->GetDirectBufferAddress(outputData);
  AVPacket* packet = av_packet_alloc();
  if (!packet) {
    LOGE("Failed to allocate packet.");
    return -1;
  }
  packet->data = inputBuffer;
  packet->size = inputSize;
  const int ret =
      decodePacket((DecoderContext*)context, packet, outputBuffer, outputSize,
                   userCenterMixLevelDb,
                   downmixNormalizationEnabled,
                   GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
  av_packet_free(&packet);
  return ret;
}

uint8_t* GrowOutputBufferCallback::operator()(int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  return static_cast<uint8_t*>(env->GetDirectBufferAddress(newOutputData));
}

AUDIO_DECODER_FUNC(jint, ffmpegGetChannelCount, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  DecoderContext* decoderContext = (DecoderContext*)context;
  if (decoderContext->has_output_layout) {
    return decoderContext->output_layout.nb_channels;
  }
  if (decoderContext->requested_output_channel_count > 0) {
    return decoderContext->requested_output_channel_count;
  }
  return decoderContext->codec_context->ch_layout.nb_channels;
}

AUDIO_DECODER_FUNC(jint, ffmpegGetSampleRate, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((DecoderContext*)context)->codec_context->sample_rate;
}

AUDIO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext, jbyteArray extraData) {
  DecoderContext* decoderContext = (DecoderContext*)jContext;
  if (!decoderContext || !decoderContext->codec_context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecContext* codecContext = decoderContext->codec_context;
  AVCodecID codecId = codecContext->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    jint outputChannelCount = decoderContext->requested_output_channel_count;
    jboolean outputFloat =
        decoderContext->output_sample_format == OUTPUT_FORMAT_PCM_FLOAT;
    jboolean transcodeToAc3 = decoderContext->transcode_to_ac3;
    jstring requestedOutputLayoutName = NULL;
    if (decoderContext->requested_output_layout_name) {
      requestedOutputLayoutName =
          env->NewStringUTF(decoderContext->requested_output_layout_name);
    }
    releaseContext(decoderContext);
    const AVCodec* codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    jlong context = (jlong)createContext(env, codec, extraData,
                                         /* rawSampleRate= */ -1,
                                         /* rawChannelCount= */ -1,
                                         outputChannelCount,
                                         requestedOutputLayoutName,
                                         outputFloat,
                                         transcodeToAc3);
    if (requestedOutputLayoutName) {
      env->DeleteLocalRef(requestedOutputLayoutName);
    }
    return context;
  }

  avcodec_flush_buffers(codecContext);
  clearResampler(decoderContext);
  return (jlong)decoderContext;
}

AUDIO_DECODER_FUNC(void, ffmpegRelease, jlong context) {
  if (context) {
    releaseContext((DecoderContext*)context);
  }
}

const AVCodec* getCodecByName(JNIEnv* env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char* codecNameChars = env->GetStringUTFChars(codecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

DecoderContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData,
                              jint rawSampleRate, jint rawChannelCount,
                              jint outputChannelCount,
                              jstring requestedOutputLayoutName,
                              jboolean outputFloat,
                              jboolean transcodeToAc3) {
  DecoderContext* decoderContext =
      static_cast<DecoderContext*>(calloc(1, sizeof(DecoderContext)));
  if (!decoderContext) {
    LOGE("Failed to allocate decoder context.");
    return NULL;
  }
  decoderContext->transcode_to_ac3 = transcodeToAc3;

  AVCodecContext* codecContext = avcodec_alloc_context3(codec);
  if (!codecContext) {
    LOGE("Failed to allocate codec context.");
    free(decoderContext);
    return NULL;
  }

  decoderContext->codec_context = codecContext;
  decoderContext->output_sample_format =
      outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT;
  decoderContext->requested_output_channel_count = outputChannelCount;
  if (requestedOutputLayoutName) {
    const char* outputLayoutNameChars =
        env->GetStringUTFChars(requestedOutputLayoutName, NULL);
    if (outputLayoutNameChars) {
      decoderContext->requested_output_layout_name = strdup(outputLayoutNameChars);
      env->ReleaseStringUTFChars(requestedOutputLayoutName, outputLayoutNameChars);
    }
  }
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    codecContext->extradata_size = size;
    codecContext->extradata =
        (uint8_t*)av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!codecContext->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseContext(decoderContext);
      return NULL;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte*)codecContext->extradata);
  }
  if (codecContext->codec_id == AV_CODEC_ID_PCM_MULAW ||
      codecContext->codec_id == AV_CODEC_ID_PCM_ALAW) {
    codecContext->sample_rate = rawSampleRate;
    av_channel_layout_default(&codecContext->ch_layout, rawChannelCount);
  }
  codecContext->err_recognition = AV_EF_IGNORE_ERR;
  int result = avcodec_open2(codecContext, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2", result);
    releaseContext(decoderContext);
    return NULL;
  }
  return decoderContext;
}

int decodePacket(DecoderContext* decoderContext, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 jint userCenterMixLevelDb,
                 jboolean downmixNormalizationEnabled,
                 GrowOutputBufferCallback growBuffer) {
  AVCodecContext* codecContext = decoderContext->codec_context;
  int result = avcodec_send_packet(codecContext, packet);
  if (result) {
    logError("avcodec_send_packet", result);
    return transformError(result);
  }

  int outSize = 0;
  while (true) {
    AVFrame* frame = av_frame_alloc();
    if (!frame) {
      LOGE("Failed to allocate output frame.");
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    result = avcodec_receive_frame(codecContext, frame);
    if (result) {
      av_frame_free(&frame);
      if (result == AVERROR(EAGAIN)) {
        break;
      }
      logError("avcodec_receive_frame", result);
      return transformError(result);
    }

    result =
        configureResampler(decoderContext, frame, userCenterMixLevelDb,
                           downmixNormalizationEnabled);
    if (result < 0) {
      av_frame_free(&frame);
      return transformError(result);
    }

    int sampleRate =
        frame->sample_rate > 0 ? frame->sample_rate : codecContext->sample_rate;
    if (decoderContext->transcode_to_ac3) {
      int outSamples = swr_get_out_samples(decoderContext->resample_context, frame->nb_samples);
      int nb_channels = decoderContext->output_layout.nb_channels;
      uint8_t** converted_data = (uint8_t**)calloc(nb_channels, sizeof(uint8_t*));
      for (int i = 0; i < nb_channels; i++) {
        converted_data[i] = (uint8_t*)malloc(outSamples * sizeof(float));
      }

      int convertedSamples =
          swr_convert(decoderContext->resample_context, converted_data, outSamples,
                      (const uint8_t**)frame->data, frame->nb_samples);
      av_frame_free(&frame);

      if (convertedSamples < 0) {
        logError("swr_convert", convertedSamples);
        for (int i = 0; i < nb_channels; i++) {
          free(converted_data[i]);
        }
        free(converted_data);
        return AUDIO_DECODER_ERROR_INVALID_DATA;
      }

      av_audio_fifo_write(decoderContext->fifo, (void**)converted_data, convertedSamples);

      for (int i = 0; i < nb_channels; i++) {
        free(converted_data[i]);
      }
      free(converted_data);

      while (av_audio_fifo_size(decoderContext->fifo) >= decoderContext->encoder_context->frame_size) {
        av_audio_fifo_read(decoderContext->fifo, (void**)decoderContext->encoder_frame->data,
                            decoderContext->encoder_context->frame_size);

        int ret = avcodec_send_frame(decoderContext->encoder_context, decoderContext->encoder_frame);
        if (ret < 0) {
          logError("avcodec_send_frame", ret);
          return AUDIO_DECODER_ERROR_OTHER;
        }

        while (true) {
          ret = avcodec_receive_packet(decoderContext->encoder_context, decoderContext->encoder_packet);
          if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            break;
          } else if (ret < 0) {
            logError("avcodec_receive_packet", ret);
            return AUDIO_DECODER_ERROR_OTHER;
          }

          int packetSize = decoderContext->encoder_packet->size;
          if (outSize + packetSize > outputSize) {
            outputSize = outSize + packetSize;
            uint8_t* newBase = growBuffer(outputSize);
            if (!newBase) {
              LOGE("Failed to grow output buffer during encoding.");
              av_packet_unref(decoderContext->encoder_packet);
              return AUDIO_DECODER_ERROR_OTHER;
            }
            outputBuffer = newBase + outSize;
          }

          memcpy(outputBuffer, decoderContext->encoder_packet->data, packetSize);
          outputBuffer += packetSize;
          outSize += packetSize;

          av_packet_unref(decoderContext->encoder_packet);
        }
      }
      codecContext->sample_rate = sampleRate;
    } else {
      int outputChannelCount = decoderContext->output_layout.nb_channels;
      int outSampleSize =
          av_get_bytes_per_sample(decoderContext->output_sample_format);
      int outSamples =
          swr_get_out_samples(decoderContext->resample_context, frame->nb_samples);
      int bufferOutSize = outSampleSize * outputChannelCount * outSamples;
      if (outSize + bufferOutSize > outputSize) {
        LOGD(
            "Output buffer size (%d) too small for output data (%d), "
            "reallocating buffer.",
            outputSize, outSize + bufferOutSize);
        outputSize = outSize + bufferOutSize;
        outputBuffer = growBuffer(outputSize);
        if (!outputBuffer) {
          LOGE("Failed to reallocate output buffer.");
          av_frame_free(&frame);
          return AUDIO_DECODER_ERROR_OTHER;
        }
      }

      int convertedSamples =
          swr_convert(decoderContext->resample_context, &outputBuffer, outSamples,
                      (const uint8_t**)frame->data, frame->nb_samples);
      av_frame_free(&frame);
      if (convertedSamples < 0) {
        logError("swr_convert", convertedSamples);
        return AUDIO_DECODER_ERROR_INVALID_DATA;
      }
      int writtenSize = outSampleSize * outputChannelCount * convertedSamples;
      outputBuffer += writtenSize;
      outSize += writtenSize;
      codecContext->sample_rate = sampleRate;
    }
  }
  return outSize;
}

int configureResampler(DecoderContext* decoderContext, AVFrame* frame,
                       jint userCenterMixLevelDb,
                       jboolean downmixNormalizationEnabled) {
  AVCodecContext* codecContext = decoderContext->codec_context;
  AVChannelLayout inputLayout = {};
  if (!getInputChannelLayout(codecContext, frame, &inputLayout)) {
    LOGE("Unable to resolve input channel layout.");
    return AUDIO_DECODER_ERROR_OTHER;
  }

  AVChannelLayout outputLayout = {};
  if (decoderContext->transcode_to_ac3) {
    av_channel_layout_default(&outputLayout, 6);
    decoderContext->output_sample_format = AV_SAMPLE_FMT_FLTP;
  } else {
    if (!getOutputChannelLayout(decoderContext, &inputLayout, &outputLayout)) {
      av_channel_layout_uninit(&inputLayout);
      LOGE("Unable to resolve output channel layout.");
      return AUDIO_DECODER_ERROR_OTHER;
    }
  }

  int inputSampleRate =
      frame->sample_rate > 0 ? frame->sample_rate : codecContext->sample_rate;
  AVSampleFormat inputSampleFormat = (AVSampleFormat)frame->format;
  bool isDownmixActive = outputLayout.nb_channels < inputLayout.nb_channels;
  bool applyNormalization = isDownmixActive && downmixNormalizationEnabled;
  double centerMixLevel =
      getAdjustedCenterMixLevel(frame, userCenterMixLevelDb, isDownmixActive);

  bool needsReconfigure =
      !decoderContext->resample_context ||
      !decoderContext->has_input_layout ||
      !decoderContext->has_output_layout ||
      !channelLayoutsEqual(&decoderContext->input_layout, &inputLayout) ||
      !channelLayoutsEqual(&decoderContext->output_layout, &outputLayout) ||
      codecContext->sample_fmt != inputSampleFormat ||
      codecContext->sample_rate != inputSampleRate ||
      decoderContext->downmix_normalization_enabled != applyNormalization ||
      (!decoderContext->has_center_mix_level && isDownmixActive) ||
      (decoderContext->has_center_mix_level != isDownmixActive) ||
      (isDownmixActive &&
       fabs(decoderContext->center_mix_level - centerMixLevel) > 0.000001);

  if (!needsReconfigure) {
    av_channel_layout_uninit(&inputLayout);
    av_channel_layout_uninit(&outputLayout);
    return 0;
  }

  clearResampler(decoderContext);

  SwrContext* resampleContext = NULL;
  int result = swr_alloc_set_opts2(&resampleContext, &outputLayout,
                                   decoderContext->output_sample_format,
                                   inputSampleRate, &inputLayout,
                                   inputSampleFormat, inputSampleRate, 0, NULL);
  if (result < 0) {
    logError("swr_alloc_set_opts2", result);
    av_channel_layout_uninit(&inputLayout);
    av_channel_layout_uninit(&outputLayout);
    return result;
  }

  if (isDownmixActive) {
    av_opt_set_double(resampleContext, "center_mix_level", centerMixLevel, 0);
    if ((decoderContext->output_sample_format == AV_SAMPLE_FMT_FLT ||
         decoderContext->output_sample_format == AV_SAMPLE_FMT_FLTP) &&
        (inputSampleFormat == AV_SAMPLE_FMT_FLT ||
         inputSampleFormat == AV_SAMPLE_FMT_FLTP) &&
        applyNormalization) {
      av_opt_set_double(resampleContext, "rematrix_maxval", 1.0, 0);
    }
  }

  result = swr_init(resampleContext);
  if (result < 0) {
    logError("swr_init", result);
    swr_free(&resampleContext);
    av_channel_layout_uninit(&inputLayout);
    av_channel_layout_uninit(&outputLayout);
    return result;
  }

  decoderContext->resample_context = resampleContext;
  if (decoderContext->has_input_layout) {
    av_channel_layout_uninit(&decoderContext->input_layout);
  }
  if (decoderContext->has_output_layout) {
    av_channel_layout_uninit(&decoderContext->output_layout);
  }
  copyChannelLayout(&inputLayout, &decoderContext->input_layout);
  copyChannelLayout(&outputLayout, &decoderContext->output_layout);
  decoderContext->has_input_layout = true;
  decoderContext->has_output_layout = true;
  decoderContext->center_mix_level = centerMixLevel;
  decoderContext->downmix_normalization_enabled = applyNormalization;
  decoderContext->has_center_mix_level = isDownmixActive;

  if (decoderContext->transcode_to_ac3 && !decoderContext->encoder_initialized) {
    const AVCodec* encoder = avcodec_find_encoder(AV_CODEC_ID_AC3);
    if (!encoder) {
      LOGE("AC3 encoder not found. Check FFmpeg build options.");
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    AVCodecContext* enc_ctx = avcodec_alloc_context3(encoder);
    if (!enc_ctx) {
      LOGE("Failed to allocate encoder context.");
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    enc_ctx->sample_fmt = AV_SAMPLE_FMT_FLTP;
    enc_ctx->sample_rate = inputSampleRate;
    av_channel_layout_copy(&enc_ctx->ch_layout, &outputLayout);
    enc_ctx->bit_rate = 640000;
    
    int ret = avcodec_open2(enc_ctx, encoder, NULL);
    if (ret < 0) {
      logError("avcodec_open2 (encoder)", ret);
      avcodec_free_context(&enc_ctx);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    decoderContext->encoder_context = enc_ctx;

    decoderContext->fifo = av_audio_fifo_alloc(enc_ctx->sample_fmt, enc_ctx->ch_layout.nb_channels, 1);
    if (!decoderContext->fifo) {
      LOGE("Failed to allocate AVAudioFifo.");
      avcodec_free_context(&enc_ctx);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }

    decoderContext->encoder_frame = av_frame_alloc();
    if (!decoderContext->encoder_frame) {
      LOGE("Failed to allocate encoder frame.");
      av_audio_fifo_free(decoderContext->fifo);
      avcodec_free_context(&enc_ctx);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    decoderContext->encoder_frame->nb_samples = enc_ctx->frame_size;
    decoderContext->encoder_frame->format = enc_ctx->sample_fmt;
    av_channel_layout_copy(&decoderContext->encoder_frame->ch_layout, &enc_ctx->ch_layout);
    ret = av_frame_get_buffer(decoderContext->encoder_frame, 0);
    if (ret < 0) {
      logError("av_frame_get_buffer (encoder)", ret);
      av_frame_free(&decoderContext->encoder_frame);
      av_audio_fifo_free(decoderContext->fifo);
      avcodec_free_context(&enc_ctx);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }

    decoderContext->encoder_packet = av_packet_alloc();
    if (!decoderContext->encoder_packet) {
      LOGE("Failed to allocate encoder packet.");
      av_frame_free(&decoderContext->encoder_frame);
      av_audio_fifo_free(decoderContext->fifo);
      avcodec_free_context(&enc_ctx);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    decoderContext->encoder_initialized = true;
  }

  av_channel_layout_uninit(&inputLayout);
  av_channel_layout_uninit(&outputLayout);
  return 0;
}

int transformError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                            : AUDIO_DECODER_ERROR_OTHER;
}

void logError(const char* functionName, int errorNumber) {
  char* buffer = (char*)malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void clearResampler(DecoderContext* decoderContext) {
  if (!decoderContext) {
    return;
  }
  if (decoderContext->resample_context) {
    swr_free(&decoderContext->resample_context);
  }
  if (decoderContext->has_input_layout) {
    av_channel_layout_uninit(&decoderContext->input_layout);
    decoderContext->has_input_layout = false;
  }
  if (decoderContext->has_output_layout) {
    av_channel_layout_uninit(&decoderContext->output_layout);
    decoderContext->has_output_layout = false;
  }
  decoderContext->downmix_normalization_enabled = false;
  decoderContext->has_center_mix_level = false;

  // Free encoder resources on reset to allow reconfiguration with correct parameters
  if (decoderContext->encoder_context) {
    avcodec_free_context(&decoderContext->encoder_context);
    decoderContext->encoder_context = nullptr;
  }
  if (decoderContext->fifo) {
    av_audio_fifo_free(decoderContext->fifo);
    decoderContext->fifo = nullptr;
  }
  if (decoderContext->encoder_frame) {
    av_frame_free(&decoderContext->encoder_frame);
    decoderContext->encoder_frame = nullptr;
  }
  if (decoderContext->encoder_packet) {
    av_packet_free(&decoderContext->encoder_packet);
    decoderContext->encoder_packet = nullptr;
  }
  decoderContext->encoder_initialized = false;
}

void releaseContext(DecoderContext* decoderContext) {
  if (!decoderContext) {
    return;
  }
  clearResampler(decoderContext);
  if (decoderContext->requested_output_layout_name) {
    free(decoderContext->requested_output_layout_name);
    decoderContext->requested_output_layout_name = NULL;
  }
  if (decoderContext->encoder_context) {
    avcodec_free_context(&decoderContext->encoder_context);
  }
  if (decoderContext->fifo) {
    av_audio_fifo_free(decoderContext->fifo);
  }
  if (decoderContext->encoder_frame) {
    av_frame_free(&decoderContext->encoder_frame);
  }
  if (decoderContext->encoder_packet) {
    av_packet_free(&decoderContext->encoder_packet);
  }
  if (decoderContext->codec_context) {
    avcodec_free_context(&decoderContext->codec_context);
  }
  free(decoderContext);
}

bool copyChannelLayout(const AVChannelLayout* source, AVChannelLayout* destination) {
  if (!source || !destination) {
    return false;
  }
  return av_channel_layout_copy(destination, source) >= 0;
}

bool channelLayoutsEqual(const AVChannelLayout* left, const AVChannelLayout* right) {
  if (!left || !right) {
    return false;
  }
  return av_channel_layout_compare(left, right) == 0;
}

bool getInputChannelLayout(AVCodecContext* codecContext, AVFrame* frame,
                           AVChannelLayout* inputLayout) {
  if (frame->ch_layout.nb_channels > 0) {
    return copyChannelLayout(&frame->ch_layout, inputLayout);
  }
  if (codecContext->ch_layout.nb_channels > 0) {
    return copyChannelLayout(&codecContext->ch_layout, inputLayout);
  }
  int channelCount = codecContext->ch_layout.nb_channels;
  if (channelCount <= 0) {
    return false;
  }
  av_channel_layout_default(inputLayout, channelCount);
  return inputLayout->nb_channels == channelCount;
}

bool getOutputChannelLayout(DecoderContext* decoderContext,
                            const AVChannelLayout* inputLayout,
                            AVChannelLayout* outputLayout) {
  if (decoderContext->requested_output_channel_count > 0 &&
      inputLayout->nb_channels >
          decoderContext->requested_output_channel_count) {
    return applyRequestedOutputLayout(decoderContext, outputLayout);
  }
  return copyChannelLayout(inputLayout, outputLayout);
}

bool applyRequestedOutputLayout(DecoderContext* decoderContext,
                                AVChannelLayout* outputLayout) {
  if (!decoderContext || !outputLayout) {
    return false;
  }

  if (decoderContext->requested_output_layout_name &&
      av_channel_layout_from_string(
          outputLayout, decoderContext->requested_output_layout_name) >= 0) {
    return outputLayout->nb_channels ==
        decoderContext->requested_output_channel_count;
  }

  av_channel_layout_default(outputLayout,
                            decoderContext->requested_output_channel_count);
  return outputLayout->nb_channels ==
      decoderContext->requested_output_channel_count;
}

double getAdjustedCenterMixLevel(AVFrame* frame, jint userCenterMixLevelDb,
                                 bool isDownmixActive) {
  if (!isDownmixActive) {
    return DEFAULT_CENTER_MIX_LEVEL;
  }

  double centerMixLevel = DEFAULT_CENTER_MIX_LEVEL;
  AVFrameSideData* sideData =
      av_frame_get_side_data(frame, AV_FRAME_DATA_DOWNMIX_INFO);
  if (sideData && sideData->size >= sizeof(AVDownmixInfo)) {
    AVDownmixInfo* downmixInfo = (AVDownmixInfo*)sideData->data;
    centerMixLevel = downmixInfo->center_mix_level;
  }

  if (centerMixLevel <= 0.0) {
    return centerMixLevel;
  }

  double currentDb = 20.0 * log10(centerMixLevel);
  return pow(10.0, (currentDb + userCenterMixLevelDb) / 20.0);
}

static int transformVideoError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? VIDEO_DECODER_ERROR_INVALID_DATA
                                            : VIDEO_DECODER_ERROR_OTHER;
}

static enum AVPixelFormat ffmpegVideoGetFormat(AVCodecContext* context,
                                               const enum AVPixelFormat* pixelFormats) {
  (void)context;
  static const enum AVPixelFormat kPreferred[] = {
      AV_PIX_FMT_YUV420P,
      AV_PIX_FMT_YUVJ420P,
      AV_PIX_FMT_NV12,
  };
  for (size_t i = 0; i < sizeof(kPreferred) / sizeof(kPreferred[0]); ++i) {
    const enum AVPixelFormat* current = pixelFormats;
    while (*current != AV_PIX_FMT_NONE) {
      if (*current == kPreferred[i]) {
        return *current;
      }
      ++current;
    }
  }
  return pixelFormats[0];
}

static bool ensureVideoJni(JNIEnv* env) {
  if (videoJniReady) {
    return true;
  }
  jclass clazz = env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
  if (!clazz) {
    LOGE("VideoDecoderOutputBuffer class not found.");
    return false;
  }
  videoInitForYuvFrameMethod =
      env->GetMethodID(clazz, "initForYuvFrame", "(IIIII)Z");
  videoYuvPlanesField =
      env->GetFieldID(clazz, "yuvPlanes", "[Ljava/nio/ByteBuffer;");
  videoYuvStridesField = env->GetFieldID(clazz, "yuvStrides", "[I");
  videoModeField = env->GetFieldID(clazz, "mode", "I");
  videoWidthField = env->GetFieldID(clazz, "width", "I");
  videoHeightField = env->GetFieldID(clazz, "height", "I");
  videoInitForPrivateFrameMethod =
      env->GetMethodID(clazz, "initForPrivateFrame", "(II)V");
  if (!videoInitForPrivateFrameMethod) {
    env->ExceptionClear();
  }
  videoDecoderPrivateField = env->GetFieldID(clazz, "decoderPrivate", "J");
  if (!videoDecoderPrivateField) {
    env->ExceptionClear();
  }
  env->DeleteLocalRef(clazz);
  if (!videoInitForYuvFrameMethod || !videoYuvPlanesField || !videoYuvStridesField ||
      !videoModeField || !videoWidthField || !videoHeightField) {
    LOGE("VideoDecoderOutputBuffer JNI lookup failed.");
    return false;
  }
  videoJniReady = true;
  return true;
}

static void copyPlane(uint8_t* dst, int dstStride, const uint8_t* src, int srcStride,
                      int width, int height) {
  if (!dst || !src || width <= 0 || height <= 0) {
    return;
  }
  if (dstStride == srcStride && dstStride > 0) {
    memcpy(dst, src, (size_t)height * (size_t)dstStride);
    return;
  }
  int copyWidth = width < dstStride ? width : dstStride;
  if (copyWidth > srcStride) {
    copyWidth = srcStride;
  }
  for (int y = 0; y < height; ++y) {
    memcpy(dst + y * dstStride, src + y * srcStride, copyWidth);
  }
}

static int videoColorspace(const AVFrame* frame) {
  switch (frame->colorspace) {
    case AVCOL_SPC_BT709:
      return kColorspaceBT709;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
      return kColorspaceBT2020;
    case AVCOL_SPC_BT470BG:
    case AVCOL_SPC_SMPTE170M:
    case AVCOL_SPC_SMPTE240M:
      return kColorspaceBT601;
    default:
      return frame->height >= 720 ? kColorspaceBT709 : kColorspaceBT601;
  }
}

static int fillYuvOutput(JNIEnv* env, jobject out, AVFrame* frame, int outputMode) {
  int width = frame->width;
  int height = frame->height;
  int yStride = frame->linesize[0];
  int uvStride;
  enum AVPixelFormat pixFmt = (enum AVPixelFormat)frame->format;
  bool nv12 = pixFmt == AV_PIX_FMT_NV12;
  if (nv12) {
    uvStride = (width + 1) / 2;
  } else {
    uvStride = frame->linesize[1] > 0 ? frame->linesize[1] : (width + 1) / 2;
  }
  jboolean ok = env->CallBooleanMethod(out, videoInitForYuvFrameMethod, width, height,
                                       yStride, uvStride, videoColorspace(frame));
  if (env->ExceptionCheck() || !ok) {
    LOGE("initForYuvFrame failed.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  env->SetIntField(out, videoModeField, outputMode);

  jobjectArray planes = (jobjectArray)env->GetObjectField(out, videoYuvPlanesField);
  jintArray strides = (jintArray)env->GetObjectField(out, videoYuvStridesField);
  if (!planes) {
    LOGE("yuvPlanes missing.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  jint* strideValues = NULL;
  if (strides) {
    strideValues = env->GetIntArrayElements(strides, NULL);
  }
  jobject yObj = env->GetObjectArrayElement(planes, 0);
  jobject uObj = env->GetObjectArrayElement(planes, 1);
  jobject vObj = env->GetObjectArrayElement(planes, 2);
  uint8_t* yDst = yObj ? (uint8_t*)env->GetDirectBufferAddress(yObj) : NULL;
  uint8_t* uDst = uObj ? (uint8_t*)env->GetDirectBufferAddress(uObj) : NULL;
  uint8_t* vDst = vObj ? (uint8_t*)env->GetDirectBufferAddress(vObj) : NULL;
  if (!yDst || !uDst || !vDst) {
    LOGE("yuv plane buffers missing.");
    if (strideValues) {
      env->ReleaseIntArrayElements(strides, strideValues, JNI_ABORT);
    }
    return VIDEO_DECODER_ERROR_OTHER;
  }
  int yDstStride = strideValues ? strideValues[0] : yStride;
  int uDstStride = strideValues ? strideValues[1] : uvStride;
  int vDstStride = strideValues ? strideValues[2] : uvStride;
  int uvHeight = (height + 1) / 2;
  int uvWidth = (width + 1) / 2;

  copyPlane(yDst, yDstStride, frame->data[0], frame->linesize[0], width, height);
  if (nv12) {
    const uint8_t* uv = frame->data[1];
    int srcUvStride = frame->linesize[1];
    for (int y = 0; y < uvHeight; ++y) {
      const uint8_t* row = uv + y * srcUvStride;
      uint8_t* uRow = uDst + y * uDstStride;
      uint8_t* vRow = vDst + y * vDstStride;
      for (int x = 0; x < uvWidth; ++x) {
        uRow[x] = row[2 * x];
        vRow[x] = row[2 * x + 1];
      }
    }
  } else {
    copyPlane(uDst, uDstStride, frame->data[1], frame->linesize[1], uvWidth, uvHeight);
    copyPlane(vDst, vDstStride, frame->data[2], frame->linesize[2], uvWidth, uvHeight);
  }
  if (strideValues) {
    env->ReleaseIntArrayElements(strides, strideValues, JNI_ABORT);
  }
  env->DeleteLocalRef(yObj);
  env->DeleteLocalRef(uObj);
  env->DeleteLocalRef(vObj);
  env->DeleteLocalRef(planes);
  if (strides) {
    env->DeleteLocalRef(strides);
  }
  return 1;
}



static int32_t nativeDataspaceFromFrame(const AVFrame* frame) {
  if (!frame) {
    return kDataSpaceBt709;
  }
  switch (videoColorspace(frame)) {
    case kColorspaceBT2020:
      return kDataSpaceBt2020;
    case kColorspaceBT601:
      return kDataSpaceBt601;
    default:
      return kDataSpaceBt709;
  }
}

static void configureHwPresent(VideoDecoderContext* context, ANativeWindow* window,
                               int width, int height, const AVFrame* frame) {
  if (!context || !window || width <= 0 || height <= 0) {
    return;
  }
  int sizeChanged = context->window_width != width || context->window_height != height;
  if (!sizeChanged && context->window_hw_ready) {
    return;
  }
  loadNativeWindowExt();
  if (!context->window_hw_ready) {
    if (g_nwSetDataSpace) {
      g_nwSetDataSpace(window, nativeDataspaceFromFrame(frame));
    }
  }
  if (sizeChanged || !context->window_hw_ready) {
    ANativeWindow_setBuffersGeometry(window, width, height, kImageFormatYV12);
    context->window_width = width;
    context->window_height = height;
  }
  context->window_hw_ready = 1;
}

static ANativeWindow* windowForSurface(VideoDecoderContext* context, JNIEnv* env,
                                       jobject surface) {
  ANativeWindow* acquired = ANativeWindow_fromSurface(env, surface);
  if (!acquired) {
    return NULL;
  }
  if (context->window == acquired) {
    ANativeWindow_release(acquired);
    return context->window;
  }
  if (context->window) {
    disconnectCpuWindow(context->window);
    ANativeWindow_release(context->window);
  }
  context->window = acquired;
  context->window_width = 0;
  context->window_height = 0;
  context->window_hw_ready = 0;
  return context->window;
}

static int blitAvFrameToNativeWindow(VideoDecoderContext* context, JNIEnv* env,
                                       jobject surface, AVFrame* frame) {
  if (!surface || !frame || !frame->data[0] || frame->width <= 0 || frame->height <= 0) {
    return VIDEO_DECODER_ERROR_OTHER;
  }
  int width = frame->width;
  int height = frame->height;
  ANativeWindow* window = windowForSurface(context, env, surface);
  if (!window) {
    return VIDEO_DECODER_ERROR_OTHER;
  }
  configureHwPresent(context, window, width, height, frame);
  ANativeWindow_Buffer nativeBuffer;
  int lockResult = ANativeWindow_lock(window, &nativeBuffer, NULL);
  if (lockResult != 0 || nativeBuffer.bits == NULL) {
    return VIDEO_DECODER_ERROR_OTHER;
  }
  uint8_t* dest = (uint8_t*)nativeBuffer.bits;
  int destYStride = nativeBuffer.stride;
  int destUvStride = (destYStride / 2 + 15) & ~15;
  int uvHeight = (height + 1) / 2;
  int uvWidth = (width + 1) / 2;
  uint8_t* destV = dest + destYStride * nativeBuffer.height;
  uint8_t* destU = destV + destUvStride * ((nativeBuffer.height + 1) / 2);
  copyPlane(dest, destYStride, frame->data[0], frame->linesize[0], width, height);
  enum AVPixelFormat pixFmt = (enum AVPixelFormat)frame->format;
  if (pixFmt == AV_PIX_FMT_NV12) {
    const uint8_t* uv = frame->data[1];
    int srcUvStride = frame->linesize[1];
    if (!uv) {
      ANativeWindow_unlockAndPost(window);
      return VIDEO_DECODER_ERROR_OTHER;
    }
    for (int y = 0; y < uvHeight; ++y) {
      const uint8_t* row = uv + y * srcUvStride;
      uint8_t* vRow = destV + y * destUvStride;
      uint8_t* uRow = destU + y * destUvStride;
      for (int x = 0; x < uvWidth; ++x) {
        uRow[x] = row[2 * x];
        vRow[x] = row[2 * x + 1];
      }
    }
  } else {
    if (!frame->data[1] || !frame->data[2]) {
      ANativeWindow_unlockAndPost(window);
      return VIDEO_DECODER_ERROR_OTHER;
    }
    copyPlane(destV, destUvStride, frame->data[2], frame->linesize[2], uvWidth, uvHeight);
    copyPlane(destU, destUvStride, frame->data[1], frame->linesize[1], uvWidth, uvHeight);
  }
  ANativeWindow_unlockAndPost(window);
  return 0;
}

static void releasePrivateAvFrame(JNIEnv* env, jobject out) {
  if (!out || !videoDecoderPrivateField) {
    return;
  }
  jlong oldPrivate = env->GetLongField(out, videoDecoderPrivateField);
  if (oldPrivate != 0) {
    AVFrame* oldFrame = (AVFrame*)oldPrivate;
    av_frame_free(&oldFrame);
    env->SetLongField(out, videoDecoderPrivateField, 0);
  }
}

static int initPrivateFrameOutput(JNIEnv* env, jobject out, AVFrame* frame, int outputMode) {
  AVFrame* clone = av_frame_clone(frame);
  if (!clone) {
    LOGE("av_frame_clone failed.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  releasePrivateAvFrame(env, out);
  env->SetLongField(out, videoDecoderPrivateField, (jlong)clone);
  env->CallVoidMethod(out, videoInitForPrivateFrameMethod, clone->width, clone->height);
  if (env->ExceptionCheck()) {
    LOGE("initForPrivateFrame failed.");
    env->ExceptionClear();
    av_frame_free(&clone);
    env->SetLongField(out, videoDecoderPrivateField, 0);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  env->SetIntField(out, videoModeField, outputMode);
  return 1;
}

static void releaseVideoContext(VideoDecoderContext* context) {
  if (!context) {
    return;
  }
  if (context->window) {
    disconnectCpuWindow(context->window);
    ANativeWindow_release(context->window);
    context->window = NULL;
    context->window_hw_ready = 0;
  }
  if (context->packet) {
    av_packet_free(&context->packet);
  }
  if (context->frame) {
    av_frame_free(&context->frame);
  }
  if (context->codec_context) {
    avcodec_free_context(&context->codec_context);
  }
  free(context);
}

VIDEO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName, jbyteArray extraData,
                   jint threads) {
  if (!ensureVideoJni(env)) {
    return 0L;
  }
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Video codec not found.");
    return 0L;
  }
  VideoDecoderContext* context =
      static_cast<VideoDecoderContext*>(calloc(1, sizeof(VideoDecoderContext)));
  if (!context) {
    LOGE("Failed to allocate video decoder context.");
    return 0L;
  }
  context->codec_context = avcodec_alloc_context3(codec);
  context->frame = av_frame_alloc();
  context->packet = av_packet_alloc();
  if (!context->codec_context || !context->frame || !context->packet) {
    LOGE("Failed to allocate video codec objects.");
    releaseVideoContext(context);
    return 0L;
  }
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    context->codec_context->extradata_size = size;
    context->codec_context->extradata =
        (uint8_t*)av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->codec_context->extradata) {
      LOGE("Failed to allocate video extradata.");
      releaseVideoContext(context);
      return 0L;
    }
    env->GetByteArrayRegion(extraData, 0, size,
                            (jbyte*)context->codec_context->extradata);
  }
  // Frame-threading delays output by ~thread_count frames (startup drops). Cap at 3
  // and prefer slice threads so VC-1/WMV stay closer to the audio clock.
  int thread_count;
  if (threads > 0) {
    thread_count = threads;
  } else {
    int cpu_count = av_cpu_count();
    if (cpu_count < 2) {
      cpu_count = 2;
    }
    if (cpu_count > 3) {
      cpu_count = 3;
    }
    thread_count = cpu_count;
  }
  context->codec_context->thread_count = thread_count;
  context->codec_context->thread_type = FF_THREAD_SLICE | FF_THREAD_FRAME;
  context->codec_context->skip_loop_filter = AVDISCARD_NONKEY;
  context->codec_context->get_format = ffmpegVideoGetFormat;
  context->codec_context->err_recognition = AV_EF_IGNORE_ERR;
  context->codec_context->pkt_timebase = AV_TIME_BASE_Q;
  int result = avcodec_open2(context->codec_context, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2(video)", result);
    releaseVideoContext(context);
    return 0L;
  }
  return (jlong)context;
}

VIDEO_DECODER_FUNC(jint, ffmpegDecode, jlong jContext, jobject encoded, jint length,
                   jobject out, jint outputMode) {
  VideoDecoderContext* context = (VideoDecoderContext*)jContext;
  if (!context || !out) {
    LOGE("Video decode context/output must be non-NULL.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  if (!ensureVideoJni(env)) {
    return VIDEO_DECODER_ERROR_OTHER;
  }
  av_packet_unref(context->packet);
  av_frame_unref(context->frame);
  int sendResult;
  if (length <= 0 || !encoded) {
    sendResult = avcodec_send_packet(context->codec_context, NULL);
  } else {
    uint8_t* input = (uint8_t*)env->GetDirectBufferAddress(encoded);
    if (!input) {
      LOGE("Video input buffer address is NULL.");
      return VIDEO_DECODER_ERROR_OTHER;
    }
    // Frame-thread workers may read the packet after decode() returns the Java
    // buffer to the pool. Copy so the pointer stays valid.
    int copyResult = av_new_packet(context->packet, length);
    if (copyResult < 0) {
      return VIDEO_DECODER_ERROR_OTHER;
    }
    memcpy(context->packet->data, input, (size_t)length);
    sendResult = avcodec_send_packet(context->codec_context, context->packet);
  }
  if (sendResult < 0 && sendResult != AVERROR(EAGAIN) && sendResult != AVERROR_EOF) {
    logError("avcodec_send_packet(video)", sendResult);
    return transformVideoError(sendResult);
  }
  int receiveResult = avcodec_receive_frame(context->codec_context, context->frame);
  if (receiveResult == AVERROR(EAGAIN) || receiveResult == AVERROR_EOF) {
    return 0;
  }
  if (receiveResult < 0) {
    logError("avcodec_receive_frame(video)", receiveResult);
    return transformVideoError(receiveResult);
  }
  enum AVPixelFormat pixFmt = (enum AVPixelFormat)context->frame->format;
  if (pixFmt != AV_PIX_FMT_YUV420P && pixFmt != AV_PIX_FMT_YUVJ420P &&
      pixFmt != AV_PIX_FMT_NV12) {
    LOGE("Unsupported video pixel format: %d", (int)pixFmt);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  bool usePrivateFrame = outputMode == VIDEO_OUTPUT_MODE_SURFACE_YUV &&
                         videoInitForPrivateFrameMethod != NULL &&
                         videoDecoderPrivateField != NULL;
  if (usePrivateFrame) {
    return initPrivateFrameOutput(env, out, context->frame, outputMode);
  }
  if (videoDecoderPrivateField) {
    releasePrivateAvFrame(env, out);
  }
  return fillYuvOutput(env, out, context->frame, outputMode);
}

VIDEO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext) {
  VideoDecoderContext* context = (VideoDecoderContext*)jContext;
  if (!context || !context->codec_context) {
    return 0L;
  }
  avcodec_flush_buffers(context->codec_context);
  return jContext;
}

VIDEO_DECODER_FUNC(void, ffmpegRelease, jlong jContext) {
  releaseVideoContext((VideoDecoderContext*)jContext);
}

VIDEO_DECODER_FUNC(void, ffmpegReleaseFrame, jlong frame) {
  if (frame == 0) {
    return;
  }
  AVFrame* avframe = (AVFrame*)frame;
  av_frame_free(&avframe);
}

VIDEO_DECODER_FUNC(void, ffmpegSetDecodeLoadLevel, jlong jContext, jint level) {
  VideoDecoderContext* context = (VideoDecoderContext*)jContext;
  if (!context || !context->codec_context) {
    return;
  }
  AVCodecContext* codecContext = context->codec_context;
  switch (level) {
    case DECODE_LOAD_NON_REFERENCE:
      codecContext->skip_frame = AVDISCARD_NONREF;
      codecContext->skip_loop_filter = AVDISCARD_NONREF;
      codecContext->skip_idct = AVDISCARD_DEFAULT;
      break;
    case DECODE_LOAD_AGGRESSIVE:
      codecContext->skip_frame = AVDISCARD_NONREF;
      codecContext->skip_loop_filter = AVDISCARD_ALL;
      codecContext->skip_idct = AVDISCARD_NONREF;
      break;
    case DECODE_LOAD_NORMAL:
    default:
      codecContext->skip_frame = AVDISCARD_DEFAULT;
      codecContext->skip_loop_filter = AVDISCARD_NONKEY;
      codecContext->skip_idct = AVDISCARD_DEFAULT;
      break;
  }
}

VIDEO_DECODER_FUNC(jint, ffmpegRenderFrame, jlong jContext, jobject surface,
                   jobject buffer) {
  VideoDecoderContext* context = (VideoDecoderContext*)jContext;
  if (!surface || !buffer) {
    return VIDEO_DECODER_ERROR_OTHER;
  }
  if (!ensureVideoJni(env)) {
    return VIDEO_DECODER_ERROR_OTHER;
  }
  if (videoDecoderPrivateField) {
    jlong decoderPrivate = env->GetLongField(buffer, videoDecoderPrivateField);
    if (decoderPrivate != 0) {
      return blitAvFrameToNativeWindow(context, env, surface, (AVFrame*)decoderPrivate);
    }
  }
  int width = env->GetIntField(buffer, videoWidthField);
  int height = env->GetIntField(buffer, videoHeightField);
  jobjectArray planes = (jobjectArray)env->GetObjectField(buffer, videoYuvPlanesField);
  jintArray strides = (jintArray)env->GetObjectField(buffer, videoYuvStridesField);
  if (!planes || !strides || width <= 0 || height <= 0) {
    return VIDEO_DECODER_ERROR_OTHER;
  }
  jint* strideValues = env->GetIntArrayElements(strides, NULL);
  jobject yObj = env->GetObjectArrayElement(planes, 0);
  jobject uObj = env->GetObjectArrayElement(planes, 1);
  jobject vObj = env->GetObjectArrayElement(planes, 2);
  uint8_t* ySrc = (uint8_t*)env->GetDirectBufferAddress(yObj);
  uint8_t* uSrc = (uint8_t*)env->GetDirectBufferAddress(uObj);
  uint8_t* vSrc = (uint8_t*)env->GetDirectBufferAddress(vObj);
  int yStride = strideValues[0];
  int uStride = strideValues[1];
  int vStride = strideValues[2];

  ANativeWindow* window = windowForSurface(context, env, surface);
  if (!window) {
    env->ReleaseIntArrayElements(strides, strideValues, JNI_ABORT);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  configureHwPresent(context, window, width, height, NULL);
  ANativeWindow_Buffer nativeBuffer;
  int lockResult = ANativeWindow_lock(window, &nativeBuffer, NULL);
  if (lockResult != 0 || nativeBuffer.bits == NULL) {
    env->ReleaseIntArrayElements(strides, strideValues, JNI_ABORT);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  uint8_t* dest = (uint8_t*)nativeBuffer.bits;
  int destYStride = nativeBuffer.stride;
  int destUvStride = (destYStride / 2 + 15) & ~15;
  int uvHeight = (height + 1) / 2;
  int uvWidth = (width + 1) / 2;
  copyPlane(dest, destYStride, ySrc, yStride, width, height);
  uint8_t* destV = dest + destYStride * nativeBuffer.height;
  uint8_t* destU = destV + destUvStride * ((nativeBuffer.height + 1) / 2);
  copyPlane(destV, destUvStride, vSrc, vStride, uvWidth, uvHeight);
  copyPlane(destU, destUvStride, uSrc, uStride, uvWidth, uvHeight);
  ANativeWindow_unlockAndPost(window);
  env->ReleaseIntArrayElements(strides, strideValues, JNI_ABORT);
  env->DeleteLocalRef(yObj);
  env->DeleteLocalRef(uObj);
  env->DeleteLocalRef(vObj);
  env->DeleteLocalRef(planes);
  env->DeleteLocalRef(strides);
  return 0;
}
