#include "common.h"
#include "decoder_common.h"
#ifdef __riscv_vector


#ifdef __riscv_v_intrinsic
# define RV_vmerge_vxm_u8m2 RV(vmerge_vxm_u8m2)
# define RV_vmerge_vxm_u16m2 RV(vmerge_vxm_u16m2)
#else
# define RV_vmerge_vxm_u8m2(v, x, m, vl) RV(vmerge_vxm_u8m2)(m, v, x, vl)
# define RV_vmerge_vxm_u16m2(v, x, m, vl) RV(vmerge_vxm_u16m2)(m, v, x, vl)
#endif

#if defined(__riscv_v_intrinsic) && __riscv_v_intrinsic >= 12000
# define RV_VEC_CAST(masksz, vecsz, vec) RV(vreinterpret_v_b##masksz##_u##vecsz##m1)(vec)
#else
# define RV_VEC_CAST(masksz, vecsz, vec) *(vuint##vecsz##m1_t*)(&(vec))
#endif


template<int shift>
static inline vbool4_t mask_lshift(vbool4_t m, unsigned shiftIn, size_t vl) {
	vuint8m1_t mv = RV_VEC_CAST(4, 8, m);
	vuint8m1_t mvl = RV(vsll_vx_u8m1)(mv, shift, vl/8);
	vuint8m1_t mvr = RV(vsrl_vx_u8m1)(mv, 8-shift, vl/8);
	mvr = RV(vslide1up_vx_u8m1)(mvr, shiftIn, vl/8);
	
	return RV(vmor_mm_b4)(
		RV_MASK_CAST(4, 8, mvl), RV_MASK_CAST(4, 8, mvr), vl
	);
}
template<int shift>
static inline vbool64_t mask_lshift(vbool64_t m, unsigned shiftIn, size_t vl) {
	vuint8m1_t mv = RV_VEC_CAST(64, 8, m);
	vuint8m1_t mvl = RV(vsll_vx_u8m1)(mv, shift, vl/8);
	vuint8m1_t mvr = RV(vsrl_vx_u8m1)(mv, 8-shift, vl/8);
	mvr = RV(vslide1up_vx_u8m1)(mvr, shiftIn, vl/8);
	
	return RV(vmor_mm_b64)(
		RV_MASK_CAST(64, 8, mvl), RV_MASK_CAST(64, 8, mvr), vl
	);
}

static inline vuint8m2_t set_first_vu8(vuint8m2_t src, uint8_t item, size_t vl) {
#ifdef __riscv_v_intrinsic
	return RV(vmv_s_x_u8m2_tu)(src, item, vl);
#else
	vuint8m1_t m = RV(vslide1up_vx_u8m1)(RV(vmv_v_x_u8m1)(0, ~0), 1, ~0);
	return RV_vmerge_vxm_u8m2(src, item, RV_MASK_CAST(4, 8, m), vl);
#endif
}
static inline vuint16m2_t set_first_vu16(vuint16m2_t src, uint16_t item, size_t vl) {
#ifdef __riscv_v_intrinsic
	return RV(vmv_s_x_u16m2_tu)(src, item, vl);
#else
	vuint16m1_t m = RV(vslide1up_vx_u16m1)(RV(vmv_v_x_u16m1)(0, ~0), 1, ~0);
	return RV_vmerge_vxm_u16m2(src, item, RV_MASK_CAST(8, 16, m), vl);
#endif
}


