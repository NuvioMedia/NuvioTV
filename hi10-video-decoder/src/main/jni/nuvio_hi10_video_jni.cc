/*
 * Isolated AVC High10 video JNI.
 * Decode/render architecture adapted from AndroidX Media PR #1591,
 * commit 1649087fbe3ce1b2c51abc320782be0b600b311b (Apache-2.0).
 */
#include <android/data_space.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <new>
#include <time.h>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libavutil/version.h>
#include <libswscale/swscale.h>
}

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NuvioHi10", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NuvioHi10", __VA_ARGS__)

namespace {

constexpr jint kSuccess = 0;
constexpr jint kEndOfStream = -5;
constexpr jint kInvalidData = -1;
constexpr jint kOtherError = -2;
constexpr jint kTryAgain = -3;
constexpr jint kSurfaceError = -4;
constexpr jint kSurfaceDropped = 1;
constexpr int kImageFormatYv12 = 0x32315659;
constexpr int kStageCount = 8;
constexpr int kStageSampleCapacity = 512;

enum StageIndex {
  kStageSend = 0,
  kStageReceive,
  kStageFrameSetup,
  kStageSws,
  kStageIntermediateCopy,
  kStageSurfaceLock,
  kStageSurfaceCopy,
  kStageSurfacePost,
};

struct StageSamples {
  std::mutex mutex;
  std::array<int64_t, kStageSampleCapacity> samples_us = {};
  uint64_t count = 0;
  uint64_t total_us = 0;
  int64_t max_us = 0;
  size_t size = 0;
  size_t next = 0;
};

struct DecoderContext {
  AVCodecContext* codec = nullptr;
  SwsContext* sws = nullptr;
  ANativeWindow* window = nullptr;
  jobject surface = nullptr;
  int window_width = 0;
  int window_height = 0;
  bool logged_pixel_format = false;
  std::mutex color_mutex;
  AVColorRange color_range = AVCOL_RANGE_UNSPECIFIED;
  AVColorPrimaries color_primaries = AVCOL_PRI_UNSPECIFIED;
  jfieldID data_field = nullptr;
  jfieldID width_field = nullptr;
  jfieldID height_field = nullptr;
  jfieldID pts_field = nullptr;
  jmethodID init_yuv_method = nullptr;
  StageSamples stages[kStageCount];
  std::atomic<uint64_t> send_attempts{0};
  std::atomic<uint64_t> send_again{0};
  std::atomic<uint64_t> send_accepted{0};
  std::atomic<uint64_t> receive_frames{0};
  std::atomic<uint64_t> receive_again{0};
  std::atomic<uint64_t> receive_eof{0};
};

int64_t NowUs() {
#if !NUVIO_HI10_DIAGNOSTICS
  return 0;
#else
  timespec time = {};
  clock_gettime(CLOCK_MONOTONIC, &time);
  return static_cast<int64_t>(time.tv_sec) * 1000000LL + time.tv_nsec / 1000;
#endif
}

void RecordStage(DecoderContext* context, StageIndex stage, int64_t start_us) {
#if NUVIO_HI10_DIAGNOSTICS
  const int64_t elapsed_us = std::max<int64_t>(NowUs() - start_us, 0);
  StageSamples& samples = context->stages[stage];
  std::lock_guard<std::mutex> lock(samples.mutex);
  samples.samples_us[samples.next] = elapsed_us;
  samples.next = (samples.next + 1) % kStageSampleCapacity;
  samples.size = std::min(samples.size + 1, static_cast<size_t>(kStageSampleCapacity));
  ++samples.count;
  samples.total_us += elapsed_us;
  samples.max_us = std::max(samples.max_us, elapsed_us);
#endif
}

std::array<int64_t, 6> SnapshotStage(StageSamples* samples) {
  std::vector<int64_t> sorted;
  uint64_t count;
  uint64_t total_us;
  int64_t max_us;
  {
    std::lock_guard<std::mutex> lock(samples->mutex);
    sorted.assign(samples->samples_us.begin(), samples->samples_us.begin() + samples->size);
    count = samples->count;
    total_us = samples->total_us;
    max_us = samples->max_us;
  }
  std::sort(sorted.begin(), sorted.end());
  auto percentile = [&sorted](double fraction) -> int64_t {
    if (sorted.empty()) {
      return 0;
    }
    size_t index = static_cast<size_t>(fraction * static_cast<double>(sorted.size() - 1));
    return sorted[index];
  };
  return {
      static_cast<int64_t>(count),
      count == 0 ? 0 : static_cast<int64_t>(total_us / count),
      percentile(0.50),
      percentile(0.95),
      percentile(0.99),
      max_us,
  };
}

void LogAvError(const char* operation, int error) {
  char message[AV_ERROR_MAX_STRING_SIZE] = {};
  av_strerror(error, message, sizeof(message));
  LOGE("%s failed: %s (%d)", operation, message, error);
}

void ReleaseWindow(JNIEnv* env, DecoderContext* context) {
  if (context->window != nullptr) {
    ANativeWindow_release(context->window);
    context->window = nullptr;
  }
  if (context->surface != nullptr) {
    env->DeleteGlobalRef(context->surface);
    context->surface = nullptr;
  }
  context->window_width = 0;
  context->window_height = 0;
}

bool AcquireWindow(JNIEnv* env, DecoderContext* context, jobject surface) {
  if (context->surface != nullptr && env->IsSameObject(context->surface, surface)) {
    return context->window != nullptr;
  }
  ReleaseWindow(env, context);
  context->window = ANativeWindow_fromSurface(env, surface);
  if (context->window == nullptr) {
    return false;
  }
  context->surface = env->NewGlobalRef(surface);
  return context->surface != nullptr;
}

void NativeReleaseWindow(JNIEnv* env, jobject, jlong native_context) {
  auto* context = reinterpret_cast<DecoderContext*>(native_context);
  if (context != nullptr) {
    // Called on the renderer thread, the same thread that renders to the window.
    ReleaseWindow(env, context);
  }
}

int VideoColorSpace(AVColorSpace color_space) {
  switch (color_space) {
    case AVCOL_SPC_BT709:
      return 2;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
      return 3;
    case AVCOL_SPC_BT470BG:
    case AVCOL_SPC_SMPTE170M:
    case AVCOL_SPC_SMPTE240M:
      return 1;
    default:
      return 0;
  }
}

const int* SwsCoefficients(AVColorSpace color_space) {
  switch (color_space) {
    case AVCOL_SPC_BT709:
      return sws_getCoefficients(SWS_CS_ITU709);
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
      return sws_getCoefficients(SWS_CS_BT2020);
    default:
      return sws_getCoefficients(SWS_CS_ITU601);
  }
}

int32_t AndroidDataSpace(DecoderContext* context) {
  std::lock_guard<std::mutex> lock(context->color_mutex);
  switch (context->color_primaries) {
    case AVCOL_PRI_BT2020:
      return ADATASPACE_BT2020;
    case AVCOL_PRI_BT470BG:
    case AVCOL_PRI_SMPTE170M:
      return context->color_range == AVCOL_RANGE_JPEG
          ? ADATASPACE_JFIF
          : ADATASPACE_BT601_625;
    case AVCOL_PRI_BT709:
    default:
      return context->color_range == AVCOL_RANGE_JPEG
          ? ADATASPACE_SRGB
          : ADATASPACE_BT709;
  }
}

void SetBuffersDataSpaceIfAvailable(ANativeWindow* window, int32_t data_space) {
  using SetBuffersDataSpace = int32_t (*)(ANativeWindow*, int32_t);
  // API28 symbol belongs to libnativewindow, not libandroid. Its dependency is
  // outside our RTLD_DEFAULT lookup group. Older APIs retain the existing fallback.
  static void* native_window_library = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL);
  static auto set_buffers_data_space = native_window_library == nullptr ? nullptr
      : reinterpret_cast<SetBuffersDataSpace>(
          dlsym(native_window_library, "ANativeWindow_setBuffersDataSpace"));
  // Keep this one process-wide platform-library handle alive while cached code is callable.
  if (set_buffers_data_space != nullptr) {
    set_buffers_data_space(window, data_space);
  }
}

