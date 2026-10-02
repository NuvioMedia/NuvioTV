#include "common.h"
#include "encoder_common.h"

#ifdef __SSE2__
#include "encoder_sse_base.h"

void RapidYenc::encoder_sse2_init() {
	_do_encode = &do_encode_simd< do_encode_sse<ISA_LEVEL_SSE2> >;
	encoder_sse_lut<ISA_LEVEL_SSE2>();
	_encode_isa = ISA_LEVEL_SSE2;
}
#else
void RapidYenc::encoder_sse2_init() {}
#endif

