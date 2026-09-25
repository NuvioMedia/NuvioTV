#include "ffmpeg_video_gl.h"

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <condition_variable>
#include <mutex>
#include <thread>
#include <vector>

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGI(...) \
  ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))

// Column-major limited-range matrices from Media3 VideoDecoderGLSurfaceView.
// The shader subtracts the studio bias before this multiply.
static const float kColorConversion601[] = {
    1.164f, 1.164f, 1.164f, 0.0f, -0.392f, 2.017f, 1.596f, -0.813f, 0.0f,
};
static const float kColorConversion709[] = {
    1.164f, 1.164f, 1.164f, 0.0f, -0.213f, 2.112f, 1.793f, -0.533f, 0.0f,
};
static const float kColorConversion2020[] = {
    1.168f, 1.168f, 1.168f, 0.0f, -0.188f, 2.148f, 1.683f, -0.652f, 0.0f,
};

static const char* kVertexShader =
    "attribute vec4 in_pos;\n"
    "attribute vec2 in_tc_y;\n"
    "varying vec2 interp_tc_y;\n"
    "void main() {\n"
    "  gl_Position = in_pos;\n"
    "  interp_tc_y = in_tc_y;\n"
    "}\n";

// VC-1 4:2:0 is left-sited: one chroma sample per two luma columns, halfway
// between rows. Point-sample X and let the texture unit blend Y. Two taps.
static const char* kFragmentShader =
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
    "precision highp float;\n"
    "#else\n"
    "precision mediump float;\n"
    "#endif\n"
    "varying vec2 interp_tc_y;\n"
    "uniform sampler2D y_tex;\n"
    "uniform sampler2D u_tex;\n"
    "uniform sampler2D v_tex;\n"
    "uniform mat3 mColorConversion;\n"
    "uniform vec2 y_tex_size;\n"
    "uniform vec2 u_tex_size;\n"
    "uniform vec2 v_tex_size;\n"
    "float ign(vec2 p) {\n"
    "  return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));\n"
    "}\n"
    "void main() {\n"
    "  float ySample = texture2D(y_tex, interp_tc_y).r;\n"
    "  float x = interp_tc_y.x * y_tex_size.x;\n"
    "  float y = interp_tc_y.y * y_tex_size.y;\n"
    "  float cx = floor(x * 0.5) + 0.5;\n"
    "  float cy = y * 0.5 + 0.5;\n"
    "  float u = texture2D(u_tex, vec2(cx / u_tex_size.x, cy / u_tex_size.y)).r;\n"
    "  float v = texture2D(v_tex, vec2(cx / v_tex_size.x, cy / v_tex_size.y)).r;\n"
    "  vec3 yuv = vec3(ySample - 0.0625, u - 0.5, v - 0.5);\n"
    "  vec3 rgb = mColorConversion * yuv;\n"
    "  rgb += (ign(gl_FragCoord.xy) - 0.5) / 255.0;\n"
    "  gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);\n"
    "}\n";

struct VideoGlPresenter {
  EGLDisplay display;
  EGLContext context;
  EGLSurface surface;
  EGLConfig config;
  ANativeWindow* window;
  GLuint program;
  // Two texture sets so an upload never waits on the frame still on screen.
  GLuint textures[2][3];
  int upload_slot;
  GLint position_location;
  GLint texcoord_location[3];
  GLint color_matrix_location;
  GLint y_size_location;
  GLint u_size_location;
  GLint v_size_location;
  int tex_width[2][3];
  int tex_height[2][3];
  int viewport_width;
  int viewport_height;
  int buffer_width;
  int buffer_height;
  int ready;
  std::mutex mu;
  std::condition_variable cv;
  std::condition_variable done_cv;
  std::thread worker;
  bool stop;
  bool detach;
  bool detach_done;
  bool has_pending;
  ANativeWindow* pending_window;
  std::vector<uint8_t> pending_y;
  std::vector<uint8_t> pending_u;
  std::vector<uint8_t> pending_v;
  int pending_y_stride;
  int pending_u_stride;
  int pending_v_stride;
  int pending_width;
  int pending_height;
  int pending_colorspace;
};