bool ConvertToYuv420p(
    DecoderContext* context,
    AVFrame* source,
    uint8_t* const destination_data[4],
    const int destination_linesize[4]) {
  {
    std::lock_guard<std::mutex> lock(context->color_mutex);
    context->color_range = source->color_range;
    context->color_primaries = source->color_primaries;
  }
  if (!context->logged_pixel_format) {
    const char* name = av_get_pix_fmt_name(static_cast<AVPixelFormat>(source->format));
    LOGI("decoder=FFmpeg h264 profile=High10 sourcePixelFormat=%s "
         "conversion=%s->yuv420p output=ANativeWindow",
         name == nullptr ? "unknown" : name, name == nullptr ? "unknown" : name);
    context->logged_pixel_format = true;
  }

  const int64_t setup_start_us = NowUs();
  context->sws = sws_getCachedContext(
      context->sws,
      source->width,
      source->height,
      static_cast<AVPixelFormat>(source->format),
      source->width,
      source->height,
      AV_PIX_FMT_YUV420P,
      SWS_BILINEAR,
      nullptr,
      nullptr,
      nullptr);
  if (context->sws == nullptr) {
    return false;
  }

  const int source_range = source->color_range == AVCOL_RANGE_JPEG ? 1 : 0;
  const int* coefficients = SwsCoefficients(source->colorspace);
  sws_setColorspaceDetails(
      context->sws,
      coefficients,
      source_range,
      coefficients,
      source_range,
      0,
      1 << 16,
      1 << 16);

  RecordStage(context, kStageFrameSetup, setup_start_us);
  const int64_t sws_start_us = NowUs();
  const int result = sws_scale(
      context->sws,
      source->data,
      source->linesize,
      0,
      source->height,
      destination_data,
      destination_linesize);
  RecordStage(context, kStageSws, sws_start_us);
  if (result <= 0) {
    LogAvError("sws_scale", result);
    return false;
  }
  return true;
}

