#include "common.h"

#include "decoder_common.h"
#ifdef __SSE2__
#include "decoder_sse_base.h"

void RapidYenc::decoder_sse_init(RapidYenc::SSELookups* HEDLEY_RESTRICT& lookups) {
	ALIGN_ALLOC(lookups, sizeof(SSELookups), 16);
	for(int i=0; i<256; i++) {
		lookups->BitsSetTable256inv[i] = 8 - (
			(i & 1) + ((i>>1) & 1) + ((i>>2) & 1) + ((i>>3) & 1) + ((i>>4) & 1) + ((i>>5) & 1) + ((i>>6) & 1) + ((i>>7) & 1)
		);
		
		#define _X(n, k) ((((n) & (1<<k)) ? 192ULL : 0ULL) << (k*8))
		lookups->eqAdd[i] = _X(i, 0) | _X(i, 1) | _X(i, 2) | _X(i, 3) | _X(i, 4) | _X(i, 5) | _X(i, 6) | _X(i, 7);
		#undef _X
	}
	for(int i=0; i<32; i++) {
		for(int j=0; j<16; j++) {
			if(i >= 16) // only used for LZCNT
				lookups->unshufMask[i*16 + j] = ((31-i)>j ? -1 : 0);
			else // only used for BSR
				lookups->unshufMask[i*16 + j] = (i>j ? -1 : 0);
		}
	}
}

void RapidYenc::decoder_set_sse2_funcs() {
	decoder_sse_init(lookups);
	decoder_init_lut(lookups->compact);
	_do_decode = &do_decode_simd<false, false, sizeof(__m128i)*2, do_decode_sse<false, false, ISA_LEVEL_SSE2> >;
	_do_decode_raw = &do_decode_simd<true, false, sizeof(__m128i)*2, do_decode_sse<true, false, ISA_LEVEL_SSE2> >;
	_do_decode_end_raw = &do_decode_simd<true, true, sizeof(__m128i)*2, do_decode_sse<true, true, ISA_LEVEL_SSE2> >;
	_decode_isa = ISA_LEVEL_SSE2;
}
#else
void RapidYenc::decoder_set_sse2_funcs() {}
#endif
