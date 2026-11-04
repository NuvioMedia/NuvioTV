/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * FFmpeg software video decoding for the NuvioTV FFmpeg extension, used for
 * codecs that Android TV hardware often lacks (VC-1 / WMV3). The surface
 * rendering follows the AndroidX Media3 libvpx extension (Apache-2.0).
 */
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>

#include <algorithm>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/pixdesc.h>
}

#define LOG_TAG "ffmpeg_video_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGW(...) \
  ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))

#define VIDEO_DECODER_FUNC(RETURN_TYPE, NAME, ...)               \
  extern "C" {                                                   \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                 \
  }                                                              \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegVideoDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

// LINT.IfChange
static const int VIDEO_DECODER_SUCCESS = 0;
static const int VIDEO_DECODER_NO_FRAME = 1;
static const int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
static const int VIDEO_DECODER_ERROR_OTHER = -2;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegVideoDecoder.java)

// Matches VideoDecoderOutputBuffer.COLORSPACE_*.
static const int COLORSPACE_UNKNOWN = 0;
static const int COLORSPACE_BT601 = 1;
static const int COLORSPACE_BT709 = 2;
static const int COLORSPACE_BT2020 = 3;

// Android's HAL_PIXEL_FORMAT_YV12.
static const int IMAGE_FORMAT_YV12 = 0x32315659;

// ADataSpace values from <android/data_space.h> (API 28+).
static const int32_t DATASPACE_UNKNOWN = 0;
static const int32_t DATASPACE_BT601_625 = 281149440;
static const int32_t DATASPACE_BT709 = 281083904;
static const int32_t DATASPACE_BT2020 = 147193856;

// A run of undecodable packets this long means the stream will not decode;
// shorter runs are damaged packets FFmpeg recovers from.
static const int MAX_CONSECUTIVE_DECODE_ERRORS = 60;

struct VideoDecoderContext {
  AVCodecContext* codec_context;
  AVPacket* packet;
  AVFrame* frame;
  bool has_frame;
  int consecutive_errors;

  // Surface output state, used only from the playback thread.
  jobject surface;
  ANativeWindow* native_window;
  int window_width;
  int window_height;
  int32_t window_dataspace;
};

struct OutputBufferFields {
  bool initialized;
  jfieldID data;
  jfieldID width;
  jfieldID height;
  jfieldID y_stride;
  jfieldID uv_stride;
  jfieldID colorspace;
  jmethodID init_for_yuv_frame;
};

static OutputBufferFields output_buffer_fields = {};

typedef int32_t (*SetBuffersDataSpaceFunc)(ANativeWindow*, int32_t);
static SetBuffersDataSpaceFunc set_buffers_data_space = nullptr;
static bool set_buffers_data_space_looked_up = false;

static void logVideoError(const char* function_name, int error_number) {
  char buffer[256];
  av_strerror(error_number, buffer, sizeof(buffer));
  LOGE("Error in %s: %s", function_name, buffer);
}

static bool initOutputBufferFields(JNIEnv* env, jobject output_buffer) {
  if (output_buffer_fields.initialized) return true;
  jclass clazz = env->GetObjectClass(output_buffer);
  if (clazz == nullptr) return false;
  OutputBufferFields fields;
  fields.data = env->GetFieldID(clazz, "data", "Ljava/nio/ByteBuffer;");
  fields.width = env->GetFieldID(clazz, "width", "I");
  fields.height = env->GetFieldID(clazz, "height", "I");
  fields.y_stride = env->GetFieldID(clazz, "yStride", "I");
  fields.uv_stride = env->GetFieldID(clazz, "uvStride", "I");
  fields.colorspace = env->GetFieldID(clazz, "colorspace", "I");
  fields.init_for_yuv_frame =
      env->GetMethodID(clazz, "initForYuvFrame", "(IIIII)Z");
  env->DeleteLocalRef(clazz);
  if (env->ExceptionCheck() || !fields.data || !fields.width ||
      !fields.height || !fields.y_stride || !fields.uv_stride ||
      !fields.colorspace || !fields.init_for_yuv_frame) {
    env->ExceptionClear();
    LOGE("VideoDecoderOutputBuffer fields not found");
    return false;
  }
  fields.initialized = true;
  output_buffer_fields = fields;
  return true;
}

static int toOutputColorspace(const AVFrame* frame) {
  switch (frame->colorspace) {
    case AVCOL_SPC_BT709:
      return COLORSPACE_BT709;
    case AVCOL_SPC_BT470BG:
    case AVCOL_SPC_SMPTE170M:
    case AVCOL_SPC_SMPTE240M:
      return COLORSPACE_BT601;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
      return COLORSPACE_BT2020;
    default:
      // VC-1 often leaves the matrix unsignalled; HD content is BT.709.
      return frame->height > 576 ? COLORSPACE_BT709 : COLORSPACE_BT601;
  }
}

