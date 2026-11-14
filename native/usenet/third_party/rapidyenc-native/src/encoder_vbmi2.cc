#include "common.h"
#include "encoder_common.h"

#if defined(__AVX512VL__) && defined(__AVX512VBMI2__) && defined(__AVX512BW__)
# ifndef YENC_DISABLE_AVX256
#  include "encoder_avx_base.h"

void RapidYenc::encoder_vbmi2_init() {
	_do_encode = &do_encode_simd< do_encode_avx2<ISA_LEVEL_VBMI2> >;
	encoder_avx2_lut<ISA_LEVEL_VBMI2>();
	_encode_isa = ISA_LEVEL_VBMI2;
}
# else
#  include "encoder_sse_base.h"
void RapidYenc::encoder_vbmi2_init() {
	_do_encode = &do_encode_simd< do_encode_sse<ISA_LEVEL_VBMI2> >;
	encoder_sse_lut<ISA_LEVEL_VBMI2>();
	_encode_isa = ISA_LEVEL_VBMI2;
}
# endif
#else
void RapidYenc::encoder_vbmi2_init() {
	encoder_avx2_init();
}
#endif
