#include "ffmpeg_vc1_pool.h"

#include <android/log.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavcodec/mpegvideodec.h>
#include <libavcodec/vc1.h>

int vc1_frame_is_progressive_b(AVCodecContext* main_ctx, const uint8_t* data, int size);
int vc1_sync_worker(AVCodecContext* dst, AVCodecContext* src);
int read_bit(const uint8_t* data, int size, int* bit) {
  int index = *bit;
  if (index < 0 || index / 8 >= size) {
    return 0;
  }
  int value = (data[index / 8] >> (7 - (index & 7))) & 1;
  *bit = index + 1;
  return value;
}

int read_unary(const uint8_t* data, int size, int* bit, int max_ones) {
  int count = 0;
  while (count < max_ones && read_bit(data, size, bit) != 0) {
    count++;
  }
  if (count < max_ones) {
    // The terminating zero is consumed by the loop condition via read_bit.
  }
  return count;
}

void unescape_prefix(const uint8_t* src, int src_size, uint8_t* dst, int dst_size, int* out_size) {
  int o = 0;
  int zeros = 0;
  for (int i = 0; i < src_size && o < dst_size; ++i) {
    if (zeros >= 2 && src[i] == 0x03) {
      zeros = 0;
      continue;
    }
    dst[o++] = src[i];
    zeros = src[i] == 0 ? zeros + 1 : 0;
  }
  *out_size = o;
}

const uint8_t* find_frame_payload(const uint8_t* data, int size, int* payload_size) {
  for (int i = 0; i + 4 < size; ++i) {
    if (data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x01 && data[i + 3] == 0x0D) {
      *payload_size = size - (i + 4);
      return data + i + 4;
    }
  }
  *payload_size = 0;
  return NULL;
}

int vc1_frame_is_progressive_b(AVCodecContext* main_ctx, const uint8_t* data, int size) {
  VC1Context* vc1 = (VC1Context*)main_ctx->priv_data;
  if (!vc1 || !vc1->s.context_initialized || vc1->profile != PROFILE_ADVANCED) {
    return 0;
  }
  int payload_size = 0;
  const uint8_t* payload = find_frame_payload(data, size, &payload_size);
  if (!payload || payload_size <= 0) {
    static int marker_logged = 0;
    if (!marker_logged && size > 0) {
      marker_logged = 1;
      __android_log_print(ANDROID_LOG_INFO, "ffmpeg_jni",
                          "VC-1 no frame marker profile=%d interlace=%d size=%d b0=%02x",
                          vc1->profile, vc1->interlace, size, data[0]);
    }
    return 0;
  }
  uint8_t raw[16];
  int raw_size = 0;
  unescape_prefix(payload, payload_size, raw, (int)sizeof(raw), &raw_size);
  if (raw_size <= 0) {
    return 0;
  }
  int bit = 0;
  // Sequence interlace means the frame starts with FCM. 0 is still progressive.
  if (vc1->interlace) {
    if (read_bit(raw, raw_size, &bit)) {
      int fcm = read_bit(raw, raw_size, &bit) + 1;
      static int ilace_logged = 0;
      if (!ilace_logged) {
        ilace_logged = 1;
        __android_log_print(ANDROID_LOG_INFO, "ffmpeg_jni",
                            "VC-1 interlaced frame fcm=%d, left on the main core", fcm);
      }
      return 0;
    }
  }
  int unary = read_unary(raw, raw_size, &bit, 4);
  // ffmpeg: 1 = B, 3 = BI. Both are non-reference.
  return unary == 1 || unary == 3;
}

typedef struct KeptPointers {
  IntraX8Context x8;
  H264ChromaContext h264chroma;
  VC1DSPContext vc1dsp;
  int* ttblk_base;
  int* ttblk;
  uint8_t* mb_type_base;
  uint8_t* mb_type[3];
  uint8_t* mv_type_mb_plane;
  uint8_t* direct_mb_plane;
  uint8_t* forward_mb_plane;
  uint8_t* acpred_plane;
  uint8_t* over_flags_plane;
  uint8_t* fieldtx_plane;
  uint8_t* blk_mv_type_base;
  uint8_t* blk_mv_type;
  uint8_t* mv_f_base;
  uint8_t* mv_f[2];
  uint8_t* mv_f_next_base;
  uint8_t* mv_f_next[2];
  int16_t (*block)[6][64];
  uint32_t* cbp_base;
  uint32_t* cbp;
  uint8_t* is_intra_base;
  uint8_t* is_intra;
  int16_t (*luma_mv_base)[2];
  int16_t (*luma_mv)[2];
  AVFrame* sprite_output_frame;
  uint8_t* sr_rows[2][2];
} KeptPointers;