static int32_t toDataspace(int colorspace) {
  switch (colorspace) {
    case COLORSPACE_BT709:
      return DATASPACE_BT709;
    case COLORSPACE_BT601:
      return DATASPACE_BT601_625;
    case COLORSPACE_BT2020:
      return DATASPACE_BT2020;
    default:
      return DATASPACE_UNKNOWN;
  }
}

static int alignTo16(int value) { return (value + 15) & ~15; }

static void releaseNativeWindow(JNIEnv* env, VideoDecoderContext* context) {
  if (context->native_window != nullptr) {
    ANativeWindow_release(context->native_window);
    context->native_window = nullptr;
  }
  if (context->surface != nullptr) {
    env->DeleteGlobalRef(context->surface);
    context->surface = nullptr;
  }
  context->window_width = 0;
  context->window_height = 0;
  context->window_dataspace = DATASPACE_UNKNOWN;
}

static void acquireNativeWindow(JNIEnv* env, VideoDecoderContext* context,
                                jobject surface) {
  if (context->surface != nullptr &&
      env->IsSameObject(context->surface, surface)) {
    return;
  }
  releaseNativeWindow(env, context);
  context->native_window = ANativeWindow_fromSurface(env, surface);
  if (context->native_window != nullptr) {
    context->surface = env->NewGlobalRef(surface);
  }
}

static void setWindowDataspace(VideoDecoderContext* context,
                               int32_t dataspace) {
  if (dataspace == DATASPACE_UNKNOWN ||
      dataspace == context->window_dataspace) {
    return;
  }
  if (!set_buffers_data_space_looked_up) {
    set_buffers_data_space_looked_up = true;
    void* library = dlopen("libandroid.so", RTLD_NOW);
    if (library != nullptr) {
      set_buffers_data_space = reinterpret_cast<SetBuffersDataSpaceFunc>(
          dlsym(library, "ANativeWindow_setBuffersDataSpace"));
    }
  }
  if (set_buffers_data_space != nullptr &&
      set_buffers_data_space(context->native_window, dataspace) == 0) {
    context->window_dataspace = dataspace;
  }
}

static void releaseVideoContext(JNIEnv* env, VideoDecoderContext* context) {
  if (context == nullptr) return;
  releaseNativeWindow(env, context);
  if (context->frame != nullptr) av_frame_free(&context->frame);
  if (context->packet != nullptr) av_packet_free(&context->packet);
  if (context->codec_context != nullptr) {
    avcodec_free_context(&context->codec_context);
  }
  delete context;
}

VIDEO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName,
                   jbyteArray extraData, jint width, jint height,
                   jint threads) {
  const char* codec_name = env->GetStringUTFChars(codecName, nullptr);
  const AVCodec* codec = avcodec_find_decoder_by_name(codec_name);
  if (codec == nullptr) {
    LOGE("Video decoder %s not found", codec_name);
    env->ReleaseStringUTFChars(codecName, codec_name);
    return 0L;
  }
  env->ReleaseStringUTFChars(codecName, codec_name);

  VideoDecoderContext* context = new VideoDecoderContext();
  context->codec_context = avcodec_alloc_context3(codec);
  context->packet = av_packet_alloc();
  context->frame = av_frame_alloc();
  if (context->codec_context == nullptr || context->packet == nullptr ||
      context->frame == nullptr) {
    LOGE("Failed to allocate the video decoder context");
    releaseVideoContext(env, context);
    return 0L;
  }

  AVCodecContext* codec_context = context->codec_context;
  if (extraData != nullptr) {
    jsize size = env->GetArrayLength(extraData);
    codec_context->extradata = static_cast<uint8_t*>(
        av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE));
    if (codec_context->extradata == nullptr) {
      LOGE("Failed to allocate extradata");
      releaseVideoContext(env, context);
      return 0L;
    }
    codec_context->extradata_size = size;
    env->GetByteArrayRegion(extraData, 0, size,
                            reinterpret_cast<jbyte*>(codec_context->extradata));
  }
  // WMV3 extradata carries no picture size; the container provides it.
  if (width > 0 && height > 0) {
    codec_context->width = width;
    codec_context->height = height;
    codec_context->coded_width = width;
    codec_context->coded_height = height;
  }
  codec_context->thread_count = threads;
  codec_context->pkt_timebase = AVRational{1, 1000000};
  codec_context->err_recognition = 0;

  int result = avcodec_open2(codec_context, codec, nullptr);
  if (result < 0) {
    logVideoError("avcodec_open2", result);
    releaseVideoContext(env, context);
    return 0L;
  }
  return reinterpret_cast<jlong>(context);
}

