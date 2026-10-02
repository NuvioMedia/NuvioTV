#ifndef __YENC_COMMON
#define __YENC_COMMON

#include "hedley.h"

#if defined(__x86_64__) || \
    defined(__amd64__ ) || \
    defined(__LP64    ) || \
    defined(_M_X64    ) || \
    defined(_M_AMD64  ) || \
    (defined(_WIN64) && !defined(_M_ARM64))
	#define PLATFORM_AMD64 1
#endif
#if defined(PLATFORM_AMD64) || \
    defined(__i386__  ) || \
    defined(__i486__  ) || \
    defined(__i586__  ) || \
    defined(__i686__  ) || \
    defined(_M_I86    ) || \
    defined(_M_IX86   ) || \
    (defined(_WIN32) && !defined(_M_ARM) && !defined(_M_ARM64))
	#define PLATFORM_X86 1
#endif
#if defined(__aarch64__) || \
    defined(__armv7__  ) || \
    defined(__arm__    ) || \
    defined(_M_ARM64   ) || \
    defined(_M_ARM     ) || \
    defined(__ARM_ARCH_6__ ) || \
    defined(__ARM_ARCH_7__ ) || \
    defined(__ARM_ARCH_7A__) || \
    defined(__ARM_ARCH_8A__) || \
    (defined(__ARM_ARCH    ) && __ARM_ARCH >= 6)
	#define PLATFORM_ARM 1
#endif


#include <stdlib.h>
#if defined(_MSC_VER) || defined(__MINGW32__) || defined(__MINGW64__)
	// MSVC doesn't support C11 aligned_alloc: https://stackoverflow.com/a/62963007
	#define ALIGN_ALLOC(buf, len, align) *(void**)&(buf) = _aligned_malloc((len), align)
	#define ALIGN_FREE _aligned_free
#elif defined(__ANDROID__)
	// Android NDK may have broken std::aligned_alloc [https://github.com/android/ndk/issues/1339]
	#include <malloc.h>
	#define ALIGN_ALLOC(buf, len, align) *(void**)&(buf) = memalign(align, (len))
	#define ALIGN_FREE free
#elif defined(_ISOC11_SOURCE)
	// C11 method
	// len needs to be a multiple of alignment, although it sometimes works if it isn't...
	#define ALIGN_ALLOC(buf, len, align) *(void**)&(buf) = aligned_alloc(align, ((len) + (align)-1) & ~((align)-1))
	#define ALIGN_FREE free
#elif defined(__cplusplus) && __cplusplus >= 201700
	// C++17 method
	#include <cstdlib>
	#define ALIGN_ALLOC(buf, len, align) *(void**)&(buf) = std::aligned_alloc(align, ((len) + (align)-1) & ~((align)-1))
	#define ALIGN_FREE free
#else
	#define ALIGN_ALLOC(buf, len, align) if(posix_memalign((void**)&(buf), align, (len))) (buf) = NULL
	#define ALIGN_FREE free
#endif


// MSVC compatibility
#if ((defined(_M_IX86_FP) && _M_IX86_FP == 2) || defined(_M_X64)) && defined(_MSC_VER) && !defined(__clang__)
	#define __SSE2__ 1
	#define __SSSE3__ 1
	#define __SSE4_1__ 1
	#if _MSC_VER >= 1600 && defined(__SSE2__)
		#define __POPCNT__ 1
		#define __LZCNT__ 1
	#endif
	#if !defined(__AVX__) && (_MSC_VER >= 1700 && defined(__SSE2__))
		#define __AVX__ 1
	#endif
	#if !defined(__AVX2__) && (_MSC_VER >= 1800 && defined(__AVX__))
		#define __AVX2__ 1
		#define __BMI2__ 1
	#endif
	/* AVX512 requires VS 15.3 */
	#if !defined(__AVX512F__) && (_MSC_VER >= 1911 && defined(__AVX__))
		#define __AVX512BW__ 1
		#define __AVX512F__ 1
	#endif
	/* AVX512VL not available until VS 15.5 */
	#if defined(__AVX512F__) && _MSC_VER >= 1912
		#define __AVX512VL__ 1
	#endif
	#if defined(__AVX512F__) && _MSC_VER >= 1920
		#define __AVX512VBMI__ 1
		#define __AVX512VBMI2__ 1
	#endif
