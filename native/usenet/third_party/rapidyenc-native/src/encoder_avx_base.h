// can't seem to make this worth it
#include "common.h"
#ifdef __AVX2__

#include "encoder.h"
#include "encoder_common.h"
#define YMM_SIZE 32

#pragma pack(16)
static struct {
	uint32_t eolLastChar[256];
	/*align32*/ __m256i shufExpand[65536]; // huge 2MB table
	/*align32*/ int8_t expandMergemix[33*2*32]; // not used in AVX3
} * HEDLEY_RESTRICT lookupsAVX2;
static struct {
	uint32_t eolLastChar[256];
} * HEDLEY_RESTRICT lookupsVBMI2;
#pragma pack()

static inline void fill_eolLastChar(uint32_t* table) {
	for(int n=0; n<256; n++) {
		table[n] = ((n == 214+'\t' || n == 214+' ' || n == 214+'\0' || n == 214+'\n' || n == 214+'\r' || n == '='-42) ? (((n+42+64)&0xff)<<8)+0x0a0d003d : ((n+42)&0xff)+0x0a0d00);
	}
}

template<enum YEncDecIsaLevel use_isa>
static void encoder_avx2_lut() {
	if(use_isa >= ISA_LEVEL_VBMI2) {
		ALIGN_ALLOC(lookupsVBMI2, sizeof(*lookupsVBMI2), 8);
		fill_eolLastChar(lookupsVBMI2->eolLastChar);
	} else {
		ALIGN_ALLOC(lookupsAVX2, sizeof(*lookupsAVX2), 32);
		fill_eolLastChar(lookupsAVX2->eolLastChar);
		for(int i=0; i<65536; i++) {
			int k = i;
			uint8_t* res = (uint8_t*)(lookupsAVX2->shufExpand + i);
			int p = 0;
			for(int j=0; j<16; j++) {
				if(k & 1) {
					res[j+p] = 0xff;
					p++;
				}
				res[j+p] = j;
				k >>= 1;
			}
			for(; p<16; p++)
				res[16+p] = 0x40; // arbitrary value (top bit cannot be set)
		}
		for(int i=0; i<33; i++) {
			int n = (i == 32 ? 32 : 31-i);
			for(int j=0; j<32; j++) {
				lookupsAVX2->expandMergemix[i*64 + j] = (n>=j ? -1 : 0);
				lookupsAVX2->expandMergemix[i*64 + j + 32] = ('='*(n==j) + 64*(n==j-1) + 42*(n!=j));
			}
		}
	}
}

