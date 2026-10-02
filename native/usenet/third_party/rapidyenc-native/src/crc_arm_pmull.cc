#include "crc_common.h"

// exclude broken/missing arm_acle.h
#if defined(__ARM_FEATURE_CRYPTO) && defined(HEDLEY_GCC_VERSION)
# if !defined(__aarch64__) && HEDLEY_GCC_VERSION_CHECK(7,0,0) && !HEDLEY_GCC_VERSION_CHECK(8,1,1)
#  undef __ARM_FEATURE_CRYPTO
# endif
# if defined(__aarch64__) && HEDLEY_GCC_VERSION_CHECK(9,4,0) && !HEDLEY_GCC_VERSION_CHECK(9,5,0)
#  undef __ARM_FEATURE_CRYPTO
# endif
#endif
#if defined(__ARM_FEATURE_CRYPTO) && defined(__has_include)
# if !__has_include(<arm_acle.h>)
#  undef __ARM_FEATURE_CRYPTO
# endif
#endif

// ARM's intrinsics guide seems to suggest that vmull_p64 is available on A32, but neither Clang/GCC seem to support it on AArch32
#if (defined(__ARM_FEATURE_CRYPTO) && defined(__ARM_FEATURE_CRC32) && defined(__aarch64__)) || (defined(_M_ARM64) && !defined(__clang__))

#include <arm_neon.h>
#if defined(_MSC_VER) && !defined(__clang__)
# include <intrin.h>

# ifdef _M_ARM64
// MSVC may detect this pattern: https://devblogs.microsoft.com/cppblog/a-tour-of-4-msvc-backend-improvements/#byteswap-identification
static HEDLEY_ALWAYS_INLINE uint64_t rbit64(uint64_t x) {
	x = _byteswap_uint64(x);
	x = (x & 0xaaaaaaaaaaaaaaaa) >> 1 | (x & 0x5555555555555555) << 1;
	x = (x & 0xcccccccccccccccc) >> 2 | (x & 0x3333333333333333) << 2;
	x = (x & 0xf0f0f0f0f0f0f0f0) >> 4 | (x & 0x0f0f0f0f0f0f0f0f) << 4;
	return x;
}
// ...whilst this seems to work best for 32-bit RBIT
static HEDLEY_ALWAYS_INLINE uint32_t rbit32(uint32_t x) {
	uint64_t r = rbit64(x);
	return r >> 32;
}
# else
#  define rbit32 _arm_rbit
# endif
#else
# include <arm_acle.h>
// __rbit not present before GCC 11.4.0 or 12.2.0; for ARM32, requires GCC 14
# if defined(HEDLEY_GCC_VERSION) && !HEDLEY_GCC_VERSION_CHECK(14,0,0) && (!defined(__aarch64__) || !HEDLEY_GCC_VERSION_CHECK(11,3,0) || (HEDLEY_GCC_VERSION_CHECK(12,0,0) && !HEDLEY_GCC_VERSION_CHECK(12,2,0)))
#  ifdef __aarch64__
static HEDLEY_ALWAYS_INLINE uint64_t rbit64(uint64_t x) {
	uint64_t r;
	__asm__ ("rbit %0,%1\n"
		: "=r"(r) : "r"(x)
		: /* No clobbers */);
	return r;
}
#  endif
static HEDLEY_ALWAYS_INLINE uint32_t rbit32(uint32_t x) {
	uint32_t r;
	__asm__ (
#  ifdef __aarch64__
		"rbit %w0,%w1\n"
#  else
		"rbit %0,%1\n"
#  endif
		: "=r"(r) : "r"(x)
		: /* No clobbers */);
	return r;
}
# else
#  define rbit32 __rbit
#  define rbit64 __rbitll
# endif
#endif


// MSVC doesn't have poly64/poly128 types, so always use uint64 instead

#ifdef __aarch64__
# if defined(__GNUC__) || defined(__clang__)
static HEDLEY_ALWAYS_INLINE uint64x2_t pmull_low(uint64x1_t a, uint64x1_t b) {
	uint64x2_t result;
	__asm__ ("pmull %0.1q,%1.1d,%2.1d"
		: "=w"(result)
		: "w"(a), "w"(b)
		: /* No clobbers */);
	return result;
}
static HEDLEY_ALWAYS_INLINE uint64x2_t pmull_high(uint64x2_t a, uint64x2_t b) {
	uint64x2_t result;
	__asm__ ("pmull2 %0.1q,%1.2d,%2.2d"
		: "=w"(result)
		: "w"(a), "w"(b)
		: /* No clobbers */);
	return result;
}
# elif defined(_MSC_VER) && !defined(__clang__)
#  define pmull_low vmull_p64
#  define pmull_high vmull_high_p64
# else
#  define pmull_low(x, y) vreinterpretq_u64_p128(vmull_p64(vreinterpret_p64_u64(x), vreinterpret_p64_u64(y)))
#  define pmull_high(x, y) vreinterpretq_u64_p128(vmull_high_p64(vreinterpretq_p64_u64(x), vreinterpretq_p64_u64(y)))
# endif
#else
# if defined(_MSC_VER) && !defined(__clang__)
#  define pmull_low vmull_p64
#  define pmull_high(x, y) vmull_p64(vget_high_u64(x), vget_high_u64(y))
# else
#  define pmull_low(x, y) vreinterpretq_u64_p128(vmull_p64(x, y))
#  define pmull_high(x, y) vreinterpretq_u64_p128(vmull_p64(vget_high_p64(vreinterpretq_p64_u64(x)), vget_high_p64(vreinterpretq_p64_u64(y))))
# endif
#endif


