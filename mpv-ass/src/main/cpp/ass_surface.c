// libass, owned by one thread: the file's fonts and subtitle tracks, and each
// moment's images blended into one premultiplied picture in a window buffer,
// so the compositor lays a single layer over the video.

#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "ass/ass.h"

// libass as libmpv carries it: the app ships it for mpv anyway, built optimised.
#define LIBASS(X) \
    X(library_init) X(library_done) X(set_message_cb) X(set_extract_fonts) X(add_font) X(clear_fonts) \
    X(renderer_init) X(renderer_done) X(set_fonts) X(set_frame_size) X(set_storage_size) X(set_margins) \
    X(set_use_margins) X(set_font_scale) X(set_line_position) X(new_track) X(free_track) \
    X(process_codec_private) X(process_chunk) X(read_memory) X(render_frame) \
    X(set_selective_style_override_enabled) X(set_selective_style_override) X(set_cache_limits) X(flush_events)

static struct {
#define MEMBER(name) __typeof__(ass_##name) *name;
    LIBASS(MEMBER)
#undef MEMBER
} libass;

static int load_libass(void) {
    if (libass.render_frame) return 1;
    void *mpv = dlopen("libmpv.so", RTLD_NOW);
    if (!mpv) return 0;
#define RESOLVE(name) if (!(libass.name = dlsym(mpv, "ass_" #name))) return 0;
    LIBASS(RESOLVE)
#undef RESOLVE
    return 1;
}

typedef struct {
    ASS_Library *library;
    ASS_Renderer *renderer;
    ANativeWindow *window;
    ASS_Image *images;
    // What the last posted buffer drew on, which the next one has to clear.
    ARect drawn;
    ANativeWindow_Buffer buffer;
    int locked;
    char *bundled;
    char *font;
    int width;
    int height;
    int cache_mb;
} Surface;

static void message(int level, const char *format, va_list args, void *data) {
    if (level <= 1) __android_log_vprint(ANDROID_LOG_WARN, "libass", format, args);
}

static inline uint32_t div255(uint32_t x) { return (x + 128 + ((x + 128) >> 8)) >> 8; }

// One image's colour over the picture so far, premultiplied, from pixel `x` of a row on.
static void blend_row(const uint8_t *src, uint32_t *dst, int x, int width,
                      uint32_t r, uint32_t g, uint32_t b, uint32_t opacity) {
    for (; x < width; x++) {
        uint32_t k = div255(src[x] * opacity);
        if (!k) continue;
        uint32_t d = dst[x], keep = 255 - k;
        uint32_t dr = d & 0xff, dg = (d >> 8) & 0xff, db = (d >> 16) & 0xff, da = d >> 24;
        dst[x] = (div255(r * k + dr * keep)) |
                 (div255(g * k + dg * keep) << 8) |
                 (div255(b * k + db * keep) << 16) |
                 ((k + div255(da * keep)) << 24);
    }
}

#if defined(__ARM_NEON)
#include <arm_neon.h>

static inline uint8x8_t div255x8(uint16x8_t x) { return vraddhn_u16(x, vrshrq_n_u16(x, 8)); }

// Eight pixels of mask, the same sums as blend_row.
static inline void blend8(uint8x8_t mask, uint32_t *dst, uint8x8_t vr, uint8x8_t vg, uint8x8_t vb, uint8x8_t vo) {
    uint8x8_t k = div255x8(vmull_u8(mask, vo));
#if defined(__aarch64__)
    if (!vmaxv_u8(k)) return;
#endif
    uint8x8_t keep = vmvn_u8(k);
    uint8x8x4_t d = vld4_u8((uint8_t *) dst);
    d.val[0] = div255x8(vmlal_u8(vmull_u8(vr, k), d.val[0], keep));
    d.val[1] = div255x8(vmlal_u8(vmull_u8(vg, k), d.val[1], keep));
    d.val[2] = div255x8(vmlal_u8(vmull_u8(vb, k), d.val[2], keep));
    d.val[3] = vadd_u8(k, div255x8(vmull_u8(d.val[3], keep)));
    vst4_u8((uint8_t *) dst, d);
}