static int renderFrame(VideoGlPresenter* presenter, ANativeWindow* window, const VideoGlFrame* frame);
static void presenterThread(VideoGlPresenter* presenter);

static GLuint compileShader(GLenum type, const char* source) {
  GLuint shader = glCreateShader(type);
  if (!shader) {
    return 0;
  }
  glShaderSource(shader, 1, &source, NULL);
  glCompileShader(shader);
  GLint compiled = 0;
  glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
  if (!compiled) {
    char log[512];
    glGetShaderInfoLog(shader, sizeof(log), NULL, log);
    LOGE("GL shader compile failed: %s", log);
    glDeleteShader(shader);
    return 0;
  }
  return shader;
}

static int createProgram(VideoGlPresenter* presenter) {
  GLuint vertex = compileShader(GL_VERTEX_SHADER, kVertexShader);
  GLuint fragment = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
  if (!vertex || !fragment) {
    if (vertex) glDeleteShader(vertex);
    if (fragment) glDeleteShader(fragment);
    return 0;
  }
  GLuint program = glCreateProgram();
  glAttachShader(program, vertex);
  glAttachShader(program, fragment);
  glLinkProgram(program);
  glDeleteShader(vertex);
  glDeleteShader(fragment);
  GLint linked = 0;
  glGetProgramiv(program, GL_LINK_STATUS, &linked);
  if (!linked) {
    char log[512];
    glGetProgramInfoLog(program, sizeof(log), NULL, log);
    LOGE("GL program link failed: %s", log);
    glDeleteProgram(program);
    return 0;
  }
  presenter->program = program;
  presenter->position_location = glGetAttribLocation(program, "in_pos");
  presenter->texcoord_location[0] = glGetAttribLocation(program, "in_tc_y");
  presenter->texcoord_location[1] = -1;
  presenter->texcoord_location[2] = -1;
  presenter->color_matrix_location = glGetUniformLocation(program, "mColorConversion");
  presenter->y_size_location = glGetUniformLocation(program, "y_tex_size");
  presenter->u_size_location = glGetUniformLocation(program, "u_tex_size");
  presenter->v_size_location = glGetUniformLocation(program, "v_tex_size");
  glUseProgram(program);
  glUniform1i(glGetUniformLocation(program, "y_tex"), 0);
  glUniform1i(glGetUniformLocation(program, "u_tex"), 1);
  glUniform1i(glGetUniformLocation(program, "v_tex"), 2);
  for (int slot = 0; slot < 2; ++slot) {
    glGenTextures(3, presenter->textures[slot]);
    for (int i = 0; i < 3; ++i) {
      glActiveTexture(GL_TEXTURE0 + i);
      glBindTexture(GL_TEXTURE_2D, presenter->textures[slot][i]);
      // Luma is 1:1. The packed chroma texture is sampled with linear taps.
      GLenum filter = i == 0 ? GL_NEAREST : GL_LINEAR;
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      presenter->tex_width[slot][i] = 0;
      presenter->tex_height[slot][i] = 0;
    }
  }
  presenter->upload_slot = 0;
  return 1;
}

