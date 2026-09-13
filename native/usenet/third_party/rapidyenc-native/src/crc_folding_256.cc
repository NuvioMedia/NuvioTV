// 256-bit version of crc_folding

#include "crc_common.h"
 
#if !defined(YENC_DISABLE_AVX256) && ((defined(__VPCLMULQDQ__) && defined(__AVX2__) && defined(__PCLMUL__)) || (defined(_MSC_VER) && _MSC_VER >= 1920 && defined(PLATFORM_X86) && !defined(__clang__)))
#include <inttypes.h>
#include <immintrin.h>


#if defined(__AVX512VL__) && defined(YENC_BUILD_NATIVE) && YENC_BUILD_NATIVE!=0
# define ENABLE_AVX512 1
#endif

static __m256i do_one_fold(__m256i src, __m256i data) {
	const __m256i fold4 = _mm256_set_epi32(
		0x00000001, 0x54442bd4,
		0x00000001, 0xc6e41596,
		0x00000001, 0x54442bd4,
		0x00000001, 0xc6e41596
	);
#ifdef ENABLE_AVX512
	return _mm256_ternarylogic_epi32(
	  _mm256_clmulepi64_epi128(src, fold4, 0x01),
	  _mm256_clmulepi64_epi128(src, fold4, 0x10),
	  data,
	  0x96
	);
#else
	return _mm256_xor_si256(_mm256_xor_si256(
	  data, _mm256_clmulepi64_epi128(src, fold4, 0x01)
	), _mm256_clmulepi64_epi128(src, fold4, 0x10));
#endif
}

ALIGN_TO(32, static const uint8_t  pshufb_rot_table[]) = {
	0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,
	16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31
};
// _mm256_castsi128_si256, but upper is defined to be 0
#if (defined(__clang__) && __clang_major__ >= 5 && (!defined(__APPLE__) || __clang_major__ >= 7)) || (defined(__GNUC__) && __GNUC__ >= 10) || (defined(_MSC_VER) && _MSC_VER >= 1910)
// intrinsic unsupported in GCC 9 and MSVC < 2017
# define zext128_256 _mm256_zextsi128_si256
#else
// technically a cast is incorrect, due to upper 128 bits being undefined, but should usually work fine
// alternative may be `_mm256_set_m128i(_mm_setzero_si128(), v)` but unsupported on GCC < 7, and most compilers generate a VINSERTF128 instruction for it
# ifdef __OPTIMIZE__
#  define zext128_256 _mm256_castsi128_si256
# else
#  define zext128_256(x) _mm256_inserti128_si256(_mm256_setzero_si256(), x, 0)
# endif
#endif

#ifdef ENABLE_AVX512
# define MM256_BLENDV(a, b, m) _mm256_ternarylogic_epi32(a, b, m, 0xd8)
# define MM_2XOR(a, b, c) _mm_ternarylogic_epi32(a, b, c, 0x96)
#else
# define MM256_BLENDV _mm256_blendv_epi8
# define MM_2XOR(a, b, c) _mm_xor_si128(_mm_xor_si128(a, b), c)
#endif

static void partial_fold(const size_t len, __m256i *crc0, __m256i *crc1, __m256i crc_part) {
	__m256i shuf = _mm256_broadcastsi128_si256(_mm_loadu_si128((__m128i*)(pshufb_rot_table + (len&15))));
	__m256i mask = _mm256_cmpgt_epi8(shuf, _mm256_set1_epi8(15));
	
	*crc0 = _mm256_shuffle_epi8(*crc0, shuf);
	*crc1 = _mm256_shuffle_epi8(*crc1, shuf);
	crc_part = _mm256_shuffle_epi8(crc_part, shuf);
	
	__m256i crc_out = _mm256_permute2x128_si256(*crc0, *crc0, 0x08);  // move bottom->top
	__m256i crc01, crc1p;
	if(len >= 16) {
		crc_out = MM256_BLENDV(crc_out, *crc0, mask);
		crc01 = *crc1;
		crc1p = crc_part;
		*crc0 = _mm256_permute2x128_si256(*crc0, *crc1, 0x21);
		*crc1 = _mm256_permute2x128_si256(*crc1, crc_part, 0x21);
		crc_part = zext128_256(_mm256_extracti128_si256(crc_part, 1));
	} else {
		crc_out = _mm256_and_si256(crc_out, mask);
		crc01 = _mm256_permute2x128_si256(*crc0, *crc1, 0x21);
		crc1p = _mm256_permute2x128_si256(*crc1, crc_part, 0x21);
	}
	
	*crc0 = MM256_BLENDV(*crc0, crc01, mask);
	*crc1 = MM256_BLENDV(*crc1, crc1p, mask);
	
	*crc1 = do_one_fold(crc_out, *crc1);
}


ALIGN_TO(16, static const unsigned crc_k[]) = {
	0xccaa009e, 0x00000000, /* rk1 */
	0x751997d0, 0x00000001, /* rk2 */
	0xccaa009e, 0x00000000, /* rk5 */
	0x63cd6124, 0x00000001, /* rk6 */
	0xf7011641, 0x00000000, /* rk7 */
	0xdb710640, 0x00000001  /* rk8 */
};