static void blend(const ASS_Image *image, uint32_t *bits, int stride) {
    uint32_t r = image->color >> 24, g = (image->color >> 16) & 0xff, b = (image->color >> 8) & 0xff;
    uint32_t opacity = 255 - (image->color & 0xff);
    uint8x8_t vr = vdup_n_u8(r), vg = vdup_n_u8(g), vb = vdup_n_u8(b), vo = vdup_n_u8(opacity);
#if defined(__aarch64__)
    uint32x4_t solid = vdupq_n_u32(r | g << 8 | b << 16 | 0xffu << 24);
#endif
    for (int y = 0; y < image->h; y++) {
        const uint8_t *src = image->bitmap + y * image->stride;
        uint32_t *dst = bits + (image->dst_y + y) * stride + image->dst_x;
        int x = 0;
#if defined(__aarch64__)
        // Most of a mask is empty, and full coverage by an opaque colour is just the colour.
        for (; x + 16 <= image->w; x += 16) {
            uint8x16_t mask = vld1q_u8(src + x);
            if (!vmaxvq_u8(mask)) continue;
            if (opacity == 255 && vminvq_u8(mask) == 255) {
                vst1q_u32(dst + x, solid);
                vst1q_u32(dst + x + 4, solid);
                vst1q_u32(dst + x + 8, solid);
                vst1q_u32(dst + x + 12, solid);
                continue;
            }
            blend8(vget_low_u8(mask), dst + x, vr, vg, vb, vo);
            blend8(vget_high_u8(mask), dst + x + 8, vr, vg, vb, vo);
        }
#endif
        for (; x + 8 <= image->w; x += 8) blend8(vld1_u8(src + x), dst + x, vr, vg, vb, vo);
        blend_row(src, dst, x, image->w, r, g, b, opacity);
    }
}
#else
static void blend(const ASS_Image *image, uint32_t *bits, int stride) {
    uint32_t r = image->color >> 24, g = (image->color >> 16) & 0xff, b = (image->color >> 8) & 0xff;
    uint32_t opacity = 255 - (image->color & 0xff);
    for (int y = 0; y < image->h; y++) {
        blend_row(image->bitmap + y * image->stride, bits + (image->dst_y + y) * stride + image->dst_x,
                  0, image->w, r, g, b, opacity);
    }
}
#endif

static int empty(const ARect *rect) { return rect->right <= rect->left || rect->bottom <= rect->top; }

static void include(ARect *rect, int left, int top, int right, int bottom) {
    if (empty(rect)) {
        *rect = (ARect) {left, top, right, bottom};
        return;
    }
    if (left < rect->left) rect->left = left;
    if (top < rect->top) rect->top = top;
    if (right > rect->right) rect->right = right;
    if (bottom > rect->bottom) rect->bottom = bottom;
}

#define METHOD(name) Java_com_nuvio_tv_ui_screens_player_mpvass_AssSurface_##name

JNIEXPORT jboolean JNICALL Java_com_nuvio_tv_ui_screens_player_mpvass_MpvAss_nativeAvailable(JNIEnv *env, jclass clazz) {
    return load_libass() ? JNI_TRUE : JNI_FALSE;
}

static void start_renderer(Surface *surface) {
    surface->renderer = libass.renderer_init(surface->library);
    if (surface->cache_mb) libass.set_cache_limits(surface->renderer, 0, surface->cache_mb);
    libass.set_fonts(surface->renderer, surface->font, "sans-serif", ASS_FONTPROVIDER_AUTODETECT, NULL, 1);
    if (surface->width) libass.set_frame_size(surface->renderer, surface->width, surface->height);
}

// Zero when libmpv can't be loaded. `font` is drawn where a script's fonts are missing, as mpv does.
JNIEXPORT jlong JNICALL METHOD(nativeCreate)(JNIEnv *env, jclass clazz, jstring font) {
    if (!load_libass()) return 0;
    Surface *surface = calloc(1, sizeof(Surface));
    surface->library = libass.library_init();
    libass.set_message_cb(surface->library, message, NULL);
    libass.set_extract_fonts(surface->library, 1);
    const char *path = (*env)->GetStringUTFChars(env, font, NULL);
    surface->bundled = strdup(path);
    surface->font = strdup(path);
    (*env)->ReleaseStringUTFChars(env, font, path);
    start_renderer(surface);
    return (jlong) surface;
}

JNIEXPORT void JNICALL METHOD(nativeAddFont)(JNIEnv *env, jclass clazz, jlong handle, jstring name, jbyteArray data) {
    Surface *surface = (Surface *) handle;
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    libass.add_font(surface->library, chars, (const char *) bytes, (*env)->GetArrayLength(env, data));
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, name, chars);
}

// libass frees a library's fonts safely only once no renderer uses them, so the renderer starts over.
JNIEXPORT void JNICALL METHOD(nativeClearFonts)(JNIEnv *env, jclass clazz, jlong handle) {
    Surface *surface = (Surface *) handle;
    surface->images = NULL;
    libass.renderer_done(surface->renderer);
    libass.clear_fonts(surface->library);
    start_renderer(surface);
}