static void destroySurface(VideoGlPresenter* presenter) {
  if (!presenter || presenter->display == EGL_NO_DISPLAY) {
    return;
  }
  eglMakeCurrent(presenter->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  if (presenter->surface != EGL_NO_SURFACE) {
    eglDestroySurface(presenter->display, presenter->surface);
    presenter->surface = EGL_NO_SURFACE;
  }
  presenter->window = NULL;
  presenter->viewport_width = 0;
  presenter->viewport_height = 0;
  presenter->buffer_width = 0;
  presenter->buffer_height = 0;
}

static int ensureContext(VideoGlPresenter* presenter, ANativeWindow* window, int width,
                         int height) {
  if (presenter->ready && presenter->window == window && presenter->buffer_width == width &&
      presenter->buffer_height == height && presenter->surface != EGL_NO_SURFACE) {
    if (!eglMakeCurrent(presenter->display, presenter->surface, presenter->surface,
                        presenter->context)) {
      LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
      return 0;
    }
    return 1;
  }
  if (presenter->surface != EGL_NO_SURFACE &&
      (presenter->window != window || presenter->buffer_width != width ||
       presenter->buffer_height != height)) {
    destroySurface(presenter);
  }
  if (presenter->context == EGL_NO_CONTEXT) {
    presenter->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (presenter->display == EGL_NO_DISPLAY) {
      LOGE("eglGetDisplay failed");
      return 0;
    }
    if (!eglInitialize(presenter->display, NULL, NULL)) {
      LOGE("eglInitialize failed: 0x%x", eglGetError());
      presenter->display = EGL_NO_DISPLAY;
      return 0;
    }
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 0,
        EGL_NONE};
    EGLint count = 0;
    eglChooseConfig(presenter->display, configAttribs, NULL, 0, &count);
    if (count < 1) {
      LOGE("eglChooseConfig failed: 0x%x", eglGetError());
      return 0;
    }
    EGLConfig* configs = new EGLConfig[count];
    eglChooseConfig(presenter->display, configAttribs, configs, count, &count);
    presenter->config = configs[0];
    for (EGLint i = 0; i < count; ++i) {
      EGLint alpha = 8;
      EGLint red = 0;
      eglGetConfigAttrib(presenter->display, configs[i], EGL_ALPHA_SIZE, &alpha);
      eglGetConfigAttrib(presenter->display, configs[i], EGL_RED_SIZE, &red);
      if (alpha == 0 && red >= 8) {
        presenter->config = configs[i];
        break;
      }
    }
    delete[] configs;
    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    presenter->context = eglCreateContext(presenter->display, presenter->config, EGL_NO_CONTEXT,
                                          contextAttribs);
    if (presenter->context == EGL_NO_CONTEXT) {
      LOGE("eglCreateContext failed: 0x%x", eglGetError());
      return 0;
    }
  }
  // Opaque RGBX plus exact sRGB lets the display plane scale 1080p to 4K.
  // WINDOW_FORMAT_RGBX_8888 = 2. ADATASPACE_SRGB = 142671872.
  ANativeWindow_setBuffersGeometry(window, width, height, 2);
  typedef int32_t (*ThrottleFn)(ANativeWindow*, bool);
  // A deeper queue absorbs a slow VC-1 frame without blocking the next present.
  typedef int (*SetBufferCountFn)(ANativeWindow*, size_t);
  void* nativeWindowLib = dlopen("libnativewindow.so", RTLD_NOW);
  if (!nativeWindowLib) {
    nativeWindowLib = dlopen("libandroid.so", RTLD_NOW);
  }
  if (nativeWindowLib) {
    SetBufferCountFn setBufferCount =
        (SetBufferCountFn)dlsym(nativeWindowLib, "native_window_set_buffer_count");
    if (setBufferCount) {
      setBufferCount(window, 4);
    }
    ThrottleFn setThrottle =
        (ThrottleFn)dlsym(nativeWindowLib, "ANativeWindow_setProducerThrottlingEnabled");
    if (setThrottle) {
      setThrottle(window, false);
    }
    typedef int (*SetUsageFn)(ANativeWindow*, uint64_t);
    typedef int32_t (*SetDataSpaceFn)(ANativeWindow*, int32_t);
    SetUsageFn setUsage = (SetUsageFn)dlsym(nativeWindowLib, "native_window_set_usage");
    if (setUsage) {
      // GPU_COLOR_OUTPUT | COMPOSER_OVERLAY. No sampled-image bit, so this stays a plane.
      setUsage(window, (1ULL << 1) | (1ULL << 11));
    }
    SetDataSpaceFn setDataSpace =
        (SetDataSpaceFn)dlsym(nativeWindowLib, "ANativeWindow_setBuffersDataSpace");
    if (setDataSpace) {
      setDataSpace(window, 142671872);
    }
  }
  presenter->buffer_width = width;
  presenter->buffer_height = height;
  presenter->surface =
      eglCreateWindowSurface(presenter->display, presenter->config, window, NULL);
  if (presenter->surface == EGL_NO_SURFACE) {
    ANativeWindow_setBuffersGeometry(window, width, height, 0);
    presenter->surface =
        eglCreateWindowSurface(presenter->display, presenter->config, window, NULL);
  }
  if (presenter->surface == EGL_NO_SURFACE) {
    LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
    if (nativeWindowLib) {
      dlclose(nativeWindowLib);
    }
    return 0;
  }
  if (!eglMakeCurrent(presenter->display, presenter->surface, presenter->surface,
                      presenter->context)) {
    LOGE("eglMakeCurrent(window) failed: 0x%x", eglGetError());
    eglDestroySurface(presenter->display, presenter->surface);
    presenter->surface = EGL_NO_SURFACE;
    if (nativeWindowLib) {
      dlclose(nativeWindowLib);
    }
    return 0;
  }
  eglSwapInterval(presenter->display, 0);
  if (nativeWindowLib) {
    typedef int32_t (*SetDataSpaceFn)(ANativeWindow*, int32_t);
    SetDataSpaceFn setDataSpace =
        (SetDataSpaceFn)dlsym(nativeWindowLib, "ANativeWindow_setBuffersDataSpace");
    if (setDataSpace) {
      setDataSpace(window, 142671872);
    }
    dlclose(nativeWindowLib);
  }
  presenter->window = window;
  if (!presenter->ready) {
    if (!createProgram(presenter)) {
      destroySurface(presenter);
      return 0;
    }
    presenter->ready = 1;
    LOGI("VC-1 RGBX sRGB overlay at %dx%d", width, height);
  }
  return 1;
}

