#include "common.h"
#include "encoder_common.h"

#ifdef __riscv_vector
#include "encoder.h"


static HEDLEY_ALWAYS_INLINE void encode_eol_handle_pre(const uint8_t* HEDLEY_RESTRICT _src, long& inpos, uint8_t*& outp, long& col, long lineSizeOffset) {
	// TODO: vectorize
	uint8_t c = _src[inpos++];
	if(HEDLEY_UNLIKELY(RapidYenc::escapedLUT[c] && c != '.'-42)) {
		memcpy(outp, &RapidYenc::escapedLUT[c], sizeof(uint16_t));
		outp += 2;
	} else {
		*(outp++) = c + 42;
	}
	
	c = _src[inpos++];
	if(LIKELIHOOD(0.0273, RapidYenc::escapedLUT[c]!=0)) {
		uint32_t w = UINT32_16_PACK(UINT16_PACK('\r', '\n'), (uint32_t)RapidYenc::escapedLUT[c]);
		memcpy(outp, &w, sizeof(w));
		outp += 4;
		col = lineSizeOffset + 2;
	} else {
		uint32_t w = UINT32_PACK('\r', '\n', (uint32_t)(c+42), 0);
		memcpy(outp, &w, sizeof(w));
		outp += 3;
		col = lineSizeOffset + 1;
	}
}