namespace RapidYenc {

template<enum YEncDecIsaLevel use_isa>
HEDLEY_ALWAYS_INLINE void do_encode_avx2(int line_size, int* colOffset, const uint8_t* HEDLEY_RESTRICT srcEnd, uint8_t* HEDLEY_RESTRICT& dest, size_t& len) {
	// offset position to enable simpler loop condition checking
	const int INPUT_OFFSET = YMM_SIZE*4 + 1 -1; // -1 to change <= to <
	if(len <= INPUT_OFFSET || line_size < 16) return;
	
	uint8_t *p = dest; // destination pointer
	intptr_t i = -(intptr_t)len; // input position
	intptr_t lineSizeOffset = -line_size +1; // -1 because we want to stop one char before the end to handle the last char differently
	intptr_t col = *colOffset + lineSizeOffset;
	
	i += INPUT_OFFSET;
	const uint8_t* es = srcEnd - INPUT_OFFSET;
	
#if !defined(__tune_bdver4__) && !defined(__tune_znver1__)
	// always process at least one byte to prevent underflow when doing a read with -1 offset
	if(col < 0 && col != -line_size+1) {
		// not the first/last character of a line
		uint8_t c = es[i++];
		if(HEDLEY_UNLIKELY(c == 214 || c == '\n'+214 || c == '\r'+214 || c == '='-42)) {
			uint16_t w = 0x6a3d + (((uint16_t)c) << 8);
			memcpy(p, &w, sizeof(w));
			p += 2;
			col += 2;
		} else {
			*p++ = c+42;
			col++;
		}
	}
#endif
	
	if(HEDLEY_UNLIKELY(col >= 0)) {
		uint8_t c = es[i++];
		if(col == 0) {
			// last char
			uint32_t eolChar = (use_isa >= ISA_LEVEL_VBMI2 ? lookupsVBMI2->eolLastChar[c] : lookupsAVX2->eolLastChar[c]);
			memcpy(p, &eolChar, sizeof(eolChar));
			p += 3 + (uintptr_t)(eolChar>>27);
			col = -line_size+1;
		} else {
			// line overflowed, insert a newline
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
		// first char of the line
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
		__m256i dataA = _mm256_loadu_si256((__m256i *)(es + i));
		__m256i dataB = _mm256_loadu_si256((__m256i *)(es + i) + 1);
		i += YMM_SIZE*2;
		// search for special chars
		__m256i cmpA = _mm256_cmpeq_epi8(
			_mm256_shuffle_epi8(_mm256_set_epi8(
				'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42,
				'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42
			), _mm256_abs_epi8(dataA)),
			dataA
		);
		__m256i cmpB = _mm256_cmpeq_epi8(
			_mm256_shuffle_epi8(_mm256_set_epi8(
				'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42,
				'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42
			), _mm256_abs_epi8(dataB)),
			dataB
		);
		
#if defined(__AVX512VL__)
		if(use_isa >= ISA_LEVEL_AVX3) {
			dataA = _mm256_add_epi8(dataA, _mm256_set1_epi8(42));
			dataA = _mm256_ternarylogic_epi32(dataA, cmpA, _mm256_set1_epi8(64), 0xf8); // data | (cmp & 64)
			dataB = _mm256_add_epi8(dataB, _mm256_set1_epi8(42));
			dataB = _mm256_ternarylogic_epi32(dataB, cmpB, _mm256_set1_epi8(64), 0xf8); // data | (cmp & 64)
		}
#endif
		
		uint32_t maskA = (uint32_t)_mm256_movemask_epi8(cmpA);
		uint32_t maskB = (uint32_t)_mm256_movemask_epi8(cmpB);
		unsigned int maskBitsA = popcnt32(maskA);
		unsigned int maskBitsB = popcnt32(maskB);
		unsigned int outputBytesA = maskBitsA + YMM_SIZE;
		unsigned int bitIndexA, bitIndexB;
		if (LIKELIHOOD(0.170, (maskBitsA|maskBitsB) > 1)) {
			_encode_loop_branch_slow:
			unsigned int m1 = maskA & 0xffff, m3 = maskB & 0xffff;
			__m256i data1A, data2A;
			__m256i data1B, data2B;
			__m256i shuf1A, shuf1B; // not set in VBMI2 path
			__m256i shuf2A, shuf2B; // not set in VBMI2 path
			
#if defined(__AVX512VBMI2__) && defined(__AVX512VL__) && defined(__AVX512BW__)
# ifdef PLATFORM_AMD64
			const uint64_t ALT_BITS = 0xaaaaaaaaaaaaaaaa;
			uint64_t eqMaskA, eqMaskB;
# else
			const uint32_t ALT_BITS = 0xaaaaaaaa;
# endif
#endif
			
#if defined(__AVX512VBMI2__) && defined(__AVX512VL__) && defined(__AVX512BW__)
			if(use_isa >= ISA_LEVEL_VBMI2) {
# ifdef PLATFORM_AMD64
				uint64_t expandMaskA = _pdep_u64(maskA, ~ALT_BITS) | ALT_BITS;
				uint64_t expandMaskB = _pdep_u64(maskB, ~ALT_BITS) | ALT_BITS;
				eqMaskA = _pext_u64(ALT_BITS, expandMaskA);
				eqMaskB = _pext_u64(ALT_BITS, expandMaskB);
				
				// _pext_u32 is ideal here, but compiler can't reason that ALT_BITS constant can be re-used; _pext_u64 is a workaround
				__mmask32 xm1 = eqMaskA;
				__mmask32 xm2 = _pext_u64(ALT_BITS, expandMaskA >> 32);
				__mmask32 xm3 = eqMaskB;
				__mmask32 xm4 = _pext_u64(ALT_BITS, expandMaskB >> 32);
# else
				__mmask32 xm1 = _pext_u32(ALT_BITS, _pdep_u32(m1, ~ALT_BITS) | ALT_BITS);
				__mmask32 xm2 = _pext_u32(ALT_BITS, _pdep_u32(maskA >> 16, ~ALT_BITS) | ALT_BITS);
				__mmask32 xm3 = _pext_u32(ALT_BITS, _pdep_u32(m3, ~ALT_BITS) | ALT_BITS);
				__mmask32 xm4 = _pext_u32(ALT_BITS, _pdep_u32(maskB >> 16, ~ALT_BITS) | ALT_BITS);
# endif
				
				data1A = _mm256_mask_expand_epi8(_mm256_set1_epi8('='), xm1, dataA);
				data2A = _mm256_mask_expand_epi8(_mm256_set1_epi8('='), xm2, _mm256_castsi128_si256(
					_mm256_extracti128_si256(dataA, 1)
				));
				data1B = _mm256_mask_expand_epi8(_mm256_set1_epi8('='), xm3, dataB);
				data2B = _mm256_mask_expand_epi8(_mm256_set1_epi8('='), xm4, _mm256_castsi128_si256(
					_mm256_extracti128_si256(dataB, 1)
				));
			} else
#endif
			{
				if(use_isa < ISA_LEVEL_AVX3) {
					dataA = _mm256_add_epi8(dataA, _mm256_blendv_epi8(_mm256_set1_epi8(42), _mm256_set1_epi8(42+64), cmpA));
					dataB = _mm256_add_epi8(dataB, _mm256_blendv_epi8(_mm256_set1_epi8(42), _mm256_set1_epi8(42+64), cmpB));
				}
				
				unsigned int m2 = (maskA >> 11) & 0x1fffe0;
				unsigned int m4 = (maskB >> 11) & 0x1fffe0;
				
				// duplicate halves
				data1A = _mm256_inserti128_si256(dataA, _mm256_castsi256_si128(dataA), 1);
				data1B = _mm256_inserti128_si256(dataB, _mm256_castsi256_si128(dataB), 1);
#if defined(__tune_znver1__) || defined(__tune_bdver4__)
				data2A = _mm256_permute4x64_epi64(dataA, 0xee);
				data2B = _mm256_permute4x64_epi64(dataB, 0xee);
#else
				data2A = _mm256_permute2x128_si256(dataA, dataA, 0x11);
				data2B = _mm256_permute2x128_si256(dataB, dataB, 0x11);
#endif
				
				shuf1A = _mm256_load_si256(lookupsAVX2->shufExpand + m1);
				shuf2A = _mm256_load_si256((__m256i*)((char*)(lookupsAVX2->shufExpand) + m2));
				shuf1B = _mm256_load_si256(lookupsAVX2->shufExpand + m3);
				shuf2B = _mm256_load_si256((__m256i*)((char*)(lookupsAVX2->shufExpand) + m4));
				
				// expand
				data1A = _mm256_shuffle_epi8(data1A, shuf1A);
				data2A = _mm256_shuffle_epi8(data2A, shuf2A);
				data1B = _mm256_shuffle_epi8(data1B, shuf1B);
				data2B = _mm256_shuffle_epi8(data2B, shuf2B);
				// add in '='
				data1A = _mm256_blendv_epi8(data1A, _mm256_set1_epi8('='), shuf1A);
				data2A = _mm256_blendv_epi8(data2A, _mm256_set1_epi8('='), shuf2A);
				data1B = _mm256_blendv_epi8(data1B, _mm256_set1_epi8('='), shuf1B);
				data2B = _mm256_blendv_epi8(data2B, _mm256_set1_epi8('='), shuf2B);
			}
			
			unsigned int shuf1Len = popcnt32(m1) + 16;
			unsigned int shuf3Len = popcnt32(m3) + 16;
			_mm256_storeu_si256((__m256i*)p, data1A);
			_mm256_storeu_si256((__m256i*)(p + shuf1Len), data2A);
			_mm256_storeu_si256((__m256i*)(p + outputBytesA), data1B);
			_mm256_storeu_si256((__m256i*)(p + outputBytesA + shuf3Len), data2B);
			unsigned int outputBytes = YMM_SIZE + outputBytesA + maskBitsB;
			p += outputBytes;
			col += outputBytes;
			
			if(col >= 0) {
				// we overflowed - find correct position to revert back to
				// this is perhaps sub-optimal on 32-bit, but who still uses that with AVX2?
				uint64_t eqMask;
				int shiftAmt = (int)(maskBitsB + YMM_SIZE -1 - col);
				if(HEDLEY_UNLIKELY(shiftAmt < 0)) {
#if defined(__AVX512VBMI2__) && defined(__AVX512VL__) && defined(__AVX512BW__)
					if(use_isa >= ISA_LEVEL_VBMI2) {
# ifdef PLATFORM_AMD64
						eqMask = eqMaskA;
# else
						uint32_t eqMask1 = _pext_u32(ALT_BITS, _pdep_u32(m1, ~ALT_BITS) | ALT_BITS);
						uint32_t eqMask2 = _pext_u32(ALT_BITS, _pdep_u32(maskA >> 16, ~ALT_BITS) | ALT_BITS);
						eqMask = eqMask1 | ((uint64_t)eqMask2 << shuf1Len);
# endif
					} else
#endif
					{
						uint32_t eqMask1 = (uint32_t)_mm256_movemask_epi8(shuf1A);
						uint32_t eqMask2 = (uint32_t)_mm256_movemask_epi8(shuf2A);
						eqMask = eqMask1 | ((uint64_t)eqMask2 << shuf1Len);
					}
					if(use_isa < ISA_LEVEL_VBMI2)
						i += (uintptr_t)maskBitsB;
					else
						i -= YMM_SIZE;
					shiftAmt += outputBytesA;
				} else {
#if defined(__AVX512VBMI2__) && defined(__AVX512VL__) && defined(__AVX512BW__)
					if(use_isa >= ISA_LEVEL_VBMI2) {
# ifdef PLATFORM_AMD64
						eqMask = eqMaskB;
# else
						uint32_t eqMask3 = _pext_u32(ALT_BITS, _pdep_u32(m3, ~ALT_BITS) | ALT_BITS);
						uint32_t eqMask4 = _pext_u32(ALT_BITS, _pdep_u32(maskB >> 16, ~ALT_BITS) | ALT_BITS);
						eqMask = eqMask3 | ((uint64_t)eqMask4 << shuf3Len);
# endif
					} else
#endif
					{
						uint32_t eqMask3 = (uint32_t)_mm256_movemask_epi8(shuf1B);
						uint32_t eqMask4 = (uint32_t)_mm256_movemask_epi8(shuf2B);
						eqMask = eqMask3 | ((uint64_t)eqMask4 << shuf3Len);
					}
				}
				
#if defined(__GNUC__) && defined(PLATFORM_AMD64)
				if(use_isa >= ISA_LEVEL_VBMI2) {
					__asm__(
						"shrq $1, %[eqMask] \n"
						"shrq %%cl, %[eqMask] \n"
						"adcq %q[col], %q[p] \n"
						: [eqMask]"+r"(eqMask), [p]"+r"(p)
						: "c"(shiftAmt), [col]"r"(~col)
					);
					i -= _mm_popcnt_u64(eqMask);
				} else
#endif
				{
					eqMask >>= shiftAmt;
					unsigned int bitCount;
#ifdef PLATFORM_AMD64
					bitCount = (unsigned int)_mm_popcnt_u64(eqMask);
#else
					bitCount = popcnt32(eqMask & 0xffffffff) + popcnt32(eqMask >> 32);
#endif
#if defined(__AVX512VBMI2__) && defined(__AVX512VL__) && defined(__AVX512BW__)
					if(use_isa >= ISA_LEVEL_VBMI2) {
						i -= bitCount;
						p -= col;
						if(LIKELIHOOD(0.98, (eqMask & 1) != 1))
							p--;
						else
							i++;
					} else
#endif
					{
						i += bitCount;
						unsigned int revert = (unsigned int)(col + (eqMask & 1));
						p -= revert;
						i -= revert;
					}
				}
				goto _encode_eol_handle_pre;
			}
		} else {
			//_encode_loop_branch_fast:
			maskBitsB += YMM_SIZE;
#if defined(__AVX512VL__) && defined(__AVX512BW__)
			if(use_isa >= ISA_LEVEL_AVX3) {
# if defined(__AVX512VBMI2__)
				if(use_isa >= ISA_LEVEL_VBMI2) {
					__m128i dataTop = _mm256_extracti128_si256(dataA, 1);
					dataA = _mm256_mask_expand_epi8(_mm256_set1_epi8('='), KNOT32(maskA), dataA);
					_mm256_storeu_si256((__m256i*)p, dataA);
					p[32] = _mm_extract_epi8(dataTop, 15);
					p += outputBytesA;
					
					dataTop = _mm256_extracti128_si256(dataB, 1);
					dataB = _mm256_mask_expand_epi8(_mm256_set1_epi8('='), KNOT32(maskB), dataB);
					_mm256_storeu_si256((__m256i*)p, dataB);
					p[32] = _mm_extract_epi8(dataTop, 15);
					p += maskBitsB;
				} else
# endif
				{
					__m256i dataSwapped = _mm256_permute4x64_epi64(dataA, _MM_SHUFFLE(1,0,3,2));
					dataA = _mm256_mask_alignr_epi8(dataA, (uint32_t)(-(int32_t)maskA), dataA, dataSwapped, 15);
					dataA = _mm256_ternarylogic_epi32(dataA, cmpA, _mm256_set1_epi8('='), 0xb8); // (data & ~cmp) | (cmp & '=')
					_mm256_storeu_si256((__m256i*)p, dataA);
					p[32] = _mm_extract_epi8(_mm256_castsi256_si128(dataSwapped), 15);
					p += outputBytesA;
					
					dataSwapped = _mm256_permute4x64_epi64(dataB, _MM_SHUFFLE(1,0,3,2));
					dataB = _mm256_mask_alignr_epi8(dataB, (uint32_t)(-(int32_t)maskB), dataB, dataSwapped, 15);
					dataB = _mm256_ternarylogic_epi32(dataB, cmpB, _mm256_set1_epi8('='), 0xb8);
					_mm256_storeu_si256((__m256i*)p, dataB);
					p[32] = _mm_extract_epi8(_mm256_castsi256_si128(dataSwapped), 15);
					p += maskBitsB;
				}
			} else
#endif
			{
				bitIndexA = _lzcnt_u32(maskA);
				bitIndexB = _lzcnt_u32(maskB);
				__m256i mergeMaskA = _mm256_load_si256((const __m256i*)(lookupsAVX2->expandMergemix + bitIndexA*2*YMM_SIZE));
				__m256i mergeMaskB = _mm256_load_si256((const __m256i*)(lookupsAVX2->expandMergemix + bitIndexB*2*YMM_SIZE));
				
#if defined(__tune_bdver4__) || defined(__tune_znver1__)
				// avoid slower 32-byte crossing loads on Zen1
				__m256i dataAShifted = _mm256_alignr_epi8(
					dataA,
					_mm256_inserti128_si256(dataA, _mm256_castsi256_si128(dataA), 1),
					15
				);
				__m256i dataBShifted = _mm256_alignr_epi8(
					dataB,
					_mm256_inserti128_si256(dataB, _mm256_castsi256_si128(dataB), 1),
					15
				);
#else
				__m256i dataAShifted = _mm256_loadu_si256((__m256i *)(es + i - YMM_SIZE*2 - 1));
				__m256i dataBShifted = _mm256_loadu_si256((__m256i *)(es + i - YMM_SIZE - 1));
#endif
				dataA = _mm256_andnot_si256(cmpA, dataA); // clear space for '=' char
				dataA = _mm256_blendv_epi8(dataAShifted, dataA, mergeMaskA);
				dataA = _mm256_add_epi8(dataA, _mm256_load_si256((const __m256i*)(lookupsAVX2->expandMergemix + bitIndexA*2*YMM_SIZE) + 1));
				_mm256_storeu_si256((__m256i*)p, dataA);
				p[YMM_SIZE] = es[i-1-YMM_SIZE] + 42 + (64 & (maskA>>(YMM_SIZE-1-6)));
				p += outputBytesA;
				
				dataB = _mm256_andnot_si256(cmpB, dataB);
				dataB = _mm256_blendv_epi8(dataBShifted, dataB, mergeMaskB);
				dataB = _mm256_add_epi8(dataB, _mm256_load_si256((const __m256i*)(lookupsAVX2->expandMergemix + bitIndexB*2*YMM_SIZE) + 1));
				_mm256_storeu_si256((__m256i*)p, dataB);
				p[YMM_SIZE] = es[i-1] + 42 + (64 & (maskB>>(YMM_SIZE-1-6)));
				p += maskBitsB;
			}
			col += outputBytesA + maskBitsB;
			
			if(col >= 0) {
				_encode_loop_branch_fast_eol:
				if(HEDLEY_UNLIKELY(col > (intptr_t)maskBitsB)) {
					if(use_isa >= ISA_LEVEL_AVX3)
						bitIndexA = _lzcnt_u32(maskA);
					bitIndexA += 1 + maskBitsB;
					
					i += maskBitsB - YMM_SIZE;
					if(HEDLEY_UNLIKELY(col == (intptr_t)bitIndexA)) {
						// this is an escape character, so line will need to overflow
						p--;
					} else {
						i += (col > (intptr_t)bitIndexA);
					}
				} else {
					if(use_isa >= ISA_LEVEL_AVX3)
						bitIndexB = _lzcnt_u32(maskB);
					bitIndexB++;
					
					if(HEDLEY_UNLIKELY(col == (intptr_t)bitIndexB)) {
						p--;
					} else {
						i += (col > (intptr_t)bitIndexB);
					}
				}
				i -= col;
				p -= col;
				
				_encode_eol_handle_pre:
				uint32_t eolChar = (use_isa >= ISA_LEVEL_VBMI2 ? lookupsVBMI2->eolLastChar[es[i]] : lookupsAVX2->eolLastChar[es[i]]);
				memcpy(p, &eolChar, sizeof(eolChar));
				p += 3 + (uintptr_t)(eolChar>>27);
				col = lineSizeOffset;
				
				if(HEDLEY_UNLIKELY(i >= 0)) { // this isn't really a proper check - it's only needed to support short lines; basically, if the line is too short, `i` never gets checked, so we need one somewhere
					i++;
					break;
				}
				
				dataA = _mm256_loadu_si256((__m256i *)(es + i + 1));
				dataB = _mm256_loadu_si256((__m256i *)(es + i + 1) + 1);
				i += YMM_SIZE*2 + 1;
				// search for special chars
				cmpA = _mm256_cmpeq_epi8(
					_mm256_shuffle_epi8(_mm256_set_epi8(
						'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42,
						'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42
					), _mm256_adds_epi8(
						_mm256_abs_epi8(dataA), _mm256_set_epi64x(0, 0, 0, 88)
					)),
					dataA
				);
				cmpB = _mm256_cmpeq_epi8(
					_mm256_shuffle_epi8(_mm256_set_epi8(
						'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42,
						'\0'-42,-42,'\r'-42,'.'-42,'='-42,'\0'-42,'\t'-42,'\n'-42,-42,-42,'\r'-42,-42,'='-42,' '-42,-42,'\n'-42
					), _mm256_abs_epi8(dataB)),
					dataB
				);
				
				// duplicate some code from above to reduce jumping a little
#if defined(__AVX512VL__)
				if(use_isa >= ISA_LEVEL_AVX3) {
					dataA = _mm256_add_epi8(dataA, _mm256_set1_epi8(42));
					dataA = _mm256_ternarylogic_epi32(dataA, cmpA, _mm256_set1_epi8(64), 0xf8); // data | (cmp & 64)
					dataB = _mm256_add_epi8(dataB, _mm256_set1_epi8(42));
					dataB = _mm256_ternarylogic_epi32(dataB, cmpB, _mm256_set1_epi8(64), 0xf8); // data | (cmp & 64)
				}
#endif
				
				maskA = (uint32_t)_mm256_movemask_epi8(cmpA);
				maskB = (uint32_t)_mm256_movemask_epi8(cmpB);
				maskBitsA = popcnt32(maskA);
				maskBitsB = popcnt32(maskB);
				outputBytesA = maskBitsA + YMM_SIZE;
				if (LIKELIHOOD(0.170, (maskBitsA|maskBitsB) > 1))
					goto _encode_loop_branch_slow;
				
				
				//goto _encode_loop_branch_fast;
				// duplicating the code, instead of using the goto above, seems to fix a performance regression in GCC
				maskBitsB += YMM_SIZE;
#if defined(__AVX512VL__) && defined(__AVX512BW__)
				if(use_isa >= ISA_LEVEL_AVX3) {
# if defined(__AVX512VBMI2__)
					if(use_isa >= ISA_LEVEL_VBMI2) {
						__m128i dataTop = _mm256_extracti128_si256(dataA, 1);
						dataA = _mm256_mask_expand_epi8(_mm256_set1_epi8('='), KNOT32(maskA), dataA);
						_mm256_storeu_si256((__m256i*)p, dataA);
						p[32] = _mm_extract_epi8(dataTop, 15);
						p += outputBytesA;
						
						dataTop = _mm256_extracti128_si256(dataB, 1);
						dataB = _mm256_mask_expand_epi8(_mm256_set1_epi8('='), KNOT32(maskB), dataB);
						_mm256_storeu_si256((__m256i*)p, dataB);
						p[32] = _mm_extract_epi8(dataTop, 15);
						p += maskBitsB;
					} else
# endif
					{
						__m256i dataSwapped = _mm256_permute4x64_epi64(dataA, _MM_SHUFFLE(1,0,3,2));
						dataA = _mm256_mask_alignr_epi8(dataA, (uint32_t)(-(int32_t)maskA), dataA, dataSwapped, 15);
						dataA = _mm256_ternarylogic_epi32(dataA, cmpA, _mm256_set1_epi8('='), 0xb8); // (data & ~cmp) | (cmp & '=')
						_mm256_storeu_si256((__m256i*)p, dataA);
						p[32] = _mm_extract_epi8(_mm256_castsi256_si128(dataSwapped), 15);
						p += outputBytesA;
						
						dataSwapped = _mm256_permute4x64_epi64(dataB, _MM_SHUFFLE(1,0,3,2));
						dataB = _mm256_mask_alignr_epi8(dataB, (uint32_t)(-(int32_t)maskB), dataB, dataSwapped, 15);
						dataB = _mm256_ternarylogic_epi32(dataB, cmpB, _mm256_set1_epi8('='), 0xb8);
						_mm256_storeu_si256((__m256i*)p, dataB);
						p[32] = _mm_extract_epi8(_mm256_castsi256_si128(dataSwapped), 15);
						p += maskBitsB;
					}
				} else
#endif
				{
					bitIndexA = _lzcnt_u32(maskA);
					bitIndexB = _lzcnt_u32(maskB);
					__m256i mergeMaskA = _mm256_load_si256((const __m256i*)(lookupsAVX2->expandMergemix + bitIndexA*2*YMM_SIZE));
					__m256i mergeMaskB = _mm256_load_si256((const __m256i*)(lookupsAVX2->expandMergemix + bitIndexB*2*YMM_SIZE));
					
#if defined(__tune_bdver4__) || defined(__tune_znver1__)
					// avoid slower 32-byte crossing loads on Zen1
					__m256i dataAShifted = _mm256_alignr_epi8(
						dataA,
						_mm256_inserti128_si256(dataA, _mm256_castsi256_si128(dataA), 1),
						15
					);
					__m256i dataBShifted = _mm256_alignr_epi8(
						dataB,
						_mm256_inserti128_si256(dataB, _mm256_castsi256_si128(dataB), 1),
						15
					);
#else
					__m256i dataAShifted = _mm256_loadu_si256((__m256i *)(es + i - YMM_SIZE*2 - 1));
					__m256i dataBShifted = _mm256_loadu_si256((__m256i *)(es + i - YMM_SIZE - 1));
#endif
					dataA = _mm256_andnot_si256(cmpA, dataA); // clear space for '=' char
					dataA = _mm256_blendv_epi8(dataAShifted, dataA, mergeMaskA);
					dataA = _mm256_add_epi8(dataA, _mm256_load_si256((const __m256i*)(lookupsAVX2->expandMergemix + bitIndexA*2*YMM_SIZE) + 1));
					_mm256_storeu_si256((__m256i*)p, dataA);
					p[YMM_SIZE] = es[i-1-YMM_SIZE] + 42 + (64 & (maskA>>(YMM_SIZE-1-6)));
					p += outputBytesA;
					
					dataB = _mm256_andnot_si256(cmpB, dataB);
					dataB = _mm256_blendv_epi8(dataBShifted, dataB, mergeMaskB);
					dataB = _mm256_add_epi8(dataB, _mm256_load_si256((const __m256i*)(lookupsAVX2->expandMergemix + bitIndexB*2*YMM_SIZE) + 1));
					_mm256_storeu_si256((__m256i*)p, dataB);
					p[YMM_SIZE] = es[i-1] + 42 + (64 & (maskB>>(YMM_SIZE-1-6)));
					p += maskBitsB;
				}
				col += outputBytesA + maskBitsB;
				
				if(col >= 0)
					goto _encode_loop_branch_fast_eol;
			}
		}
	} while(i < 0);
	
	_mm256_zeroupper();
	
	*colOffset = (int)(col + line_size -1);
	dest = p;
	len = -(i - INPUT_OFFSET);
}
} // namespace

#endif