#endif
#if defined(_M_ARM64)
	#define __aarch64__ 1
	#define __ARM_NEON 1
#endif
#if defined(_M_ARM)
	#define __ARM_NEON 1
#endif
#ifdef _MSC_VER
# ifndef __BYTE_ORDER__
#  define __BYTE_ORDER__ 1234
# endif
# ifndef __ORDER_BIG_ENDIAN__
#  define __ORDER_BIG_ENDIAN__ 4321
# endif
# include <intrin.h>
#endif


// combine two 8-bit ints into a 16-bit one
#if __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
#define UINT16_PACK(a, b) (((a) << 8) | (b))
#define UINT32_PACK(a, b, c, d) (((a) << 24) | ((b) << 16) | ((c) << 8) | (d))
#define UINT32_16_PACK(a, b) (((a) << 16) | (b))
#else
#define UINT16_PACK(a, b) ((a) | ((b) << 8))
#define UINT32_PACK(a, b, c, d) ((a) | ((b) << 8) | ((c) << 16) | ((d) << 24))
#define UINT32_16_PACK(a, b) ((a) | ((b) << 16))
#endif

#ifdef __SSE2__
#include <emmintrin.h>
#define XMM_SIZE 16 /*== (signed int)sizeof(__m128i)*/

#ifdef __SSSE3__
#include <tmmintrin.h>
#endif
#ifdef __POPCNT__
#include <nmmintrin.h>
// POPCNT can never return a negative result, but GCC doesn't seem to realise this, so typecast it to hint it better
#define popcnt32 (unsigned int)_mm_popcnt_u32
#endif

#if defined(__AVX2__) || defined(__AVX512F__)
#include <immintrin.h>
#endif


#if defined(__tune_core2__) || defined(__tune_atom__)
/* on older Intel CPUs, plus first gen Atom, it is faster to store XMM registers in half */
# define STOREU_XMM(dest, xmm) \
  _mm_storel_epi64((__m128i*)(dest), xmm); \
  _mm_storeh_pi(((__m64*)(dest) +1), _mm_castsi128_ps(xmm))
#else
# define STOREU_XMM(dest, xmm) \
  _mm_storeu_si128((__m128i*)(dest), xmm)
#endif

#endif

#if defined(__ARM_NEON) && defined(__has_include)
# if !__has_include(<arm_neon.h>)
#  undef __ARM_NEON
HEDLEY_WARNING("NEON has been disabled due to missing arm_neon.h");
# endif
#endif

#ifdef __ARM_NEON
# include <arm_neon.h>

// ARM provides no standard way to inline define a vector :(
static HEDLEY_ALWAYS_INLINE uint8x8_t vmake_u8(
	uint8_t a, uint8_t b, uint8_t c, uint8_t d, uint8_t e, uint8_t f, uint8_t g, uint8_t h
) {
# if defined(_MSC_VER)
	uint8_t t[] = {a,b,c,d,e,f,g,h};
	return vld1_u8(t);
# else
	return (uint8x8_t){a,b,c,d,e,f,g,h};
# endif
}
static HEDLEY_ALWAYS_INLINE uint8x16_t vmakeq_u8(
	uint8_t a, uint8_t b, uint8_t c, uint8_t d, uint8_t e, uint8_t f, uint8_t g, uint8_t h,
	uint8_t i, uint8_t j, uint8_t k, uint8_t l, uint8_t m, uint8_t n, uint8_t o, uint8_t p
) {
# if defined(_MSC_VER)
	uint8_t t[] = {a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p};
	return vld1q_u8(t);
# else
	return (uint8x16_t){a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p};
# endif
}
static HEDLEY_ALWAYS_INLINE int8x16_t vmakeq_s8(
	int8_t a, int8_t b, int8_t c, int8_t d, int8_t e, int8_t f, int8_t g, int8_t h,
	int8_t i, int8_t j, int8_t k, int8_t l, int8_t m, int8_t n, int8_t o, int8_t p
) {
# if defined(_MSC_VER)
	int8_t t[] = {a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p};
	return vld1q_s8(t);
# else
	return (int8x16_t){a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p};
# endif
}

