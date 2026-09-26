
#ifdef __AVX2__

// GCC (ver 6-10(dev)) fails to optimize pure C version of mask testing, but has this intrinsic; Clang >= 7 optimizes C version fine; functions added in Clang 8
#if (defined(__GNUC__) && __GNUC__ >= 7) || (defined(_MSC_VER) && _MSC_VER >= 1924)
# define KORTEST32(a, b) !_kortestz_mask32_u8((a), (b))
# define KAND32(a, b) _kand_mask32((a), (b))
# define KOR32(a, b) _kor_mask32((a), (b))
#else
# define KORTEST32(a, b) ((a) | (b))
# define KAND32(a, b) ((a) & (b))
# define KOR32(a, b) ((a) | (b))
#endif

#pragma pack(16)
static struct {
	/*align16*/ struct { char bytes[16]; } compact[32768];
} * HEDLEY_RESTRICT lookups;
#pragma pack()


static HEDLEY_ALWAYS_INLINE __m256i force_align_read_256(const void* p) {
#ifdef _MSC_VER
	// MSVC complains about casting away volatile
	return *(__m256i *)(p);
#else
	return *(volatile __m256i *)(p);
#endif
}

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

#if defined(__tune_icelake_client__) || defined(__tune_icelake_server__) || defined(__tune_tigerlake__) || defined(__tune_rocketlake__) || defined(__tune_alderlake__) || defined(__tune_sapphirerapids__)
# define COMPRESS_STORE _mm256_mask_compressstoreu_epi8
#else
// avoid uCode on Zen4
# define COMPRESS_STORE(dst, mask, vec) _mm256_storeu_si256((__m256i*)(dst), _mm256_maskz_compress_epi8(mask, vec))
#endif