static void uploadPlane(VideoGlPresenter* presenter, int slot, int index, const uint8_t* data,
                        int stride, int height) {
  glActiveTexture(GL_TEXTURE0 + index);
  glBindTexture(GL_TEXTURE_2D, presenter->textures[slot][index]);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  if (presenter->tex_width[slot][index] == stride && presenter->tex_height[slot][index] == height) {
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, stride, height, GL_LUMINANCE, GL_UNSIGNED_BYTE, data);
  } else {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, stride, height, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE,
                 data);
    presenter->tex_width[slot][index] = stride;
    presenter->tex_height[slot][index] = height;
  }
}

static void setTexCoords(GLint location, float widthRatio, float* coords) {
  coords[0] = 0.0f;
  coords[1] = 0.0f;
  coords[2] = 0.0f;
  coords[3] = 1.0f;
  coords[4] = widthRatio;
  coords[5] = 0.0f;
  coords[6] = widthRatio;
  coords[7] = 1.0f;
  glVertexAttribPointer(location, 2, GL_FLOAT, GL_FALSE, 0, coords);
}

VideoGlPresenter* video_gl_create() {
  VideoGlPresenter* presenter = new VideoGlPresenter();
  presenter->display = EGL_NO_DISPLAY;
  presenter->context = EGL_NO_CONTEXT;
  presenter->surface = EGL_NO_SURFACE;
  presenter->config = NULL;
  presenter->window = NULL;
  presenter->program = 0;
  presenter->upload_slot = 0;
  memset(presenter->textures, 0, sizeof(presenter->textures));
  presenter->position_location = -1;
  presenter->texcoord_location[0] = presenter->texcoord_location[1] =
      presenter->texcoord_location[2] = -1;
  presenter->color_matrix_location = -1;
  presenter->y_size_location = -1;
  presenter->u_size_location = -1;
  presenter->v_size_location = -1;
  memset(presenter->tex_width, 0, sizeof(presenter->tex_width));
  memset(presenter->tex_height, 0, sizeof(presenter->tex_height));
  presenter->viewport_width = 0;
  presenter->viewport_height = 0;
  presenter->buffer_width = 0;
  presenter->buffer_height = 0;
  presenter->ready = 0;
  presenter->stop = false;
  presenter->detach = false;
  presenter->detach_done = false;
  presenter->has_pending = false;
  presenter->pending_window = NULL;
  presenter->pending_y_stride = 0;
  presenter->pending_u_stride = 0;
  presenter->pending_v_stride = 0;
  presenter->pending_width = 0;
  presenter->pending_height = 0;
  presenter->pending_colorspace = 2;
  presenter->worker = std::thread(presenterThread, presenter);
  return presenter;
}