void CopyPlane(
    const uint8_t* source,
    int source_stride,
    uint8_t* destination,
    int destination_stride,
    int width,
    int height) {
  for (int row = 0; row < height; ++row) {
    memcpy(destination + row * destination_stride, source + row * source_stride, width);
  }
}

jstring NativeGetVersion(JNIEnv* env, jclass) {
  return env->NewStringUTF(av_version_info());
}

jint NativeGetInputBufferPaddingSize(JNIEnv*, jclass) {
  return AV_INPUT_BUFFER_PADDING_SIZE;
}

jboolean NativeHasH264Decoder(JNIEnv*, jclass) {
  return avcodec_find_decoder(AV_CODEC_ID_H264) != nullptr ? JNI_TRUE : JNI_FALSE;
}

jlong NativeInitialize(
    JNIEnv* env,
    jobject,
    jbyteArray extra_data,
    jint threads,
    jint rotation_degrees,
    jint width,
    jint height) {
  if (rotation_degrees != 0) {
    LOGE("Rotation %d is outside this High10 POC scope", rotation_degrees);
    return 0;
  }
  const AVCodec* decoder = avcodec_find_decoder(AV_CODEC_ID_H264);
  if (decoder == nullptr) {
    return 0;
  }
  DecoderContext* context = new (std::nothrow) DecoderContext();
  if (context == nullptr) {
    return 0;
  }
  context->codec = avcodec_alloc_context3(decoder);
  if (context->codec == nullptr) {
    delete context;
    return 0;
  }
  context->codec->thread_count = std::max(threads, 1);
  context->codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
  context->codec->pkt_timebase = AVRational{1, 1000000};
  context->codec->width = width;
  context->codec->height = height;

  if (extra_data != nullptr) {
    jsize size = env->GetArrayLength(extra_data);
    context->codec->extradata = static_cast<uint8_t*>(
        av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE));
    if (context->codec->extradata == nullptr) {
      avcodec_free_context(&context->codec);
      delete context;
      return 0;
    }
    context->codec->extradata_size = size;
    env->GetByteArrayRegion(
        extra_data, 0, size, reinterpret_cast<jbyte*>(context->codec->extradata));
  }

  int result = avcodec_open2(context->codec, decoder, nullptr);
  if (result < 0) {
    LogAvError("avcodec_open2", result);
    avcodec_free_context(&context->codec);
    delete context;
    return 0;
  }
  LOGI(
      "threading requested=%d selected=%d configuredType=%d activeType=%d capabilities=0x%x",
      threads,
      context->codec->thread_count,
      context->codec->thread_type,
      context->codec->active_thread_type,
      decoder->capabilities);

  jclass output_class = env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
  if (output_class == nullptr) {
    avcodec_free_context(&context->codec);
    delete context;
    return 0;
  }
  context->data_field = env->GetFieldID(output_class, "data", "Ljava/nio/ByteBuffer;");
  context->width_field = env->GetFieldID(output_class, "width", "I");
  context->height_field = env->GetFieldID(output_class, "height", "I");
  context->pts_field = env->GetFieldID(output_class, "timeUs", "J");
  context->init_yuv_method = env->GetMethodID(output_class, "initForYuvFrame", "(IIIII)Z");
  if (env->ExceptionCheck() || context->data_field == nullptr ||
      context->init_yuv_method == nullptr) {
    avcodec_free_context(&context->codec);
    delete context;
    return 0;
  }
  return reinterpret_cast<jlong>(context);
}