namespace RapidYenc {

template<bool isRaw, bool searchEnd, enum YEncDecIsaLevel use_isa>
HEDLEY_ALWAYS_INLINE void do_decode_avx2(const uint8_t* src, long& len, unsigned char*& p, unsigned char& _escFirst, uint16_t& _nextMask) {
	HEDLEY_ASSUME(_escFirst == 0 || _escFirst == 1);
	HEDLEY_ASSUME(_nextMask == 0 || _nextMask == 1 || _nextMask == 2);
	uintptr_t escFirst = _escFirst;
	__m256i yencOffset = escFirst ? _mm256_set_epi8(
		-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,
		-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42-64
	) : _mm256_set1_epi8(-42);
	__m256i minMask = _mm256_set1_epi8('.');
	if(_nextMask && isRaw) {
		minMask = _mm256_set_epi8(
			'.','.','.','.','.','.','.','.','.','.','.','.','.','.','.','.',
			'.','.','.','.','.','.','.','.','.','.','.','.','.','.',_nextMask==2?0:'.',_nextMask==1?0:'.'
		);
	}
	
	decoder_set_nextMask<isRaw>(src, len, _nextMask); // set this before the loop because we can't check src after it's been overwritten
	
	// for some reason, MSVC Win32 seems to crash when trying to compile _mm256_mask_cmpeq_epi8_mask
	// the crash can be fixed by switching the order of the last two arguments, but it seems to generate wrong code
	// so just disable the optimisation as it seems to be problematic there
#if defined(__AVX512VL__) && defined(__AVX512BW__)
# if defined(_MSC_VER) && !defined(PLATFORM_AMD64) && !defined(__clang__)
	const bool useAVX3MaskCmp = false;
# else
	const bool useAVX3MaskCmp = (use_isa >= ISA_LEVEL_AVX3);
# endif
#endif
	intptr_t i;
	for(i = -len; i; i += sizeof(__m256i)*2) {
		__m256i oDataA = _mm256_load_si256((__m256i *)(src+i));
		__m256i oDataB = _mm256_load_si256((__m256i *)(src+i) + 1);
		
		// search for special chars
		__m256i cmpA = _mm256_cmpeq_epi8(oDataA, _mm256_shuffle_epi8(
			_mm256_set_epi8(
				-1,'=','\r',-1,-1,'\n',-1,-1,-1,-1,-1,-1,-1,-1,-1,'.',
				-1,'=','\r',-1,-1,'\n',-1,-1,-1,-1,-1,-1,-1,-1,-1,'.'
			),
			_mm256_min_epu8(oDataA, minMask)
		));
		__m256i cmpB = _mm256_cmpeq_epi8(oDataB, _mm256_shuffle_epi8(
			_mm256_set_epi8(
				-1,'=','\r',-1,-1,'\n',-1,-1,-1,-1,-1,-1,-1,-1,-1,'.',
				-1,'=','\r',-1,-1,'\n',-1,-1,-1,-1,-1,-1,-1,-1,-1,'.'
			),
			_mm256_min_epu8(oDataB, _mm256_set1_epi8('.'))
		));
		
		// TODO: can OR the vectors together to save generating a mask, but may not be worth it
		uint64_t mask = (uint32_t)_mm256_movemask_epi8(cmpB); // not the most accurate mask if we have invalid sequences; we fix this up later
		mask = (mask << 32) | (uint32_t)_mm256_movemask_epi8(cmpA);
		__m256i dataA, dataB;
		if(use_isa >= ISA_LEVEL_AVX3)
			dataA = _mm256_add_epi8(oDataA, yencOffset);
		
		if (mask != 0) {
			__m256i cmpEqA = _mm256_cmpeq_epi8(oDataA, _mm256_set1_epi8('='));
			__m256i cmpEqB = _mm256_cmpeq_epi8(oDataB, _mm256_set1_epi8('='));
			uint64_t maskEq = (uint32_t)_mm256_movemask_epi8(cmpEqB);
			maskEq = (maskEq << 32) | (uint32_t)_mm256_movemask_epi8(cmpEqA);
			
			// handle \r\n. sequences
			// RFC3977 requires the first dot on a line to be stripped, due to dot-stuffing
			if((isRaw || searchEnd) && LIKELIHOOD(0.45, mask != maskEq)) {
#if 0
				// prefer shuffling data over unaligned loads on Zen (unknown if worth it on Zen2/Excavator)
				// unfortunately not beneficial, probably due to available register pressure; this is left here because it could be beneficial if we figure out how to use fewer registers
				__m256i nextDataA, nextDataB;
				if(searchEnd) {
					nextDataA = _mm256_inserti128_si256(
						_mm256_castsi128_si256(_mm256_extracti128_si256(oDataA, 1)),
						_mm256_castsi256_si128(oDataB),
						1
					);
					nextDataB = _mm256_inserti128_si256(
						_mm256_castsi128_si256(_mm256_extracti128_si256(oDataB, 1)),
						_mm_load_si128((__m128i*)(src+i+sizeof(__m256i)*2)),
						1
					);
				}
# define SHIFT_DATA_A(offs) (searchEnd ? _mm256_alignr_epi8(nextDataA, oDataA, offs) : _mm256_loadu_si256((__m256i *)(src+i+offs)))
# define SHIFT_DATA_B(offs) (searchEnd ? _mm256_alignr_epi8(nextDataB, oDataB, offs) : _mm256_loadu_si256((__m256i *)(src+i+offs) + 1))
#else
# define SHIFT_DATA_A(offs) _mm256_loadu_si256((__m256i *)(src+i+offs))
# define SHIFT_DATA_B(offs) _mm256_loadu_si256((__m256i *)(src+i+offs) + 1)
#endif
				__m256i tmpData2A = SHIFT_DATA_A(2);
				__m256i tmpData2B = SHIFT_DATA_B(2);
				__m256i match2EqA, match2EqB;
#if defined(__AVX512VL__) && defined(__AVX512BW__)
				__mmask32 match2EqMaskA, match2EqMaskB;
				__mmask32 match0CrMaskA, match0CrMaskB;
				__mmask32 match2CrXDtMaskA, match2CrXDtMaskB;
				if(useAVX3MaskCmp && searchEnd) {
					match2EqMaskA = _mm256_cmpeq_epi8_mask(_mm256_set1_epi8('='), tmpData2A);
					match2EqMaskB = _mm256_cmpeq_epi8_mask(_mm256_set1_epi8('='), tmpData2B);
				} else
#endif
				if(searchEnd) {
					match2EqA = _mm256_cmpeq_epi8(_mm256_set1_epi8('='), tmpData2A);
					match2EqB = _mm256_cmpeq_epi8(_mm256_set1_epi8('='), tmpData2B);
				}
				
				int partialKillDotFound;
				__m256i match2CrXDtA, match2CrXDtB;
				if(isRaw) {
					// find patterns of \r_.
					
#if defined(__AVX512VL__) && defined(__AVX512BW__)
					if(useAVX3MaskCmp) {
						match0CrMaskA = _mm256_cmpeq_epi8_mask(oDataA, _mm256_set1_epi8('\r'));
						match0CrMaskB = _mm256_cmpeq_epi8_mask(oDataB, _mm256_set1_epi8('\r'));
						match2CrXDtMaskA = _mm256_mask_cmpeq_epi8_mask(match0CrMaskA, tmpData2A, _mm256_set1_epi8('.'));
						match2CrXDtMaskB = _mm256_mask_cmpeq_epi8_mask(match0CrMaskB, tmpData2B, _mm256_set1_epi8('.'));
						partialKillDotFound = KORTEST32(match2CrXDtMaskA, match2CrXDtMaskB);
					} else
#endif
					{
						match2CrXDtA = _mm256_and_si256(
							_mm256_cmpeq_epi8(oDataA, _mm256_set1_epi8('\r')),
							_mm256_cmpeq_epi8(tmpData2A, _mm256_set1_epi8('.'))
						);
						match2CrXDtB = _mm256_and_si256(
							_mm256_cmpeq_epi8(oDataB, _mm256_set1_epi8('\r')),
							_mm256_cmpeq_epi8(tmpData2B, _mm256_set1_epi8('.'))
						);
						partialKillDotFound = _mm256_movemask_epi8(_mm256_or_si256(
							match2CrXDtA, match2CrXDtB
						));
					}
				}
				
				if(isRaw && LIKELIHOOD(0.002, partialKillDotFound)) {
					// merge matches for \r\n.
					__m256i match2NlDotA, match1NlA;
					__m256i match2NlDotB, match1NlB;
#if defined(__AVX512VL__) && defined(__AVX512BW__)
					__mmask32 match1NlMaskA, match1NlMaskB;
					__mmask32 match2NlDotMaskA, match2NlDotMaskB;
					if(useAVX3MaskCmp) {
						match1NlMaskA = _mm256_mask_cmpeq_epi8_mask(
							match0CrMaskA,
							_mm256_set1_epi8('\n'),
							SHIFT_DATA_A(1)
						);
						match1NlMaskB = _mm256_mask_cmpeq_epi8_mask(
							match0CrMaskB,
							_mm256_set1_epi8('\n'),
							SHIFT_DATA_B(1)
						);
						match2NlDotMaskA = KAND32(match2CrXDtMaskA, match1NlMaskA);
						match2NlDotMaskB = KAND32(match2CrXDtMaskB, match1NlMaskB);
					} else
#endif
					{
						__m256i match1LfA = _mm256_cmpeq_epi8(
							_mm256_set1_epi8('\n'),
							SHIFT_DATA_A(1)
						);
						__m256i match1LfB = _mm256_cmpeq_epi8(
							_mm256_set1_epi8('\n'),
							SHIFT_DATA_B(1)
						);
						// force re-computing these to avoid register spills elsewhere
						match1NlA = _mm256_and_si256(match1LfA, _mm256_cmpeq_epi8(force_align_read_256(src+i), _mm256_set1_epi8('\r')));
						match1NlB = _mm256_and_si256(match1LfB, _mm256_cmpeq_epi8(force_align_read_256(src+i + sizeof(__m256i)), _mm256_set1_epi8('\r')));
						match2NlDotA = _mm256_and_si256(match2CrXDtA, match1NlA);
						match2NlDotB = _mm256_and_si256(match2CrXDtB, match1NlB);
					}
					if(searchEnd) {
						__m256i tmpData4A;
#if defined(__AVX512VL__) && defined(PLATFORM_AMD64)
						if(use_isa >= ISA_LEVEL_AVX3)
							// AVX512 with 32 registers shouldn't have any issue with holding onto oData* in registers
							tmpData4A = _mm256_alignr_epi32(oDataB, oDataA, 1);
						else
#endif
							tmpData4A = SHIFT_DATA_A(4);
						__m256i tmpData4B = SHIFT_DATA_B(4);
						// match instances of \r\n.\r\n and \r\n.=y
						__m256i match3CrA = _mm256_cmpeq_epi8(
							_mm256_set1_epi8('\r'),
							SHIFT_DATA_A(3)
						);
						__m256i match3CrB = _mm256_cmpeq_epi8(
							_mm256_set1_epi8('\r'),
							SHIFT_DATA_B(3)
						);
						__m256i match4LfA = _mm256_cmpeq_epi8(tmpData4A, _mm256_set1_epi8('\n'));
						__m256i match4LfB = _mm256_cmpeq_epi8(tmpData4B, _mm256_set1_epi8('\n'));
						__m256i match4EqYA = _mm256_cmpeq_epi16(tmpData4A, _mm256_set1_epi16(0x793d)); // =y
						__m256i match4EqYB = _mm256_cmpeq_epi16(tmpData4B, _mm256_set1_epi16(0x793d)); // =y
						
						int matchEnd;
#if defined(__AVX512VL__) && defined(__AVX512BW__)
						if(useAVX3MaskCmp) {
							__mmask32 match3EqYMaskA = _mm256_mask_cmpeq_epi8_mask(
								match2EqMaskA,
								_mm256_set1_epi8('y'),
								SHIFT_DATA_A(3)
							);
							__mmask32 match3EqYMaskB = _mm256_mask_cmpeq_epi8_mask(
								match2EqMaskB,
								_mm256_set1_epi8('y'),
								SHIFT_DATA_B(3)
							);
							__m256i match34EqYA, match34EqYB;
# ifdef __AVX512VBMI2__
							if(use_isa >= ISA_LEVEL_VBMI2) {
								match34EqYA = _mm256_shrdi_epi16(_mm256_movm_epi8(match3EqYMaskA), match4EqYA, 8);
								match34EqYB = _mm256_shrdi_epi16(_mm256_movm_epi8(match3EqYMaskB), match4EqYB, 8);
							} else
# endif
							{
								// (match4EqY & 0xff00) | (match3EqY >> 8)
								match34EqYA = _mm256_mask_blend_epi8(match3EqYMaskA>>1, _mm256_and_si256(match4EqYA, _mm256_set1_epi16(-0xff)), _mm256_set1_epi8(-1));
								match34EqYB = _mm256_mask_blend_epi8(match3EqYMaskB>>1, _mm256_and_si256(match4EqYB, _mm256_set1_epi16(-0xff)), _mm256_set1_epi8(-1));
							}
							// merge \r\n and =y matches for tmpData4
							__m256i match4EndA = _mm256_ternarylogic_epi32(match34EqYA, match3CrA, match4LfA, 0xF8); // (match3Cr & match4Lf) | match34EqY
							__m256i match4EndB = _mm256_ternarylogic_epi32(match34EqYB, match3CrB, match4LfB, 0xF8);
							// merge with \r\n. and combine
							matchEnd = KORTEST32(
								KOR32(
									_mm256_mask_test_epi8_mask(match2NlDotMaskA, match4EndA, match4EndA),
									KAND32(match3EqYMaskA, match1NlMaskA)
								),
								KOR32(
									_mm256_mask_test_epi8_mask(match2NlDotMaskB, match4EndB, match4EndB),
									KAND32(match3EqYMaskB, match1NlMaskB)
								)
							);
						} else
#endif
						{
							__m256i match3EqYA = _mm256_and_si256(match2EqA, _mm256_cmpeq_epi8(
								_mm256_set1_epi8('y'),
								SHIFT_DATA_A(3)
							));
							__m256i match3EqYB = _mm256_and_si256(match2EqB, _mm256_cmpeq_epi8(
								_mm256_set1_epi8('y'),
								SHIFT_DATA_B(3)
							));
							match4EqYA = _mm256_slli_epi16(match4EqYA, 8); // TODO: also consider using PBLENDVB here with shifted match3EqY instead
							match4EqYB = _mm256_slli_epi16(match4EqYB, 8);
							// merge \r\n and =y matches for tmpData4
							__m256i match4EndA = _mm256_or_si256(
								_mm256_and_si256(match3CrA, match4LfA),
								_mm256_or_si256(match4EqYA, _mm256_srli_epi16(match3EqYA, 8)) // _mm256_srli_si256 by 1 also works
							);
							__m256i match4EndB = _mm256_or_si256(
								_mm256_and_si256(match3CrB, match4LfB),
								_mm256_or_si256(match4EqYB, _mm256_srli_epi16(match3EqYB, 8))
							);
							// merge with \r\n.
							match4EndA = _mm256_and_si256(match4EndA, match2NlDotA);
							match4EndB = _mm256_and_si256(match4EndB, match2NlDotB);
							// match \r\n=y
							__m256i match3EndA = _mm256_and_si256(match3EqYA, match1NlA);
							__m256i match3EndB = _mm256_and_si256(match3EqYB, match1NlB);
							// combine match sequences
							matchEnd = _mm256_movemask_epi8(_mm256_or_si256(
								_mm256_or_si256(match4EndA, match3EndA),
								_mm256_or_si256(match4EndB, match3EndB)
							));
						}
						if(LIKELIHOOD(0.002, matchEnd)) {
							// terminator found
							// there's probably faster ways to do this, but reverting to scalar code should be good enough
							len += (long)i;
							_nextMask = decoder_set_nextMask<isRaw>(src+i, mask);
							break;
						}
					}
#if defined(__AVX512VL__) && defined(__AVX512BW__)
					if(useAVX3MaskCmp) {
						mask |= (uint64_t)match2NlDotMaskA << 2;
						mask |= (uint64_t)match2NlDotMaskB << 34;
						minMask = _mm256_maskz_mov_epi8(~(match2NlDotMaskB>>30), _mm256_set1_epi8('.'));
					} else
#endif
					{
						mask |= (uint64_t)((uint32_t)_mm256_movemask_epi8(match2NlDotA)) << 2;
						mask |= (uint64_t)((uint32_t)_mm256_movemask_epi8(match2NlDotB)) << 34;
						match2NlDotB = zext128_256(_mm_srli_si128(_mm256_extracti128_si256(match2NlDotB, 1), 14));
						minMask = _mm256_subs_epu8(_mm256_set1_epi8('.'), match2NlDotB);
					}
				}
				else if(searchEnd) {
					bool partialEndFound;
					__m256i match3EqYA, match3EqYB;
#if defined(__AVX512VL__) && defined(__AVX512BW__)
					__mmask32 match3EqYMaskA, match3EqYMaskB;
					if(useAVX3MaskCmp) {
						match3EqYMaskA = _mm256_mask_cmpeq_epi8_mask(
							match2EqMaskA,
							_mm256_set1_epi8('y'),
							SHIFT_DATA_A(3)
						);
						match3EqYMaskB = _mm256_mask_cmpeq_epi8_mask(
							match2EqMaskB,
							_mm256_set1_epi8('y'),
							SHIFT_DATA_B(3)
						);
						partialEndFound = KORTEST32(match3EqYMaskA, match3EqYMaskB);
					} else
#endif
					{
						__m256i match3YA = _mm256_cmpeq_epi8(
							_mm256_set1_epi8('y'),
							SHIFT_DATA_A(3)
						);
						__m256i match3YB = _mm256_cmpeq_epi8(
							_mm256_set1_epi8('y'),
							SHIFT_DATA_B(3)
						);
						match3EqYA = _mm256_and_si256(match2EqA, match3YA);
						match3EqYB = _mm256_and_si256(match2EqB, match3YB);
						partialEndFound = _mm256_movemask_epi8(_mm256_or_si256(match3EqYA, match3EqYB));
					}
					if(LIKELIHOOD(0.002, partialEndFound)) {
						bool endFound;
#if defined(__AVX512VL__) && defined(__AVX512BW__)
						if(useAVX3MaskCmp) {
							__mmask32 match3LfEqYMaskA = _mm256_mask_cmpeq_epi8_mask(
								match3EqYMaskA,
								_mm256_set1_epi8('\n'),
								SHIFT_DATA_A(1)
							);
							__mmask32 match3LfEqYMaskB = _mm256_mask_cmpeq_epi8_mask(
								match3EqYMaskB,
								_mm256_set1_epi8('\n'),
								SHIFT_DATA_B(1)
							);
							
							endFound = KORTEST32(
								_mm256_mask_cmpeq_epi8_mask(match3LfEqYMaskA, oDataA, _mm256_set1_epi8('\r')),
								_mm256_mask_cmpeq_epi8_mask(match3LfEqYMaskB, oDataB, _mm256_set1_epi8('\r'))
							);
						} else
#endif
						{
							__m256i match1LfA = _mm256_cmpeq_epi8(
								_mm256_set1_epi8('\n'),
								SHIFT_DATA_A(1)
							);
							__m256i match1LfB = _mm256_cmpeq_epi8(
								_mm256_set1_epi8('\n'),
								SHIFT_DATA_B(1)
							);
							endFound = _mm256_movemask_epi8(_mm256_or_si256(
								_mm256_and_si256(
									match3EqYA,
									_mm256_and_si256(match1LfA, _mm256_cmpeq_epi8(force_align_read_256(src+i), _mm256_set1_epi8('\r')))
								),
								_mm256_and_si256(
									match3EqYB,
									_mm256_and_si256(match1LfB, _mm256_cmpeq_epi8(force_align_read_256(src+i + sizeof(__m256i)), _mm256_set1_epi8('\r')))
								)
							));
						}
						if(endFound) {
							len += (long)i;
							_nextMask = decoder_set_nextMask<isRaw>(src+i, mask);
							break;
						}
					}
					if(isRaw) minMask = _mm256_set1_epi8('.');
				}
				else if(isRaw) // no \r_. found
					minMask = _mm256_set1_epi8('.');
			}
#undef SHIFT_DATA_A
#undef SHIFT_DATA_B
			
			if(use_isa >= ISA_LEVEL_AVX3)
				dataB = _mm256_add_epi8(oDataB, _mm256_set1_epi8(-42));
			
			uint64_t maskEqShift1 = (maskEq << 1) + escFirst;
			if(LIKELIHOOD(0.0001, (mask & maskEqShift1) != 0)) {
				maskEq = fix_eqMask<uint64_t>(maskEq, maskEqShift1);
				mask &= ~(uint64_t)escFirst;
				escFirst = maskEq>>63;
				// next, eliminate anything following a `=` from the special char mask; this eliminates cases of `=\r` so that they aren't removed
				maskEq <<= 1;
				mask &= ~maskEq;
				
				// unescape chars following `=`
#if defined(__AVX512VL__) && defined(__AVX512BW__)
				if(use_isa >= ISA_LEVEL_AVX3) {
					// GCC < 7 seems to generate rubbish assembly for this
					dataA = _mm256_mask_add_epi8(
						dataA,
						(__mmask32)maskEq,
						dataA,
						_mm256_set1_epi8(-64)
					);
					dataB = _mm256_mask_add_epi8(
						dataB,
						(__mmask32)(maskEq>>32),
						dataB,
						_mm256_set1_epi8(-64)
					);
				} else
#endif
				{
					// convert maskEq into vector form (i.e. reverse pmovmskb)
#ifdef PLATFORM_AMD64
					__m256i vMaskEq = _mm256_broadcastq_epi64(_mm_cvtsi64_si128(maskEq));
#else
					__m256i vMaskEq = _mm256_permute4x64_epi64(_mm256_insert_epi32(
						_mm256_set_epi32(0,0,0,0, 0,0,0, maskEq & 0xffffffff),
						maskEq >> 32,
						1
					), 0);
#endif
					__m256i vMaskEqA = _mm256_shuffle_epi8(vMaskEq, _mm256_set_epi32(
						0x03030303, 0x03030303, 0x02020202, 0x02020202,
						0x01010101, 0x01010101, 0x00000000, 0x00000000
					));
					__m256i vMaskEqB = _mm256_shuffle_epi8(vMaskEq, _mm256_set_epi32(
						0x07070707, 0x07070707, 0x06060606, 0x06060606,
						0x05050505, 0x05050505, 0x04040404, 0x04040404
					));
					vMaskEqA = _mm256_cmpeq_epi8(
						_mm256_and_si256(vMaskEqA, _mm256_set1_epi64x(0x8040201008040201ULL)),
						_mm256_set1_epi64x(0x8040201008040201ULL)
					);
					vMaskEqB = _mm256_cmpeq_epi8(
						_mm256_and_si256(vMaskEqB, _mm256_set1_epi64x(0x8040201008040201ULL)),
						_mm256_set1_epi64x(0x8040201008040201ULL)
					);
					dataA = _mm256_add_epi8(oDataA, _mm256_blendv_epi8(yencOffset, _mm256_set1_epi8(-42-64), vMaskEqA));
					dataB = _mm256_add_epi8(oDataB, _mm256_blendv_epi8(_mm256_set1_epi8(-42), _mm256_set1_epi8(-42-64), vMaskEqB));
				}
			} else {
				escFirst = (maskEq >> 63);
				
#if defined(__AVX512VL__) && defined(__AVX512BW__)
				if(use_isa >= ISA_LEVEL_AVX3) {
					dataA = _mm256_mask_add_epi8(
						dataA,
						(__mmask32)(maskEq << 1),
						dataA,
						_mm256_set1_epi8(-64)
					);
					dataB = _mm256_mask_add_epi8(
						dataB,
						(__mmask32)(maskEq >> 31),
						dataB,
						_mm256_set1_epi8(-64)
					);
				} else
#endif
				{
					// << 1 byte
					cmpEqA = _mm256_alignr_epi8(cmpEqA, _mm256_inserti128_si256(
						_mm256_set1_epi8('='), _mm256_castsi256_si128(cmpEqA), 1
					), 15);
					cmpEqB = _mm256_cmpeq_epi8(_mm256_set1_epi8('='), _mm256_loadu_si256((__m256i *)(src+i-1) + 1));
					dataA = _mm256_add_epi8(
						oDataA,
						_mm256_blendv_epi8(
							yencOffset,
							_mm256_set1_epi8(-42-64),
							cmpEqA
						)
					);
					dataB = _mm256_add_epi8(
						oDataB,
						_mm256_blendv_epi8(
							_mm256_set1_epi8(-42),
							_mm256_set1_epi8(-42-64),
							cmpEqB
						)
					);
				}
			}
			// subtract 64 from first element if escFirst == 1
#if defined(__AVX512VL__) && defined(__AVX512BW__)
			if(use_isa >= ISA_LEVEL_AVX3) {
				yencOffset = _mm256_mask_add_epi8(_mm256_set1_epi8(-42), (__mmask32)escFirst, _mm256_set1_epi8(-42), _mm256_set1_epi8(-64));
			} else
#endif
			{
				yencOffset = _mm256_xor_si256(_mm256_set1_epi8(-42), zext128_256(
					_mm_slli_epi16(_mm_cvtsi32_si128((int)escFirst), 6)
				));
			}
			
			// all that's left is to 'compress' the data (skip over masked chars)
#if defined(__AVX512VBMI2__) && defined(__AVX512VL__)
			if(use_isa >= ISA_LEVEL_VBMI2) {
				COMPRESS_STORE(p, KNOT32(mask), dataA);
				p -= popcnt32(mask & 0xffffffff);
				COMPRESS_STORE((p + XMM_SIZE*2), KNOT32(mask>>32), dataB);
				p += XMM_SIZE*4 - popcnt32(mask >> 32);
			} else
#endif
			{
				// lookup compress masks and shuffle
				__m256i shuf = _mm256_inserti128_si256(
					_mm256_castsi128_si256(_mm_load_si128((__m128i*)(lookups->compact + (mask & 0x7fff)))),
					*(__m128i*)((char*)lookups->compact + ((mask >> 12) & 0x7fff0)),
					1
				);
				dataA = _mm256_shuffle_epi8(dataA, shuf);
				
				_mm_storeu_si128((__m128i*)p, _mm256_castsi256_si128(dataA));
				// increment output position
				p -= popcnt32(mask & 0xffff);
				
				_mm_storeu_si128((__m128i*)(p + XMM_SIZE), _mm256_extracti128_si256(dataA, 1));
				p -= popcnt32(mask & 0xffff0000);
				
#ifdef PLATFORM_AMD64
				mask >>= 28;
				shuf = _mm256_inserti128_si256(
					_mm256_castsi128_si256(_mm_load_si128((__m128i*)((char*)lookups->compact + (mask & 0x7fff0)))),
					*(__m128i*)((char*)lookups->compact + ((mask >> 16) & 0x7fff0)),
					1
				);
				dataB = _mm256_shuffle_epi8(dataB, shuf);
				
				_mm_storeu_si128((__m128i*)(p + XMM_SIZE*2), _mm256_castsi256_si128(dataB));
				p -= popcnt32(mask & 0xffff0);
				
				_mm_storeu_si128((__m128i*)(p + XMM_SIZE*3), _mm256_extracti128_si256(dataB, 1));
				p -= popcnt32((unsigned int)(mask >> 20));
#else
				mask >>= 32;
				shuf = _mm256_inserti128_si256(
					_mm256_castsi128_si256(_mm_load_si128((__m128i*)(lookups->compact + (mask & 0x7fff)))),
					*(__m128i*)((char*)lookups->compact + ((mask >> 12) & 0x7fff0)),
					1
				);
				dataB = _mm256_shuffle_epi8(dataB, shuf);
				
				_mm_storeu_si128((__m128i*)(p + XMM_SIZE*2), _mm256_castsi256_si128(dataB));
				p -= popcnt32(mask & 0xffff);
				
				_mm_storeu_si128((__m128i*)(p + XMM_SIZE*3), _mm256_extracti128_si256(dataB, 1));
				p -= popcnt32(mask & 0xffff0000);
#endif
				p += XMM_SIZE*4;
			}
		} else {
			if(use_isa < ISA_LEVEL_AVX3)
				dataA = _mm256_add_epi8(oDataA, yencOffset);
			dataB = _mm256_add_epi8(oDataB, _mm256_set1_epi8(-42));
			
			_mm256_storeu_si256((__m256i*)p, dataA);
			_mm256_storeu_si256((__m256i*)p + 1, dataB);
			p += sizeof(__m256i)*2;
			escFirst = 0;
			yencOffset = _mm256_set1_epi8(-42);
		}
	}
	_escFirst = (unsigned char)escFirst;
	_mm256_zeroupper();
}
} // namespace
#endif
