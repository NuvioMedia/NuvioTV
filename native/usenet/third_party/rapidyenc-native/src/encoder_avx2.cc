#include "common.h"
#include "encoder_common.h"

#if defined(__AVX2__) && !defined(YENC_DISABLE_AVX256)
#include "encoder_avx_base.h"

void RapidYenc::encoder_avx2_init() {
	_do_encode = &do_encode_simd< do_encode_avx2<ISA_LEVEL_AVX2> >;
	encoder_avx2_lut<ISA_LEVEL_AVX2>();
	_encode_isa = ISA_LEVEL_AVX2;
}
#else
void RapidYenc::encoder_avx2_init() {
	encoder_avx_init();
}
#endif

