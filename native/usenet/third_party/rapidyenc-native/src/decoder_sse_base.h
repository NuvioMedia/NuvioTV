
#ifdef __SSE2__

#if defined(__clang__) && __clang_major__ == 6 && __clang_minor__ == 0
// VBMI2 introduced in clang 6.0, but 128-bit functions misnamed there; fixed in clang 7.0, but we'll handle those on 6.0
# define _mm_mask_compressstoreu_epi8 _mm128_mask_compressstoreu_epi8
# define _mm_shrdi_epi16 _mm128_shrdi_epi16
#endif

#if defined(__tune_icelake_client__) || defined(__tune_icelake_server__) || defined(__tune_tigerlake__) || defined(__tune_rocketlake__) || defined(__tune_alderlake__) || defined(__tune_sapphirerapids__)
# define COMPRESS_STORE _mm_mask_compressstoreu_epi8
#else
// avoid uCode on Zen4
# define COMPRESS_STORE(dst, mask, vec) _mm_storeu_si128((__m128i*)(dst), _mm_maskz_compress_epi8(mask, vec))
#endif

// GCC (ver 6-10(dev)) fails to optimize pure C version of mask testing, but has this intrinsic; Clang >= 7 optimizes C version fine
#if (defined(__GNUC__) && __GNUC__ >= 7) || (defined(_MSC_VER) && _MSC_VER >= 1924)
# define KORTEST16(a, b) !_kortestz_mask16_u8((a), (b))
# define KAND16(a, b) _kand_mask16((a), (b))
# define KOR16(a, b) _kor_mask16((a), (b))
#else
# define KORTEST16(a, b) ((a) | (b))
# define KAND16(a, b) ((a) & (b))
# define KOR16(a, b) ((a) | (b))
#endif

namespace RapidYenc {
	#pragma pack(16)
	typedef struct {
		unsigned char BitsSetTable256inv[256];
		/*align16*/ struct { char bytes[16]; } compact[32768];
		/*align8*/ uint64_t eqAdd[256];
		/*align16*/ int8_t unshufMask[32*16];
	} SSELookups;
	#pragma pack()
}
static RapidYenc::SSELookups* HEDLEY_RESTRICT lookups;


static HEDLEY_ALWAYS_INLINE __m128i force_align_read_128(const void* p) {
#ifdef _MSC_VER
	// MSVC complains about casting away volatile
	return *(__m128i *)(p);
#else
	return *(volatile __m128i *)(p);
#endif
}

namespace RapidYenc {
	void decoder_sse_init(SSELookups* HEDLEY_RESTRICT& lookups); // defined in decoder_sse2.cc
}


// for LZCNT/BSR
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
static HEDLEY_ALWAYS_INLINE __m128i sse2_compact_vect(uint32_t mask, __m128i data) {
	while(mask) {
		unsigned bitIndex;
#if defined(__LZCNT__)
		if(use_isa & ISA_FEATURE_LZCNT) {
			// lzcnt is always at least as fast as bsr, so prefer it if it's available
			bitIndex = _lzcnt_u32(mask);
			mask &= 0x7fffffffU>>bitIndex;
		} else
#endif
		{
			bitIndex = BSR32(mask);
			mask ^= 1<<bitIndex;
		}
		__m128i mergeMask = _mm_load_si128((__m128i*)lookups->unshufMask + bitIndex);
		data = _mm_or_si128(
			_mm_and_si128(mergeMask, data),
			_mm_andnot_si128(mergeMask, _mm_srli_si128(data, 1))
		);
	}
	return data;
}

