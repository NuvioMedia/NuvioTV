#include "common.h"
#include "encoder_common.h"

#ifdef __ARM_NEON
#include "encoder.h"

// Clang wrongly assumes alignment on vst1q_u8_x2, and ARMv7 GCC doesn't support the function, so effectively, it can only be used in ARMv8 compilers
#if defined(__aarch64__) && (defined(__clang__) || HEDLEY_GCC_VERSION_CHECK(8,5,0))
# define vst1q_u8_x2_unaligned vst1q_u8_x2
#else
static HEDLEY_ALWAYS_INLINE void vst1q_u8_x2_unaligned(uint8_t* p, uint8x16x2_t data) {
	vst1q_u8(p, data.val[0]);
	vst1q_u8(p+16, data.val[1]);
}
#endif


// ARM's CLZ instruction at native bit-width
#ifdef __aarch64__
static HEDLEY_ALWAYS_INLINE int clz_n(uint64_t v) {
# ifdef _MSC_VER
	long r;
	// does this work?
	if(_BitScanReverse64((unsigned long*)&r, v))
		r ^= 63;
	else
		r = 64;
	return r;
# else
#  if defined(__clang__) || HEDLEY_GCC_VERSION_CHECK(11,0,0)
	// this pattern is only detected on GCC >= 11 (Clang 9 seems to as well, unsure about earlier versions)
	// - note: return type must be 'int'; GCC fails to optimise this if type is 'long'
	// GCC <= 10 doesn't optimize around the '0 = undefined behaviour', so not needed there
	if(v == 0) return 64;
#  endif
	return __builtin_clzll(v);
# endif
}
#else
static HEDLEY_ALWAYS_INLINE int clz_n(uint32_t v) {
# ifdef __GNUC__
#  if defined(__clang__) || HEDLEY_GCC_VERSION_CHECK(7,0,0)
	// as with AArch64 version above, only insert this check if compiler can optimise it away
	if(v == 0) return 32;
#  endif
	return __builtin_clz(v);
# elif defined(_MSC_VER)
	return _arm_clz(v);
# else
	return __clz(v); // ARM compiler?
# endif
}
#endif

static uint8x16_t ALIGN_TO(16, shufLUT[256]);
static uint16_t expandLUT[256];