namespace RapidYenc {

template<bool isRaw, bool searchEnd>
HEDLEY_ALWAYS_INLINE void do_decode_rvv(const uint8_t* src, long& len, unsigned char*& outp, unsigned char& escFirst, uint16_t& nextMask) {
	HEDLEY_ASSUME(escFirst == 0 || escFirst == 1);
	HEDLEY_ASSUME(nextMask == 0 || nextMask == 1 || nextMask == 2);
	
	size_t vl2 = RV(vsetvlmax_e8m2)();
	
	vuint8m2_t yencOffset = RV(vmv_v_x_u8m2)(42, vl2);
	if(escFirst) yencOffset = set_first_vu8(yencOffset, 42+64, vl2);
	vuint8m2_t lfCompare = RV(vmv_v_x_u8m2)('\n', vl2);
	if(nextMask && isRaw) {
		lfCompare = RV(vreinterpret_v_u16m2_u8m2)(
			set_first_vu16(RV(vreinterpret_v_u8m2_u16m2)(lfCompare), nextMask == 1 ? 0x0a2e /*".\n"*/ : 0x2e0a /*"\n."*/, vl2/2)
		);
	}
	
	// mask where only the highest bit is set
	vbool4_t lastBit = RV(vmseq_vx_u8m2_b4)(
		RV(vslide1down_vx_u8m2)(RV(vmv_v_x_u8m2)(0, vl2), 1, vl2),
		1, vl2
	);
	
	decoder_set_nextMask<isRaw>(src, len, nextMask);
	
	// TODO: consider exploiting partial vector capability
	long inpos;
	for(inpos = -len; inpos; inpos += vl2) {
		vuint8m2_t data = RV(vle8_v_u8m2)(src + inpos, vl2);
		
		// search for special chars
		vbool4_t cmpEq = RV(vmseq_vx_u8m2_b4)(data, '=', vl2);
		vbool4_t cmpCr = RV(vmseq_vx_u8m2_b4)(data, '\r', vl2);
		// note: cmp is always negated (unlike cmpEq/Cr)
		vbool4_t cmp = RV(vmnor_mm_b4)(
			RV(vmor_mm_b4)(cmpEq, cmpCr, vl2),
			isRaw ? RV(vmseq_vv_u8m2_b4)(data, lfCompare, vl2) : RV(vmseq_vx_u8m2_b4)(data, '\n', vl2),
			vl2
		);
		
		size_t numOutputChars = RV(vcpop_m_b4)(cmp, vl2);
		
		if(numOutputChars != vl2) {
			// dot-unstuffing + end detection
			if((isRaw || searchEnd) && RV(vcpop_m_b4)(RV(vmxnor_mm_b4)(cmp, cmpEq, vl2), vl2)) {
				uint32_t nextWord;
				if(!searchEnd) {
					memcpy(&nextWord, src + inpos + vl2, 2);
				} else {
					memcpy(&nextWord, src + inpos + vl2, 4);
				}
				vuint8m2_t nextData2 = RV(vreinterpret_v_u16m2_u8m2)(RV(vslide1down_vx_u16m2)(RV(vreinterpret_v_u8m2_u16m2)(data), nextWord, vl2/2));
				
				vbool4_t match2Cr_Dot, match3EqY;
				vuint8m2_t nextData3;
				if(isRaw) {
					match2Cr_Dot = RV(vmand_mm_b4)(cmpCr, RV(vmseq_vx_u8m2_b4)(nextData2, '.', vl2), vl2);
				}
				
				if(searchEnd) {
					nextData3 = RV(vslide1down_vx_u8m2)(nextData2, nextWord>>16, vl2);
					match3EqY = RV(vmand_mm_b4)(
						RV(vmseq_vx_u8m2_b4)(nextData2, '=', vl2),
						RV(vmseq_vx_u8m2_b4)(nextData3, 'y', vl2),
						vl2
					);
				}
				
				// find patterns of \r_.
				if(isRaw && LIKELIHOOD(0.001, RV(vcpop_m_b4)(match2Cr_Dot, vl2) > 0)) {
					// find \r\n.
					vuint8m2_t nextData1 = RV(vslide1down_vx_u8m2)(data, nextWord, vl2);
					vbool4_t match1Lf = RV(vmseq_vx_u8m2_b4)(nextData1, '\n', vl2);
					vbool4_t match2NlDot = RV(vmand_mm_b4)(match2Cr_Dot, match1Lf, vl2);
					
					if(searchEnd) {
						vbool4_t match1Nl = RV(vmand_mm_b4)(cmpCr, match1Lf, vl2);
						
						vuint8m2_t nextData4 = RV(vreinterpret_v_u32m2_u8m2)(RV(vslide1down_vx_u32m2)(RV(vreinterpret_v_u8m2_u32m2)(data), nextWord, vl2/4));
						
						// match instances of \r\n.\r\n and \r\n.=y
						vbool4_t match4Nl = RV(vmand_mm_b4)(
							RV(vmseq_vx_u8m2_b4)(nextData3, '\r', vl2),
							RV(vmseq_vx_u8m2_b4)(nextData4, '\n', vl2),
							vl2
						);
						vbool4_t match4EqY = RV(vmand_mm_b4)(
							RV(vmseq_vx_u8m2_b4)(nextData3, '=', vl2),
							RV(vmseq_vx_u8m2_b4)(nextData4, 'y', vl2),
							vl2
						);
						
						// merge \r\n and =y matches
						vbool4_t match4End = RV(vmor_mm_b4)(match4Nl, match4EqY, vl2);
						// merge with \r\n.
						match4End = RV(vmand_mm_b4)(match4End, match2NlDot, vl2);
						// merge \r\n=y
						vbool4_t match3End = RV(vmand_mm_b4)(match1Nl, match3EqY, vl2);
						
						vbool4_t matchEnd = RV(vmor_mm_b4)(match4End, match3End, vl2);
						
						// combine match sequences
						if(LIKELIHOOD(0.001, RV(vcpop_m_b4)(matchEnd, vl2) > 0)) {
							// terminator found
							len += inpos;
							nextMask = decoder_set_nextMask<isRaw>(src+inpos, ~RV(vmv_x_s_u8m1_u8)(RV_VEC_CAST(4, 8, cmp)));
							break;
						}
					}
					
					// shift match2NlDot by 2
					cmp = RV(vmandn_mm_b4)(cmp, mask_lshift<2>(match2NlDot, 0, vl2), vl2);
					numOutputChars = RV(vcpop_m_b4)(cmp, vl2);
					
					vuint8mf4_t nextNlDot = RV(vslidedown_vx_u8mf4)(
#ifndef __riscv_v_intrinsic
						RV(vmv_v_x_u8mf4)(0, vl2/8),
#endif
						RV_VEC_U8MF4_CAST(match2NlDot), vl2/8-1, vl2/8
					);
					nextNlDot = RV(vsrl_vx_u8mf4)(nextNlDot, 6, vl2/8);
					vuint8m1_t nextNlDotVec = RV(vlmul_ext_v_u8mf4_u8m1)(nextNlDot);
					lfCompare = RV_vmerge_vxm_u8m2(RV(vmv_v_x_u8m2)('\n', vl2), '.', RV_MASK_CAST(4, 8, nextNlDotVec), vl2);
				} else if(searchEnd) {
					if(LIKELIHOOD(0.001, RV(vcpop_m_b4)(match3EqY, vl2) != 0)) {
						vuint8m2_t nextData1 = RV(vslide1down_vx_u8m2)(data, nextWord, vl2);
						vbool4_t match1Lf = RV(vmseq_vx_u8m2_b4)(nextData1, '\n', vl2);
						vbool4_t matchEnd = RV(vmand_mm_b4)(RV(vmand_mm_b4)(match3EqY, cmpCr, vl2), match1Lf, vl2);
						if(LIKELIHOOD(0.001, RV(vcpop_m_b4)(matchEnd, vl2) > 0)) {
							len += inpos;
							nextMask = decoder_set_nextMask<isRaw>(src+inpos, ~RV(vmv_x_s_u8m1_u8)(RV_VEC_CAST(4, 8, cmp)));
							break;
						}
					}
					if(isRaw)
						lfCompare = RV(vmv_v_x_u8m2)('\n', vl2);
				} else if(isRaw) // no \r_. found
					lfCompare = RV(vmv_v_x_u8m2)('\n', vl2);
			}
			
			// the second character in an escape sequence
			vbool4_t cmpEqShift1 = mask_lshift<1>(cmpEq, escFirst, vl2);
			
			// a spec compliant encoder should never generate sequences: ==, =\n and =\r, but we'll handle them to be spec compliant
			// the yEnc specification requires any character following = to be unescaped, not skipped over, so we'll deal with that
			// firstly, check for invalid sequences of = (we assume that these are rare, as a spec compliant yEnc encoder should not generate these)
			if(LIKELIHOOD(0.0001, RV(vcpop_m_b4)(RV(vmandn_mm_b4)(cmpEqShift1, cmp, vl2), vl2) != 0)) {
				// replicate fix_eqMask, but in vector form
				vbool4_t groupStart = RV(vmandn_mm_b4)(cmpEq, cmpEqShift1, vl2);
				vuint8m1_t evenBitsV = RV(vmv_v_x_u8m1)(0x55, vl2);
				vbool4_t evenBits = RV_MASK_CAST(4, 8, evenBitsV);
				vbool4_t evenStart = RV(vmand_mm_b4)(groupStart, evenBits, vl2);
				
				// compute `cmpEq + evenStart` to obtain oddGroups
				vbool4_t oddGroups;
				vuint64m1_t cmpEq64 = RV_VEC_CAST(4, 64, cmpEq);
				vuint64m1_t evenStart64 = RV_VEC_CAST(4, 64, evenStart);
				vuint64m1_t oddGroups64;
				if(vl2 <= 64) {
					// no loop needed - single 64b add will work
					oddGroups64 = RV(vadd_vv_u64m1)(cmpEq64, evenStart64, 1);
				} else {
					// need to loop whilst the add causes a carry
					unsigned vl64 = vl2/64;
					vbool64_t carry = RV(vmadc_vv_u64m1_b64)(cmpEq64, evenStart64, vl64);
					carry = mask_lshift<1>(carry, 0, vl64);
					oddGroups64 = RV(vadd_vv_u64m1)(cmpEq64, evenStart64, 1);
					while(RV(vcpop_m_b64)(carry, vl64)) {
						vbool64_t nextCarry = RV(vmadc_vx_u64m1_b64)(oddGroups64, 1, vl64);
						oddGroups64 = RV(vadd_vx_u64m1_mu)(carry, oddGroups64, oddGroups64, 1, vl64);
						carry = mask_lshift<1>(nextCarry, 0, vl64);
					}
				}
				oddGroups = RV_MASK_CAST(4, 64, oddGroups64);
				
				cmpEq = RV(vmand_mm_b4)(RV(vmxor_mm_b4)(oddGroups, evenBits, vl2), cmpEq, vl2);
				
				cmpEqShift1 = mask_lshift<1>(cmpEq, escFirst, vl2);
				cmp = RV(vmor_mm_b4)(cmpEqShift1, cmp, vl2); // ~(~cmp & ~cmpEqShift1)
				numOutputChars = RV(vcpop_m_b4)(cmp, vl2);
			}
			escFirst = RV(vcpop_m_b4)(RV(vmand_mm_b4)(cmpEq, lastBit, vl2), vl2);
			
			data = RV(vsub_vv_u8m2)(data, RV_vmerge_vxm_u8m2(yencOffset, 64+42, cmpEqShift1, vl2), vl2);
			yencOffset = set_first_vu8(yencOffset, 42 | (escFirst<<6), vl2);
			
			// all that's left is to remove unwanted chars
#ifdef __riscv_v_intrinsic
			data = RV(vcompress_vm_u8m2)(data, cmp, vl2);
#else
			data = RV(vcompress_vm_u8m2)(cmp, data, data, vl2);
#endif
			RV(vse8_v_u8m2)(outp, data, vl2);
		} else {
			data = RV(vsub_vv_u8m2)(data, yencOffset, vl2);
			RV(vse8_v_u8m2)(outp, data, vl2);
			// TODO: should these be done at LMUL=1? or, it might not be worth this strategy (e.g. do an additional OR instead), considering the cost of LMUL=2
			yencOffset = RV(vmv_v_x_u8m2)(42, vl2);
			if(isRaw) lfCompare = RV(vmv_v_x_u8m2)('\n', vl2);
			escFirst = 0;
		}
		outp += numOutputChars;
	}
}

size_t decoder_rvv_width() {
	return RV(vsetvlmax_e8m2)();
}
} // namespace

void RapidYenc::decoder_set_rvv_funcs() {
	_do_decode = &do_decode_simd<false, false, decoder_rvv_width, do_decode_rvv<false, false> >;
	_do_decode_raw = &do_decode_simd<true, false, decoder_rvv_width, do_decode_rvv<true, false> >;
	_do_decode_end_raw = &do_decode_simd<true, true, decoder_rvv_width, do_decode_rvv<true, true> >;
	_decode_isa = ISA_LEVEL_RVV;
}
#else
void RapidYenc::decoder_set_rvv_funcs() {}
#endif