namespace RapidYenc {

HEDLEY_ALWAYS_INLINE void do_encode_rvv(int line_size, int* colOffset, const uint8_t* HEDLEY_RESTRICT srcEnd, uint8_t* HEDLEY_RESTRICT& dest, size_t& len) {
	size_t vl2 = RV(vsetvlmax_e8m2)(); // TODO: limit to line length
	// TODO: have a LMUL=1 variant if line_size < vl
	
	// offset position to enable simpler loop condition checking
	const int INPUT_OFFSET = vl2*2 -1; // extra chars for EOL handling, -1 to change <= to <
	if((intptr_t)len <= INPUT_OFFSET || line_size < (int)vl2*2) return;
	
	uint8_t *outp = dest;
	long inpos = -(long)len;
	long lineSizeOffset = -line_size +1;
	long col = *colOffset - line_size +1;
	
	inpos += INPUT_OFFSET;
	const uint8_t* _src = srcEnd - INPUT_OFFSET;
	
	if (HEDLEY_LIKELY(col == -line_size+1)) {
		uint8_t c = _src[inpos++];
		if (LIKELIHOOD(0.0273, escapedLUT[c] != 0)) {
			memcpy(outp, escapedLUT + c, 2);
			outp += 2;
			col += 2;
		} else {
			*(outp++) = c + 42;
			col += 1;
		}
	}
	if(HEDLEY_UNLIKELY(col >= 0)) {
		if(col == 0)
			encode_eol_handle_pre(_src, inpos, outp, col, lineSizeOffset);
		else {
			uint8_t c = _src[inpos++];
			if(LIKELIHOOD(0.0273, escapedLUT[c]!=0)) {
				uint32_t v = UINT32_16_PACK(UINT16_PACK('\r', '\n'), (uint32_t)escapedLUT[c]);
				memcpy(outp, &v, sizeof(v));
				outp += 4;
				col = 2-line_size + 1;
			} else {
				uint32_t v = UINT32_PACK('\r', '\n', (uint32_t)(c+42), 0);
				memcpy(outp, &v, sizeof(v));
				outp += 3;
				col = 2-line_size;
			}
		}
	}
	
	// vector constants
	const vuint8mf2_t ALT_SHIFT = RV(vreinterpret_v_u16mf2_u8mf2)(RV(vmv_v_x_u16mf2)(4, vl2));
	const uint8_t _MASK_EXPAND[] = {0xAA, 0xAB, 0xAE, 0xAF, 0xBA, 0xBB, 0xBE, 0xBF, 0xEA, 0xEB, 0xEE, 0xEF, 0xFA, 0xFB, 0xFE, 0xFF};
	const vuint8m1_t MASK_EXPAND = RV(vle8_v_u8m1)(_MASK_EXPAND, 16);
	
	
	// TODO: consider exploiting partial vector capability
	while(inpos < 0) {
		vuint8m2_t data = RV(vle8_v_u8m2)(_src + inpos, vl2);
		inpos += vl2;
		
		// search for special chars
		// TODO: vrgather strat
		
		vuint8m2_t tmpData = RV(vsub_vx_u8m2)(data, -42, vl2);
		vbool4_t cmp = RV(vmor_mm_b4)(
			RV(vmor_mm_b4)(
				RV(vmseq_vx_u8m2_b4)(data, -42, vl2),
				RV(vmseq_vx_u8m2_b4)(tmpData, '=', vl2),
				vl2
			),
			RV(vmor_mm_b4)(
				RV(vmseq_vx_u8m2_b4)(data, '\r'-42, vl2),
				RV(vmseq_vx_u8m2_b4)(data, '\n'-42, vl2),
				vl2
			),
			vl2
		);
		
#ifdef __riscv_v_intrinsic
		data = RV(vor_vx_u8m2_mu)(cmp, tmpData, tmpData, 64, vl2);
#else
		data = RV(vor_vx_u8m2_m)(cmp, tmpData, tmpData, 64, vl2);
#endif
		
		int idx;
		size_t count = RV(vcpop_m_b4)(cmp, vl2);
		if(count > 1) {
			// widen mask: 4b->8b
			vuint8mf4_t vcmp = RV_VEC_U8MF4_CAST(cmp);
			// TODO: use vwsll instead if available
			//    -  is clmul useful here?
			vuint8mf2_t xcmp = RV(vreinterpret_v_u16mf2_u8mf2)(RV(vwmulu_vx_u16mf2)(vcmp, 16, vl2));
			xcmp = RV(vsrl_vv_u8mf2)(xcmp, ALT_SHIFT, vl2);
			
			// expand mask by inserting '1' between each bit (0000abcd -> 1a1b1c1d)
			vuint8m1_t xcmpTmp = RV(vrgather_vv_u8m1)(MASK_EXPAND, RV(vlmul_ext_v_u8mf2_u8m1)(xcmp), vl2);
			vbool2_t cmpmask = RV_MASK_CAST(2, 8, xcmpTmp);
			
			// expand data and insert =
			// TODO: use vwsll instead if available
			vuint16m4_t data2 = RV(vzext_vf2_u16m4)(data, vl2);
			data2 = RV(vsll_vx_u16m4)(data2, 8, vl2);
			data2 = RV(vor_vx_u16m4)(data2, '=', vl2);
			
			// prune unneeded =
			vuint8m4_t dataTmp = RV(vreinterpret_v_u16m4_u8m4)(data2);
			vuint8m4_t final_data = RV(vcompress_vm_u8m4)(
#ifdef __riscv_v_intrinsic
				dataTmp, cmpmask, vl2*2
#else
				cmpmask, dataTmp, dataTmp, vl2*2
#endif
			);
			
			RV(vse8_v_u8m4)(outp, final_data, vl2*2);
			outp += vl2 + count;
			col += vl2 + count;
			
			if(col >= 0) {
				// we overflowed - find correct position to revert back to
				// TODO: stick with u8 type for vlmax <= 2048 (need to check if ok if vlmax == 2048)
				//   - considering that it's rare for colWidth > 128, maybe just don't support vectors that long
				vuint16m8_t xidx = RV(viota_m_u16m8)(cmpmask, vl2*2);
				vbool2_t discardmask = RV(vmsgeu_vx_u16m8_b2)(xidx, vl2 + count - col, vl2*2);
				long idx_revert = RV(vcpop_m_b2)(discardmask, vl2*2);
				
				outp -= col + (idx_revert & 1);
				inpos -= ((idx_revert+1) >> 1);
				
				goto _encode_eol_handle_pre;
			}
		} else {
			// 0 or 1 special characters
			{
				vbool4_t mask = RV(vmsbf_m_b4)(cmp, vl2);
				// TODO: is it better to shuffle this into two stores, instead of three?
				RV(vse8_v_u8m2_m)(mask, outp, data, vl2);
				idx = RV(vcpop_m_b4)(mask, vl2);
				outp[idx] = '=';
				RV(vse8_v_u8m2_m)(RV(vmnot_m_b4)(mask, vl2), outp+1, data, vl2);
				
				outp += vl2 + count;
				col += vl2 + count;
			}
			
			if(col >= 0) {
				if(count > 0) {
					idx = vl2 - idx;
					if(HEDLEY_UNLIKELY(col == idx)) {
						// this is an escape character, so line will need to overflow
						outp--;
					} else {
						inpos += (col > idx);
					}
				}
				outp -= col;
				inpos -= col;
				
				_encode_eol_handle_pre:
				encode_eol_handle_pre(_src, inpos, outp, col, lineSizeOffset);
			}
		}
	}
	
	*colOffset = col + line_size -1;
	dest = outp;
	len = -(inpos - INPUT_OFFSET);
}
} // namespace

void RapidYenc::encoder_rvv_init() {
	_do_encode = &do_encode_simd<do_encode_rvv>;
	_encode_isa = ISA_LEVEL_RVV;
}
#else
void RapidYenc::encoder_rvv_init() {}
#endif /* defined(__riscv_vector) */