static int receiveFrame(VideoDecoderContext* context) {
  av_frame_unref(context->frame);
  int result = avcodec_receive_frame(context->codec_context, context->frame);
  if (result == 0) {
    context->has_frame = true;
    return VIDEO_DECODER_SUCCESS;
  }
  context->has_frame = false;
  if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
    return VIDEO_DECODER_NO_FRAME;
  }
  return result;
}

VIDEO_DECODER_FUNC(jint, ffmpegDecode, jlong jContext, jobject encoded,
                   jint length, jlong timeUs) {
  VideoDecoderContext* context =
      reinterpret_cast<VideoDecoderContext*>(jContext);
  uint8_t* data =
      static_cast<uint8_t*>(env->GetDirectBufferAddress(encoded));
  if (data == nullptr) {
    LOGE("Input buffer is not direct");
    return VIDEO_DECODER_ERROR_OTHER;
  }

  AVPacket* packet = context->packet;
  av_packet_unref(packet);
  packet->data = data;
  packet->size = length;
  packet->pts = timeUs;

  int result = avcodec_send_packet(context->codec_context, packet);
  if (result == AVERROR(EAGAIN)) {
    // A frame is still waiting; take it and queue the packet again.
    int receive_result = receiveFrame(context);
    if (receive_result < 0) {
      logVideoError("avcodec_receive_frame", receive_result);
      return VIDEO_DECODER_ERROR_OTHER;
    }
    result = avcodec_send_packet(context->codec_context, packet);
    av_packet_unref(packet);
    if (result < 0 && result != AVERROR(EAGAIN)) {
      logVideoError("avcodec_send_packet", result);
    }
    return context->has_frame ? VIDEO_DECODER_SUCCESS : VIDEO_DECODER_NO_FRAME;
  }
  av_packet_unref(packet);

  if (result < 0) {
    if (result == AVERROR(ENOMEM)) {
      logVideoError("avcodec_send_packet", result);
      return VIDEO_DECODER_ERROR_OTHER;
    }
    // VC-1 reports damaged pictures as errors; FFmpeg resyncs on the next
    // packet, so skip this one unless the stream never decodes.
    if (++context->consecutive_errors >= MAX_CONSECUTIVE_DECODE_ERRORS) {
      logVideoError("avcodec_send_packet", result);
      return VIDEO_DECODER_ERROR_OTHER;
    }
    return VIDEO_DECODER_ERROR_INVALID_DATA;
  }

  result = receiveFrame(context);
  if (result < 0) {
    if (++context->consecutive_errors >= MAX_CONSECUTIVE_DECODE_ERRORS) {
      logVideoError("avcodec_receive_frame", result);
      return VIDEO_DECODER_ERROR_OTHER;
    }
    return VIDEO_DECODER_ERROR_INVALID_DATA;
  }
  context->consecutive_errors = 0;
  return result;
}

VIDEO_DECODER_FUNC(jboolean, ffmpegCopyFrame, jlong jContext,
                   jobject outputBuffer) {
  VideoDecoderContext* context =
      reinterpret_cast<VideoDecoderContext*>(jContext);
  if (!context->has_frame) return JNI_FALSE;
  const AVFrame* frame = context->frame;
  if (frame->format != AV_PIX_FMT_YUV420P &&
      frame->format != AV_PIX_FMT_YUVJ420P) {
    const char* name =
        av_get_pix_fmt_name(static_cast<AVPixelFormat>(frame->format));
    LOGE("Unsupported pixel format %s", name ? name : "unknown");
    return JNI_FALSE;
  }
  if (!initOutputBufferFields(env, outputBuffer)) return JNI_FALSE;

  const int width = frame->width;
  const int height = frame->height;
  const int uv_width = (width + 1) / 2;
  const int uv_height = (height + 1) / 2;
  const int y_stride = alignTo16(width);
  const int uv_stride = alignTo16(uv_width);
  jboolean initialized = env->CallBooleanMethod(
      outputBuffer, output_buffer_fields.init_for_yuv_frame, width, height,
      y_stride, uv_stride, toOutputColorspace(frame));
  if (env->ExceptionCheck() || !initialized) {
    env->ExceptionClear();
    return JNI_FALSE;
  }
  jobject data_object =
      env->GetObjectField(outputBuffer, output_buffer_fields.data);
  uint8_t* data =
      data_object ? static_cast<uint8_t*>(env->GetDirectBufferAddress(data_object))
                  : nullptr;
  if (data_object) env->DeleteLocalRef(data_object);
  if (data == nullptr) return JNI_FALSE;

  uint8_t* destination = data;
  for (int y = 0; y < height; y++) {
    memcpy(destination, frame->data[0] + y * frame->linesize[0], width);
    destination += y_stride;
  }
  uint8_t* destination_u = data + y_stride * height;
  uint8_t* destination_v = destination_u + uv_stride * uv_height;
  for (int y = 0; y < uv_height; y++) {
    memcpy(destination_u, frame->data[1] + y * frame->linesize[1], uv_width);
    memcpy(destination_v, frame->data[2] + y * frame->linesize[2], uv_width);
    destination_u += uv_stride;
    destination_v += uv_stride;
  }
  return JNI_TRUE;
}

