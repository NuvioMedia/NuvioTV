#include "common.h"
# include "decoder_common.h"

#if defined(__AVX512VL__) && defined(__AVX512VBMI2__) && defined(__AVX512BW__)
# ifndef YENC_DISABLE_AVX256
#  include "decoder_avx2_base.h"
void RapidYenc::decoder_set_vbmi2_funcs() {
	_do_decode = &do_decode_simd<false, false, sizeof(__m256i)*2, do_decode_avx2<false, false, ISA_LEVEL_VBMI2> >;
	_do_decode_raw = &do_decode_simd<true, false, sizeof(__m256i)*2, do_decode_avx2<true, false, ISA_LEVEL_VBMI2> >;
	_do_decode_end_raw = &do_decode_simd<true, true, sizeof(__m256i)*2, do_decode_avx2<true, true, ISA_LEVEL_VBMI2> >;
	_decode_isa = ISA_LEVEL_VBMI2;
}
# else
#  include "decoder_sse_base.h"
void RapidYenc::decoder_set_vbmi2_funcs() {
	_do_decode = &do_decode_simd<false, false, sizeof(__m128i)*2, do_decode_sse<false, false, ISA_LEVEL_VBMI2> >;
	_do_decode_raw = &do_decode_simd<true, false, sizeof(__m128i)*2, do_decode_sse<true, false, ISA_LEVEL_VBMI2> >;
	_do_decode_end_raw = &do_decode_simd<true, true, sizeof(__m128i)*2, do_decode_sse<true, true, ISA_LEVEL_VBMI2> >;
	_decode_isa = ISA_LEVEL_VBMI2;
}
# endif
#else
void RapidYenc::decoder_set_vbmi2_funcs() {
	decoder_set_avx2_funcs();
}
#endif