// libass keeps the font it picked for each name, so fonts added later need a new renderer.
JNIEXPORT void JNICALL METHOD(nativeReloadFonts)(JNIEnv *env, jclass clazz, jlong handle) {
    Surface *surface = (Surface *) handle;
    surface->images = NULL;
    libass.renderer_done(surface->renderer);
    start_renderer(surface);
}

// A track of the file's, from its header; Matroska sends its lines one by one.
JNIEXPORT jlong JNICALL METHOD(nativeNewTrack)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray header) {
    ASS_Track *track = libass.new_track(((Surface *) handle)->library);
    jsize size = (*env)->GetArrayLength(env, header);
    jbyte *bytes = (*env)->GetByteArrayElements(env, header, NULL);
    // Some muxers end the header with a NUL.
    while (size > 0 && bytes[size - 1] == 0) size--;
    libass.process_codec_private(track, (char *) bytes, size);
    (*env)->ReleaseByteArrayElements(env, header, bytes, JNI_ABORT);
    return (jlong) track;
}

// A whole script, as a file added from outside holds it.
JNIEXPORT jlong JNICALL METHOD(nativeReadScript)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray script) {
    jsize size = (*env)->GetArrayLength(env, script);
    char *copy = malloc(size + 1);
    (*env)->GetByteArrayRegion(env, script, 0, size, (jbyte *) copy);
    copy[size] = 0;
    ASS_Track *track = libass.read_memory(((Surface *) handle)->library, copy, size, NULL);
    free(copy);
    return (jlong) track;
}

