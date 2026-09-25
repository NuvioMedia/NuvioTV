#pragma once

#include <android/native_window.h>

// colorspace matches VideoDecoderOutputBuffer: 1 = BT.601, 2 = BT.709, 3 = BT.2020.
struct VideoGlFrame {
  const uint8_t* y;
  const uint8_t* u;
  const uint8_t* v;
  int y_stride;
  int u_stride;
  int v_stride;
  int width;
  int height;
  int colorspace;
};

struct VideoGlPresenter;

VideoGlPresenter* video_gl_create();
void video_gl_destroy(VideoGlPresenter* presenter);
void video_gl_detach(VideoGlPresenter* presenter);
int video_gl_has_surface(const VideoGlPresenter* presenter);
// Returns 0 on success. The window is not owned.
int video_gl_draw(VideoGlPresenter* presenter, ANativeWindow* window, const VideoGlFrame* frame);