static HEDLEY_ALWAYS_INLINE void encode_eol_handle_pre(const uint8_t* HEDLEY_RESTRICT es, long& i, uint8_t*& p, long& col, long lineSizeOffset) {
	uint8x16_t oDataA = vld1q_u8(es + i);
	uint8x16_t oDataB = vld1q_u8(es + i + sizeof(uint8x16_t));
	uint8x16_t dataA = oDataA;
	uint8x16_t dataB = oDataB;
#ifdef __aarch64__
	uint8x16_t cmpA = vreinterpretq_u8_s8(vqtbx2q_s8(
		vdupq_n_s8('='-42),
		vcreate2_s8(vmakeq_s8('\0'-42,-128,-128,'\0'-42,'\t'-42,'\n'-42,'\r'-42,'\t'-42,'\n'-42,'\r'-42,-128,-128,'\0'-42,-128,-128,-128), vmakeq_s8(' '-42,'\n'-42,'\r'-42,' '-42,-128,-128,-128,-128,-128,-128,'.'-42,-128,-128,-128,'='-42,-128)),
		vreinterpretq_u8_s8(vhaddq_s8(vreinterpretq_s8_u8(dataA), vmakeq_s8(42,48,66,66, 66,66,66,66, 66,66,66,66, 66,66,66,66)))
	));
	cmpA = vceqq_u8(cmpA, dataA);
	
	dataB = vaddq_u8(oDataB, vdupq_n_u8(42));
	uint8x16_t cmpB = vqtbx1q_u8(
		vceqq_u8(oDataB, vdupq_n_u8('='-42)),
		//         \0                    \n      \r
		vmakeq_u8(255,0,0,0,0,0,0,0,0,0,255,0,0,255,0,0),
		dataB
	);
	dataA = vaddq_u8(dataA, vbslq_u8(cmpA, vdupq_n_u8(64+42), vdupq_n_u8(42)));
	dataB = vorrq_u8(dataB, vandq_u8(cmpB, vdupq_n_u8(64)));
#else
	uint8x16_t cmpA = vorrq_u8(
		vorrq_u8(
			vceqq_u8(oDataA, vdupq_n_u8(-42)),
			vceqq_u8(oDataA, vdupq_n_u8('='-42))
		),
		vorrq_u8(
			vceqq_u8(oDataA, vdupq_n_u8('\r'-42)),
			vceqq_u8(oDataA, vdupq_n_u8('\n'-42))
		)
	);
	uint8x16_t cmpB = vorrq_u8(
		vorrq_u8(
			vceqq_u8(oDataB, vdupq_n_u8(-42)),
			vceqq_u8(oDataB, vdupq_n_u8('='-42))
		),
		vorrq_u8(
			vceqq_u8(oDataB, vdupq_n_u8('\r'-42)),
			vceqq_u8(oDataB, vdupq_n_u8('\n'-42))
		)
	);
	
	// dup low 2 bytes & compare
	uint8x8_t firstTwoChars = vreinterpret_u8_u16(vdup_lane_u16(vreinterpret_u16_u8(vget_low_u8(oDataA)), 0));
	uint8x8_t cmpNl = vceq_u8(firstTwoChars, vmake_u8(
		' '+214,' '+214,'\t'+214,'\t'+214,'\r'+214,'.'-42,'='-42,'='-42
	));
	// use padd to merge comparisons
	uint16x4_t cmpNl2 = vreinterpret_u16_u8(cmpNl);
	cmpNl2 = vpadd_u16(cmpNl2, vdup_n_u16(0));
	cmpNl2 = vpadd_u16(cmpNl2, vdup_n_u16(0));
	cmpA = vcombine_u8(
		vorr_u8(vget_low_u8(cmpA), vreinterpret_u8_u16(cmpNl2)),
		vget_high_u8(cmpA)
	);
	dataA = vsubq_u8(dataA, vbslq_u8(cmpA, vdupq_n_u8(-64-42), vdupq_n_u8(-42)));
	dataB = vsubq_u8(dataB, vbslq_u8(cmpB, vdupq_n_u8(-64-42), vdupq_n_u8(-42)));
#endif
	
	
	uint8x16_t cmpAMasked = vandq_u8(cmpA, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128));
	uint8x16_t cmpBMasked = vandq_u8(cmpB, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128));
