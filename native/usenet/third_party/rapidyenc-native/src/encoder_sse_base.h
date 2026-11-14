#include "common.h"

#include "encoder.h"
#include "encoder_common.h"

#if defined(__clang__) && __clang_major__ == 6 && __clang_minor__ == 0
// VBMI2 introduced in clang 6.0, but 128-bit functions misnamed there; fixed in clang 7.0, but we'll handle those on 6.0
# define _mm_mask_expand_epi8 _mm128_mask_expand_epi8
#endif

#if (defined(__GNUC__) && __GNUC__ >= 7) || (defined(_MSC_VER) && _MSC_VER >= 1924)
# define KLOAD16(a, offs) _load_mask16((__mmask16*)(a) + (offs))
#else
# define KLOAD16(a, offs) (((uint16_t*)(a))[(offs)])
#endif

#pragma pack(16)
static struct {
	/*align16*/ struct { __m128i shuf, mix; } shufMix[256];
	unsigned char BitsSetTable256plus8[256];
	uint32_t eolLastChar[256];
	uint16_t eolFirstMask[256];
	uint16_t expandMask[256];
	/*align16*/ int8_t expandMaskmix[33*2*32];
	/*align16*/ int8_t expandShufmaskmix[33*2*32];
} * HEDLEY_RESTRICT lookups;
#pragma pack()

template<enum YEncDecIsaLevel use_isa>
static void encoder_sse_lut() {
	ALIGN_ALLOC(lookups, sizeof(*lookups), 16);
	for(int i=0; i<256; i++) {
		int k = i;
		uint8_t* res = (uint8_t*)(&(lookups->shufMix[i].shuf));
		uint16_t expand = 0;
		int p = 0;
		for(int j=0; j<8; j++) {
			if(k & 1) {
				res[j+p] = 0xf0 + j;
				p++;
			}
			expand |= 1<<(j+p);
			res[j+p] = j;
			k >>= 1;
		}
		for(; p<8; p++)
			res[8+p] = 8+p +0x40; // +0x40 is an arbitrary value to make debugging slightly easier?  the top bit cannot be set
		
		lookups->expandMask[i] = expand;
		
		// calculate add mask for mixing escape chars in
		__m128i shuf = _mm_load_si128((__m128i*)res);
		__m128i maskEsc = _mm_cmpeq_epi8(_mm_and_si128(shuf, _mm_set1_epi8(-16)), _mm_set1_epi8(-16)); // -16 == 0xf0
		__m128i addMask = _mm_and_si128(_mm_slli_si128(maskEsc, 1), _mm_set1_epi8(64));
		addMask = _mm_or_si128(addMask, _mm_and_si128(maskEsc, _mm_set1_epi8('='-42)));
		addMask = _mm_add_epi8(addMask, _mm_set1_epi8(42));
		
		_mm_store_si128(&(lookups->shufMix[i].mix), addMask);
		
		
		lookups->eolLastChar[i] = ((i == 214+'\t' || i == 214+' ' || i == 214+'\0' || i == 214+'\n' || i == 214+'\r' || i == '='-42) ? (((i+42+64)&0xff)<<8)+0x0a0d003d : ((i+42)&0xff)+0x0a0d00);
		lookups->eolFirstMask[i] = ((i == 214+'\t' || i == 214+' ' || i == '.'-42) ? 1 : 0);
		
		lookups->BitsSetTable256plus8[i] = 8 + (
			(i & 1) + ((i>>1) & 1) + ((i>>2) & 1) + ((i>>3) & 1) + ((i>>4) & 1) + ((i>>5) & 1) + ((i>>6) & 1) + ((i>>7) & 1)
		);
	}
	for(int i=0; i<33; i++) {
		int n = (use_isa & ISA_FEATURE_LZCNT) ? (i == 32 ? 32 : 31-i) : (i == 0 ? 32 : i-1);
		for(int j=0; j<32; j++) {
			lookups->expandMaskmix[i*64 + j] = (n>j ? -1 : 0);
			if(j > 15) // mask part
				lookups->expandShufmaskmix[i*64 + j] = (n>=j ? -1 : 0);
			else // shuffle part
				lookups->expandShufmaskmix[i*64 + j] = (n==j ? -1 : (j-(n<j)));
			lookups->expandMaskmix[i*64 + j + 32] = (n==j ? '=' : 42+64*(n==j-1));
			lookups->expandShufmaskmix[i*64 + j + 32] = (n==j ? '=' : 42+64*(n==j-1));
		}
	}
}