jlong NativeReset(JNIEnv*, jobject, jlong native_context) {
  DecoderContext* context = reinterpret_cast<DecoderContext*>(native_context);
  if (context == nullptr || context->codec == nullptr) {
    return 0;
  }
  avcodec_flush_buffers(context->codec);
  return native_context;
}

void NativeRelease(JNIEnv* env, jobject, jlong native_context) {
  DecoderContext* context = reinterpret_cast<DecoderContext*>(native_context);
  if (context == nullptr) {
    return;
  }
  ReleaseWindow(env, context);
  sws_freeContext(context->sws);
  avcodec_free_context(&context->codec);
  delete context;
}

jint NativeSendPacket(
    JNIEnv* env,
    jobject,
    jlong native_context,
    jobject encoded_data,
    jint length,
    jlong time_us) {
  DecoderContext* context = reinterpret_cast<DecoderContext*>(native_context);
  if (context == nullptr || context->codec == nullptr || encoded_data == nullptr) {
    return kOtherError;
  }
  AVPacket packet = {};
  packet.data = static_cast<uint8_t*>(env->GetDirectBufferAddress(encoded_data));
  packet.size = length;
  // Media3 provides presentation time, not decoding time. Do not fabricate DTS.
  packet.pts = time_us == INT64_MIN + 1 ? AV_NOPTS_VALUE : time_us;
  packet.dts = AV_NOPTS_VALUE;
  const int64_t send_start_us = NowUs();
  int result = avcodec_send_packet(context->codec, &packet);
  RecordStage(context, kStageSend, send_start_us);
  if constexpr (NUVIO_HI10_DIAGNOSTICS) ++context->send_attempts;
  if (result == AVERROR(EAGAIN)) {
    if constexpr (NUVIO_HI10_DIAGNOSTICS) ++context->send_again;
    return kTryAgain;
  }
  if (result == AVERROR_INVALIDDATA) {
    return kInvalidData;
  }
  if (result < 0) {
    LogAvError("avcodec_send_packet", result);
    return kOtherError;
  }
  if constexpr (NUVIO_HI10_DIAGNOSTICS) ++context->send_accepted;
  return kSuccess;
}