#ifdef __aarch64__
	uint8x16_t cmpMerge = vpaddq_u8(cmpAMasked, cmpBMasked);
	cmpMerge = vpaddq_u8(cmpMerge, cmpMerge);
	uint64_t mask = vgetq_lane_u64(vreinterpretq_u64_u8(cmpMerge), 0);
	
	// write out first char + newline
	uint32_t firstChar = vgetq_lane_u8(dataA, 0);
	if(LIKELIHOOD(0.0234, mask & 1)) {
		firstChar <<= 8;
		firstChar |= 0x0a0d003d;
		memcpy(p, &firstChar, sizeof(firstChar));
		p += 4;
		mask ^= 1;
		cmpMerge = vbicq_u8(cmpMerge, vmakeq_u8(1,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0));
	} else {
		firstChar |= 0x0a0d00;
		memcpy(p, &firstChar, sizeof(firstChar));
		p += 3;
	}
	
	if(LIKELIHOOD(0.09, (mask & (mask-1)) != 0)) {
		mask |= mask >> 8;
		uint8x8_t cmpPacked = vpadd_u8(vget_low_u8(cmpMerge), vget_low_u8(cmpMerge));
		uint8_t m1 = (mask & 0xff);
		uint8_t m2 = ((mask >> 16) & 0xff);
		uint8_t m3 = ((mask >> 32) & 0xff);
		uint8_t m4 = ((mask >> 48) & 0xff);
#else
	// no vpaddq_u8 in ARMv7, so need extra 64-bit VPADD
	uint8x8_t cmpPacked = vpadd_u8(
		vpadd_u8(
			vget_low_u8(cmpAMasked), vget_high_u8(cmpAMasked)
		),
		vpadd_u8(
			vget_low_u8(cmpBMasked), vget_high_u8(cmpBMasked)
		)
	);
	cmpPacked = vpadd_u8(cmpPacked, cmpPacked);
	uint32_t mask = vget_lane_u32(vreinterpret_u32_u8(cmpPacked), 0);
	
	// write out first char + newline
	uint32_t firstChar = vgetq_lane_u8(dataA, 0);
	if(LIKELIHOOD(0.0234, mask & 1)) {
		firstChar <<= 8;
		firstChar |= 0x0a0d003d;
		memcpy(p, &firstChar, sizeof(firstChar));
		p += 4;
		mask ^= 1;
		cmpPacked = vbic_u8(cmpPacked, vmake_u8(1,0,0,0, 0,0,0,0));
	} else {
		firstChar |= 0x0a0d00;
		memcpy(p, &firstChar, sizeof(firstChar));
		p += 3;
	}
	
	if(LIKELIHOOD(0.09, (mask & (mask-1)) != 0)) {
		uint8_t m1 = (mask & 0xff);
		uint8_t m2 = ((mask >> 8) & 0xff);
		uint8_t m3 = ((mask >> 16) & 0xff);
		uint8_t m4 = ((mask >> 24) & 0xff);
#endif
		
		// perform lookup for shuffle mask
		uint8x16_t shuf1 = vld1q_u8((uint8_t*)(shufLUT + m1));
		uint8x16_t shuf2 = vld1q_u8((uint8_t*)(shufLUT + m2));
		uint8x16_t shuf3 = vld1q_u8((uint8_t*)(shufLUT + m3));
		uint8x16_t shuf4 = vld1q_u8((uint8_t*)(shufLUT + m4));
#ifdef __aarch64__
		uint8x16_t data1A = vqtbx1q_u8(shuf1, dataA, shuf1);
		uint8x16_t data2A = vqtbx1q_u8(shuf2, vextq_u8(dataA, dataA, 8), shuf2);
		uint8x16_t data1B = vqtbx1q_u8(shuf3, dataB, shuf3);
		uint8x16_t data2B = vqtbx1q_u8(shuf4, vextq_u8(dataB, dataB, 8), shuf4);
#else
		uint8x8_t shuf1l = vget_low_u8(shuf1);
		uint8x8_t shuf1h = vget_high_u8(shuf1);
		uint8x8_t shuf2l = vget_low_u8(shuf2);
		uint8x8_t shuf2h = vget_high_u8(shuf2);
		uint8x8_t shuf3l = vget_low_u8(shuf3);
		uint8x8_t shuf3h = vget_high_u8(shuf3);
		uint8x8_t shuf4l = vget_low_u8(shuf4);
		uint8x8_t shuf4h = vget_high_u8(shuf4);
		uint8x16_t data1A = vcombine_u8(vtbx1_u8(shuf1l, vget_low_u8(dataA), shuf1l),
		                                vtbx1_u8(shuf1h, vget_low_u8(dataA), shuf1h));
		uint8x16_t data2A = vcombine_u8(vtbx1_u8(shuf2l, vget_high_u8(dataA), shuf2l),
		                                vtbx1_u8(shuf2h, vget_high_u8(dataA), shuf2h));
		uint8x16_t data1B = vcombine_u8(vtbx1_u8(shuf3l, vget_low_u8(dataB), shuf3l),
		                                vtbx1_u8(shuf3h, vget_low_u8(dataB), shuf3h));
		uint8x16_t data2B = vcombine_u8(vtbx1_u8(shuf4l, vget_high_u8(dataB), shuf4l),
		                                vtbx1_u8(shuf4h, vget_high_u8(dataB), shuf4h));
#endif
		data1A = vextq_u8(data1A, data1A, 1); // shift out processed byte (last char of line)
		
		uint32_t counts = vget_lane_u32(vreinterpret_u32_u8(vcnt_u8(cmpPacked)), 0);
		counts += 0x08080807;
		
		unsigned char shuf1Len = counts & 0xff;
		unsigned char shuf2Len = (counts>>8) & 0xff;
		unsigned char shuf3Len = (counts>>16) & 0xff;
		unsigned char shuf4Len = (counts>>24) & 0xff;
		uint32_t shufTotalLen = counts * 0x1010101;
		shufTotalLen >>= 24;
		
		vst1q_u8(p, data1A);
		p += shuf1Len;
		vst1q_u8(p, data2A);
		p += shuf2Len;
		vst1q_u8(p, data1B);
		p += shuf3Len;
		vst1q_u8(p, data2B);
		p += shuf4Len;
		col = shufTotalLen+1 + lineSizeOffset-32;
	} else {
		// shuffle stuff up
		long bitIndex = clz_n(mask);
		uint8x16_t vClz = vdupq_n_u8(bitIndex & ~(sizeof(mask)*8));
#ifdef __aarch64__
		uint8x16_t blendA = vcgtq_u8(vmakeq_u8(63,62,61,60,51,50,49,48,47,46,45,44,35,34,33,32), vClz);
		uint8x16_t blendB = vcgtq_u8(vmakeq_u8(31,30,29,28,19,18,17,16,15,14,13,12, 3, 2, 1, 0), vClz);
#else
		uint8x16_t blendA = vcgtq_u8(vmakeq_u8(31,30,29,28,27,26,25,24,23,22,21,20,19,18,17,16), vClz);
		uint8x16_t blendB = vcgtq_u8(vmakeq_u8(15,14,13,12,11,10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0), vClz);
#endif
		uint8x16_t dataAShifted = vbslq_u8(cmpA, vdupq_n_u8('='), dataA);
		uint8x16_t dataBShifted = vbslq_u8(cmpB, vdupq_n_u8('='), dataB);
		dataAShifted = vextq_u8(dataAShifted, dataBShifted, 1);
		dataBShifted = vextq_u8(dataBShifted, dataBShifted, 1);
		dataA = vbslq_u8(blendA, dataAShifted, dataA);
		dataB = vbslq_u8(blendB, dataBShifted, dataB);
		
		vst1q_u8_x2_unaligned(p, vcreate2_u8(dataA, dataB));
		p += sizeof(uint8x16_t)*2 - 1;
		p += (mask != 0);
		col = lineSizeOffset + (mask != 0);
	}
	
	i += sizeof(uint8x16_t)*2;
	// TODO: check col >= 0 if we want to support short lines
}


