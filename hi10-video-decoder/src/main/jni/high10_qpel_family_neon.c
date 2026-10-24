/* Temporary coherent ARMv7 High10 qpel experiment.
 * H.264 six-tap/rounding definitions: FFmpeg 7.0.2 h264qpel_template.c.
 * 1-D unsigned saturation strategy: FFmpeg AArch64 h264qpel_neon.S
 * (0f745b74ecd4). No AArch64 assembly translated; ARMv7 intrinsics here.
 * LGPL-2.1-or-later, consistent with those FFmpeg sources.
 * Only 10-bit 8/16-wide put/avg entries. No hot-path CPU query.
 */
#include <arm_neon.h>
#include <stdint.h>
#include "libavcodec/h264qpel.h"
#include "libavutil/cpu.h"

#define INLINE static inline __attribute__((always_inline))
#define HELPER static __attribute__((noinline))
#define PAD 10230

INLINE uint16x8_t finish1(uint16x8_t positive, uint16x8_t negative) {
    return vminq_u16(vrshrq_n_u16(vqsubq_u16(positive, negative), 5), vdupq_n_u16(1023));
}
INLINE int16x8_t horizontal_raw(const uint16_t *s) {
    uint16x8_t a = vld1q_u16(s - 2), tail = vld1q_u16(s + 3);
    uint16x8_t b = vextq_u16(tail, tail, 3);
    uint16x8_t p1 = vextq_u16(a,b,1), p2 = vextq_u16(a,b,2), p3 = vextq_u16(a,b,3);
    uint16x8_t p4 = vextq_u16(a,b,4), p5 = vextq_u16(a,b,5);
    uint16x8_t positive = vmlaq_n_u16(vaddq_u16(a,p5),vaddq_u16(p2,p3),20);
    uint16x8_t negative = vmulq_n_u16(vaddq_u16(p1,p4),5);
    /* Wrapping arithmetic intentionally preserves unrounded signed result.
     * Final biased range [-20460,32736] fits int16 exactly. */
    return vreinterpretq_s16_u16(vsubq_u16(vsubq_u16(positive,negative),vdupq_n_u16(PAD)));
}
INLINE uint16x8_t clip_raw1(int16x8_t raw) {
    uint16x8_t positive = vaddq_u16(vreinterpretq_u16_s16(raw),vdupq_n_u16(PAD));
    positive = vandq_u16(positive,vcgeq_s16(raw,vdupq_n_s16(-PAD)));
    return vminq_u16(vrshrq_n_u16(positive,5),vdupq_n_u16(1023));
}
HELPER void horizontal(uint16_t *out, const uint16_t *src, int stride, int n) {
    for (int y=0;y<n;y++) for(int x=0;x<n;x+=8)
        vst1q_u16(out+y*n+x,clip_raw1(horizontal_raw(src+y*stride+x)));
}
INLINE uint16x8_t filter_vertical1(uint16x8_t a,uint16x8_t b,uint16x8_t c,uint16x8_t d,uint16x8_t e,uint16x8_t f) {
    uint16x8_t positive=vmlaq_n_u16(vaddq_u16(a,f),vaddq_u16(c,d),20);
    uint16x8_t negative=vmulq_n_u16(vaddq_u16(b,e),5);
    return finish1(positive,negative);
}
HELPER void vertical(uint16_t *out, const uint16_t *src, int stride, int n) {
    for(int x=0;x<n;x+=8) {
        uint16x8_t a=vld1q_u16(src-2*stride+x),b=vld1q_u16(src-stride+x);
        uint16x8_t c=vld1q_u16(src+x),d=vld1q_u16(src+stride+x);
        uint16x8_t e=vld1q_u16(src+2*stride+x),f=vld1q_u16(src+3*stride+x);
        for(int y=0;y<n;y++) {
            vst1q_u16(out+y*n+x,filter_vertical1(a,b,c,d,e,f));
            if(y+1<n) { a=b;b=c;c=d;d=e;e=f;f=vld1q_u16(src+(y+4)*stride+x); }
        }
    }
}
INLINE uint16x4_t finish2(int16x4_t a,int16x4_t b,int16x4_t c,int16x4_t d,int16x4_t e,int16x4_t f) {
    int32x4_t total=vmlaq_n_s32(vaddl_s16(a,f),vaddl_s16(c,d),20);
    total=vmlsq_n_s32(total,vaddl_s16(b,e),5);
    total=vaddq_s32(total,vdupq_n_s32(PAD*32+512));
    total=vshrq_n_s32(total,10);
    total=vminq_s32(vmaxq_s32(total,vdupq_n_s32(0)),vdupq_n_s32(1023));
    return vqmovun_s32(total);
}
HELPER void horizontal_intermediate(int16_t *raw,const uint16_t *src,int stride,int n) {
    for(int y=0;y<n+5;y++) for(int x=0;x<n;x+=8)
        vst1q_s16(raw+y*n+x,horizontal_raw(src+(y-2)*stride+x));
}
HELPER void two_axis(uint16_t *out,const int16_t *raw,int n) {
    for(int x=0;x<n;x+=8) {
        int16x8_t a=vld1q_s16(raw+x),b=vld1q_s16(raw+n+x),c=vld1q_s16(raw+2*n+x);
        int16x8_t d=vld1q_s16(raw+3*n+x),e=vld1q_s16(raw+4*n+x),f=vld1q_s16(raw+5*n+x);
        for(int y=0;y<n;y++) {
            uint16x4_t lo=finish2(vget_low_s16(a),vget_low_s16(b),vget_low_s16(c),vget_low_s16(d),vget_low_s16(e),vget_low_s16(f));
            uint16x4_t hi=finish2(vget_high_s16(a),vget_high_s16(b),vget_high_s16(c),vget_high_s16(d),vget_high_s16(e),vget_high_s16(f));
            vst1q_u16(out+y*n+x,vcombine_u16(lo,hi));
            if(y+1<n) { a=b;b=c;c=d;d=e;e=f;f=vld1q_s16(raw+(y+6)*n+x); }
        }
    }
}