jint NativeBeginDrain(JNIEnv*, jobject, jlong native_context) {
  DecoderContext* context = reinterpret_cast<DecoderContext*>(native_context);
  if (context == nullptr || context->codec == nullptr) return kOtherError;
  const int64_t send_start_us = NowUs();
  const int result = avcodec_send_packet(context->codec, nullptr);
  RecordStage(context, kStageSend, send_start_us);
  if constexpr (NUVIO_HI10_DIAGNOSTICS) ++context->send_attempts;
  if (result == AVERROR(EAGAIN)) {
    if constexpr (NUVIO_HI10_DIAGNOSTICS) ++context->send_again;
    return kTryAgain;
  }
  if (result < 0) {
    LogAvError("avcodec_send_packet drain", result);
    return kOtherError;
  }
  if constexpr (NUVIO_HI10_DIAGNOSTICS) ++context->send_accepted;
  return kSuccess;
}

jint NativeReceiveFrame(
    JNIEnv* env,
    jobject,
    jlong native_context,
    jint,
    jobject output_buffer,
    jboolean decode_only) {
  DecoderContext* context = reinterpret_cast<DecoderContext*>(native_context);
  if (context == nullptr || context->codec == nullptr || output_buffer == nullptr) {
    return kOtherError;
  }
  const int64_t source_frame_setup_start_us = NowUs();
  AVFrame* frame = av_frame_alloc();
  if (frame == nullptr) {
    return kOtherError;
  }
  RecordStage(context, kStageFrameSetup, source_frame_setup_start_us);
  const int64_t receive_start_us = NowUs();
  int result = avcodec_receive_frame(context->codec, frame);
  RecordStage(context, kStageReceive, receive_start_us);
  if (result == AVERROR(EAGAIN)) {
    if constexpr (NUVIO_HI10_DIAGNOSTICS) ++context->receive_again;
  } else if (result == AVERROR_EOF) {
    if constexpr (NUVIO_HI10_DIAGNOSTICS) ++context->receive_eof;
  } else if (result >= 0) {
    if constexpr (NUVIO_HI10_DIAGNOSTICS) ++context->receive_frames;
  }
  if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
    av_frame_free(&frame);
    return result == AVERROR_EOF ? kEndOfStream : kTryAgain;
  }
  if (result < 0) {
    LogAvError("avcodec_receive_frame", result);
    av_frame_free(&frame);
    return result == AVERROR_INVALIDDATA ? kInvalidData : kOtherError;
  }

  const int64_t presentation_time = frame->best_effort_timestamp != AV_NOPTS_VALUE
      ? frame->best_effort_timestamp : frame->pts;
  if (presentation_time == AV_NOPTS_VALUE || decode_only) {
    av_frame_free(&frame);
    return kOtherError;
  }

  const int width = frame->width;
  const int height = frame->height;
  const int y_stride = width;
  const int uv_stride = (width + 1) / 2;
  jboolean initialized = env->CallBooleanMethod(
      output_buffer,
      context->init_yuv_method,
      width,
      height,
      y_stride,
      uv_stride,
      VideoColorSpace(frame->colorspace));
  if (env->ExceptionCheck() || !initialized) {
    av_frame_free(&frame);
    return kOtherError;
  }
  env->SetLongField(output_buffer, context->pts_field, presentation_time);
  jobject data_buffer = env->GetObjectField(output_buffer, context->data_field);
  uint8_t* destination =
      static_cast<uint8_t*>(env->GetDirectBufferAddress(data_buffer));
  if (destination == nullptr) {
    av_frame_free(&frame);
    return kOtherError;
  }
  const int uv_height = (height + 1) / 2;
  const size_t y_size = static_cast<size_t>(y_stride) * height;
  const size_t uv_size = static_cast<size_t>(uv_stride) * uv_height;
  uint8_t* destination_data[4] = {
      destination,
      destination + y_size,
      destination + y_size + uv_size,
      nullptr,
  };
  const int destination_linesize[4] = {y_stride, uv_stride, uv_stride, 0};
  const bool converted =
      ConvertToYuv420p(context, frame, destination_data, destination_linesize);
  av_frame_free(&frame);
  return converted ? kSuccess : kOtherError;
}