# ifdef _MSC_VER
#  define _CREATE_TUPLE(type, ...) type{{ __VA_ARGS__ }}
# else
#  define _CREATE_TUPLE(type, ...) (type){{ __VA_ARGS__ }}
# endif
static HEDLEY_ALWAYS_INLINE uint8x16x2_t vcreate2_u8(uint8x16_t a, uint8x16_t b) {
	return _CREATE_TUPLE(uint8x16x2_t, a, b);
}
static HEDLEY_ALWAYS_INLINE int8x16x2_t vcreate2_s8(int8x16_t a, int8x16_t b) {
	return _CREATE_TUPLE(int8x16x2_t, a, b);
}
static HEDLEY_ALWAYS_INLINE uint8x16x3_t vcreate3_u8(uint8x16_t a, uint8x16_t b, uint8x16_t c) {
	return _CREATE_TUPLE(uint8x16x3_t, a, b, c);
}
static HEDLEY_ALWAYS_INLINE uint8x16x4_t vcreate4_u8(uint8x16_t a, uint8x16_t b, uint8x16_t c, uint8x16_t d) {
	return _CREATE_TUPLE(uint8x16x4_t, a, b, c, d);
}
# undef _CREATE_TUPLE
#endif
#ifdef PLATFORM_ARM
namespace RapidYenc {
	bool cpu_supports_neon();
}
#endif

#ifdef _MSC_VER
#define ALIGN_TO(a, v) __declspec(align(a)) v
#else
#define ALIGN_TO(a, v) v __attribute__((aligned(a)))
#endif


#ifdef PLATFORM_X86
enum YEncDecIsaLevel {
	ISA_GENERIC = 0,
	ISA_FEATURE_POPCNT = 0x1,
	ISA_FEATURE_LZCNT = 0x2,
	ISA_LEVEL_SSE2 = 0x100,
	ISA_LEVEL_SSSE3 = 0x200,
	ISA_LEVEL_SSE41 = 0x300,
	ISA_LEVEL_SSE4_POPCNT = 0x301,
	ISA_LEVEL_PCLMUL = 0x340,
	ISA_LEVEL_AVX = 0x381, // same as above, just used as a differentiator for `cpu_supports_isa`
	ISA_LEVEL_AVX2 = 0x403, // also includes BMI1/2 and LZCNT
	ISA_LEVEL_VPCLMUL = 0x440,
	ISA_LEVEL_AVX3 = 0x503, // SKX variant; AVX512VL + AVX512BW
	ISA_LEVEL_VBMI2 = 0x603 // ICL, AVX10
};
#elif defined(PLATFORM_ARM)
enum YEncDecIsaLevel {
	ISA_GENERIC = 0,
	ISA_FEATURE_CRC = 8,
	ISA_FEATURE_PMULL = 0x40,
	ISA_LEVEL_NEON = 0x1000
};
#elif defined(__riscv)
enum YEncDecIsaLevel {
	ISA_GENERIC = 0,
	ISA_FEATURE_ZBC = 16,
	ISA_LEVEL_RVV = 0x10000
};
#else
enum YEncDecIsaLevel {
	ISA_GENERIC = 0
};
#endif
#ifdef PLATFORM_X86
#ifdef _MSC_VER
// native tuning not supported in MSVC
# define ISA_NATIVE ISA_LEVEL_SSE2
#else
# if defined(__AVX512VBMI2__)
#  define _ISA_NATIVE ISA_LEVEL_VBMI2
# elif defined(__AVX512BW__)
#  define _ISA_NATIVE ISA_LEVEL_AVX3
# elif defined(__AVX2__)
#  define _ISA_NATIVE ISA_LEVEL_AVX2
# elif defined(__SSE4_1__)
#  define _ISA_NATIVE ISA_LEVEL_SSE41
# elif defined(__SSSE3__)
#  define _ISA_NATIVE ISA_LEVEL_SSSE3
# else
#  define _ISA_NATIVE ISA_LEVEL_SSE2
# endif
# if defined(__POPCNT__)
#  if defined(__LZCNT__)
#   define ISA_NATIVE (enum YEncDecIsaLevel)(_ISA_NATIVE | ISA_FEATURE_POPCNT | ISA_FEATURE_LZCNT)
#  else
#   define ISA_NATIVE (enum YEncDecIsaLevel)(_ISA_NATIVE | ISA_FEATURE_POPCNT)
#  endif
# else
#  define ISA_NATIVE _ISA_NATIVE
# endif
#endif

