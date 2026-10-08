#include "common.h"
#include "decoder_common.h"
#if defined(__ARM_NEON) && defined(__aarch64__)


#pragma pack(16)
static struct { char bytes[16]; } ALIGN_TO(16, compactLUT[32768]);
#pragma pack()


// AArch64 GCC lacks these functions until 8.5, 9.4 and 10.1 (10.0 unknown)
#if !defined(__clang__) && !defined(_MSC_VER) && (!defined(__aarch64__) || !(HEDLEY_GCC_VERSION_CHECK(9,4,0) || (!HEDLEY_GCC_VERSION_CHECK(9,0,0) && HEDLEY_GCC_VERSION_CHECK(8,5,0))))
static HEDLEY_ALWAYS_INLINE uint8x16x4_t _vld1q_u8_x4(const uint8_t* p) {
	uint8x16x4_t ret;
	ret.val[0] = vld1q_u8(p);
	ret.val[1] = vld1q_u8(p+16);
	ret.val[2] = vld1q_u8(p+32);
	ret.val[3] = vld1q_u8(p+48);
	return ret;
}
static HEDLEY_ALWAYS_INLINE void _vst1q_u8_x4(uint8_t* p, uint8x16x4_t data) {
	vst1q_u8(p, data.val[0]);
	vst1q_u8(p+16, data.val[1]);
	vst1q_u8(p+32, data.val[2]);
	vst1q_u8(p+48, data.val[3]);
}
#else
# define _vld1q_u8_x4 vld1q_u8_x4
# define _vst1q_u8_x4 vst1q_u8_x4
#endif


static bool neon_vect_is_nonzero(uint8x16_t v) {
	return !!(vget_lane_u64(vreinterpret_u64_u32(vqmovn_u64(vreinterpretq_u64_u8(v))), 0));
}

static HEDLEY_ALWAYS_INLINE uint8x16_t mergeCompares(uint8x16_t a, uint8x16_t b, uint8x16_t c, uint8x16_t d) {
	// constant vectors arbitrarily chosen from ones that can be reused; exact ordering of bits doesn't matter, we just need to mix them in
	return vbslq_u8(
		vdupq_n_u8('='),
		vbslq_u8(vdupq_n_u8('y'), a, b),
		vbslq_u8(vdupq_n_u8('y'), c, d)
	);
}