jint NativeRenderFrame(
    JNIEnv* env,
    jobject,
    jlong native_context,
    jobject surface,
    jobject output_buffer,
    jint displayed_width,
    jint displayed_height) {
  DecoderContext* context = reinterpret_cast<DecoderContext*>(native_context);
  if (context == nullptr || !AcquireWindow(env, context, surface)) {
    return kSurfaceError;
  }
  if (context->window_width != displayed_width || context->window_height != displayed_height) {
    int result = ANativeWindow_setBuffersGeometry(
        context->window, displayed_width, displayed_height, kImageFormatYv12);
    if (result != 0) {
      if (result == -19) {
        LOGI("Surface abandoned during geometry update; dropping frame");
        ReleaseWindow(env, context);
        return kSurfaceDropped;
      }
      return kSurfaceError;
    }
    SetBuffersDataSpaceIfAvailable(context->window, AndroidDataSpace(context));
    context->window_width = displayed_width;
    context->window_height = displayed_height;
  }

  ANativeWindow_Buffer window_buffer = {};
  const int64_t surface_lock_start_us = NowUs();
  int result = ANativeWindow_lock(context->window, &window_buffer, nullptr);
  RecordStage(context, kStageSurfaceLock, surface_lock_start_us);
  if (result != 0 || window_buffer.bits == nullptr) {
    if (result == -19) {
      LOGI("Surface abandoned during lock; dropping frame");
      ReleaseWindow(env, context);
      return kSurfaceDropped;
    }
    return kSurfaceError;
  }

  jobject data_buffer = env->GetObjectField(output_buffer, context->data_field);
  const uint8_t* source =
      static_cast<const uint8_t*>(env->GetDirectBufferAddress(data_buffer));
  const int width = env->GetIntField(output_buffer, context->width_field);
  const int height = env->GetIntField(output_buffer, context->height_field);
  const int source_y_stride = width;
  const int source_uv_stride = (width + 1) / 2;
  const int uv_height = (height + 1) / 2;
  const size_t source_y_size = static_cast<size_t>(source_y_stride) * height;
  const size_t source_uv_size = static_cast<size_t>(source_uv_stride) * uv_height;

  uint8_t* window = static_cast<uint8_t*>(window_buffer.bits);
  const int window_y_stride = window_buffer.stride;
  const int window_uv_stride = ((window_buffer.stride / 2) + 15) & ~15;
  const size_t window_y_size = static_cast<size_t>(window_y_stride) * window_buffer.height;
  const size_t window_v_size = static_cast<size_t>(window_uv_stride) * uv_height;
  const int64_t surface_copy_start_us = NowUs();
  CopyPlane(source, source_y_stride, window, window_y_stride, width, height);
  CopyPlane(
      source + source_y_size + source_uv_size,
      source_uv_stride,
      window + window_y_size,
      window_uv_stride,
      (width + 1) / 2,
      uv_height);
  CopyPlane(
      source + source_y_size,
      source_uv_stride,
      window + window_y_size + window_v_size,
      window_uv_stride,
      (width + 1) / 2,
      uv_height);
  RecordStage(context, kStageSurfaceCopy, surface_copy_start_us);
  const int64_t surface_post_start_us = NowUs();
  result = ANativeWindow_unlockAndPost(context->window);
  RecordStage(context, kStageSurfacePost, surface_post_start_us);
  if (result == -19) {
    ReleaseWindow(env, context);
    return kSurfaceDropped;
  }
  return result == 0 ? kSuccess : kSurfaceError;
}