// for LZCNT/BSF
#ifdef _MSC_VER
# include <intrin.h>
# include <ammintrin.h>
static HEDLEY_ALWAYS_INLINE unsigned BSR32(unsigned src) {
	unsigned long result;
	_BitScanReverse((unsigned long*)&result, src);
	return result;
}
#elif defined(__GNUC__)
// have seen Clang not like _bit_scan_reverse
# include <x86intrin.h> // for lzcnt
# define BSR32(src) (31^__builtin_clz(src))
#else
# include <x86intrin.h>
# define BSR32 _bit_scan_reverse
#endif

template<enum YEncDecIsaLevel use_isa>
static HEDLEY_ALWAYS_INLINE __m128i sse2_expand_bytes(unsigned mask, __m128i data) {
	while(mask) {
		// get highest bit
		unsigned bitIndex;
		__m128i mergeMask;
#if defined(__LZCNT__)
		if(use_isa & ISA_FEATURE_LZCNT) {
			bitIndex = _lzcnt_u32(mask);
			mergeMask = _mm_load_si128((const __m128i*)lookups->expandMaskmix + bitIndex*4);
			mask &= 0x7fffffffU>>bitIndex;
		} else
#endif
		{
			// TODO: consider LUT for when BSR is slow
			bitIndex = BSR32(mask);
			mergeMask = _mm_load_si128((const __m128i*)lookups->expandMaskmix + (bitIndex+1)*4);
			mask ^= 1<<bitIndex;
		}
		// perform expansion
		data = _mm_or_si128(
			_mm_and_si128(mergeMask, data),
			_mm_slli_si128(_mm_andnot_si128(mergeMask, data), 1)
		);
	}
	return data;
}

template<enum YEncDecIsaLevel use_isa>
static HEDLEY_ALWAYS_INLINE uintptr_t sse2_expand_store_vector(__m128i data, unsigned int mask, unsigned maskP1, unsigned maskP2, uint8_t* p, unsigned int& shufLenP1, unsigned int& shufLenP2) {
	// TODO: consider 1 bit shortcut (slightly tricky with needing bit counts though)
	if(mask) {
		__m128i dataA = sse2_expand_bytes<use_isa>(maskP1, data);
		__m128i dataB = sse2_expand_bytes<use_isa>(maskP2, _mm_srli_si128(data, 8));
		dataA = _mm_add_epi8(dataA, _mm_load_si128(&(lookups->shufMix[maskP1].mix)));
		dataB = _mm_add_epi8(dataB, _mm_load_si128(&(lookups->shufMix[maskP2].mix)));
		shufLenP1 = lookups->BitsSetTable256plus8[maskP1];
		shufLenP2 = shufLenP1 + lookups->BitsSetTable256plus8[maskP2];
		STOREU_XMM(p, dataA);
		STOREU_XMM(p+shufLenP1, dataB);
		return shufLenP2;
	} else {
		STOREU_XMM(p, _mm_sub_epi8(data, _mm_set1_epi8(-42)));
		shufLenP1 = 8;
		shufLenP2 = 16;
		return XMM_SIZE;
	}
}