namespace RapidYenc {
	int cpu_supports_isa();
	int cpu_supports_crc_isa();
}
#endif // PLATFORM_X86


#ifdef __riscv
namespace RapidYenc {
	bool cpu_supports_rvv();
}
#endif
#if defined(__riscv_vector) && defined(HEDLEY_GCC_VERSION) && !HEDLEY_GCC_VERSION_CHECK(13,0,0)
// GCC added RVV intrinsics in GCC13
# undef __riscv_vector
#elif defined(__riscv_vector) && defined(HEDLEY_GCC_VERSION) && !HEDLEY_GCC_VERSION_CHECK(14,0,0)
// ...however, GCC13 lacks necessary mask<>vector vreinterpret casts, and it crashes on type punning, so I can't be bothered trying to make it work
# undef __riscv_vector
#endif
#ifdef __riscv_vector
# include <riscv_vector.h>
# ifdef __riscv_v_intrinsic
#  define RV(f) __riscv_##f
# else
#  define RV(f) f
# endif
# if defined(__riscv_v_intrinsic) && __riscv_v_intrinsic >= 12000
#  define RV_MASK_CAST(masksz, vecsz, vec) RV(vreinterpret_v_u##vecsz##m1_b##masksz)(vec)
#  define RV_VEC_U8MF4_CAST(vec) RV(vlmul_trunc_v_u8m1_u8mf4)(RV(vreinterpret_v_b4_u8m1)(vec))
# else
#  define RV_MASK_CAST(masksz, vecsz, vec) *(vbool##masksz##_t*)(&(vec))
#  define RV_VEC_U8MF4_CAST(vec) *(vuint8mf4_t*)(&(vec))
# endif
#endif

#include <string.h>
#if !defined(_MSC_VER) || defined(_STDINT) || _MSC_VER >= 1900
# include <stdint.h>
# include <stddef.h>
#else
/* Workaround for older MSVC not supporting stdint.h - just pull it from V8 */
# if defined(NODE_GYP_MODULE_NAME) || defined(V8_DEPRECATION_WARNINGS)
#  include <v8.h>
# else
#  include "stdint.h"
# endif
#endif


// GCC 8/9/10(dev) fails to optimize cases where KNOT should be used, so use intrinsic explicitly; Clang 6+ has no issue, but Clang 6/7 doesn't have the intrinsic; MSVC 2019 also fails and lacks the intrinsic
#if (defined(__GNUC__) && __GNUC__ >= 7) || (defined(_MSC_VER) && _MSC_VER >= 1924)
# define KNOT16 _knot_mask16
# define KNOT32 _knot_mask32
#else
# define KNOT16(x) ((__mmask16)~(x))
# define KNOT32(x) ((__mmask32)~(x))
#endif

// weird thing with Apple's Clang; doesn't seem to always occur, so assume that Clang >= 9 is fine: https://github.com/animetosho/node-yencode/issues/8#issuecomment-583385864
// seems that Clang < 3.6 also uses the old name
#if defined(__clang__) && ((defined(__APPLE__) && __clang_major__ < 9) || __clang_major__ < 3 || (__clang_major__ == 3 && __clang_minor__ < 6))
# define _lzcnt_u32 __lzcnt32
#endif



#ifdef __GNUC__
# if __GNUC__ >= 9
#  define LIKELIHOOD(p, c) (HEDLEY_PREDICT(!!(c), 1, p))
# else
#  define LIKELIHOOD(p, c) (p>0.3 && p<0.7 ? HEDLEY_UNPREDICTABLE(!!(c)) : __builtin_expect(!!(c), (p >= 0.5)))
# endif
#else
# define LIKELIHOOD(p, c) (c)
#endif

#endif /* __YENC_COMMON */
