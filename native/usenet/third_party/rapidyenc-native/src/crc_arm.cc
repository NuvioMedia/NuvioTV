#include "crc_common.h"

#if defined(PLATFORM_ARM) && defined(_MSC_VER) && defined(__clang__) && !defined(__ARM_FEATURE_CRC32)
// I don't think GYP provides a nice way to detect whether MSVC or clang-cl is being used, but it doesn't use clang-cl by default, so a warning here is probably sufficient
HEDLEY_WARNING("CRC32 acceleration is not been enabled under ARM clang-cl by default; add `-march=armv8-a+crc` to additional compiler arguments to enable");
#endif

// disable CRC on GCC versions with broken arm_acle.h
#if defined(__ARM_FEATURE_CRC32) && defined(HEDLEY_GCC_VERSION)
# if !defined(__aarch64__) && HEDLEY_GCC_VERSION_CHECK(7,0,0) && !HEDLEY_GCC_VERSION_CHECK(8,1,1)
#  undef __ARM_FEATURE_CRC32
HEDLEY_WARNING("CRC32 acceleration has been disabled due to broken arm_acle.h shipped in GCC 7.0 - 8.1 [https://gcc.gnu.org/bugzilla/show_bug.cgi?id=81497]. If you need this feature, please use a different compiler or version of GCC");
# endif
# if defined(__aarch64__) && HEDLEY_GCC_VERSION_CHECK(9,4,0) && !HEDLEY_GCC_VERSION_CHECK(9,5,0)
#  undef __ARM_FEATURE_CRC32
HEDLEY_WARNING("CRC32 acceleration has been disabled due to broken arm_acle.h shipped in GCC 9.4 [https://gcc.gnu.org/bugzilla/show_bug.cgi?id=100985]. If you need this feature, please use a different compiler or version of GCC");
# endif
#endif
#if defined(__ARM_FEATURE_CRC32) && defined(__has_include)
# if !__has_include(<arm_acle.h>)
#  undef __ARM_FEATURE_CRC32
HEDLEY_WARNING("CRC32 acceleration has been disabled due to missing arm_acle.h");
# endif
#endif

#if defined(__ARM_FEATURE_CRC32) || (defined(_M_ARM64) && !defined(__clang__)) // MSVC doesn't support CRC for ARM32

/* ARMv8 accelerated CRC */
#if defined(_MSC_VER) && !defined(__clang__)
#include <intrin.h>
#else
#include <arm_acle.h>
#endif


#if __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
# ifdef __GNUC__
#  define _LE16 __builtin_bswap16
#  define _LE32 __builtin_bswap32
#  define _LE64 __builtin_bswap64
# else
// currently not supported
#  error No endian swap intrinsic defined
# endif
#else
# define _LE16(x) (x)
# define _LE32(x) (x)
# define _LE64(x) (x)
#endif

#ifdef __aarch64__
# define WORD_T uint64_t
# define WORDSIZE_LOG 3  // sizeof(WORD_T) == 1<<WORDSIZE_LOG
# define CRC_WORD(crc, data) __crc32d(crc, _LE64(data))
#else
# define WORD_T uint32_t
# define WORDSIZE_LOG 2  // sizeof(WORD_T) == 1<<WORDSIZE_LOG
# define CRC_WORD(crc, data) __crc32w(crc, _LE32(data))
#endif



#ifdef __aarch64__
static uint32_t crc32_multiply_arm(uint32_t a, uint32_t b) {
	// perform PMULL
	uint64_t res = 0;
	uint64_t a64 = (uint64_t)a << 32;
	int64_t b64 = (int64_t)b << 32;
	for(int i=0; i<32; i++) {
		res ^= a64 & (b64 >> 63);
		b64 += b64;
		a64 >>= 1;
	}
	// reduction via CRC
	res = __crc32w(0, res) ^ (res >> 32);
	return res;
}
#endif
// regular multiply is probably better for AArch32


// exploit CPU pipelining during CRC computation; unfortunately I haven't been able to measure any benefit
// - Neoverse N1: no noticeable difference
// - Cortex A53: actually runs a bit slower
//#define ENABLE_PIPELINE_OPT 1

#ifdef ENABLE_PIPELINE_OPT
#ifndef __aarch64__
# define crc32_multiply_arm RapidYenc::crc32_multiply_generic
#endif
#endif