void save_pointers(VC1Context* v, KeptPointers* keep) {
  memcpy(&keep->x8, &v->x8, sizeof(keep->x8));
  memcpy(&keep->h264chroma, &v->h264chroma, sizeof(keep->h264chroma));
  memcpy(&keep->vc1dsp, &v->vc1dsp, sizeof(keep->vc1dsp));
  keep->ttblk_base = v->ttblk_base;
  keep->ttblk = v->ttblk;
  keep->mb_type_base = v->mb_type_base;
  memcpy(keep->mb_type, v->mb_type, sizeof(keep->mb_type));
  keep->mv_type_mb_plane = v->mv_type_mb_plane;
  keep->direct_mb_plane = v->direct_mb_plane;
  keep->forward_mb_plane = v->forward_mb_plane;
  keep->acpred_plane = v->acpred_plane;
  keep->over_flags_plane = v->over_flags_plane;
  keep->fieldtx_plane = v->fieldtx_plane;
  keep->blk_mv_type_base = v->blk_mv_type_base;
  keep->blk_mv_type = v->blk_mv_type;
  keep->mv_f_base = v->mv_f_base;
  memcpy(keep->mv_f, v->mv_f, sizeof(keep->mv_f));
  keep->mv_f_next_base = v->mv_f_next_base;
  memcpy(keep->mv_f_next, v->mv_f_next, sizeof(keep->mv_f_next));
  keep->block = v->block;
  keep->cbp_base = v->cbp_base;
  keep->cbp = v->cbp;
  keep->is_intra_base = v->is_intra_base;
  keep->is_intra = v->is_intra;
  keep->luma_mv_base = v->luma_mv_base;
  keep->luma_mv = v->luma_mv;
  keep->sprite_output_frame = v->sprite_output_frame;
  memcpy(keep->sr_rows, v->sr_rows, sizeof(keep->sr_rows));
}

void restore_pointers(VC1Context* v, const KeptPointers* keep) {
  memcpy(&v->x8, &keep->x8, sizeof(v->x8));
  memcpy(&v->h264chroma, &keep->h264chroma, sizeof(v->h264chroma));
  memcpy(&v->vc1dsp, &keep->vc1dsp, sizeof(v->vc1dsp));
  v->ttblk_base = keep->ttblk_base;
  v->ttblk = keep->ttblk;
  v->mb_type_base = keep->mb_type_base;
  memcpy(v->mb_type, keep->mb_type, sizeof(v->mb_type));
  v->mv_type_mb_plane = keep->mv_type_mb_plane;
  v->direct_mb_plane = keep->direct_mb_plane;
  v->forward_mb_plane = keep->forward_mb_plane;
  v->acpred_plane = keep->acpred_plane;
  v->over_flags_plane = keep->over_flags_plane;
  v->fieldtx_plane = keep->fieldtx_plane;
  v->blk_mv_type_base = keep->blk_mv_type_base;
  v->blk_mv_type = keep->blk_mv_type;
  v->mv_f_base = keep->mv_f_base;
  memcpy(v->mv_f, keep->mv_f, sizeof(v->mv_f));
  v->mv_f_next_base = keep->mv_f_next_base;
  memcpy(v->mv_f_next, keep->mv_f_next, sizeof(v->mv_f_next));
  v->block = keep->block;
  v->cbp_base = keep->cbp_base;
  v->cbp = keep->cbp;
  v->is_intra_base = keep->is_intra_base;
  v->is_intra = keep->is_intra;
  v->luma_mv_base = keep->luma_mv_base;
  v->luma_mv = keep->luma_mv;
  v->sprite_output_frame = keep->sprite_output_frame;
  memcpy(v->sr_rows, keep->sr_rows, sizeof(v->sr_rows));
}

void rebase_lookup_tables(VC1Context* dst, const VC1Context* src) {
  if (dst->curr_luty == src->last_luty) {
    dst->curr_luty = dst->last_luty;
  } else if (dst->curr_luty == src->next_luty) {
    dst->curr_luty = dst->next_luty;
  } else if (dst->curr_luty == src->aux_luty) {
    dst->curr_luty = dst->aux_luty;
  }
  if (dst->curr_lutuv == src->last_lutuv) {
    dst->curr_lutuv = dst->last_lutuv;
  } else if (dst->curr_lutuv == src->next_lutuv) {
    dst->curr_lutuv = dst->next_lutuv;
  } else if (dst->curr_lutuv == src->aux_lutuv) {
    dst->curr_lutuv = dst->aux_lutuv;
  }
  if (dst->curr_use_ic == &src->last_use_ic) {
    dst->curr_use_ic = &dst->last_use_ic;
  } else if (dst->curr_use_ic == &src->next_use_ic) {
    dst->curr_use_ic = &dst->next_use_ic;
  } else if (dst->curr_use_ic == &src->aux_use_ic) {
    dst->curr_use_ic = &dst->aux_use_ic;
  }
}

int vc1_sync_worker(AVCodecContext* dst, AVCodecContext* src) {
  VC1Context* dst_vc1 = (VC1Context*)dst->priv_data;
  VC1Context* src_vc1 = (VC1Context*)src->priv_data;
  if (!src_vc1->s.context_initialized) {
    return -1;
  }
  if (!dst_vc1->s.context_initialized) {
    dst->coded_width = src->coded_width;
    dst->coded_height = src->coded_height;
    dst->width = src->width;
    dst->height = src->height;
    if (ff_vc1_decode_init(dst) < 0) {
      return -1;
    }
  }
  KeptPointers keep;
  save_pointers(dst_vc1, &keep);
  int ret = ff_mpeg_update_thread_context(dst, src);
  if (ret < 0) {
    return ret;
  }
  memcpy((char*)dst_vc1 + sizeof(MpegEncContext), (char*)src_vc1 + sizeof(MpegEncContext),
         sizeof(VC1Context) - sizeof(MpegEncContext));
  restore_pointers(dst_vc1, &keep);
  rebase_lookup_tables(dst_vc1, src_vc1);
  return 0;
}