JNIEXPORT void JNICALL METHOD(nativeAddLine)(
        JNIEnv *env, jclass clazz, jlong track, jbyteArray line, jlong startMs, jlong durationMs) {
    jsize size = (*env)->GetArrayLength(env, line);
    void *bytes = (*env)->GetPrimitiveArrayCritical(env, line, NULL);
    libass.process_chunk((ASS_Track *) track, bytes, size, startMs, durationMs);
    (*env)->ReleasePrimitiveArrayCritical(env, line, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL METHOD(nativeFreeTrack)(JNIEnv *env, jclass clazz, jlong track) {
    libass.free_track((ASS_Track *) track);
}

JNIEXPORT void JNICALL METHOD(nativeFlushTrack)(JNIEnv *env, jclass clazz, jlong track) {
    libass.flush_events((ASS_Track *) track);
}

JNIEXPORT void JNICALL METHOD(nativeSetCacheLimit)(JNIEnv *env, jclass clazz, jlong handle, jint mb) {
    Surface *surface = (Surface *) handle;
    surface->cache_mb = mb;
    libass.set_cache_limits(surface->renderer, 0, mb);
}

JNIEXPORT void JNICALL METHOD(nativeSetWindow)(
        JNIEnv *env, jclass clazz, jlong handle, jobject view, jint width, jint height) {
    Surface *surface = (Surface *) handle;
    if (surface->window) ANativeWindow_release(surface->window);
    surface->window = view ? ANativeWindow_fromSurface(env, view) : NULL;
    surface->drawn = (ARect) {0, 0, 0, 0};
    surface->images = NULL;
    if (surface->window) {
        surface->width = width;
        surface->height = height;
        ANativeWindow_setBuffersGeometry(surface->window, width, height, WINDOW_FORMAT_RGBA_8888);
        libass.set_frame_size(surface->renderer, width, height);
    }
}

// The video's place in the frame: margins are negative where it is cropped.
JNIEXPORT void JNICALL METHOD(nativeSetLayout)(
        JNIEnv *env, jclass clazz, jlong handle, jint videoWidth, jint videoHeight,
        jint top, jint bottom, jint left, jint right, jboolean useMargins,
        jfloat fontScale, jdouble linePosition) {
    ASS_Renderer *renderer = ((Surface *) handle)->renderer;
    libass.set_storage_size(renderer, videoWidth, videoHeight);
    libass.set_margins(renderer, top, bottom, left, right);
    libass.set_use_margins(renderer, useMargins);
    libass.set_font_scale(renderer, fontScale);
    libass.set_line_position(renderer, linePosition);
}

static void use_font(Surface *surface, const char *path) {
    if (!strcmp(path, surface->font)) return;
    free(surface->font);
    surface->font = strdup(path);
    libass.set_fonts(surface->renderer, surface->font, "sans-serif", ASS_FONTPROVIDER_AUTODETECT, NULL, 1);
}

// `override` is AssStyle's NONE, SCALE or FORCE.
JNIEXPORT void JNICALL METHOD(nativeSetStyle)(
        JNIEnv *env, jclass clazz, jlong handle, jint override, jint color, jint outlineColor,
        jint backColor, jdouble outlineSize, jint borderStyle, jboolean bold, jdouble shadowSize, jstring fontFile) {
    Surface *surface = (Surface *) handle;
    ASS_Renderer *renderer = surface->renderer;
    if (fontFile) {
        const char *path = (*env)->GetStringUTFChars(env, fontFile, NULL);
        use_font(surface, path);
        (*env)->ReleaseStringUTFChars(env, fontFile, path);
    } else {
        use_font(surface, surface->bundled);
    }
    int flags = override ? ASS_OVERRIDE_BIT_SELECTIVE_FONT_SCALE : 0;
    if (override == 2) {
        flags |= ASS_OVERRIDE_BIT_FONT_NAME | ASS_OVERRIDE_BIT_FONT_SIZE_FIELDS | ASS_OVERRIDE_BIT_COLORS |
                 ASS_OVERRIDE_BIT_BORDER | ASS_OVERRIDE_BIT_BLUR;
    }
    libass.set_selective_style_override_enabled(renderer, flags);
    // mpv's defaults, in its 720-line units, scaled to the 288 lines the style is set at.
    double scale = 288 / 720.0;
    ASS_Style style = {
        .FontName = "sans-serif",
        .FontSize = 38 * scale,
        .PrimaryColour = color,
        .SecondaryColour = color,
        .OutlineColour = outlineColor,
        .BackColour = backColor,
        .Bold = bold,
        .ScaleX = 1,
        .ScaleY = 1,
        .BorderStyle = borderStyle,
        .Outline = outlineSize * scale,
        .Shadow = shadowSize * scale,
        .Alignment = 2,
        .MarginL = 19 * scale,
        .MarginR = 19 * scale,
        .MarginV = 34 * scale,
    };
    libass.set_selective_style_override(renderer, &style);
}

// Renders the track at `timeMs`; true when the picture changed and needs drawing.
JNIEXPORT jboolean JNICALL METHOD(nativeRender)(
        JNIEnv *env, jclass clazz, jlong handle, jlong track, jlong timeMs, jboolean force) {
    Surface *surface = (Surface *) handle;
    if (!surface->window) return JNI_FALSE;
    int changed = 0;
    surface->images = track ? libass.render_frame(surface->renderer, (ASS_Track *) track, timeMs, &changed) : NULL;
    if (!surface->images && empty(&surface->drawn)) return JNI_FALSE;
    return changed || force;
}

// Blends the rendered images into the next buffer, clearing only what changed.
JNIEXPORT jboolean JNICALL METHOD(nativeDraw)(JNIEnv *env, jclass clazz, jlong handle) {
    Surface *surface = (Surface *) handle;
    ARect next = {0, 0, 0, 0};
    for (ASS_Image *image = surface->images; image; image = image->next) {
        if (image->w > 0 && image->h > 0) {
            include(&next, image->dst_x, image->dst_y, image->dst_x + image->w, image->dst_y + image->h);
        }
    }
    ARect dirty = surface->drawn;
    if (!empty(&next)) include(&dirty, next.left, next.top, next.right, next.bottom);
    if (empty(&dirty)) return JNI_FALSE;
    // The window copies the rest from the buffer before, and may widen the rectangle.
    if (ANativeWindow_lock(surface->window, &surface->buffer, &dirty) != 0) return JNI_FALSE;
    ANativeWindow_Buffer *buffer = &surface->buffer;
    if (dirty.right > buffer->width) dirty.right = buffer->width;
    if (dirty.bottom > buffer->height) dirty.bottom = buffer->height;
    uint32_t *bits = buffer->bits;
    for (int y = dirty.top; y < dirty.bottom; y++) {
        memset(bits + y * buffer->stride + dirty.left, 0, (dirty.right - dirty.left) * 4);
    }
    for (ASS_Image *image = surface->images; image; image = image->next) {
        if (image->w > 0 && image->h > 0 &&
            image->dst_x + image->w <= buffer->width && image->dst_y + image->h <= buffer->height) {
            blend(image, bits, buffer->stride);
        }
    }
    surface->drawn = next;
    surface->locked = 1;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL METHOD(nativePost)(JNIEnv *env, jclass clazz, jlong handle) {
    Surface *surface = (Surface *) handle;
    if (!surface->locked) return;
    surface->locked = 0;
    ANativeWindow_unlockAndPost(surface->window);
}

JNIEXPORT void JNICALL METHOD(nativeRelease)(JNIEnv *env, jclass clazz, jlong handle) {
    Surface *surface = (Surface *) handle;
    if (surface->window) ANativeWindow_release(surface->window);
    libass.renderer_done(surface->renderer);
    libass.library_done(surface->library);
    free(surface->bundled);
    free(surface->font);
    free(surface);
}
