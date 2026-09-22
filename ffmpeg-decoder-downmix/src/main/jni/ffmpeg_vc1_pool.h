#ifndef FFMPEG_VC1_POOL_H
#define FFMPEG_VC1_POOL_H

#include <stdint.h>

struct AVCodec;
struct AVCodecContext;
struct AVFrame;

#ifdef __cplusplus
extern "C" {
#endif

int vc1_frame_is_progressive_b(struct AVCodecContext* main_ctx, const uint8_t* data, int size);
int vc1_sync_worker(struct AVCodecContext* dst, struct AVCodecContext* src);

#ifdef __cplusplus
}
#endif

typedef struct Vc1Pool Vc1Pool;

// One worker per extra core. B-frames are not references, so they decode beside
// the I/P chain without changing the picture.
Vc1Pool* vc1_pool_create(const struct AVCodec* codec, const uint8_t* extradata,
                         int extradata_size);
void vc1_pool_destroy(Vc1Pool* pool);
void vc1_pool_flush(Vc1Pool* pool);

// Returns 1 when the packet is a B-frame now running on another core.
int vc1_pool_submit_b(Vc1Pool* pool, struct AVCodecContext* main_ctx, const uint8_t* data,
                      int size, int64_t pts);

void vc1_pool_push_frame(Vc1Pool* pool, struct AVFrame* frame, int64_t pts);

// Returns 1 and transfers ownership of *out_frame when the oldest frame is finished.
int vc1_pool_pop_frame(Vc1Pool* pool, struct AVFrame** out_frame, int64_t* out_pts);

#endif
