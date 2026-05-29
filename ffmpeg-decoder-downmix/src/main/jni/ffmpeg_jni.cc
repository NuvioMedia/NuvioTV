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
#include <libavutil/downmix_info.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
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
                              jboolean outputFloat);

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
                   jboolean outputFloat) {
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  return (jlong)createContext(env, codec, extraData, rawSampleRate,
                              rawChannelCount, outputChannelCount,
                              requestedOutputLayoutName, outputFloat);
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
                                         outputFloat);
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
                              jboolean outputFloat) {
  DecoderContext* decoderContext =
      static_cast<DecoderContext*>(calloc(1, sizeof(DecoderContext)));
  if (!decoderContext) {
    LOGE("Failed to allocate decoder context.");
    return NULL;
  }

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
  if (!getOutputChannelLayout(decoderContext, &inputLayout, &outputLayout)) {
    av_channel_layout_uninit(&inputLayout);
    LOGE("Unable to resolve output channel layout.");
    return AUDIO_DECODER_ERROR_OTHER;
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