namespace RapidYenc {

template<bool isRaw, bool searchEnd, enum YEncDecIsaLevel use_isa>
HEDLEY_ALWAYS_INLINE void do_decode_sse(const uint8_t* src, long& len, unsigned char*& p, unsigned char& _escFirst, uint16_t& _nextMask) {
	HEDLEY_ASSUME(_escFirst == 0 || _escFirst == 1);
	HEDLEY_ASSUME(_nextMask == 0 || _nextMask == 1 || _nextMask == 2);
	uintptr_t escFirst = _escFirst;
	__m128i yencOffset = escFirst ? _mm_set_epi8(
		-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42,-42-64
	) : _mm_set1_epi8(-42);
	
#if defined(__SSSE3__) && !defined(__tune_atom__) && !defined(__tune_slm__) && !defined(__tune_btver1__) && !defined(__tune_btver2__)
	const bool _USING_FAST_MATCH = (use_isa >= ISA_LEVEL_SSSE3);
#else
	const bool _USING_FAST_MATCH = false;
#endif
#if defined(__SSE4_1__) && !defined(__tune_slm__) && !defined(__tune_goldmont__) && !defined(__tune_goldmont_plus__) && !defined(__tune_tremont__)
	const bool _USING_BLEND_ADD = (use_isa >= ISA_LEVEL_SSE41);
#else
	const bool _USING_BLEND_ADD = false;
#endif
#if defined(__AVX512VL__) && defined(__AVX512BW__)
# if defined(_MSC_VER) && !defined(PLATFORM_AMD64) && !defined(__clang__)
	const bool useAVX3MaskCmp = false;
# else
	const bool useAVX3MaskCmp = (use_isa >= ISA_LEVEL_AVX3);
# endif
#endif
	
	__m128i lfCompare = _mm_set1_epi8('\n');
	__m128i minMask = _mm_set1_epi8('.');
	if(_nextMask && isRaw) {
		if(_USING_FAST_MATCH)
			minMask = _mm_insert_epi16(minMask, _nextMask == 1 ? 0x2e00 : 0x002e, 0);
		else
			lfCompare = _mm_insert_epi16(lfCompare, _nextMask == 1 ? 0x0a2e /*".\n"*/ : 0x2e0a /*"\n."*/, 0);
	}
	
	decoder_set_nextMask<isRaw>(src, len, _nextMask); // set this before the loop because we can't check src after it's been overwritten
	
	intptr_t i;
	for(i = -len; i; i += sizeof(__m128i)*2) {
		__m128i oDataA = _mm_load_si128((__m128i *)(src+i));
		__m128i oDataB = _mm_load_si128((__m128i *)(src+i) + 1);
		
		// search for special chars
		__m128i cmpEqA, cmpEqB, cmpCrA, cmpCrB;
		__m128i cmpA, cmpB;
#if defined(__SSSE3__)
		if(_USING_FAST_MATCH) {
			cmpA = _mm_cmpeq_epi8(oDataA, _mm_shuffle_epi8(
				_mm_set_epi8(-1,'=','\r',-1,-1,'\n',-1,-1,-1,-1,-1,-1,-1,-1,-1,'.'),
				_mm_min_epu8(oDataA, minMask)
			));
			cmpB = _mm_cmpeq_epi8(oDataB, _mm_shuffle_epi8(
				_mm_set_epi8(-1,'=','\r',-1,-1,'\n',-1,-1,-1,-1,-1,-1,-1,-1,-1,'.'),
				_mm_min_epu8(oDataB, _mm_set1_epi8('.'))
			));
		} else
#endif
		{
			cmpEqA = _mm_cmpeq_epi8(oDataA, _mm_set1_epi8('='));
			cmpEqB = _mm_cmpeq_epi8(oDataB, _mm_set1_epi8('='));
			cmpCrA = _mm_cmpeq_epi8(oDataA, _mm_set1_epi8('\r'));
			cmpCrB = _mm_cmpeq_epi8(oDataB, _mm_set1_epi8('\r'));
			cmpA = _mm_or_si128(
				_mm_or_si128(
					_mm_cmpeq_epi8(oDataA, lfCompare), cmpCrA
				),
				cmpEqA
			);
			cmpB = _mm_or_si128(
				_mm_or_si128(
					_mm_cmpeq_epi8(oDataB, _mm_set1_epi8('\n')), cmpCrB
				),
				cmpEqB
			);
		}
		
		__m128i dataA, dataB;
		if(!_USING_BLEND_ADD)
			dataA = _mm_add_epi8(oDataA, yencOffset);
		uint32_t mask = (unsigned)_mm_movemask_epi8(cmpA) | ((unsigned)_mm_movemask_epi8(cmpB) << 16); // not the most accurate mask if we have invalid sequences; we fix this up later
		
		if (LIKELIHOOD(0.42 /* rough guess */, mask != 0)) {
			if(_USING_FAST_MATCH) {
				cmpEqA = _mm_cmpeq_epi8(oDataA, _mm_set1_epi8('='));
				cmpEqB = _mm_cmpeq_epi8(oDataB, _mm_set1_epi8('='));
			}
			
#define LOAD_HALVES(a, b) _mm_castps_si128(_mm_loadh_pi( \
	_mm_castsi128_ps(_mm_loadl_epi64((__m128i*)(a))), \
	(__m64*)(b) \
))
			
			// a spec compliant encoder should never generate sequences: ==, =\n and =\r, but we'll handle them to be spec compliant
			// the yEnc specification requires any character following = to be unescaped, not skipped over, so we'll deal with that
			// firstly, check for invalid sequences of = (we assume that these are rare, as a spec compliant yEnc encoder should not generate these)
			uint32_t maskEq = (unsigned)_mm_movemask_epi8(cmpEqA) | ((unsigned)_mm_movemask_epi8(cmpEqB) << 16);
			
			// handle \r\n. sequences
			// RFC3977 requires the first dot on a line to be stripped, due to dot-stuffing
			if((isRaw || searchEnd) && LIKELIHOOD(0.25, mask != maskEq)) {
#if 0
				// for experimentation: prefer shifting data over unaligned loads on CPUs with slow unaligned handling
				// haven't ever seen this be beneficial though
				__m128i nextDataB;
				if(searchEnd && _USING_BLEND_ADD)
					nextDataB = _mm_cvtsi32_si128(*(uint32_t*)(src+i+sizeof(__m128i)*2));
# define SHIFT_DATA_A(offs) (searchEnd && _USING_BLEND_ADD ? _mm_alignr_epi8(oDataB, oDataA, offs) : _mm_loadu_si128((__m128i *)(src+i+offs)))
# define SHIFT_DATA_B(offs) (searchEnd && _USING_BLEND_ADD ? _mm_alignr_epi8(nextDataB, oDataB, offs) : _mm_loadu_si128((__m128i *)(src+i+offs) + 1))
#else
# define SHIFT_DATA_A(offs) _mm_loadu_si128((__m128i *)(src+i+offs))
# define SHIFT_DATA_B(offs) _mm_loadu_si128((__m128i *)(src+i+offs) + 1)
#endif
				__m128i tmpData2A = SHIFT_DATA_A(2);
				__m128i tmpData2B = SHIFT_DATA_B(2);
				__m128i match2EqA, match2EqB;
				
#if defined(__AVX512VL__) && defined(__AVX512BW__)
				__mmask16 match2EqMaskA, match2EqMaskB;
				__mmask16 match0CrMaskA, match0CrMaskB;
				__mmask16 match2CrXDtMaskA, match2CrXDtMaskB;
				if(useAVX3MaskCmp && searchEnd) {
					match2EqMaskA = _mm_cmpeq_epi8_mask(_mm_set1_epi8('='), tmpData2A);
					match2EqMaskB = _mm_cmpeq_epi8_mask(_mm_set1_epi8('='), tmpData2B);
				} else
#endif
				if(searchEnd) {
#if !defined(__tune_btver1__)
					if(use_isa < ISA_LEVEL_SSSE3)
#endif
						match2EqA = _mm_cmpeq_epi8(_mm_set1_epi8('='), tmpData2A);
					match2EqB = _mm_cmpeq_epi8(_mm_set1_epi8('='), tmpData2B);
				}
				int partialKillDotFound;
				__m128i match2CrXDtA, match2CrXDtB;
				if(isRaw) {
#if defined(__AVX512VL__) && defined(__AVX512BW__)
					if(useAVX3MaskCmp) {
						match0CrMaskA = _mm_cmpeq_epi8_mask(oDataA, _mm_set1_epi8('\r'));
						match0CrMaskB = _mm_cmpeq_epi8_mask(oDataB, _mm_set1_epi8('\r'));
						match2CrXDtMaskA = _mm_mask_cmpeq_epi8_mask(match0CrMaskA, tmpData2A, _mm_set1_epi8('.'));
						match2CrXDtMaskB = _mm_mask_cmpeq_epi8_mask(match0CrMaskB, tmpData2B, _mm_set1_epi8('.'));
						partialKillDotFound = KORTEST16(match2CrXDtMaskA, match2CrXDtMaskB);
					} else
#endif
					{
						if(_USING_FAST_MATCH) {
							cmpCrA = _mm_cmpeq_epi8(oDataA, _mm_set1_epi8('\r'));
							cmpCrB = _mm_cmpeq_epi8(oDataB, _mm_set1_epi8('\r'));
						}
						match2CrXDtA = _mm_and_si128(cmpCrA, _mm_cmpeq_epi8(tmpData2A, _mm_set1_epi8('.')));
						match2CrXDtB = _mm_and_si128(cmpCrB, _mm_cmpeq_epi8(tmpData2B, _mm_set1_epi8('.')));
						partialKillDotFound = _mm_movemask_epi8(_mm_or_si128(match2CrXDtA, match2CrXDtB));
					}
				}
				
				if(isRaw && LIKELIHOOD(0.001, partialKillDotFound)) {
					__m128i match2NlDotA, match1NlA;
					__m128i match2NlDotB, match1NlB;
					// merge matches for \r\n.
#if defined(__AVX512VL__) && defined(__AVX512BW__)
					__mmask16 match1NlMaskA, match1NlMaskB;
					__mmask16 match2NlDotMaskA, match2NlDotMaskB;
					if(useAVX3MaskCmp) {
						match1NlMaskA = _mm_mask_cmpeq_epi8_mask(
							match0CrMaskA,
							_mm_set1_epi8('\n'),
							SHIFT_DATA_A(1)
						);
						match1NlMaskB = _mm_mask_cmpeq_epi8_mask(
							match0CrMaskB,
							_mm_set1_epi8('\n'),
							SHIFT_DATA_B(1)
						);
						match2NlDotMaskA = KAND16(match2CrXDtMaskA, match1NlMaskA);
						match2NlDotMaskB = KAND16(match2CrXDtMaskB, match1NlMaskB);
					} else
#endif
					{
						__m128i match1LfA = _mm_cmpeq_epi8(_mm_set1_epi8('\n'), SHIFT_DATA_A(1));
						__m128i match1LfB = _mm_cmpeq_epi8(_mm_set1_epi8('\n'), SHIFT_DATA_B(1));
						
						// always recompute cmpCr to avoid register spills above
						cmpCrA = _mm_cmpeq_epi8(force_align_read_128(src+i), _mm_set1_epi8('\r'));
						cmpCrB = _mm_cmpeq_epi8(force_align_read_128(src+i + sizeof(__m128i)), _mm_set1_epi8('\r'));
						match1NlA = _mm_and_si128(match1LfA, cmpCrA);
						match1NlB = _mm_and_si128(match1LfB, cmpCrB);
						match2NlDotA = _mm_and_si128(match2CrXDtA, match1NlA);
						match2NlDotB = _mm_and_si128(match2CrXDtB, match1NlB);
					}
					if(searchEnd) {
						__m128i tmpData3A = SHIFT_DATA_A(3);
						__m128i tmpData3B = SHIFT_DATA_B(3);
						__m128i tmpData4A = SHIFT_DATA_A(4);
						__m128i tmpData4B = SHIFT_DATA_B(4);
						// match instances of \r\n.\r\n and \r\n.=y
						// TODO: consider doing a PALIGNR using match1Nl for match4NlA
						__m128i match3CrA = _mm_cmpeq_epi8(_mm_set1_epi8('\r'), tmpData3A);
						__m128i match3CrB = _mm_cmpeq_epi8(_mm_set1_epi8('\r'), tmpData3B);
						__m128i match4LfA = _mm_cmpeq_epi8(tmpData4A, _mm_set1_epi8('\n'));
						__m128i match4LfB = _mm_cmpeq_epi8(tmpData4B, _mm_set1_epi8('\n'));
						__m128i match4EqYA = _mm_cmpeq_epi16(tmpData4A, _mm_set1_epi16(0x793d)); // =y
						__m128i match4EqYB = _mm_cmpeq_epi16(tmpData4B, _mm_set1_epi16(0x793d)); // =y
						
						int matchEnd;
#if defined(__AVX512VL__) && defined(__AVX512BW__)
						if(useAVX3MaskCmp) {
							__mmask16 match3EqYMaskA = _mm_mask_cmpeq_epi8_mask(
								match2EqMaskA, _mm_set1_epi8('y'), tmpData3A
							);
							__mmask16 match3EqYMaskB = _mm_mask_cmpeq_epi8_mask(
								match2EqMaskB, _mm_set1_epi8('y'), tmpData3B
							);
							__m128i match34EqYA, match34EqYB;
# ifdef __AVX512VBMI2__
							if(use_isa >= ISA_LEVEL_VBMI2) {
								match34EqYA = _mm_shrdi_epi16(_mm_movm_epi8(match3EqYMaskA), match4EqYA, 8);
								match34EqYB = _mm_shrdi_epi16(_mm_movm_epi8(match3EqYMaskB), match4EqYB, 8);
							} else
# endif
							{
								// (match4EqY & 0xff00) | (match3EqY >> 8)
								match34EqYA = _mm_mask_blend_epi8(match3EqYMaskA>>1, _mm_and_si128(match4EqYA, _mm_set1_epi16(-0xff)), _mm_set1_epi8(-1));
								match34EqYB = _mm_mask_blend_epi8(match3EqYMaskB>>1, _mm_and_si128(match4EqYB, _mm_set1_epi16(-0xff)), _mm_set1_epi8(-1));
							}
							// merge \r\n and =y matches for tmpData4
							__m128i match4EndA = _mm_ternarylogic_epi32(match34EqYA, match3CrA, match4LfA, 0xF8); // (match3Cr & match4Lf) | match34EqY
							__m128i match4EndB = _mm_ternarylogic_epi32(match34EqYB, match3CrB, match4LfB, 0xF8);
							// merge with \r\n. and combine
							matchEnd = KORTEST16(
								KOR16(
									_mm_mask_test_epi8_mask(match2NlDotMaskA, match4EndA, match4EndA),
									KAND16(match3EqYMaskA, match1NlMaskA)
								),
								KOR16(
									_mm_mask_test_epi8_mask(match2NlDotMaskB, match4EndB, match4EndB),
									KAND16(match3EqYMaskB, match1NlMaskB)
								)
							);
						} else
#endif
						{
#if defined(__SSSE3__) && !defined(__tune_btver1__)
							if(use_isa >= ISA_LEVEL_SSSE3)
								match2EqA = _mm_alignr_epi8(cmpEqB, cmpEqA, 2);
#endif
							__m128i match3EqYA = _mm_and_si128(match2EqA, _mm_cmpeq_epi8(_mm_set1_epi8('y'), tmpData3A));
							__m128i match3EqYB = _mm_and_si128(match2EqB, _mm_cmpeq_epi8(_mm_set1_epi8('y'), tmpData3B));
							match4EqYA = _mm_slli_epi16(match4EqYA, 8); // TODO: also consider using PBLENDVB here with shifted match3EqY instead
							match4EqYB = _mm_slli_epi16(match4EqYB, 8);
							// merge \r\n and =y matches for tmpData4
							__m128i match4EndA = _mm_or_si128(
								_mm_and_si128(match3CrA, match4LfA),
								_mm_or_si128(match4EqYA, _mm_srli_epi16(match3EqYA, 8)) // _mm_srli_si128 by 1 also works
							);
							__m128i match4EndB = _mm_or_si128(
								_mm_and_si128(match3CrB, match4LfB),
								_mm_or_si128(match4EqYB, _mm_srli_epi16(match3EqYB, 8)) // _mm_srli_si128 by 1 also works
							);
							// merge with \r\n.
							match4EndA = _mm_and_si128(match4EndA, match2NlDotA);
							match4EndB = _mm_and_si128(match4EndB, match2NlDotB);
							// match \r\n=y
							__m128i match3EndA = _mm_and_si128(match3EqYA, match1NlA);
							__m128i match3EndB = _mm_and_si128(match3EqYB, match1NlB);
							// combine match sequences
							matchEnd = _mm_movemask_epi8(_mm_or_si128(
								_mm_or_si128(match4EndA, match3EndA),
								_mm_or_si128(match4EndB, match3EndB)
							));
						}
						
						if(LIKELIHOOD(0.001, matchEnd)) {
							// terminator found
							// there's probably faster ways to do this, but reverting to scalar code should be good enough
							len += (long)i;
							_nextMask = decoder_set_nextMask<isRaw>(src+i, mask);
							break;
						}
					}
#if defined(__AVX512VL__) && defined(__AVX512BW__)
					if(useAVX3MaskCmp) {
						mask |= match2NlDotMaskA << 2;
						mask |= (match2NlDotMaskB << 18) & 0xffffffff;
						minMask = _mm_maskz_mov_epi8(~(match2NlDotMaskB>>14), _mm_set1_epi8('.'));
					} else
#endif
					{
						mask |= (_mm_movemask_epi8(match2NlDotA) << 2);
						mask |= (_mm_movemask_epi8(match2NlDotB) << 18) & 0xffffffff;
						match2NlDotB = _mm_srli_si128(match2NlDotB, 14);
						if(_USING_FAST_MATCH)
							minMask = _mm_subs_epu8(_mm_set1_epi8('.'), match2NlDotB);
						else
							// this bitiwse trick works because '.'|'\n' == '.'
							lfCompare = _mm_or_si128(
								_mm_and_si128(match2NlDotB, _mm_set1_epi8('.')),
								_mm_set1_epi8('\n')
							);
					}
				}
				else if(searchEnd) {
					bool partialEndFound;
					__m128i match3EqYA, match3EqYB;
#if defined(__AVX512VL__) && defined(__AVX512BW__)
					__mmask16 match3EqYMaskA, match3EqYMaskB;
					if(useAVX3MaskCmp) {
						match3EqYMaskA = _mm_mask_cmpeq_epi8_mask(
							match2EqMaskA,
							_mm_set1_epi8('y'),
							SHIFT_DATA_A(3)
						);
						match3EqYMaskB = _mm_mask_cmpeq_epi8_mask(
							match2EqMaskB,
							_mm_set1_epi8('y'),
							SHIFT_DATA_B(3)
						);
						partialEndFound = KORTEST16(match3EqYMaskA, match3EqYMaskB);
					} else
#endif
					{
						__m128i match3YA = _mm_cmpeq_epi8(
							_mm_set1_epi8('y'),
							SHIFT_DATA_A(3)
						);
						__m128i match3YB = _mm_cmpeq_epi8(
							_mm_set1_epi8('y'),
							SHIFT_DATA_B(3)
						);
#if defined(__SSSE3__) && !defined(__tune_btver1__)
						if(use_isa >= ISA_LEVEL_SSSE3)
							match2EqA = _mm_alignr_epi8(cmpEqB, cmpEqA, 2);
#endif
						match3EqYA = _mm_and_si128(match2EqA, match3YA);
						match3EqYB = _mm_and_si128(match2EqB, match3YB);
						partialEndFound = _mm_movemask_epi8(_mm_or_si128(match3EqYA, match3EqYB));
					}
					if(LIKELIHOOD(0.001, partialEndFound)) {
						// if the rare case of '=y' is found, do a more precise check
						bool endFound;
						
#if defined(__AVX512VL__) && defined(__AVX512BW__)
						if(useAVX3MaskCmp) {
							__mmask16 match3LfEqYMaskA = _mm_mask_cmpeq_epi8_mask(
								match3EqYMaskA,
								_mm_set1_epi8('\n'),
								SHIFT_DATA_A(1)
							);
							__mmask16 match3LfEqYMaskB = _mm_mask_cmpeq_epi8_mask(
								match3EqYMaskB,
								_mm_set1_epi8('\n'),
								SHIFT_DATA_B(1)
							);
							
							endFound = KORTEST16(
								_mm_mask_cmpeq_epi8_mask(match3LfEqYMaskA, oDataA, _mm_set1_epi8('\r')),
								_mm_mask_cmpeq_epi8_mask(match3LfEqYMaskB, oDataB, _mm_set1_epi8('\r'))
							);
						} else
#endif
						{
							// always recompute cmpCr to avoid register spills above
							cmpCrA = _mm_cmpeq_epi8(force_align_read_128(src+i), _mm_set1_epi8('\r'));
							cmpCrB = _mm_cmpeq_epi8(force_align_read_128(src+i + sizeof(__m128i)), _mm_set1_epi8('\r'));
							__m128i match1LfA = _mm_cmpeq_epi8(
								_mm_set1_epi8('\n'),
								SHIFT_DATA_A(1)
							);
							__m128i match1LfB = _mm_cmpeq_epi8(
								_mm_set1_epi8('\n'),
								SHIFT_DATA_B(1)
							);
							endFound = _mm_movemask_epi8(_mm_or_si128(
								_mm_and_si128(
									match3EqYA,
									_mm_and_si128(match1LfA, cmpCrA)
								),
								_mm_and_si128(
									match3EqYB,
									_mm_and_si128(match1LfB, cmpCrB)
								)
							));
						}
						
						if(endFound) {
							len += (long)i;
							_nextMask = decoder_set_nextMask<isRaw>(src+i, mask);
							break;
						}
					}
					if(isRaw) {
						if(_USING_FAST_MATCH)
							minMask = _mm_set1_epi8('.');
						else
							lfCompare = _mm_set1_epi8('\n');
					}
				}
				else if(isRaw) { // no \r_. found
					if(_USING_FAST_MATCH)
						minMask = _mm_set1_epi8('.');
					else
						lfCompare = _mm_set1_epi8('\n');
				}
			}
#undef SHIFT_DATA_A
#undef SHIFT_DATA_B
			
			if(!_USING_BLEND_ADD)
				dataB = _mm_add_epi8(oDataB, _mm_set1_epi8(-42));
			
			uint32_t maskEqShift1 = (maskEq << 1) + escFirst;
			if(LIKELIHOOD(0.0001, (mask & maskEqShift1) != 0)) {
				maskEq = fix_eqMask<uint32_t>(maskEq, maskEqShift1);
				mask &= ~escFirst;
				escFirst = maskEq >> 31;
				// next, eliminate anything following a `=` from the special char mask; this eliminates cases of `=\r` so that they aren't removed
				maskEq <<= 1;
				mask &= ~maskEq;
				
				if(_USING_BLEND_ADD) {
					dataA = _mm_add_epi8(oDataA, yencOffset);
					dataB = _mm_add_epi8(oDataB, _mm_set1_epi8(-42));
				}
				
				// unescape chars following `=`
#if defined(__AVX512VL__) && defined(__AVX512BW__)
				if(use_isa >= ISA_LEVEL_AVX3) {
					// GCC < 7 seems to generate rubbish assembly for this
					dataA = _mm_mask_add_epi8(
						dataA,
						(__mmask16)maskEq,
						dataA,
						_mm_set1_epi8(-64)
					);
					dataB = _mm_mask_add_epi8(
						dataB,
						(__mmask16)(maskEq>>16),
						dataB,
						_mm_set1_epi8(-64)
					);
				} else
#endif
				{
					dataA = _mm_add_epi8(
						dataA,
						LOAD_HALVES(
							lookups->eqAdd + (maskEq&0xff),
							lookups->eqAdd + ((maskEq>>8)&0xff)
						)
					);
					maskEq >>= 16;
					dataB = _mm_add_epi8(
						dataB,
						LOAD_HALVES(
							lookups->eqAdd + (maskEq&0xff),
							lookups->eqAdd + ((maskEq>>8)&0xff)
						)
					);
					
					yencOffset = _mm_xor_si128(_mm_set1_epi8(-42), 
						_mm_slli_epi16(_mm_cvtsi32_si128((int)escFirst), 6)
					);
				}
			} else {
				// no invalid = sequences found - we can cut out some things from above
				// this code path is a shortened version of above; it's here because it's faster, and what we'll be dealing with most of the time
				escFirst = (maskEq >> 31);
				
#if defined(__AVX512VL__) && defined(__AVX512BW__)
				if(use_isa >= ISA_LEVEL_AVX3) {
					dataA = _mm_add_epi8(
						oDataA,
						_mm_ternarylogic_epi32(
							_mm_slli_si128(cmpEqA, 1), yencOffset, _mm_set1_epi8(-42-64), 0xac
						)
					);
					dataB = _mm_add_epi8(
						oDataB,
						_mm_ternarylogic_epi32(
							_mm_alignr_epi8(cmpEqB, cmpEqA, 15), _mm_set1_epi8(-42), _mm_set1_epi8(-42-64), 0xac
						)
					);
				} else
#endif
#if defined(__SSE4_1__)
				if(_USING_BLEND_ADD) {
					/* // the following strategy seems more ideal, however, both GCC and Clang go bonkers over it and spill more registers
					cmpEqA = _mm_blendv_epi8(_mm_set1_epi8(-42), _mm_set1_epi8(-42-64), cmpEqA);
					cmpEqB = _mm_blendv_epi8(_mm_set1_epi8(-42), _mm_set1_epi8(-42-64), cmpEqB);
					dataB = _mm_add_epi8(oDataB, _mm_alignr_epi8(cmpEqB, cmpEqA, 15));
					dataA = _mm_add_epi8(oDataA, _mm_and_si128(
						_mm_alignr_epi8(cmpEqA, _mm_set1_epi8(-42), 15),
						yencOffset
					));
					yencOffset = _mm_alignr_epi8(_mm_set1_epi8(-42), cmpEqB, 15);
					*/
					
					dataA = _mm_add_epi8(
						oDataA,
						_mm_blendv_epi8(
							yencOffset, _mm_set1_epi8(-42-64), _mm_slli_si128(cmpEqA, 1)
						)
					);
					dataB = _mm_add_epi8(
						oDataB,
						_mm_blendv_epi8(
							_mm_set1_epi8(-42), _mm_set1_epi8(-42-64), _mm_alignr_epi8(cmpEqB, cmpEqA, 15)
						)
					);
					yencOffset = _mm_xor_si128(_mm_set1_epi8(-42), 
						_mm_slli_epi16(_mm_cvtsi32_si128((int)escFirst), 6)
					);
				} else
#endif
				{
					cmpEqA = _mm_and_si128(cmpEqA, _mm_set1_epi8(-64));
					cmpEqB = _mm_and_si128(cmpEqB, _mm_set1_epi8(-64));
					yencOffset = _mm_add_epi8(_mm_set1_epi8(-42), _mm_srli_si128(cmpEqB, 15));
#if defined(__SSSE3__) && !defined(__tune_btver1__)
					if(use_isa >= ISA_LEVEL_SSSE3)
						cmpEqB = _mm_alignr_epi8(cmpEqB, cmpEqA, 15);
					else
#endif
						cmpEqB = _mm_or_si128(
							_mm_slli_si128(cmpEqB, 1),
							_mm_srli_si128(cmpEqA, 15)
						);
					cmpEqA = _mm_slli_si128(cmpEqA, 1);
					dataA = _mm_add_epi8(dataA, cmpEqA);
					dataB = _mm_add_epi8(dataB, cmpEqB);
				}
			}
			// subtract 64 from first element if escFirst == 1
#if defined(__AVX512VL__) && defined(__AVX512BW__)
			if(use_isa >= ISA_LEVEL_AVX3) {
				yencOffset = _mm_mask_add_epi8(_mm_set1_epi8(-42), (__mmask16)escFirst, _mm_set1_epi8(-42), _mm_set1_epi8(-64));
			}
#endif
			
			// all that's left is to 'compress' the data (skip over masked chars)
#ifdef __SSSE3__
			if(use_isa >= ISA_LEVEL_SSSE3) {
# if defined(__AVX512VBMI2__) && defined(__AVX512VL__) && defined(__POPCNT__)
				if(use_isa >= ISA_LEVEL_VBMI2) {
					COMPRESS_STORE(p, KNOT16(mask), dataA);
					p -= popcnt32(mask & 0xffff);
					COMPRESS_STORE(p+XMM_SIZE, KNOT16(mask>>16), dataB);
					p -= popcnt32(mask>>16);
					p += XMM_SIZE*2;
				} else
# endif
				{
					
					dataA = _mm_shuffle_epi8(dataA, _mm_load_si128((__m128i*)(lookups->compact + (mask&0x7fff))));
					STOREU_XMM(p, dataA);
					
					dataB = _mm_shuffle_epi8(dataB, _mm_load_si128((__m128i*)((char*)lookups->compact + ((mask >> 12) & 0x7fff0))));
					
# if defined(__POPCNT__) && !defined(__tune_btver1__)
					if(use_isa & ISA_FEATURE_POPCNT) {
						p -= popcnt32(mask & 0xffff);
						STOREU_XMM(p+XMM_SIZE, dataB);
						p -= popcnt32(mask & 0xffff0000);
						p += XMM_SIZE*2;
					} else
# endif
					{
						p += lookups->BitsSetTable256inv[mask & 0xff] + lookups->BitsSetTable256inv[(mask >> 8) & 0xff];
						STOREU_XMM(p, dataB);
						mask >>= 16;
						p += lookups->BitsSetTable256inv[mask & 0xff] + lookups->BitsSetTable256inv[(mask >> 8) & 0xff];
					}
				}
			} else
#endif
			{
				dataA = sse2_compact_vect<use_isa>(mask & 0xffff, dataA);
				STOREU_XMM(p, dataA);
				p += lookups->BitsSetTable256inv[mask & 0xff] + lookups->BitsSetTable256inv[(mask >> 8) & 0xff];
				mask >>= 16;
				dataB = sse2_compact_vect<use_isa>(mask, dataB);
				STOREU_XMM(p, dataB);
				p += lookups->BitsSetTable256inv[mask & 0xff] + lookups->BitsSetTable256inv[(mask >> 8) & 0xff];
			}
#undef LOAD_HALVES
		} else {
			if(_USING_BLEND_ADD)
				dataA = _mm_add_epi8(oDataA, yencOffset);
			dataB = _mm_add_epi8(oDataB, _mm_set1_epi8(-42));
			
			STOREU_XMM(p, dataA);
			STOREU_XMM(p+XMM_SIZE, dataB);
			p += XMM_SIZE*2;
			escFirst = 0;
			yencOffset = _mm_set1_epi8(-42);
		}
	}
	_escFirst = (unsigned char)escFirst;
}
} // namespace
#endif