INLINE void predict(uint8_t *dst_bytes,const uint8_t *src_bytes,ptrdiff_t bytes,int n,int fx,int fy,int avg) {
    int stride=bytes/2;
    uint16_t *dst=(uint16_t *)dst_bytes;
    const uint16_t *src=(const uint16_t *)src_bytes;
    uint16_t h[16*16],v[16*16],hv[16*16];
    int16_t raw[16*21];
    const uint16_t *p1=NULL,*p2=NULL;int st1=n,st2=n;
    int raw_h=0;
    if(!fx&&!fy) { p1=src;st1=stride; }
    else if(!fy) {
        horizontal(h,src,stride,n);p1=h;
        if(fx!=2) { p2=src+(fx==3);st2=stride; }
    } else if(!fx) {
        vertical(v,src,stride,n);p1=v;
        if(fy!=2) { p2=src+(fy==3)*stride;st2=stride; }
    } else if((fx&1)&&(fy&1)) {
        horizontal(h,src+(fy==3)*stride,stride,n);
        vertical(v,src+(fx==3),stride,n);p1=h;p2=v;
    } else {
        horizontal_intermediate(raw,src,stride,n);two_axis(hv,raw,n);p1=hv;
        if(fx==2&&fy!=2) { raw_h=1; }
        else if(fy==2&&fx!=2) { vertical(v,src+(fx==3),stride,n);p2=v; }
    }
    for(int y=0;y<n;y++) for(int x=0;x<n;x+=8) {
        uint16x8_t value=vld1q_u16(p1+y*st1+x);
        if(raw_h) value=vrhaddq_u16(value,clip_raw1(vld1q_s16(raw+(y+2+(fy==3))*n+x)));
        else if(p2) value=vrhaddq_u16(value,vld1q_u16(p2+y*st2+x));
        if(avg) value=vrhaddq_u16(value,vld1q_u16(dst+y*stride+x));
        vst1q_u16(dst+y*stride+x,value);
    }
}
#define ENTRY(N,X,Y,A) static void q##N##_##X##Y##_##A(uint8_t*d,const uint8_t*s,ptrdiff_t t){predict(d,s,t,N,X,Y,A);}
#define ROW(N,Y,A) ENTRY(N,0,Y,A) ENTRY(N,1,Y,A) ENTRY(N,2,Y,A) ENTRY(N,3,Y,A)
#define SIZE(N,A) ROW(N,0,A) ROW(N,1,A) ROW(N,2,A) ROW(N,3,A)
SIZE(16,0) SIZE(16,1) SIZE(8,0) SIZE(8,1)
#define ASSIGN(N,I,X,Y) c->put_h264_qpel_pixels_tab[I][X+Y*4]=q##N##_##X##Y##_0; c->avg_h264_qpel_pixels_tab[I][X+Y*4]=q##N##_##X##Y##_1;
#define SETROW(N,I,Y) ASSIGN(N,I,0,Y) ASSIGN(N,I,1,Y) ASSIGN(N,I,2,Y) ASSIGN(N,I,3,Y)
int nuvio_high10_qpel_init(H264QpelContext *c,int bit_depth,int cpu_flags) {
    if(bit_depth!=10 || !(cpu_flags&AV_CPU_FLAG_NEON)) return 0;
    SETROW(16,0,0) SETROW(16,0,1) SETROW(16,0,2) SETROW(16,0,3)
    SETROW(8,1,0) SETROW(8,1,1) SETROW(8,1,2) SETROW(8,1,3)
    return 1;
}

/* Temporary integration: original object renamed, machine code unchanged. */
extern void nuvio_baseline_h264qpel_init(H264QpelContext *, int);
void ff_h264qpel_init(H264QpelContext *c, int depth) {
    nuvio_baseline_h264qpel_init(c, depth);
    nuvio_high10_qpel_init(c, depth, av_get_cpu_flags());
}