static uint32_t crc32_multiply_pmull(uint32_t a, uint32_t b) {
	uint64x1_t prod = vget_low_u64(pmull_low(
		vreinterpret_u64_u32(vset_lane_u32(a, vdup_n_u32(0), 0)),
		vreinterpret_u64_u32(vset_lane_u32(b, vdup_n_u32(0), 0))
	));
	#ifdef __aarch64__
	uint64_t p = vget_lane_u64(prod, 0);
	return __crc32w(0, p+p) ^ (p >> 31);
	#else
	prod = vadd_u64(prod, prod);
	uint32x2_t prod32 = vreinterpret_u32_u64(prod);
	return __crc32w(0, vget_lane_u32(prod32, 0)) ^ vget_lane_u32(prod32, 1);
	#endif
}



static const uint32_t crc_power_rev[32] = { // bit-reversed crc_power
	0x00000002, 0x00000004, 0x00000010, 0x00000100, 0x00010000, 0x04c11db7, 0x490d678d, 0xe8a45605,
	0x75be46b7, 0xe6228b11, 0x567fddeb, 0x88fe2237, 0x0e857e71, 0x7001e426, 0x075de2b2, 0xf12a7f90,
	0xf0b4a1c1, 0x58f46c0c, 0xc3395ade, 0x96837f8c, 0x544037f9, 0x23b7b136, 0xb2e16ba8, 0x725e7bfa,
	0xec709b5d, 0xf77a7274, 0x2845d572, 0x034e2515, 0x79695942, 0x540cb128, 0x0b65d023, 0x3c344723
};


static HEDLEY_ALWAYS_INLINE uint64x1_t crc32_shift_pmull_mulred(uint64x1_t a, uint64x1_t b) {
	uint64x2_t r = pmull_low(a, b);
	uint64x2_t h = pmull_high(r, vdupq_n_u64(0x490d678d));
	return veor_u64(vget_low_u64(r), vget_low_u64(h));
}


static uint32_t crc32_shift_pmull(uint32_t crc1, uint32_t n) {
	crc1 = rbit32(crc1);
	
	uint64x1_t res;
	#ifdef __aarch64__
	uint64_t crc = (uint64_t)crc1 << (n & 31);
	res = vset_lane_u64(crc, vdup_n_u64(0), 0);
	#else
	res = vreinterpret_u64_u32(vset_lane_u32(crc1, vdup_n_u32(0), 0));
	res = vshl_u64(res, vdup_n_u64(n&31));
	#endif
	n &= ~31;
	
	if(n) {
		#define LOAD_NEXT_POWER vreinterpret_u64_u32(vset_lane_u32(crc_power_rev[ctz32(n)], vdup_n_u32(0), 0))
		uint64x1_t res2 = LOAD_NEXT_POWER;
		n &= n-1;
		
		if(n) {
			// first multiply doesn't need reduction
			res2 = vget_low_u64(pmull_low(res2, LOAD_NEXT_POWER));
			n &= n-1;
			
			while(n) {
				res = crc32_shift_pmull_mulred(res, LOAD_NEXT_POWER);
				n &= n-1;
				
				if(n) {
					res2 = crc32_shift_pmull_mulred(res2, LOAD_NEXT_POWER);
					n &= n-1;
				}
			}
		}
		#undef LOAD_NEXT_POWER
		
		// merge two results
		uint64x2_t prod = pmull_low(res, res2);
		// weirdly, vrbitq_u8 is missing in ARM32 MSVC
		prod = vreinterpretq_u64_u8(vrev64q_u8(vrbitq_u8(vreinterpretq_u8_u64(prod))));
		#ifdef __aarch64__
		crc = __crc32d(0, vgetq_lane_u64(prod, 1));
		uint64_t rem = vgetq_lane_u64(prod, 0);
		crc = __crc32w(rem, crc) ^ (rem >> 32);
		#else
		uint32x4_t prod32 = vreinterpretq_u32_u64(prod);
		uint32_t crc = __crc32w(0, vgetq_lane_u32(prod32, 2));
		crc = __crc32w(vgetq_lane_u32(prod32, 3), crc);
		crc = __crc32w(vgetq_lane_u32(prod32, 0), crc) ^ vgetq_lane_u32(prod32, 1);
		#endif
		return crc;
	} else {
		#ifdef __aarch64__
		crc = rbit64(crc);
		crc = __crc32w(0, crc) ^ (crc >> 32);
		return crc;
		#else
		uint32x2_t r = vreinterpret_u32_u64(res);
		return __crc32w(0, rbit32(vget_lane_u32(r, 1))) ^ rbit32(vget_lane_u32(r, 0));
		#endif
	}
}


void RapidYenc::crc_pmull_set_funcs() {
	_crc32_multiply = &crc32_multiply_pmull;
	_crc32_shift = &crc32_shift_pmull;
	_crc32_isa |= ISA_FEATURE_PMULL;
}

#else
void RapidYenc::crc_pmull_set_funcs() {}
#endif /* defined(__ARM_FEATURE_CRYPTO) && defined(__ARM_FEATURE_CRC32) */