// inspired/stolen off https://github.com/jocover/crc32_armv8/blob/master/crc32_armv8.c
static uint32_t arm_crc_calc(uint32_t crc, const unsigned char *src, long len) {
	
	// initial alignment
	if (len >= 16) { // 16 is an arbitrary number; it just needs to be >=8
		if ((uintptr_t)src & sizeof(uint8_t)) {
			crc = __crc32b(crc, *src);
			src++;
			len--;
		}
		if ((uintptr_t)src & sizeof(uint16_t)) {
			crc = __crc32h(crc, _LE16(*((uint16_t *)src)));
			src += sizeof(uint16_t);
			len -= sizeof(uint16_t);
		}
#ifdef __aarch64__
		if ((uintptr_t)src & sizeof(uint32_t)) {
			crc = __crc32w(crc, _LE32(*((uint32_t *)src)));
			src += sizeof(uint32_t);
			len -= sizeof(uint32_t);
		}
#endif
	}
	
	const WORD_T* srcW = (const WORD_T*)src;
	
#ifdef ENABLE_PIPELINE_OPT
	// uses ideas from https://github.com/komrad36/crc#option-13-golden
	// (this is a slightly less efficient, but much simpler implementation of the idea)
	const unsigned SPLIT_WORDS_LOG = 10;  // make sure it's at least 2
	const unsigned SPLIT_WORDS = 1<<SPLIT_WORDS_LOG;
	const unsigned blockCoeff = RapidYenc::crc_power[SPLIT_WORDS_LOG + WORDSIZE_LOG + 3];
	while(len >= (long)(sizeof(WORD_T)*SPLIT_WORDS*2)) {
		// compute 2x CRCs concurrently to leverage piplining
		uint32_t crc2 = 0;
		for(unsigned i=0; i<SPLIT_WORDS; i+=4) {
			crc = CRC_WORD(crc, *srcW);
			crc2 = CRC_WORD(crc2, *(srcW + SPLIT_WORDS));
			srcW++;
			crc = CRC_WORD(crc, *srcW);
			crc2 = CRC_WORD(crc2, *(srcW + SPLIT_WORDS));
			srcW++;
			crc = CRC_WORD(crc, *srcW);
			crc2 = CRC_WORD(crc2, *(srcW + SPLIT_WORDS));
			srcW++;
			crc = CRC_WORD(crc, *srcW);
			crc2 = CRC_WORD(crc2, *(srcW + SPLIT_WORDS));
			srcW++;
		}
		// merge the CRCs
		crc = crc32_multiply_arm(crc, blockCoeff) ^ crc2;
		srcW += SPLIT_WORDS;
		len -= sizeof(WORD_T)*SPLIT_WORDS*2;
	}
#endif
	
	while ((len -= sizeof(WORD_T)*8) >= 0) {
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
	}
	if (len & sizeof(WORD_T)*4) {
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
	}
	if (len & sizeof(WORD_T)*2) {
		crc = CRC_WORD(crc, *(srcW++));
		crc = CRC_WORD(crc, *(srcW++));
	}
	if (len & sizeof(WORD_T)) {
		crc = CRC_WORD(crc, *(srcW++));
	}
	src = (const unsigned char*)srcW;
	
#ifdef __aarch64__
	if (len & sizeof(uint32_t)) {
		crc = __crc32w(crc, _LE32(*((uint32_t *)src)));
		src += sizeof(uint32_t);
	}
#endif
	if (len & sizeof(uint16_t)) {
		crc = __crc32h(crc, _LE16(*((uint16_t *)src)));
		src += sizeof(uint16_t);
	}
	if (len & sizeof(uint8_t))
		crc = __crc32b(crc, *src);
	
	return crc;
}

static uint32_t do_crc32_incremental_arm(const void* data, size_t length, uint32_t init) {
	return ~arm_crc_calc(~init, (const unsigned char*)data, (long)length);
}


#if defined(__aarch64__) && (defined(__GNUC__) || defined(_MSC_VER))
static uint32_t crc32_shift_arm(uint32_t crc1, uint32_t n) {
	uint32_t result = crc1;
	uint64_t prod = result;
	prod <<= 32 - (n&31);
	result = __crc32w(0, prod) ^ (prod >> 32);
	n &= ~31;
	
	while(n) {
		result = crc32_multiply_arm(result, RapidYenc::crc_power[ctz32(n)]);
		n &= n-1;
	}
	return result;
}
#endif


void RapidYenc::crc_arm_set_funcs() {
	_do_crc32_incremental = &do_crc32_incremental_arm;
#ifdef __aarch64__
	_crc32_multiply = &crc32_multiply_arm;
# if defined(__GNUC__) || defined(_MSC_VER)
	_crc32_shift = &crc32_shift_arm;
# endif
#endif
	_crc32_isa = ISA_FEATURE_CRC;
}
#else
void RapidYenc::crc_arm_set_funcs() {}
#endif