static uint32_t crc_fold(const unsigned char *src, long len, uint32_t initial) {
	__m128i xmm_t0 = _mm_clmulepi64_si128(
		_mm_cvtsi32_si128(~initial),
		_mm_cvtsi32_si128(0xdfded7ec),
		0
	);
	
	__m256i crc0 = zext128_256(xmm_t0);
	__m256i crc1 = _mm256_setzero_si256();
	
	if (len < 32) {
		if (len == 0)
			return initial;
		__m256i crc_part = _mm256_setzero_si256();
		memcpy(&crc_part, src, len);
		partial_fold(len, &crc0, &crc1, crc_part);
	} else {
		uintptr_t algn_diff = (0 - (uintptr_t)src) & 0x1F;
		if (algn_diff) {
			partial_fold(algn_diff, &crc0, &crc1, _mm256_loadu_si256((__m256i *)src));
			src += algn_diff;
			len -= algn_diff;
		}
		
		while (len >= 64) {
			crc0 = do_one_fold(crc0, _mm256_load_si256((__m256i*)src));
			crc1 = do_one_fold(crc1, _mm256_load_si256((__m256i*)src + 1));
			src += 64;
			len -= 64;
		}
		
		if (len >= 32) {
			__m256i old = crc1;
			crc1 = do_one_fold(crc0, _mm256_load_si256((__m256i*)src));
			crc0 = old;
			
			len -= 32;
			src += 32;
		}
		
		if(len != 0) {
			partial_fold(len, &crc0, &crc1, _mm256_load_si256((__m256i *)src));
		}
	}
	
	const __m128i xmm_mask = _mm_set_epi32(-1,-1,-1,0);
	__m128i x_tmp0, x_tmp1, x_tmp2, crc_fold;
	
	__m128i xmm_crc0 = _mm256_castsi256_si128(crc0);
	__m128i xmm_crc1 = _mm256_extracti128_si256(crc0, 1);
	__m128i xmm_crc2 = _mm256_castsi256_si128(crc1);
	__m128i xmm_crc3 = _mm256_extracti128_si256(crc1, 1);

	/*
	 * k1
	 */
	crc_fold = _mm_load_si128((__m128i *)crc_k);

	x_tmp0 = _mm_clmulepi64_si128(xmm_crc0, crc_fold, 0x10);
	xmm_crc0 = _mm_clmulepi64_si128(xmm_crc0, crc_fold, 0x01);
	xmm_crc1 = MM_2XOR(xmm_crc1, x_tmp0, xmm_crc0);

	x_tmp1 = _mm_clmulepi64_si128(xmm_crc1, crc_fold, 0x10);
	xmm_crc1 = _mm_clmulepi64_si128(xmm_crc1, crc_fold, 0x01);
	xmm_crc2 = MM_2XOR(xmm_crc2, x_tmp1, xmm_crc1);

	x_tmp2 = _mm_clmulepi64_si128(xmm_crc2, crc_fold, 0x10);
	xmm_crc2 = _mm_clmulepi64_si128(xmm_crc2, crc_fold, 0x01);
	xmm_crc3 = MM_2XOR(xmm_crc3, x_tmp2, xmm_crc2);

	/*
	 * k5
	 */
	crc_fold = _mm_load_si128((__m128i *)crc_k + 1);

	xmm_crc0 = xmm_crc3;
	xmm_crc3 = _mm_clmulepi64_si128(xmm_crc3, crc_fold, 0);
	xmm_crc0 = _mm_srli_si128(xmm_crc0, 8);
	xmm_crc3 = _mm_xor_si128(xmm_crc3, xmm_crc0);

	xmm_crc0 = xmm_crc3;
	xmm_crc3 = _mm_slli_si128(xmm_crc3, 4);
	xmm_crc3 = _mm_clmulepi64_si128(xmm_crc3, crc_fold, 0x10);
#ifdef ENABLE_AVX512
	//xmm_crc3 = _mm_maskz_xor_epi32(14, xmm_crc3, xmm_crc0);
	xmm_crc3 = _mm_ternarylogic_epi32(xmm_crc3, xmm_crc0, xmm_mask, 0x28);
#else
	xmm_crc0 = _mm_and_si128(xmm_crc0, xmm_mask);
	xmm_crc3 = _mm_xor_si128(xmm_crc3, xmm_crc0);
#endif

	/*
	 * k7
	 */
	xmm_crc1 = xmm_crc3;
	crc_fold = _mm_load_si128((__m128i *)crc_k + 2);

	xmm_crc3 = _mm_clmulepi64_si128(xmm_crc3, crc_fold, 0);
	xmm_crc3 = _mm_clmulepi64_si128(xmm_crc3, crc_fold, 0x10);
#ifdef ENABLE_AVX512
	xmm_crc3 = _mm_ternarylogic_epi32(xmm_crc3, xmm_crc1, xmm_crc1, 0xC3); // NOT(xmm_crc3 ^ xmm_crc1)
#else
	xmm_crc1 = _mm_xor_si128(xmm_crc1, xmm_mask);
	xmm_crc3 = _mm_xor_si128(xmm_crc3, xmm_crc1);
#endif
	return _mm_extract_epi32(xmm_crc3, 2);
}

static uint32_t do_crc32_incremental_clmul(const void* data, size_t length, uint32_t init) {
	return crc_fold((const unsigned char*)data, (long)length, init);
}

void RapidYenc::crc_clmul256_set_funcs() {
	crc_clmul_set_funcs(); // set multiply/shift function
	_do_crc32_incremental = &do_crc32_incremental_clmul;
	_crc32_isa = ISA_LEVEL_VPCLMUL;
}
#else
void RapidYenc::crc_clmul256_set_funcs() {
	crc_clmul_set_funcs();
}
#endif