jlongArray NativeGetPerformanceSnapshot(
    JNIEnv* env, jobject, jlong native_context) {
  DecoderContext* context = reinterpret_cast<DecoderContext*>(native_context);
  constexpr int kValuesPerStage = 6;
  constexpr int kHeaderValues = 2;
  constexpr int kPipelineValues = 6;
  const int value_count = kHeaderValues + kStageCount * kValuesPerStage + kPipelineValues;
  jlongArray result = env->NewLongArray(value_count);
  if (result == nullptr) {
    return nullptr;
  }
  std::array<jlong, value_count> values = {};
  if (context != nullptr && context->codec != nullptr) {
    values[0] = context->codec->thread_count;
    values[1] = context->codec->active_thread_type;
    for (int stage = 0; stage < kStageCount; ++stage) {
      const auto snapshot = SnapshotStage(&context->stages[stage]);
      for (int value = 0; value < kValuesPerStage; ++value) {
        values[kHeaderValues + stage * kValuesPerStage + value] = snapshot[value];
      }
    }
    const int pipeline_offset = kHeaderValues + kStageCount * kValuesPerStage;
    values[pipeline_offset] = context->send_attempts.load();
    values[pipeline_offset + 1] = context->send_again.load();
    values[pipeline_offset + 2] = context->send_accepted.load();
    values[pipeline_offset + 3] = context->receive_frames.load();
    values[pipeline_offset + 4] = context->receive_again.load();
    values[pipeline_offset + 5] = context->receive_eof.load();
  }
  env->SetLongArrayRegion(result, 0, value_count, values.data());
  return result;
}

const JNINativeMethod kLibraryMethods[] = {
    {"nativeGetVersion", "()Ljava/lang/String;", reinterpret_cast<void*>(NativeGetVersion)},
    {"nativeGetInputBufferPaddingSize", "()I", reinterpret_cast<void*>(NativeGetInputBufferPaddingSize)},
    {"nativeHasH264Decoder", "()Z", reinterpret_cast<void*>(NativeHasH264Decoder)},
};

const JNINativeMethod kDecoderMethods[] = {
    {"nativeInitialize", "([BIIII)J", reinterpret_cast<void*>(NativeInitialize)},
    {"nativeReset", "(J)J", reinterpret_cast<void*>(NativeReset)},
    {"nativeRelease", "(J)V", reinterpret_cast<void*>(NativeRelease)},
    {"nativeSendPacket", "(JLjava/nio/ByteBuffer;IJ)I", reinterpret_cast<void*>(NativeSendPacket)},
    {"nativeBeginDrain", "(J)I", reinterpret_cast<void*>(NativeBeginDrain)},
    {"nativeReceiveFrame", "(JILandroidx/media3/decoder/VideoDecoderOutputBuffer;Z)I", reinterpret_cast<void*>(NativeReceiveFrame)},
    {"nativeRenderFrame", "(JLandroid/view/Surface;Landroidx/media3/decoder/VideoDecoderOutputBuffer;II)I", reinterpret_cast<void*>(NativeRenderFrame)},
    {"nativeReleaseWindow", "(J)V", reinterpret_cast<void*>(NativeReleaseWindow)},
    {"nativeGetPerformanceSnapshot", "(J)[J", reinterpret_cast<void*>(NativeGetPerformanceSnapshot)},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }
  jclass library = env->FindClass("com/nuvio/hi10video/NuvioHi10VideoLibrary");
  jclass decoder = env->FindClass("com/nuvio/hi10video/FfmpegHigh10VideoDecoder");
  if (library == nullptr || decoder == nullptr ||
      env->RegisterNatives(
          library, kLibraryMethods, sizeof(kLibraryMethods) / sizeof(kLibraryMethods[0])) != 0 ||
      env->RegisterNatives(
          decoder, kDecoderMethods, sizeof(kDecoderMethods) / sizeof(kDecoderMethods[0])) != 0) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