namespace RapidYenc {

template<enum YEncDecIsaLevel use_isa>
HEDLEY_ALWAYS_INLINE void do_encode_sse(int line_size, int* colOffset, const uint8_t* HEDLEY_RESTRICT srcEnd, uint8_t* HEDLEY_RESTRICT& dest, size_t& len) {
	// offset position to enable simpler loop condition checking
	const int INPUT_OFFSET = XMM_SIZE*4+1 -1; // EOL handling reads an additional byte, -1 to change <= to <
	if(len <= INPUT_OFFSET || line_size < XMM_SIZE) return;
	
	// slower CPUs prefer to branch as mispredict penalty is probably small relative to general execution
#if defined(__tune_atom__) || defined(__tune_slm__) || defined(__tune_btver1__) || defined(__tune_btver2__)
	const bool _PREFER_BRANCHING = true;
#else
	const bool _PREFER_BRANCHING = (use_isa < ISA_LEVEL_SSSE3);
#endif
	
	uint8_t *p = dest; // destination pointer
	intptr_t i = -(intptr_t)len; // input position
	intptr_t lineSizeOffset = -line_size +1;
	//intptr_t col = *colOffset - line_size +1; // for some reason, this causes GCC-8 to spill an extra register, causing the main loop to run ~5% slower, so use the alternative version below
	intptr_t col = *colOffset + lineSizeOffset;
	
	i += INPUT_OFFSET;
	const uint8_t* es = srcEnd - INPUT_OFFSET;
	
	if(HEDLEY_UNLIKELY(col >= 0)) {
		uint8_t c = es[i++];
		if(col == 0) {
			uint32_t eolChar = lookups->eolLastChar[c];
			memcpy(p, &eolChar, sizeof(eolChar));
			p += 3 + (eolChar>>27);
			col = -line_size+1;
		} else {
			uint32_t w;
			if (LIKELIHOOD(0.0273, escapedLUT[c]!=0)) {
				w = UINT32_16_PACK(UINT16_PACK('\r', '\n'), (uint32_t)escapedLUT[c]);
				memcpy(p, &w, sizeof(w));
				p += 4;
				col = 2-line_size + 1;
			} else {
				w = UINT32_PACK('\r', '\n', (uint32_t)(c+42), 0);
				memcpy(p, &w, sizeof(w));
				p += 3;
				col = 2-line_size;
			}
		}
	}
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
	do {
		__m128i dataA = _mm_loadu_si128((__m128i *)(es + i)); // probably not worth the effort to align
		__m128i dataB = _mm_loadu_si128((__m128i *)(es + i) +1);
		
		i += XMM_SIZE*2;
		// search for special chars
		__m128i cmpA, cmpB;
#if defined(__SSSE3__) && !defined(__tune_atom__) && !defined(__tune_slm__) && !defined(__tune_btver1__)
		if(use_isa >= ISA_LEVEL_SSSE3) {
			cmpA = _mm_cmpeq_epi8(
				_mm_shuffle_epi8(_mm_set_epi8(
					'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42
				), _mm_abs_epi8(dataA)),
				dataA
			);
		} else
#endif
		{
			cmpA = _mm_or_si128(
				_mm_or_si128(
					_mm_cmpeq_epi8(dataA, _mm_set1_epi8(-42)),
					_mm_cmpeq_epi8(dataA, _mm_set1_epi8('\n'-42))
				),
				_mm_or_si128(
					_mm_cmpeq_epi8(dataA, _mm_set1_epi8('='-42)),
					_mm_cmpeq_epi8(dataA, _mm_set1_epi8('\r'-42))
				)
			);
		}
		
		_encode_loop_branchA:
		unsigned int maskA = _mm_movemask_epi8(cmpA);
		_encode_loop_branchB:
		
#if defined(__SSSE3__) && !defined(__tune_atom__) && !defined(__tune_slm__) && !defined(__tune_btver1__)
		if(use_isa >= ISA_LEVEL_SSSE3) {
			cmpB = _mm_cmpeq_epi8(
				_mm_shuffle_epi8(_mm_set_epi8(
					'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42
				), _mm_abs_epi8(dataB)),
				dataB
			);
		} else
#endif
		{
			cmpB = _mm_or_si128(
				_mm_or_si128(
					_mm_cmpeq_epi8(dataB, _mm_set1_epi8(-42)),
					_mm_cmpeq_epi8(dataB, _mm_set1_epi8('\n'-42))
				),
				_mm_or_si128(
					_mm_cmpeq_epi8(dataB, _mm_set1_epi8('='-42)),
					_mm_cmpeq_epi8(dataB, _mm_set1_epi8('\r'-42))
				)
			);
		}
		unsigned int maskB = _mm_movemask_epi8(cmpB);
		
		uint32_t mask = (maskB<<16) | maskA;
		intptr_t bitIndex; // because you can't goto past variable declarations...
		intptr_t maskBits, outputBytes;
		
		bool manyBitsSet;
#if defined(__POPCNT__) && !defined(__tune_btver1__)
		if(use_isa & ISA_FEATURE_POPCNT) {
			maskBits = popcnt32(mask);
			outputBytes = maskBits + XMM_SIZE*2;
			manyBitsSet = maskBits > 1;
		} else
#endif
		{
			manyBitsSet = (mask & (mask-1)) != 0;
		}
		
		if (LIKELIHOOD(0.089, manyBitsSet)) {
			_encode_loop_branch_slow:
			unsigned m1 = maskA & 0xFF;
			unsigned m2 = maskA >> 8;
			unsigned m3 = maskB & 0xFF;
			unsigned m4 = maskB >> 8;
			unsigned int shuf1Len, shuf2Len, shuf3Len;
			__m128i shuf1A, shuf1B, shuf2A, shuf2B; // only used for SSSE3 path
			__m128i data1A, data1B, data2A, data2B;
			
#if defined(__AVX512VBMI2__) && defined(__AVX512VL__) && defined(__AVX512BW__)
			if(use_isa >= ISA_LEVEL_VBMI2) {
				// do +42 and +64 to data
				dataA = _mm_sub_epi8(dataA, _mm_set1_epi8(-42));
				dataA = _mm_ternarylogic_epi32(dataA, cmpA, _mm_set1_epi8(64), 0xf8); // data | (cmp & 64)
				dataB = _mm_sub_epi8(dataB, _mm_set1_epi8(-42));
				dataB = _mm_ternarylogic_epi32(dataB, cmpB, _mm_set1_epi8(64), 0xf8);
				
				/* alternative no-LUT 64-bit only version
				 * LUT generally seems to be faster though
				const uint64_t ALT_BITS = 0xaaaaaaaaaaaaaaaa;
				uint64_t expandMask = _pdep_u64(mask, ~ALT_BITS) | ALT_BITS;
				data2A = _mm_mask_expand_epi8(_mm_set1_epi8('='), _pext_u64(ALT_BITS, expandMask>>16), _mm_srli_si128(dataA, 8));
				data1A = _mm_mask_expand_epi8(_mm_set1_epi8('='), _pext_u64(ALT_BITS, expandMask    ), dataA);
				data2B = _mm_mask_expand_epi8(_mm_set1_epi8('='), _pext_u64(ALT_BITS, expandMask>>48), _mm_srli_si128(dataB, 8));
				data1B = _mm_mask_expand_epi8(_mm_set1_epi8('='), _pext_u64(ALT_BITS, expandMask>>32), dataB);
				*/
				data2A = _mm_mask_expand_epi8(_mm_set1_epi8('='), KLOAD16(lookups->expandMask, m2), _mm_srli_si128(dataA, 8));
				data1A = _mm_mask_expand_epi8(_mm_set1_epi8('='), KLOAD16(lookups->expandMask, m1), dataA);
				data2B = _mm_mask_expand_epi8(_mm_set1_epi8('='), KLOAD16(lookups->expandMask, m4), _mm_srli_si128(dataB, 8));
				data1B = _mm_mask_expand_epi8(_mm_set1_epi8('='), KLOAD16(lookups->expandMask, m3), dataB);
			} else
#endif
#ifdef __SSSE3__
			if(use_isa >= ISA_LEVEL_SSSE3) {
				// perform lookup for shuffle mask
				shuf1A = _mm_load_si128(&(lookups->shufMix[m1].shuf));
				shuf2A = _mm_load_si128(&(lookups->shufMix[m2].shuf));
				shuf1B = _mm_load_si128(&(lookups->shufMix[m3].shuf));
				shuf2B = _mm_load_si128(&(lookups->shufMix[m4].shuf));
				
				// second mask processes on second half, so add to the offsets
				shuf2A = _mm_or_si128(shuf2A, _mm_set1_epi8(8));
				shuf2B = _mm_or_si128(shuf2B, _mm_set1_epi8(8));
				
				// expand halves
				data2A = _mm_shuffle_epi8(dataA, shuf2A);
				data1A = _mm_shuffle_epi8(dataA, shuf1A);
				data2B = _mm_shuffle_epi8(dataB, shuf2B);
				data1B = _mm_shuffle_epi8(dataB, shuf1B);
				
				// add in escaped chars
				data1A = _mm_add_epi8(data1A, _mm_load_si128(&(lookups->shufMix[m1].mix)));
				data2A = _mm_add_epi8(data2A, _mm_load_si128(&(lookups->shufMix[m2].mix)));
				data1B = _mm_add_epi8(data1B, _mm_load_si128(&(lookups->shufMix[m3].mix)));
				data2B = _mm_add_epi8(data2B, _mm_load_si128(&(lookups->shufMix[m4].mix)));
			} else
#endif
			{
				p += sse2_expand_store_vector<use_isa>(dataA, maskA, m1, m2, p, shuf1Len, shuf2Len);
				unsigned int shuf4Len;
				p += sse2_expand_store_vector<use_isa>(dataB, maskB, m3, m4, p, shuf3Len, shuf4Len);
				shuf3Len += shuf2Len;
#if !defined(__tune_btver1__)
				if(!(use_isa & ISA_FEATURE_POPCNT))
#endif
					outputBytes = shuf2Len + shuf4Len;
			}
			
			if(use_isa >= ISA_LEVEL_SSSE3) {
				// store out
#if defined(__POPCNT__) && !defined(__tune_btver1__)
				if(use_isa & ISA_FEATURE_POPCNT) {
					shuf2Len = popcnt32(maskA) + 16;
# if defined(__tune_znver6__) || defined(__tune_znver5__) || defined(__tune_znver4__) || defined(__tune_znver3__) || defined(__tune_znver2__) || defined(__tune_znver1__) || defined(__tune_btver2__)
					shuf1Len = popcnt32(m1) + 8;
					shuf3Len = popcnt32(m3) + shuf2Len + 8;
# else
					shuf1Len = lookups->BitsSetTable256plus8[m1];
					shuf3Len = lookups->BitsSetTable256plus8[m3] + shuf2Len;
# endif
				} else
#endif
				{
					shuf1Len = lookups->BitsSetTable256plus8[m1];
					shuf2Len = shuf1Len + lookups->BitsSetTable256plus8[m2];
					shuf3Len = shuf2Len + lookups->BitsSetTable256plus8[m3];
					outputBytes = shuf3Len + lookups->BitsSetTable256plus8[m4];
				}
				STOREU_XMM(p, data1A);
				STOREU_XMM(p+shuf1Len, data2A);
				STOREU_XMM(p+shuf2Len, data1B);
				STOREU_XMM(p+shuf3Len, data2B);
				p += outputBytes;
			}
			col += outputBytes;
			
			if(LIKELIHOOD(0.3 /*guess, using 128b lines*/, col >= 0)) {
				uintptr_t bitCount;
				intptr_t shiftAmt = (outputBytes - shuf2Len) - col -1;
				uint32_t eqMask;
				if(HEDLEY_UNLIKELY(shiftAmt < 0)) {
					shiftAmt += shuf2Len;
					i -= 16;
					if(use_isa >= ISA_LEVEL_VBMI2 || use_isa < ISA_LEVEL_SSSE3) {
						eqMask =
						  ((uint32_t)lookups->expandMask[m2] << shuf1Len)
						  | (uint32_t)lookups->expandMask[m1];
					} else {
						eqMask =
						  ((uint32_t)_mm_movemask_epi8(shuf2A) << shuf1Len)
						  | (uint32_t)_mm_movemask_epi8(shuf1A);
						i += outputBytes - shuf2Len;
					}
				} else {
					if(use_isa >= ISA_LEVEL_VBMI2 || use_isa < ISA_LEVEL_SSSE3) {
						eqMask =
						  ((uint32_t)lookups->expandMask[m4] << (shuf3Len-shuf2Len))
						  | (uint32_t)lookups->expandMask[m3];
					} else {
						eqMask =
						  ((uint32_t)_mm_movemask_epi8(shuf2B) << (shuf3Len-shuf2Len))
						  | (uint32_t)_mm_movemask_epi8(shuf1B);
					}
				}
				
				if(use_isa >= ISA_LEVEL_VBMI2 || use_isa < ISA_LEVEL_SSSE3) {
#if defined(__GNUC__)
					// be careful to avoid partial flag stalls on Intel P6 CPUs (SHR+ADC will likely stall)
# if !(defined(__tune_amdfam10__) || defined(__tune_k8__))
					if(use_isa >= ISA_LEVEL_VBMI2)
# endif
					{
						__asm__(
							"shrl $1, %[eqMask] \n"
							"shrl %%cl, %[eqMask] \n" // TODO: can use shrq to avoid above shift?
# if defined(PLATFORM_AMD64) && !defined(__ILP32__)
							"adcq %q[col], %q[p] \n"
# else
							"adcl %[col], %[p] \n"
# endif
							: [eqMask]"+r"(eqMask), [p]"+r"(p)
							: "c"(shiftAmt), [col]"r"(~col)
						);
					}
# if !(defined(__tune_amdfam10__) || defined(__tune_k8__))
					else
# else
					if(0)
# endif
#endif
					{
						eqMask >>= shiftAmt;
						p -= col;
						if(LIKELIHOOD(0.98, (eqMask & 1) != 1))
							p--;
						else
							i++;
					}
				} else {
					eqMask >>= shiftAmt;
					col += eqMask & 1; // revert if escape char
				}
				
#if defined(__POPCNT__)
				if(use_isa & ISA_FEATURE_POPCNT) {
					bitCount = popcnt32(eqMask);
				} else
#endif
				{
					unsigned char cnt = lookups->BitsSetTable256plus8[eqMask & 0xff];
					cnt += lookups->BitsSetTable256plus8[(eqMask>>8) & 0xff];
					cnt += lookups->BitsSetTable256plus8[(eqMask>>16) & 0xff];
					cnt += lookups->BitsSetTable256plus8[(eqMask>>24) & 0xff];
					bitCount = (uintptr_t)cnt - 32;
				}
				
				if(use_isa >= ISA_LEVEL_VBMI2 || use_isa < ISA_LEVEL_SSSE3) {
					i -= bitCount;
					goto _encode_eol_handle_pre;
				} else {
					i += bitCount;
					goto _encode_eol_handle_pre_adjust;
				}
			}
		} else {
			if(_PREFER_BRANCHING && LIKELIHOOD(0.663, !mask)) {
				_encode_loop_branch_fast_noesc:
				dataA = _mm_sub_epi8(dataA, _mm_set1_epi8(-42));
				dataB = _mm_sub_epi8(dataB, _mm_set1_epi8(-42));
				STOREU_XMM(p, dataA);
				STOREU_XMM(p+XMM_SIZE, dataB);
				p += XMM_SIZE*2;
				col += XMM_SIZE*2;
				if(LIKELIHOOD(0.15, col >= 0))
					goto _encode_eol_handle_pre_adjust;
				continue;
			}
			// shortcut for common case of only 1 bit set
			_encode_loop_branch_fast_1ch:
#if defined(__AVX512VL__) && defined(__AVX512BW__)
			if(use_isa >= ISA_LEVEL_AVX3) {
				dataA = _mm_sub_epi8(dataA, _mm_set1_epi8(-42));
				dataA = _mm_ternarylogic_epi32(dataA, cmpA, _mm_set1_epi8(64), 0xf8); // data | (cmp & 64)
				dataB = _mm_sub_epi8(dataB, _mm_set1_epi8(-42));
				dataB = _mm_ternarylogic_epi32(dataB, cmpB, _mm_set1_epi8(64), 0xf8);
				
				// store last char
				p[XMM_SIZE*2] = _mm_extract_epi8(dataB, 15);
				
				uint32_t blendMask = (uint32_t)(-(int32_t)mask);
				dataB = _mm_mask_alignr_epi8(dataB, blendMask>>16, dataB, dataA, 15);
				dataB = _mm_ternarylogic_epi32(dataB, cmpB, _mm_set1_epi8('='), 0xb8); // (data & ~cmp) | (cmp & '=')
				
# if defined(__AVX512VBMI2__)
				if(use_isa >= ISA_LEVEL_VBMI2)
					dataA = _mm_mask_expand_epi8(_mm_set1_epi8('='), ~mask, dataA);
				else
# endif
				{
					dataA = _mm_mask_alignr_epi8(dataA, blendMask, dataA, dataA, 15); // there's no masked shift, so use ALIGNR instead
					dataA = _mm_ternarylogic_epi32(dataA, cmpA, _mm_set1_epi8('='), 0xb8);
				}
			} else
#endif
			{
				
#if !defined(__tune_btver1__)
				if(!(use_isa & ISA_FEATURE_POPCNT))
#endif
					maskBits = (mask != 0);
				if(_PREFER_BRANCHING) maskBits = 1;
#if !defined(__tune_btver1__)
				if(!(use_isa & ISA_FEATURE_POPCNT))
#endif
					outputBytes = XMM_SIZE*2 + maskBits;
				
#if defined(__LZCNT__)
				if(use_isa & ISA_FEATURE_LZCNT)
					bitIndex = _lzcnt_u32(mask);
				else
#endif
				{
					bitIndex = BSR32(mask);
					bitIndex |= maskBits-1; // if(mask == 0) bitIndex = -1;
				}
				const __m128i* entries;
				
#if defined(__SSSE3__) && !defined(__tune_atom__) && !defined(__tune_slm__) && !defined(__tune_btver1__)
				if(use_isa >= ISA_LEVEL_SSSE3) {
					entries = (const __m128i*)lookups->expandShufmaskmix;
					if(!(use_isa & ISA_FEATURE_LZCNT))
						entries += 4;
					entries += bitIndex*4;
					
					__m128i shufMaskA = _mm_load_si128(entries+0);
					__m128i mergeMaskB = _mm_load_si128(entries+1);
					__m128i dataBShifted = _mm_alignr_epi8(dataB, dataA, 15);
					dataB = _mm_andnot_si128(cmpB, dataB);
					
					dataA = _mm_shuffle_epi8(dataA, shufMaskA);
					
# if defined(__SSE4_1__) && !defined(__tune_slm__) && !defined(__tune_goldmont__) && !defined(__tune_goldmont_plus__) && !defined(__tune_tremont__)
					if(use_isa >= ISA_LEVEL_SSE41) {
						dataB = _mm_blendv_epi8(dataBShifted, dataB, mergeMaskB);
					} else
# endif
					{
						dataB = _mm_or_si128(
							_mm_and_si128(mergeMaskB, dataB),
							_mm_andnot_si128(mergeMaskB, dataBShifted)
						);
					}
				} else
#endif
				{
					
					entries = (const __m128i*)lookups->expandMaskmix;
					if(!(use_isa & ISA_FEATURE_LZCNT))
						entries += 4;
					entries += bitIndex*4;
					
					__m128i mergeMaskA = _mm_load_si128(entries+0);
					__m128i mergeMaskB = _mm_load_si128(entries+1);
					// TODO: consider deferring mask operation? (does require an extra ANDN but may help with L1 latency)
					__m128i dataAMasked = _mm_andnot_si128(mergeMaskA, dataA);
					__m128i dataBMasked = _mm_andnot_si128(mergeMaskB, dataB);
					__m128i dataAShifted = _mm_slli_si128(dataAMasked, 1);
					__m128i dataBShifted;
					
#if defined(__SSSE3__) && !defined(__tune_btver1__)
					if(use_isa >= ISA_LEVEL_SSSE3)
						dataBShifted = _mm_alignr_epi8(dataBMasked, dataAMasked, 15);
					else
#endif
						dataBShifted = _mm_or_si128(
							_mm_slli_si128(dataBMasked, 1),
							_mm_srli_si128(dataAMasked, 15)
						);
					
					// alternatively `_mm_xor_si128(dataAMasked, dataA)` if compiler wants to load mergeMask* again
					dataB = _mm_or_si128(
						_mm_and_si128(mergeMaskB, dataB), dataBShifted
					);
					dataA = _mm_or_si128(
						_mm_and_si128(mergeMaskA, dataA), dataAShifted
					);
				}
				// add escape chars
				dataA = _mm_add_epi8(dataA, _mm_load_si128(entries+2));
				dataB = _mm_add_epi8(dataB, _mm_load_si128(entries+3));
				
				// store final char
				p[XMM_SIZE*2] = es[i-1] + 42 + (64 & (mask>>(XMM_SIZE*2-1-6)));
			}
			
			// store main part
			STOREU_XMM(p, dataA);
			STOREU_XMM(p+XMM_SIZE, dataB);
			
			p += outputBytes;
			col += outputBytes;
			
			if(LIKELIHOOD(0.3, col >= 0)) {
#if defined(__AVX512VL__)
				if(use_isa >= ISA_LEVEL_AVX3)
					bitIndex = _lzcnt_u32(mask) +1;
				else
#endif
				if(use_isa & ISA_FEATURE_LZCNT)
					bitIndex = bitIndex +1;
				else
					bitIndex = 31-bitIndex +1;
				if(HEDLEY_UNLIKELY(col == bitIndex)) {
					// this is an escape character, so line will need to overflow
					p--;
				} else {
					i += (col > bitIndex);
				}
				_encode_eol_handle_pre_adjust:
				p -= col;
				i -= col;
				
				_encode_eol_handle_pre:
				uint32_t eolChar = lookups->eolLastChar[es[i]];
				memcpy(p, &eolChar, sizeof(eolChar));
				p += 3 + (eolChar>>27);
				col = lineSizeOffset;
				
				if(HEDLEY_UNLIKELY(i >= 0)) { // this isn't really a proper check - it's only needed to support short lines; basically, if the line is too short, `i` never gets checked, so we need one somewhere
					i++;
					break;
				}
				
				dataA = _mm_loadu_si128((__m128i *)(es + i + 1));
				dataB = _mm_loadu_si128((__m128i *)(es + i + 1) + 1);
				// search for special chars (EOL)
#if defined(__SSSE3__) && !defined(__tune_atom__) && !defined(__tune_slm__) && !defined(__tune_btver1__)
				if(use_isa >= ISA_LEVEL_SSSE3) {
					cmpA = _mm_cmpeq_epi8(
						_mm_shuffle_epi8(_mm_set_epi8(
							'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42
						), _mm_adds_epi8(
							_mm_abs_epi8(dataA), _mm_cvtsi32_si128(88)
						)),
						dataA
					);
					i += XMM_SIZE*2 + 1;
# if defined(__GNUC__) && !defined(__clang__)
					// GCC seems to have trouble keeping track of variable usage and spills many of them if we goto after declarations; Clang9 seems to be fine, or if _PREFER_BRANCHING is used
					if(!_PREFER_BRANCHING)
						goto _encode_loop_branchA;
# endif
					maskA = _mm_movemask_epi8(cmpA);
					cmpB = _mm_cmpeq_epi8(
						_mm_shuffle_epi8(_mm_set_epi8(
							'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42
						), _mm_abs_epi8(dataB)),
						dataB
					);
				} else
#endif
				{
					cmpA = _mm_or_si128(
						_mm_or_si128(
							_mm_cmpeq_epi8(dataA, _mm_set1_epi8(-42)),
							_mm_cmpeq_epi8(dataA, _mm_set1_epi8('\n'-42))
						),
						_mm_or_si128(
							_mm_cmpeq_epi8(dataA, _mm_set1_epi8('='-42)),
							_mm_cmpeq_epi8(dataA, _mm_set1_epi8('\r'-42))
						)
					);
					maskA = _mm_movemask_epi8(cmpA);
					maskA |= lookups->eolFirstMask[es[i+1]];
					i += XMM_SIZE*2 + 1;
#if defined(__GNUC__) && !defined(__clang__)
					if(!_PREFER_BRANCHING)
						goto _encode_loop_branchB;
#endif
					cmpB = _mm_or_si128(
						_mm_or_si128(
							_mm_cmpeq_epi8(dataB, _mm_set1_epi8(-42)),
							_mm_cmpeq_epi8(dataB, _mm_set1_epi8('\n'-42))
						),
						_mm_or_si128(
							_mm_cmpeq_epi8(dataB, _mm_set1_epi8('='-42)),
							_mm_cmpeq_epi8(dataB, _mm_set1_epi8('\r'-42))
						)
					);
				}
				maskB = _mm_movemask_epi8(cmpB);
				
				mask = (maskB<<16) | maskA;
				bool manyBitsSet; // don't retain this across loop cycles
#if defined(__POPCNT__) && !defined(__tune_btver1__)
				if(use_isa & ISA_FEATURE_POPCNT) {
					maskBits = popcnt32(mask);
					outputBytes = maskBits + XMM_SIZE*2;
					manyBitsSet = maskBits > 1;
				} else
#endif
				{
					manyBitsSet = (mask & (mask-1)) != 0;
				}
				
				if (LIKELIHOOD(0.089, manyBitsSet))
					goto _encode_loop_branch_slow;
				if(_PREFER_BRANCHING && LIKELIHOOD(0.663, !mask))
					goto _encode_loop_branch_fast_noesc;
				goto _encode_loop_branch_fast_1ch;
				if(0) { // silence unused label warnings
					goto _encode_loop_branchA;
					goto _encode_loop_branchB;
				}
			}
			
		}
	} while(i < 0);
	
	*colOffset = (int)(col + line_size -1);
	dest = p;
	len = -(i - INPUT_OFFSET);
}
} // namespace

