#ifndef __YENC_ENCODER_COMMON
#define __YENC_ENCODER_COMMON

namespace RapidYenc {
	void encoder_sse2_init();
	void encoder_ssse3_init();
	void encoder_avx_init();
	void encoder_avx2_init();
	void encoder_vbmi2_init();
	void encoder_neon_init();
	void encoder_rvv_init();
	
	// lookup tables for scalar processing
	extern const unsigned char escapeLUT[256];
	extern const uint16_t escapedLUT[256];
	
	size_t do_encode_generic(int line_size, int* colOffset, const unsigned char* HEDLEY_RESTRICT src, unsigned char* HEDLEY_RESTRICT dest, size_t len, int doEnd);
}



template<void(&kernel)(int, int*, const uint8_t* HEDLEY_RESTRICT, uint8_t* HEDLEY_RESTRICT&, size_t&)>
static size_t do_encode_simd(int line_size, int* colOffset, const unsigned char* HEDLEY_RESTRICT src, unsigned char* HEDLEY_RESTRICT dest, size_t len, int doEnd) {
	if(len < 1) return 0;
	if(line_size < 12) { // short lines probably not worth processing in a SIMD way
		// we assume at least the first and last char exist in the line, and since the first char could be escaped, and SIMD encoder assumes at least one non-first/last char, assumption means that line size has to be >= 4
		return RapidYenc::do_encode_generic(line_size, colOffset, src, dest, len, doEnd);
	}
	
	const uint8_t* es = src + len;
	uint8_t* p = dest;
	
	if(*colOffset < 0) *colOffset = 0; // sanity check
	
	kernel(line_size, colOffset, es, p, len);
	
	// scalar loop to process remaining
	long i = -(long)len;
	if(*colOffset == 0 && i < 0) {
		uint8_t c = es[i++];
		if (LIKELIHOOD(0.0273, RapidYenc::escapedLUT[c] != 0)) {
			memcpy(p, RapidYenc::escapedLUT + c, 2);
			p += 2;
			*colOffset = 2;
		} else {
			*(p++) = c + 42;
			*colOffset = 1;
		}
	}
	while(i < 0) {
		uint8_t c = es[i++];
		if(*colOffset < line_size-1) {
			if(!RapidYenc::escapeLUT[c]) {
				p[0] = '=';
				p[1] = c+42+64;
				p += 2;
				(*colOffset) += 2;
			} else {
				*(p++) = RapidYenc::escapeLUT[c];
				(*colOffset) += 1;
			}
		} else {
			if(*colOffset < line_size) {
				if (RapidYenc::escapedLUT[c] && c != '.'-42) {
					memcpy(p, RapidYenc::escapedLUT + c, 2);
					p += 2;
				} else {
					*(p++) = c + 42;
				}
				if(i == 0) break;
				c = es[i++];
			}
			
			// handle EOL
			if (RapidYenc::escapedLUT[c]) {
				uint32_t w = UINT32_16_PACK(UINT16_PACK('\r', '\n'), (uint32_t)RapidYenc::escapedLUT[c]);
				memcpy(p, &w, sizeof(w));
				p += 4;
				*colOffset = 2;
			} else {
				uint32_t w = UINT32_PACK('\r', '\n', (uint32_t)(c+42), 0);
				memcpy(p, &w, sizeof(w));
				p += 3;
				*colOffset = 1;
			}
		}
	}
	
	if(doEnd) {
		// special case: if the last character is a space/tab, it needs to be escaped as it's the final character on the line
		unsigned char lc = *(p-1);
		if(lc == '\t' || lc == ' ') {
			p[-1] = '=';
			*p = lc+64;
			p++;
			(*colOffset)++;
		}
	}
	return p - dest;
}

#endif /* __YENC_ENCODER_COMMON */