VIDEO_DECODER_FUNC(jboolean, ffmpegRenderFrame, jlong jContext,
                   jobject surface, jobject outputBuffer) {
  VideoDecoderContext* context =
      reinterpret_cast<VideoDecoderContext*>(jContext);
  if (!initOutputBufferFields(env, outputBuffer)) return JNI_FALSE;
  acquireNativeWindow(env, context, surface);
  if (context->native_window == nullptr) {
    // No surface to draw on (e.g. it is being torn down); drop the frame.
    return JNI_TRUE;
  }

  const int width = env->GetIntField(outputBuffer, output_buffer_fields.width);
  const int height =
      env->GetIntField(outputBuffer, output_buffer_fields.height);
  const int src_y_stride =
      env->GetIntField(outputBuffer, output_buffer_fields.y_stride);
  const int src_uv_stride =
      env->GetIntField(outputBuffer, output_buffer_fields.uv_stride);
  const int colorspace =
      env->GetIntField(outputBuffer, output_buffer_fields.colorspace);
  jobject data_object =
      env->GetObjectField(outputBuffer, output_buffer_fields.data);
  const uint8_t* src =
      data_object
          ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(data_object))
          : nullptr;
  if (data_object) env->DeleteLocalRef(data_object);
  if (src == nullptr || width <= 0 || height <= 0) return JNI_FALSE;

  if (context->window_width != width || context->window_height != height) {
    ANativeWindow_setBuffersGeometry(context->native_window, width, height,
                                     IMAGE_FORMAT_YV12);
    context->window_width = width;
    context->window_height = height;
  }
  setWindowDataspace(context, toDataspace(colorspace));

  ANativeWindow_Buffer buffer;
  if (ANativeWindow_lock(context->native_window, &buffer, nullptr) != 0 ||
      buffer.bits == nullptr) {
    return JNI_FALSE;
  }

  // Y plane.
  const int copy_height = std::min(height, static_cast<int>(buffer.height));
  const int copy_width = std::min(width, static_cast<int>(buffer.stride));
  const uint8_t* src_y = src;
  uint8_t* dest_y = static_cast<uint8_t*>(buffer.bits);
  for (int y = 0; y < copy_height; y++) {
    memcpy(dest_y, src_y, copy_width);
    src_y += src_y_stride;
    dest_y += buffer.stride;
  }

  // YV12 stores V before U, each with a 16-byte aligned stride.
  const int src_uv_height = (height + 1) / 2;
  const int dest_uv_stride = alignTo16(buffer.stride / 2);
  const int dest_uv_height = (buffer.height + 1) / 2;
  const int uv_rows = std::min(src_uv_height, dest_uv_height);
  const int uv_copy_width = std::min((width + 1) / 2, dest_uv_stride);
  const uint8_t* src_u = src + src_y_stride * height;
  const uint8_t* src_v = src_u + src_uv_stride * src_uv_height;
  uint8_t* dest_v =
      static_cast<uint8_t*>(buffer.bits) + buffer.stride * buffer.height;
  uint8_t* dest_u = dest_v + dest_uv_stride * dest_uv_height;
  for (int y = 0; y < uv_rows; y++) {
    memcpy(dest_u, src_u, uv_copy_width);
    memcpy(dest_v, src_v, uv_copy_width);
    src_u += src_uv_stride;
    src_v += src_uv_stride;
    dest_u += dest_uv_stride;
    dest_v += dest_uv_stride;
  }
  return ANativeWindow_unlockAndPost(context->native_window) == 0 ? JNI_TRUE
                                                                  : JNI_FALSE;
}

VIDEO_DECODER_FUNC(void, ffmpegFlush, jlong jContext) {
  VideoDecoderContext* context =
      reinterpret_cast<VideoDecoderContext*>(jContext);
  avcodec_flush_buffers(context->codec_context);
  av_frame_unref(context->frame);
  context->has_frame = false;
  context->consecutive_errors = 0;
}

VIDEO_DECODER_FUNC(void, ffmpegRelease, jlong jContext) {
  releaseVideoContext(env, reinterpret_cast<VideoDecoderContext*>(jContext));
}