void video_gl_detach(VideoGlPresenter* presenter) {
  if (!presenter) {
    return;
  }
  std::unique_lock<std::mutex> lock(presenter->mu);
  presenter->detach = true;
  presenter->detach_done = false;
  presenter->cv.notify_all();
  presenter->done_cv.wait(lock, [&] { return presenter->detach_done; });
}

int video_gl_has_surface(const VideoGlPresenter* presenter) {
  return presenter && presenter->surface != EGL_NO_SURFACE;
}

static void releaseGl(VideoGlPresenter* presenter) {
  if (presenter->display != EGL_NO_DISPLAY && presenter->context != EGL_NO_CONTEXT &&
      presenter->surface != EGL_NO_SURFACE) {
    eglMakeCurrent(presenter->display, presenter->surface, presenter->surface, presenter->context);
    if (presenter->program) {
      glDeleteProgram(presenter->program);
      presenter->program = 0;
    }
    for (int slot = 0; slot < 2; ++slot) {
      if (presenter->textures[slot][0]) {
        glDeleteTextures(3, presenter->textures[slot]);
        presenter->textures[slot][0] = 0;
      }
    }
    eglMakeCurrent(presenter->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  }
  if (presenter->display != EGL_NO_DISPLAY) {
    destroySurface(presenter);
    if (presenter->context != EGL_NO_CONTEXT) {
      eglDestroyContext(presenter->display, presenter->context);
      presenter->context = EGL_NO_CONTEXT;
    }
    eglTerminate(presenter->display);
    presenter->display = EGL_NO_DISPLAY;
  }
}

static void copyPlaneBytes(std::vector<uint8_t>& dst, const uint8_t* src, int stride, int height) {
  size_t bytes = (size_t)stride * (size_t)height;
  dst.resize(bytes);
  if (bytes > 0 && src) {
    memcpy(dst.data(), src, bytes);
  }
}

static void presenterThread(VideoGlPresenter* presenter) {
  pthread_setname_np(pthread_self(), "VC1GlPresent");
  for (;;) {
    std::vector<uint8_t> y;
    std::vector<uint8_t> u;
    std::vector<uint8_t> v;
    int y_stride = 0;
    int u_stride = 0;
    int v_stride = 0;
    int width = 0;
    int height = 0;
    int colorspace = 2;
    ANativeWindow* window = NULL;
    bool detach = false;
    {
      std::unique_lock<std::mutex> lock(presenter->mu);
      presenter->cv.wait(lock, [&] {
        return presenter->stop || presenter->detach || presenter->has_pending;
      });
      if (presenter->detach) {
        detach = true;
        presenter->detach = false;
      } else if (presenter->has_pending) {
        y.swap(presenter->pending_y);
        u.swap(presenter->pending_u);
        v.swap(presenter->pending_v);
        y_stride = presenter->pending_y_stride;
        u_stride = presenter->pending_u_stride;
        v_stride = presenter->pending_v_stride;
        width = presenter->pending_width;
        height = presenter->pending_height;
        colorspace = presenter->pending_colorspace;
        window = presenter->pending_window;
        presenter->has_pending = false;
      } else if (presenter->stop) {
        break;
      }
    }
    if (detach) {
      destroySurface(presenter);
      std::lock_guard<std::mutex> lock(presenter->mu);
      presenter->detach_done = true;
      presenter->done_cv.notify_all();
      continue;
    }
    if (window && !y.empty() && !u.empty() && !v.empty()) {
      VideoGlFrame frame;
      frame.y = y.data();
      frame.u = u.data();
      frame.v = v.data();
      frame.y_stride = y_stride;
      frame.u_stride = u_stride;
      frame.v_stride = v_stride;
      frame.width = width;
      frame.height = height;
      frame.colorspace = colorspace;
      renderFrame(presenter, window, &frame);
    }
  }
  releaseGl(presenter);
}

void video_gl_destroy(VideoGlPresenter* presenter) {
  if (!presenter) {
    return;
  }
  {
    std::lock_guard<std::mutex> lock(presenter->mu);
    presenter->stop = true;
  }
  presenter->cv.notify_all();
  if (presenter->worker.joinable()) {
    presenter->worker.join();
  }
  delete presenter;
}

static int renderFrame(VideoGlPresenter* presenter, ANativeWindow* window, const VideoGlFrame* frame) {
  if (!presenter || !window || !frame || !frame->y || !frame->u || !frame->v ||
      frame->width <= 0 || frame->height <= 0 || frame->y_stride <= 0 || frame->u_stride <= 0 ||
      frame->v_stride <= 0) {
    return -1;
  }
  if (!ensureContext(presenter, window, frame->width, frame->height)) {
    return -1;
  }
  EGLint surfaceWidth = 0;
  EGLint surfaceHeight = 0;
  eglQuerySurface(presenter->display, presenter->surface, EGL_WIDTH, &surfaceWidth);
  eglQuerySurface(presenter->display, presenter->surface, EGL_HEIGHT, &surfaceHeight);
  if (surfaceWidth <= 0 || surfaceHeight <= 0) {
    surfaceWidth = frame->width;
    surfaceHeight = frame->height;
  }
  if (surfaceWidth != presenter->viewport_width || surfaceHeight != presenter->viewport_height) {
    glViewport(0, 0, surfaceWidth, surfaceHeight);
    presenter->viewport_width = surfaceWidth;
    presenter->viewport_height = surfaceHeight;
  }

  glUseProgram(presenter->program);
  const float* matrix = kColorConversion709;
  if (frame->colorspace == 1) {
    matrix = kColorConversion601;
  } else if (frame->colorspace == 3) {
    matrix = kColorConversion2020;
  }
  glUniformMatrix3fv(presenter->color_matrix_location, 1, GL_FALSE, matrix);

  int chromaHeight = (frame->height + 1) / 2;
  glUniform2f(presenter->y_size_location, (float)frame->y_stride, (float)frame->height);
  glUniform2f(presenter->u_size_location, (float)frame->u_stride, (float)chromaHeight);
  glUniform2f(presenter->v_size_location, (float)frame->v_stride, (float)chromaHeight);
  int slot = presenter->upload_slot;
  uploadPlane(presenter, slot, 0, frame->y, frame->y_stride, frame->height);
  uploadPlane(presenter, slot, 1, frame->u, frame->u_stride, chromaHeight);
  uploadPlane(presenter, slot, 2, frame->v, frame->v_stride, chromaHeight);

  const float position[] = {-1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f, -1.0f};
  float texY[8];
  glEnableVertexAttribArray(presenter->position_location);
  glVertexAttribPointer(presenter->position_location, 2, GL_FLOAT, GL_FALSE, 0, position);
  glEnableVertexAttribArray(presenter->texcoord_location[0]);
  setTexCoords(presenter->texcoord_location[0],
               (float)frame->width / (float)frame->y_stride, texY);
  glDisable(GL_DEPTH_TEST);
  glDisable(GL_BLEND);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  if (!eglSwapBuffers(presenter->display, presenter->surface)) {
    LOGE("eglSwapBuffers failed: 0x%x", eglGetError());
    return -1;
  }
  presenter->upload_slot ^= 1;
  return 0;
}

int video_gl_draw(VideoGlPresenter* presenter, ANativeWindow* window, const VideoGlFrame* frame) {
  if (!presenter || !window || !frame || !frame->y || !frame->u || !frame->v ||
      frame->width <= 0 || frame->height <= 0 || frame->y_stride <= 0 || frame->u_stride <= 0 ||
      frame->v_stride <= 0) {
    return -1;
  }
  int chromaHeight = (frame->height + 1) / 2;
  {
    std::lock_guard<std::mutex> lock(presenter->mu);
    copyPlaneBytes(presenter->pending_y, frame->y, frame->y_stride, frame->height);
    copyPlaneBytes(presenter->pending_u, frame->u, frame->u_stride, chromaHeight);
    copyPlaneBytes(presenter->pending_v, frame->v, frame->v_stride, chromaHeight);
    presenter->pending_y_stride = frame->y_stride;
    presenter->pending_u_stride = frame->u_stride;
    presenter->pending_v_stride = frame->v_stride;
    presenter->pending_width = frame->width;
    presenter->pending_height = frame->height;
    presenter->pending_colorspace = frame->colorspace;
    presenter->pending_window = window;
    presenter->has_pending = true;
  }
  presenter->cv.notify_all();
  return 0;
}
