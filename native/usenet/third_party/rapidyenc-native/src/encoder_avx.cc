#include "common.h"
#include "encoder_common.h"

#if defined(__AVX__) && defined(__POPCNT__)
#include "encoder_sse_base.h"

void RapidYenc::encoder_avx_init() {
	_do_encode = &do_encode_simd< do_encode_sse<ISA_LEVEL_SSE4_POPCNT> >;
	encoder_sse_lut<ISA_LEVEL_SSE4_POPCNT>();
	_encode_isa = ISA_LEVEL_AVX;
}
#else
void RapidYenc::encoder_avx_init() {
	encoder_ssse3_init();
}
#endif