namespace RapidYenc {

template<bool isRaw, bool searchEnd>
HEDLEY_ALWAYS_INLINE void do_decode_neon(const uint8_t* src, long& len, unsigned char*& p, unsigned char& escFirst, uint16_t& nextMask) {
	HEDLEY_ASSUME(escFirst == 0 || escFirst == 1);
	HEDLEY_ASSUME(nextMask == 0 || nextMask == 1 || nextMask == 2);
	uint8x16_t nextMaskMix = vdupq_n_u8(0);
	if(nextMask == 1)
		nextMaskMix = vsetq_lane_u8(1, nextMaskMix, 0);
	if(nextMask == 2)
		nextMaskMix = vsetq_lane_u8(2, nextMaskMix, 1);
	uint8x16_t yencOffset = escFirst ? vmakeq_u8(42+64,42,42,42,42,42,42,42,42,42,42,42,42,42,42,42) : vdupq_n_u8(42);
	
	decoder_set_nextMask<isRaw>(src, len, nextMask);
	
	long i;
	for(i = -len; i; i += sizeof(uint8x16_t)*4) {
		uint8x16x4_t data = _vld1q_u8_x4(src+i);
		uint8x16_t dataA = data.val[0];
		uint8x16_t dataB = data.val[1];
		uint8x16_t dataC = data.val[2];
		uint8x16_t dataD = data.val[3];
		
		// search for special chars
		uint8x16_t cmpEqA = vceqq_u8(dataA, vdupq_n_u8('=')),
		cmpEqB = vceqq_u8(dataB, vdupq_n_u8('=')),
		cmpEqC = vceqq_u8(dataC, vdupq_n_u8('=')),
		cmpEqD = vceqq_u8(dataD, vdupq_n_u8('=')),
		cmpA = vqtbx1q_u8(
			cmpEqA,
			//                             \n      \r
			vmakeq_u8(0,0,0,0,0,0,0,0,0,0,255,0,0,255,0,0),
			dataA
		),
		cmpB = vqtbx1q_u8(
			cmpEqB,
			vmakeq_u8(0,0,0,0,0,0,0,0,0,0,255,0,0,255,0,0),
			dataB
		),
		cmpC = vqtbx1q_u8(
			cmpEqC,
			vmakeq_u8(0,0,0,0,0,0,0,0,0,0,255,0,0,255,0,0),
			dataC
		),
		cmpD = vqtbx1q_u8(
			cmpEqD,
			vmakeq_u8(0,0,0,0,0,0,0,0,0,0,255,0,0,255,0,0),
			dataD
		);
		if(isRaw) cmpA = vorrq_u8(cmpA, nextMaskMix);
		
		if (LIKELIHOOD(0.42 /*guess*/, neon_vect_is_nonzero(vorrq_u8(
			vorrq_u8(cmpA, cmpB),
			vorrq_u8(cmpC, cmpD)
		)))) {
			uint8x16_t cmpMerge = vpaddq_u8(
				vpaddq_u8(
					vandq_u8(cmpA, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128)),
					vandq_u8(cmpB, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128))
				),
				vpaddq_u8(
					vandq_u8(cmpC, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128)),
					vandq_u8(cmpD, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128))
				)
			);
			uint8x16_t cmpEqMerge = vpaddq_u8(
				vpaddq_u8(
					vandq_u8(cmpEqA, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128)),
					vandq_u8(cmpEqB, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128))
				),
				vpaddq_u8(
					vandq_u8(cmpEqC, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128)),
					vandq_u8(cmpEqD, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128))
				)
			);
			
			uint8x16_t cmpCombined = vpaddq_u8(cmpMerge, cmpEqMerge);
			uint64_t mask = vgetq_lane_u64(vreinterpretq_u64_u8(cmpCombined), 0);
			uint64_t maskEq = vgetq_lane_u64(vreinterpretq_u64_u8(cmpCombined), 1);
			
			// handle \r\n. sequences
			// RFC3977 requires the first dot on a line to be stripped, due to dot-stuffing
			if((isRaw || searchEnd) && LIKELIHOOD(0.15, mask != maskEq)) {
				// vext seems to be a cheap operation on ARM, relative to loads, so only avoid it if there's only one load (isRaw only)
				uint8x16_t tmpData2, nextData;
				if(isRaw && !searchEnd) {
					tmpData2 = vld1q_u8(src+i + 2 + sizeof(uint8x16_t)*3);
				} else {
					nextData = vld1q_u8(src+i + sizeof(uint8x16_t)*4); // only 32-bits needed, but there doesn't appear a nice way to do this via intrinsics: https://stackoverflow.com/questions/46910799/arm-neon-intrinsics-convert-d-64-bit-register-to-low-half-of-q-128-bit-regis
					tmpData2 = vextq_u8(dataD, nextData, 2);
				}
				uint8x16_t cmpCrA = vceqq_u8(dataA, vdupq_n_u8('\r'));
				uint8x16_t cmpCrB = vceqq_u8(dataB, vdupq_n_u8('\r'));
				uint8x16_t cmpCrC = vceqq_u8(dataC, vdupq_n_u8('\r'));
				uint8x16_t cmpCrD = vceqq_u8(dataD, vdupq_n_u8('\r'));
				uint8x16_t match2EqA, match2Cr_DotA;
				uint8x16_t match2EqB, match2Cr_DotB;
				uint8x16_t match2EqC, match2Cr_DotC;
				uint8x16_t match2EqD, match2Cr_DotD;
				if(searchEnd) {
					match2EqD = vceqq_u8(tmpData2, vdupq_n_u8('='));
				}
				if(isRaw) {
					match2Cr_DotA = vandq_u8(cmpCrA, vceqq_u8(vextq_u8(dataA, dataB, 2), vdupq_n_u8('.')));
					match2Cr_DotB = vandq_u8(cmpCrB, vceqq_u8(vextq_u8(dataB, dataC, 2), vdupq_n_u8('.')));
					match2Cr_DotC = vandq_u8(cmpCrC, vceqq_u8(vextq_u8(dataC, dataD, 2), vdupq_n_u8('.')));
					match2Cr_DotD = vandq_u8(cmpCrD, vceqq_u8(tmpData2, vdupq_n_u8('.')));
				}
				
				// find patterns of \r_.
				if(isRaw && LIKELIHOOD(0.001, neon_vect_is_nonzero(vorrq_u8(
					vorrq_u8(match2Cr_DotA, match2Cr_DotB),
					vorrq_u8(match2Cr_DotC, match2Cr_DotD)
				)))) {
					uint8x16_t match1LfA = vceqq_u8(vextq_u8(dataA, dataB, 1), vdupq_n_u8('\n'));
					uint8x16_t match1LfB = vceqq_u8(vextq_u8(dataB, dataC, 1), vdupq_n_u8('\n'));
					uint8x16_t match1LfC = vceqq_u8(vextq_u8(dataC, dataD, 1), vdupq_n_u8('\n'));
					uint8x16_t match1LfD;
					if(searchEnd)
						match1LfD = vceqq_u8(vextq_u8(dataD, nextData, 1), vdupq_n_u8('\n'));
					else
						match1LfD = vceqq_u8(vld1q_u8(src+i + 1+sizeof(uint8x16_t)*3), vdupq_n_u8('\n'));
					// merge matches of \r_. with those for \n
					uint8x16_t match2NlDotA = vandq_u8(match2Cr_DotA, match1LfA);
					uint8x16_t match2NlDotB = vandq_u8(match2Cr_DotB, match1LfB);
					uint8x16_t match2NlDotC = vandq_u8(match2Cr_DotC, match1LfC);
					uint8x16_t match2NlDotD = vandq_u8(match2Cr_DotD, match1LfD);
					if(searchEnd) {
						uint8x16_t match1NlA = vandq_u8(match1LfA, cmpCrA);
						uint8x16_t match1NlB = vandq_u8(match1LfB, cmpCrB);
						uint8x16_t match1NlC = vandq_u8(match1LfC, cmpCrC);
						uint8x16_t match1NlD = vandq_u8(match1LfD, cmpCrD);
						
						uint8x16_t tmpData3 = vextq_u8(dataD, nextData, 3);
						uint8x16_t tmpData4 = vextq_u8(dataD, nextData, 4);
						// match instances of \r\n.\r\n and \r\n.=y
						uint8x16_t match3CrD = vceqq_u8(tmpData3, vdupq_n_u8('\r'));
						uint8x16_t match4LfD = vceqq_u8(tmpData4, vdupq_n_u8('\n'));
						uint8x16_t match4Nl = mergeCompares(
							vextq_u8(match1NlA, match1NlB, 3),
							vextq_u8(match1NlB, match1NlC, 3),
							vextq_u8(match1NlC, match1NlD, 3),
							vandq_u8(match3CrD, match4LfD)
						);
						uint8x16_t match4EqY = mergeCompares(
							// match with =y
							vreinterpretq_u8_u16(vceqq_u16(vreinterpretq_u16_u8(vextq_u8(dataA, dataB, 4)), vdupq_n_u16(0x793d))),
							vreinterpretq_u8_u16(vceqq_u16(vreinterpretq_u16_u8(vextq_u8(dataB, dataC, 4)), vdupq_n_u16(0x793d))),
							vreinterpretq_u8_u16(vceqq_u16(vreinterpretq_u16_u8(vextq_u8(dataC, dataD, 4)), vdupq_n_u16(0x793d))),
							vreinterpretq_u8_u16(vceqq_u16(vreinterpretq_u16_u8(tmpData4), vdupq_n_u16(0x793d)))
						);
						match2EqA = vextq_u8(cmpEqA, cmpEqB, 2);
						match2EqB = vextq_u8(cmpEqB, cmpEqC, 2);
						match2EqC = vextq_u8(cmpEqC, cmpEqD, 2);
						uint8x16_t match3EqY = mergeCompares(
							vandq_u8(
								vceqq_u8(vextq_u8(dataA, dataB, 3), vdupq_n_u8('y')),
								match2EqA
							), vandq_u8(
								vceqq_u8(vextq_u8(dataB, dataC, 3), vdupq_n_u8('y')),
								match2EqB
							), vandq_u8(
								vceqq_u8(vextq_u8(dataC, dataD, 3), vdupq_n_u8('y')),
								match2EqC
							), vandq_u8(
								vceqq_u8(tmpData3, vdupq_n_u8('y')),
								match2EqD
							)
						);
						
						// merge \r\n and =y matches for tmpData4
						uint8x16_t match4End = vorrq_u8(
							match4Nl,
							vreinterpretq_u8_u16(vsriq_n_u16(vreinterpretq_u16_u8(match4EqY), vreinterpretq_u16_u8(match3EqY), 8))
						);
						// merge with \r\n.
						uint8x16_t match2NlDot = mergeCompares(match2NlDotA, match2NlDotB, match2NlDotC, match2NlDotD);
						match4End = vandq_u8(match4End, match2NlDot);
						// match \r\n=y
						uint8x16_t match1Nl = mergeCompares(match1NlA, match1NlB, match1NlC, match1NlD);
						uint8x16_t match3End = vandq_u8(match3EqY, match1Nl);
						// combine match sequences
						if(LIKELIHOOD(0.001, neon_vect_is_nonzero(vorrq_u8(match4End, match3End)))) {
							// terminator found
							// there's probably faster ways to do this, but reverting to scalar code should be good enough
							len += i;
							nextMask = decoder_set_nextMask<isRaw>(src+i, mask);
							break;
						}
					}
					uint8x16_t match2NlDotDMasked = vandq_u8(match2NlDotD, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128));
					uint8x16_t mergeKillDots = vpaddq_u8(
						vpaddq_u8(
							vandq_u8(match2NlDotA, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128)),
							vandq_u8(match2NlDotB, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128))
						),
						vpaddq_u8(
							vandq_u8(match2NlDotC, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128)),
							match2NlDotDMasked
						)
					);
					mergeKillDots = vpaddq_u8(mergeKillDots, mergeKillDots);
					uint64x2_t mergeKillDotsShifted = vshlq_n_u64(vreinterpretq_u64_u8(mergeKillDots), 2);
					mask |= vgetq_lane_u64(mergeKillDotsShifted, 0);
					cmpCombined = vorrq_u8(cmpCombined, vreinterpretq_u8_u64(mergeKillDotsShifted));
					nextMaskMix = vextq_u8(match2NlDotD, vdupq_n_u8(0), 14);
				} else if(searchEnd) {
					match2EqA = vextq_u8(cmpEqA, cmpEqB, 2);
					match2EqB = vextq_u8(cmpEqB, cmpEqC, 2);
					match2EqC = vextq_u8(cmpEqC, cmpEqD, 2);
					
					uint8x16_t match3EqYA = vandq_u8(match2EqA, vceqq_u8(vextq_u8(dataA, dataB, 3), vdupq_n_u8('y')));
					uint8x16_t match3EqYB = vandq_u8(match2EqB, vceqq_u8(vextq_u8(dataB, dataC, 3), vdupq_n_u8('y')));
					uint8x16_t match3EqYC = vandq_u8(match2EqC, vceqq_u8(vextq_u8(dataC, dataD, 3), vdupq_n_u8('y')));
					uint8x16_t match3EqYD = vandq_u8(match2EqD, vceqq_u8(vextq_u8(dataD, nextData, 3), vdupq_n_u8('y')));
					if(LIKELIHOOD(0.001, neon_vect_is_nonzero(vorrq_u8(
						vorrq_u8(match3EqYA, match3EqYB),
						vorrq_u8(match3EqYC, match3EqYD)
					)))) {
						uint8x16_t match1LfA = vceqq_u8(vextq_u8(dataA, dataB, 1), vdupq_n_u8('\n'));
						uint8x16_t match1LfB = vceqq_u8(vextq_u8(dataB, dataC, 1), vdupq_n_u8('\n'));
						uint8x16_t match1LfC = vceqq_u8(vextq_u8(dataC, dataD, 1), vdupq_n_u8('\n'));
						uint8x16_t match1LfD = vceqq_u8(vextq_u8(dataD, nextData, 1), vdupq_n_u8('\n'));
						uint8x16_t matchEnd = vorrq_u8(
							vorrq_u8(
								vandq_u8(match3EqYA, vandq_u8(match1LfA, cmpCrA)),
								vandq_u8(match3EqYB, vandq_u8(match1LfB, cmpCrB))
							),
							vorrq_u8(
								vandq_u8(match3EqYC, vandq_u8(match1LfC, cmpCrC)),
								vandq_u8(match3EqYD, vandq_u8(match1LfD, cmpCrD))
							)
						);
						if(LIKELIHOOD(0.001, neon_vect_is_nonzero(matchEnd))) {
							len += i;
							nextMask = decoder_set_nextMask<isRaw>(src+i, mask);
							break;
						}
					}
					if(isRaw)
						nextMaskMix = vdupq_n_u8(0);
				} else if(isRaw) // no \r_. found
					nextMaskMix = vdupq_n_u8(0);
			}
			
			// a spec compliant encoder should never generate sequences: ==, =\n and =\r, but we'll handle them to be spec compliant
			// the yEnc specification requires any character following = to be unescaped, not skipped over, so we'll deal with that
			// firstly, check for invalid sequences of = (we assume that these are rare, as a spec compliant yEnc encoder should not generate these)
			uint64_t maskEqShift1 = (maskEq << 1) | escFirst;
			if(LIKELIHOOD(0.0001, (mask & maskEqShift1) != 0)) {
				maskEq = fix_eqMask<uint64_t>(maskEq, maskEqShift1);
				
				unsigned char nextEscFirst = maskEq>>63;
				// next, eliminate anything following a `=` from the special char mask; this eliminates cases of `=\r` so that they aren't removed
				maskEq = (maskEq<<1) | escFirst;
				mask &= ~maskEq;
				escFirst = nextEscFirst;
				
				// unescape chars following `=`
#if defined(__GNUC__) && !defined(__clang__)
				// this seems to stop GCC9 producing slow code, for some reason... TODO: investigate why
				uint8x8_t _maskEqTemp = vreinterpret_u8_u64(vmov_n_u64(maskEq));
				uint8x16_t maskEqTemp = vcombine_u8(_maskEqTemp, vdup_n_u8(0));
#else
				uint8x16_t maskEqTemp = vreinterpretq_u8_u64(vmovq_n_u64(maskEq));
#endif
				cmpCombined = vbicq_u8(cmpCombined, maskEqTemp); // `mask &= ~maskEq` in vector form
				
				uint8x16_t vMaskEqA = vqtbl1q_u8(
					maskEqTemp,
					vmakeq_u8(0,0,0,0,0,0,0,0, 1,1,1,1,1,1,1,1)
				);
				maskEqTemp = vextq_u8(maskEqTemp, maskEqTemp, 2);
				uint8x16_t vMaskEqB = vqtbl1q_u8(
					maskEqTemp,
					vmakeq_u8(0,0,0,0,0,0,0,0, 1,1,1,1,1,1,1,1)
				);
				maskEqTemp = vextq_u8(maskEqTemp, maskEqTemp, 2);
				uint8x16_t vMaskEqC = vqtbl1q_u8(
					maskEqTemp,
					vmakeq_u8(0,0,0,0,0,0,0,0, 1,1,1,1,1,1,1,1)
				);
				maskEqTemp = vextq_u8(maskEqTemp, maskEqTemp, 2);
				uint8x16_t vMaskEqD = vqtbl1q_u8(
					maskEqTemp,
					vmakeq_u8(0,0,0,0,0,0,0,0, 1,1,1,1,1,1,1,1)
				);
				vMaskEqA = vtstq_u8(vMaskEqA, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128));
				vMaskEqB = vtstq_u8(vMaskEqB, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128));
				vMaskEqC = vtstq_u8(vMaskEqC, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128));
				vMaskEqD = vtstq_u8(vMaskEqD, vmakeq_u8(1,2,4,8,16,32,64,128, 1,2,4,8,16,32,64,128));
				
				dataA = vsubq_u8(
					dataA,
					vbslq_u8(vMaskEqA, vdupq_n_u8(64+42), vdupq_n_u8(42))
				);
				dataB = vsubq_u8(
					dataB,
					vbslq_u8(vMaskEqB, vdupq_n_u8(64+42), vdupq_n_u8(42))
				);
				dataC = vsubq_u8(
					dataC,
					vbslq_u8(vMaskEqC, vdupq_n_u8(64+42), vdupq_n_u8(42))
				);
				dataD = vsubq_u8(
					dataD,
					vbslq_u8(vMaskEqD, vdupq_n_u8(64+42), vdupq_n_u8(42))
				);
			} else {
				// no invalid = sequences found - we can cut out some things from above
				// this code path is a shortened version of above; it's here because it's faster, and what we'll be dealing with most of the time
				escFirst = (maskEq >> 63);
				
				dataA = vsubq_u8(
					dataA,
					vbslq_u8(
						vextq_u8(vdupq_n_u8(42), cmpEqA, 15),
						vdupq_n_u8(64+42),
						yencOffset
					)
				);
				dataB = vsubq_u8(
					dataB,
					vbslq_u8(
						vextq_u8(cmpEqA, cmpEqB, 15),
						vdupq_n_u8(64+42),
						vdupq_n_u8(42)
					)
				);
				dataC = vsubq_u8(
					dataC,
					vbslq_u8(
						vextq_u8(cmpEqB, cmpEqC, 15),
						vdupq_n_u8(64+42),
						vdupq_n_u8(42)
					)
				);
				dataD = vsubq_u8(
					dataD,
					vbslq_u8(
						vextq_u8(cmpEqC, cmpEqD, 15),
						vdupq_n_u8(64+42),
						vdupq_n_u8(42)
					)
				);
			}
			yencOffset = vsetq_lane_u8((escFirst << 6) | 42, yencOffset, 0);
			
			// all that's left is to 'compress' the data (skip over masked chars)
			uint64_t counts = vget_lane_u64(vreinterpret_u64_u8(vcnt_u8(vget_low_u8(cmpCombined))), 0);
			counts = 0x0808080808080808ULL - counts;
			counts += counts>>8;
			
			vst1q_u8(p, vqtbl1q_u8(
				dataA,
				vld1q_u8((uint8_t*)(compactLUT + (mask&0x7fff)))
			));
			p += counts & 0xff;
			mask >>= 16;
			vst1q_u8(p, vqtbl1q_u8(
				dataB,
				vld1q_u8((uint8_t*)(compactLUT + (mask&0x7fff)))
			));
			p += (counts>>16) & 0xff;
			mask >>= 16;
			vst1q_u8(p, vqtbl1q_u8(
				dataC,
				vld1q_u8((uint8_t*)(compactLUT + (mask&0x7fff)))
			));
			p += (counts>>32) & 0xff;
			mask >>= 16;
			vst1q_u8(p, vqtbl1q_u8(
				dataD,
				vld1q_u8((uint8_t*)(compactLUT + (mask&0x7fff)))
			));
			p += (counts>>48) & 0xff;
		} else {
			dataA = vsubq_u8(dataA, yencOffset);
			dataB = vsubq_u8(dataB, vdupq_n_u8(42));
			dataC = vsubq_u8(dataC, vdupq_n_u8(42));
			dataD = vsubq_u8(dataD, vdupq_n_u8(42));
			_vst1q_u8_x4(p, vcreate4_u8(dataA, dataB, dataC, dataD));
			p += sizeof(uint8x16_t)*4;
			escFirst = 0;
			yencOffset = vdupq_n_u8(42);
		}
	}
}
} // namespace

void RapidYenc::decoder_set_neon_funcs() {
	decoder_init_lut(compactLUT);
	_do_decode = &do_decode_simd<false, false, sizeof(uint8x16_t)*4, do_decode_neon<false, false> >;
	_do_decode_raw = &do_decode_simd<true, false, sizeof(uint8x16_t)*4, do_decode_neon<true, false> >;
	_do_decode_end_raw = &do_decode_simd<true, true, sizeof(uint8x16_t)*4, do_decode_neon<true, true> >;
	_decode_isa = ISA_LEVEL_NEON;
}
#else
void RapidYenc::decoder_set_neon_funcs() {}
#endif