namespace RapidYenc {

HEDLEY_ALWAYS_INLINE void do_encode_neon(int line_size, int* colOffset, const uint8_t* HEDLEY_RESTRICT srcEnd, uint8_t* HEDLEY_RESTRICT& dest, size_t& len) {
	// offset position to enable simpler loop condition checking
	const int INPUT_OFFSET = sizeof(uint8x16_t)*4 -1; // extra chars for EOL handling, -1 to change <= to <
	if(len <= INPUT_OFFSET || line_size < (int)sizeof(uint8x16_t)*4) return;
	
	uint8_t *p = dest; // destination pointer
	long i = -(long)len; // input position
	long lineSizeOffset = -line_size +32; // line size plus vector length
	long col = *colOffset - line_size +1;
	
	i += INPUT_OFFSET;
	const uint8_t* es = srcEnd - INPUT_OFFSET;
	
	if (HEDLEY_LIKELY(col == -line_size+1)) {
		uint8_t c = es[i++];
		if (LIKELIHOOD(0.0273, escapedLUT[c] != 0)) {
			memcpy(p, escapedLUT + c, 2);
			p += 2;
			col += 2;
		} else {
			*(p++) = c + 42;
			col += 1;
		}
	}
	if(HEDLEY_UNLIKELY(col >= 0)) {
		if(col == 0)
			encode_eol_handle_pre(es, i, p, col, lineSizeOffset);
		else {
			uint8_t c = es[i++];
			if (LIKELIHOOD(0.0273, escapedLUT[c]!=0)) {
				uint32_t v = UINT32_16_PACK(UINT16_PACK('\r', '\n'), (uint32_t)escapedLUT[c]);
				memcpy(p, &v, sizeof(v));
				p += 4;
				col = 2-line_size + 1;
			} else {
				uint32_t v = UINT32_PACK('\r', '\n', (uint32_t)(c+42), 0);
				memcpy(p, &v, sizeof(v));
				p += 3;
				col = 2-line_size;
			}
		}
	}
	while(i < 0) {
		// for unaligned loads, separate loads seem to be faster than vld1q_u8_x2 on Cortex A53; unsure if this applies elsewhere
		uint8x16_t dataA = vld1q_u8(es + i);
		uint8x16_t dataB = vld1q_u8(es + i + sizeof(uint8x16_t));
		i += sizeof(uint8x16_t)*2;
		// search for special chars
#ifdef __aarch64__
		uint8x16_t cmpEqA = vceqq_u8(dataA, vdupq_n_u8('='-42));
		uint8x16_t cmpEqB = vceqq_u8(dataB, vdupq_n_u8('='-42));
		dataA = vaddq_u8(dataA, vdupq_n_u8(42));
		dataB = vaddq_u8(dataB, vdupq_n_u8(42));
		uint8x16_t cmpA = vqtbx1q_u8(
			cmpEqA,
			//         \0                    \n      \r
			vmakeq_u8(255,0,0,0,0,0,0,0,0,0,255,0,0,255,0,0),
			dataA
		);
		uint8x16_t cmpB = vqtbx1q_u8(
			cmpEqB,
			//         \0                    \n      \r
			vmakeq_u8(255,0,0,0,0,0,0,0,0,0,255,0,0,255,0,0),
			dataB
		);
		
		dataA = vorrq_u8(dataA, vandq_u8(cmpA, vdupq_n_u8(64)));
		dataB = vorrq_u8(dataB, vandq_u8(cmpB, vdupq_n_u8(64)));
#else
		// the ARMv8 strategy may be worth it here with 2x vtbx2's, but both GCC-9 and Clang-9 generate poor assembly for it, so it performs worse than the following
		uint8x16_t cmpA = vorrq_u8(
			vorrq_u8(
				vceqq_u8(dataA, vdupq_n_u8(-42)),
				vceqq_u8(dataA, vdupq_n_u8('='-42))
			),
			vorrq_u8(
				vceqq_u8(dataA, vdupq_n_u8('\r'-42)),
				vceqq_u8(dataA, vdupq_n_u8('\n'-42))
			)
		);
		uint8x16_t cmpB = vorrq_u8(
			vorrq_u8(
				vceqq_u8(dataB, vdupq_n_u8(-42)),
				vceqq_u8(dataB, vdupq_n_u8('='-42))
			),
			vorrq_u8(
				vceqq_u8(dataB, vdupq_n_u8('\r'-42)),
				vceqq_u8(dataB, vdupq_n_u8('\n'-42))
			)
		);
		
		dataA = vsubq_u8(dataA, vbslq_u8(cmpA, vdupq_n_u8(-64-42), vdupq_n_u8(-42)));
		dataB = vsubq_u8(dataB, vbslq_u8(cmpB, vdupq_n_u8(-64-42), vdupq_n_u8(-42)));
#endif
		
		
		long bitIndex; // prevent compiler whining
		uint8x16_t cmpAMasked = vandq_u8(cmpA, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128));
		uint8x16_t cmpBMasked = vandq_u8(cmpB, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128));
#ifdef __aarch64__
		uint8x16_t cmpMerge = vpaddq_u8(cmpAMasked, cmpBMasked);
		cmpMerge = vpaddq_u8(cmpMerge, cmpMerge);
		uint64_t mask = vgetq_lane_u64(vreinterpretq_u64_u8(cmpMerge), 0);
		if(LIKELIHOOD(0.09, (mask & (mask-1)) != 0)) {
			mask |= mask >> 8;
			uint8x8_t cmpPacked = vpadd_u8(vget_low_u8(cmpMerge), vget_low_u8(cmpMerge));
			uint8_t m1 = (mask & 0xff);
			uint8_t m2 = ((mask >> 16) & 0xff);
			uint8_t m3 = ((mask >> 32) & 0xff);
			uint8_t m4 = ((mask >> 48) & 0xff);
#else
		// no vpaddq_u8 in ARMv7, so need extra 64-bit VPADD
		uint8x8_t cmpPacked = vpadd_u8(
			vpadd_u8(
				vget_low_u8(cmpAMasked), vget_high_u8(cmpAMasked)
			),
			vpadd_u8(
				vget_low_u8(cmpBMasked), vget_high_u8(cmpBMasked)
			)
		);
		cmpPacked = vpadd_u8(cmpPacked, cmpPacked);
		uint32_t mask = vget_lane_u32(vreinterpret_u32_u8(cmpPacked), 0);
		if(LIKELIHOOD(0.09, (mask & (mask-1)) != 0)) {
			uint8_t m1 = (mask & 0xff);
			uint8_t m2 = ((mask >> 8) & 0xff);
			uint8_t m3 = ((mask >> 16) & 0xff);
			uint8_t m4 = ((mask >> 24) & 0xff);
#endif
			
			// perform lookup for shuffle mask
			uint8x16_t shuf1 = vld1q_u8((uint8_t*)(shufLUT + m1));
			uint8x16_t shuf2 = vld1q_u8((uint8_t*)(shufLUT + m2));
			uint8x16_t shuf3 = vld1q_u8((uint8_t*)(shufLUT + m3));
			uint8x16_t shuf4 = vld1q_u8((uint8_t*)(shufLUT + m4));
			
			// expand halves
#ifdef __aarch64__
			uint8x16_t data1A = vqtbx1q_u8(shuf1, dataA, shuf1);
			uint8x16_t data2A = vqtbx1q_u8(shuf2, vextq_u8(dataA, dataA, 8), shuf2);
			uint8x16_t data1B = vqtbx1q_u8(shuf3, dataB, shuf3);
			uint8x16_t data2B = vqtbx1q_u8(shuf4, vextq_u8(dataB, dataB, 8), shuf4);
#else
			uint8x8_t shuf1l = vget_low_u8(shuf1);
			uint8x8_t shuf1h = vget_high_u8(shuf1);
			uint8x8_t shuf2l = vget_low_u8(shuf2);
			uint8x8_t shuf2h = vget_high_u8(shuf2);
			uint8x8_t shuf3l = vget_low_u8(shuf3);
			uint8x8_t shuf3h = vget_high_u8(shuf3);
			uint8x8_t shuf4l = vget_low_u8(shuf4);
			uint8x8_t shuf4h = vget_high_u8(shuf4);
			uint8x16_t data1A = vcombine_u8(vtbx1_u8(shuf1l, vget_low_u8(dataA), shuf1l),
			                                vtbx1_u8(shuf1h, vget_low_u8(dataA), shuf1h));
			uint8x16_t data2A = vcombine_u8(vtbx1_u8(shuf2l, vget_high_u8(dataA), shuf2l),
			                                vtbx1_u8(shuf2h, vget_high_u8(dataA), shuf2h));
			uint8x16_t data1B = vcombine_u8(vtbx1_u8(shuf3l, vget_low_u8(dataB), shuf3l),
			                                vtbx1_u8(shuf3h, vget_low_u8(dataB), shuf3h));
			uint8x16_t data2B = vcombine_u8(vtbx1_u8(shuf4l, vget_high_u8(dataB), shuf4l),
			                                vtbx1_u8(shuf4h, vget_high_u8(dataB), shuf4h));
#endif
			
			// store out
			uint32_t counts = vget_lane_u32(vreinterpret_u32_u8(vcnt_u8(cmpPacked)), 0);
			counts += 0x08080808;
			
			unsigned char shuf1Len = counts & 0xff;
			unsigned char shuf2Len = (counts>>8) & 0xff;
			unsigned char shuf3Len = (counts>>16) & 0xff;
			unsigned char shuf4Len = (counts>>24) & 0xff;
			uint32_t shufTotalLen = counts * 0x1010101;
			shufTotalLen >>= 24;
			
			vst1q_u8(p, data1A);
			p += shuf1Len;
			vst1q_u8(p, data2A);
			p += shuf2Len;
			vst1q_u8(p, data1B);
			p += shuf3Len;
			vst1q_u8(p, data2B);
			p += shuf4Len;
			col += shufTotalLen;
			
			if(LIKELIHOOD(0.3, col >= 0)) {
				// we overflowed - find correct position to revert back to
				long revert = col;
				long len2ndHalf = shuf3Len+shuf4Len;
				long shiftAmt = len2ndHalf - col -1;
				uint32_t eqMaskHalf;
				if(HEDLEY_UNLIKELY(shiftAmt < 0)) {
					eqMaskHalf = (expandLUT[m2] << shuf1Len) | expandLUT[m1];
					eqMaskHalf >>= shufTotalLen - col -1;
					i += len2ndHalf - 16;
				} else {
					eqMaskHalf = (expandLUT[m4] << shuf3Len) | expandLUT[m3];
					eqMaskHalf >>= shiftAmt;
				}
				revert += eqMaskHalf & 1;
				
				// count bits in eqMask
				uint8x8_t vCnt = vcnt_u8(vreinterpret_u8_u32(vmov_n_u32(eqMaskHalf)));
				uint32_t cnt = vget_lane_u32(vreinterpret_u32_u8(vCnt), 0);
				cnt *= 0x1010101;
				i += cnt >> 24;
				
				p -= revert;
				i -= revert;
				goto _encode_eol_handle_pre;
			}
		} else {
			{
				bitIndex = clz_n(mask);
				uint8x16_t vClz = vdupq_n_u8(bitIndex & ~(sizeof(mask)*8));
#ifdef __aarch64__
				uint8x16_t blendA = vcgeq_u8(vmakeq_u8(63,62,61,60,51,50,49,48,47,46,45,44,35,34,33,32), vClz);
				uint8x16_t blendB = vcgeq_u8(vmakeq_u8(31,30,29,28,19,18,17,16,15,14,13,12, 3, 2, 1, 0), vClz);
#else
				uint8x16_t blendA = vcgeq_u8(vmakeq_u8(31,30,29,28,27,26,25,24,23,22,21,20,19,18,17,16), vClz);
				uint8x16_t blendB = vcgeq_u8(vmakeq_u8(15,14,13,12,11,10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0), vClz);
#endif
				uint8x16_t dataAShifted = vextq_u8(dataA, dataA, 15);
				uint8x16_t dataBShifted = vextq_u8(dataA, dataB, 15);
				dataA = vbslq_u8(cmpA, vdupq_n_u8('='), dataA);
				uint8x16_t outDataB = vbslq_u8(cmpB, vdupq_n_u8('='), dataB);
				dataA = vbslq_u8(blendA, dataA, dataAShifted);
				outDataB = vbslq_u8(blendB, outDataB, dataBShifted);
				
				vst1q_u8_x2_unaligned(p, vcreate2_u8(dataA, outDataB));
				p += sizeof(uint8x16_t)*2;
				// write last byte
				*p = vgetq_lane_u8(dataB, 15);
				p += (mask != 0);
				col += (mask != 0) + sizeof(uint8x16_t)*2;
			}
			
			if(HEDLEY_UNLIKELY(col >= 0)) {
#ifdef __aarch64__
				// fixup bitIndex
				bitIndex -= ((bitIndex+4)>>4)<<3;
#endif
				bitIndex = bitIndex +1;
				if(HEDLEY_UNLIKELY(col == bitIndex)) {
					// this is an escape character, so line will need to overflow
					p--;
				} else {
					i += (col > bitIndex);
				}
				p -= col;
				i -= col;
				
				_encode_eol_handle_pre:
				encode_eol_handle_pre(es, i, p, col, lineSizeOffset);
			}
		}
	}
	
	*colOffset = col + line_size -1;
	dest = p;
	len = -(i - INPUT_OFFSET);
}
} // namespace

void RapidYenc::encoder_neon_init() {
	_do_encode = &do_encode_simd<do_encode_neon>;
	_encode_isa = ISA_LEVEL_NEON;
	// generate shuf LUT
	for(int i=0; i<256; i++) {
		int k = i;
		uint16_t expand = 0;
		uint8_t* res = (uint8_t*)(shufLUT + i);
		int p = 0;
		for(int j=0; j<8; j++) {
			if(k & 1) {
				res[j+p] = '=';
				expand |= 1<<(j+p);
				p++;
			}
			res[j+p] = j;
			k >>= 1;
		}
		for(; p<8; p++)
			res[8+p] = 8+p +0x80; // +0x80 => 0 discarded entries; has no effect other than to ease debugging
		
		expandLUT[i] = expand;
	}
}
#else
void RapidYenc::encoder_neon_init() {}
#endif /* defined(__ARM_NEON) */
