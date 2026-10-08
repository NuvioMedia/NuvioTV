#include "common.h"

#include "decoder_common.h"
#include "decoder.h"



// TODO: add branch probabilities


// state var: refers to the previous state - only used for incremental processing
template<bool isRaw>
static size_t do_decode_noend_scalar(const unsigned char* src, unsigned char* dest, size_t len, RapidYenc::YencDecoderState* state) {
	using namespace RapidYenc;
	
	const unsigned char *es = src + len; // end source pointer
	unsigned char *p = dest; // destination pointer
	long i = -(long)len; // input position
	unsigned char c; // input character
	
	if(len < 1) return 0;
	
	if(isRaw) {
		
		if(state) switch(*state) {
			case YDEC_STATE_EQ:
				c = es[i];
				*p++ = c - 42 - 64;
				i++;
				if(c == '\r') {
					*state = YDEC_STATE_CR;
					if(i >= 0) return 0;
				} else {
					*state = YDEC_STATE_NONE;
					break;
				}
				// fall-thru
			case YDEC_STATE_CR:
				if(es[i] != '\n') break;
				i++;
				*state = YDEC_STATE_CRLF;
				if(i >= 0) return 0;
				// Else fall-thru
			case YDEC_STATE_CRLF:
				// skip past first dot
				if(es[i] == '.') i++;
				// fall-thru
			default: break; // silence compiler warnings
		} else // treat as YDEC_STATE_CRLF
			if(es[i] == '.') i++;
		
		for(; i < -2; i++) {
			c = es[i];
			switch(c) {
				case '\r':
					// skip past \r\n. sequences
					//i += (es[i+1] == '\n' && es[i+2] == '.') << 1;
					if(es[i+1] == '\n' && es[i+2] == '.')
						i += 2;
					// fall-thru
				case '\n':
					continue;
				case '=':
					c = es[i+1];
					*p++ = c - 42 - 64;
					i += (c != '\r'); // if we have a \r, reprocess character to deal with \r\n. case
					continue;
				default:
					*p++ = c - 42;
			}
		}
		if(state) *state = YDEC_STATE_NONE;
		
		if(i == -2) { // 2nd last char
			c = es[i];
			switch(c) {
				case '\r':
					if(state && es[i+1] == '\n') {
						*state = YDEC_STATE_CRLF;
						return p - dest;
					}
					// Else fall-thru
				case '\n':
					break;
				case '=':
					c = es[i+1];
					*p++ = c - 42 - 64;
					i += (c != '\r');
					break;
				default:
					*p++ = c - 42;
			}
			i++;
		}
		
		// do final char; we process this separately to prevent an overflow if the final char is '='
		if(i == -1) {
			c = es[i];
			if(c != '\n' && c != '\r' && c != '=') {
				*p++ = c - 42;
			} else if(state) {
				if(c == '=') *state = YDEC_STATE_EQ;
				else if(c == '\r') *state = YDEC_STATE_CR;
				else *state = YDEC_STATE_NONE;
			}
		}
		
	} else {
		
		if(state && *state == YDEC_STATE_EQ) {
			*p++ = es[i] - 42 - 64;
			i++;
			*state = YDEC_STATE_NONE;
		}
		
		/*for(i = 0; i < len - 1; i++) {
			c = src[i];
			if(c == '\n' || c == '\r') continue;
			unsigned char isEquals = (c == '=');
			i += isEquals;
			*p++ = src[i] - (42 + (isEquals << 6));
		}*/
		for(; i < -1; i++) {
			c = es[i];
			switch(c) {
				case '\n': case '\r': continue;
				case '=':
					i++;
					c = es[i] - 64;
			}
			*p++ = c - 42;
		}
		if(state) *state = YDEC_STATE_NONE;
		// do final char; we process this separately to prevent an overflow if the final char is '='
		if(i == -1) {
			c = es[i];
			if(c != '\n' && c != '\r' && c != '=') {
				*p++ = c - 42;
			} else
				if(state) *state = (c == '=' ? YDEC_STATE_EQ : YDEC_STATE_NONE);
		}
		
	}
	
	return p - dest;
}

template<bool isRaw>
static RapidYenc::YencDecoderEnd do_decode_end_scalar(const unsigned char** src, unsigned char** dest, size_t len, RapidYenc::YencDecoderState* state) {
	using namespace RapidYenc;
	
	const unsigned char *es = (*src) + len; // end source pointer
	unsigned char *p = *dest; // destination pointer
	long i = -(long)len; // input position
	unsigned char c; // input character
	
	if(len < 1) return YDEC_END_NONE;
	
#define YDEC_CHECK_END(s) if(i == 0) { \
	*state = s; \
	*src = es; \
	*dest = p; \
	return YDEC_END_NONE; \
}
	if(state) switch(*state) {
		case YDEC_STATE_CRLFEQ: do_decode_endable_scalar_ceq:
			if(es[i] == 'y') {
				*state = YDEC_STATE_NONE;
				*src = es+i+1;
				*dest = p;
				return YDEC_END_CONTROL;
			} // Else fall-thru
		case YDEC_STATE_EQ:
			c = es[i];
			*p++ = c - 42 - 64;
			i++;
			if(c != '\r') break;
			YDEC_CHECK_END(YDEC_STATE_CR)
			// fall-through
		case YDEC_STATE_CR:
			if(es[i] != '\n') break;
			i++;
			YDEC_CHECK_END(YDEC_STATE_CRLF)
			// fall-through
		case YDEC_STATE_CRLF: do_decode_endable_scalar_c0:
			if(es[i] == '.' && isRaw) {
				i++;
				YDEC_CHECK_END(YDEC_STATE_CRLFDT)
			} else if(es[i] == '=') {
				i++;
				YDEC_CHECK_END(YDEC_STATE_CRLFEQ)
				goto do_decode_endable_scalar_ceq;
			} else
				break;
			// fall-through
		case YDEC_STATE_CRLFDT:
			if(isRaw && es[i] == '\r') {
				i++;
				YDEC_CHECK_END(YDEC_STATE_CRLFDTCR)
			} else if(isRaw && es[i] == '=') { // check for dot-stuffed ending: \r\n.=y
				i++;
				YDEC_CHECK_END(YDEC_STATE_CRLFEQ)
				goto do_decode_endable_scalar_ceq;
			} else
				break;
			// fall-through
		case YDEC_STATE_CRLFDTCR:
			if(es[i] == '\n') {
				if(isRaw) {
					*state = YDEC_STATE_CRLF;
					*src = es + i + 1;
					*dest = p;
					return YDEC_END_ARTICLE;
				} else {
					i++;
					YDEC_CHECK_END(YDEC_STATE_CRLF)
					goto do_decode_endable_scalar_c0; // handle as CRLF
				}
			} else
				break;
		case YDEC_STATE_NONE: break; // silence compiler warning
	} else // treat as YDEC_STATE_CRLF
		goto do_decode_endable_scalar_c0;
	
	for(; i < -2; i++) {
		c = es[i];
		switch(c) {
			case '\r': if(es[i+1] == '\n') {
				if(isRaw && es[i+2] == '.') {
					// skip past \r\n. sequences
					i += 3;
					YDEC_CHECK_END(YDEC_STATE_CRLFDT)
					// check for end
					if(es[i] == '\r') {
						i++;
						YDEC_CHECK_END(YDEC_STATE_CRLFDTCR)
						if(es[i] == '\n') {
							*src = es + i + 1;
							*dest = p;
							*state = YDEC_STATE_CRLF;
							return YDEC_END_ARTICLE;
						} else i--;
					} else if(es[i] == '=') {
						i++;
						YDEC_CHECK_END(YDEC_STATE_CRLFEQ)
						if(es[i] == 'y') {
							*src = es + i + 1;
							*dest = p;
							*state = YDEC_STATE_NONE;
							return YDEC_END_CONTROL;
						} else {
							// escape char & continue
							c = es[i];
							*p++ = c - 42 - 64;
							i -= (c == '\r');
						}
					} else i--;
				}
				else if(es[i+2] == '=') {
					i += 3;
					YDEC_CHECK_END(YDEC_STATE_CRLFEQ)
					if(es[i] == 'y') {
						// ended
						*src = es + i + 1;
						*dest = p;
						*state = YDEC_STATE_NONE;
						return YDEC_END_CONTROL;
					} else {
						// escape char & continue
						c = es[i];
						*p++ = c - 42 - 64;
						i -= (c == '\r');
					}
				}
			} // fall-thru
			case '\n':
				continue;
			case '=':
				c = es[i+1];
				*p++ = c - 42 - 64;
				i += (c != '\r'); // if we have a \r, reprocess character to deal with \r\n. case
				continue;
			default:
				*p++ = c - 42;
		}
	}
	if(state) *state = YDEC_STATE_NONE;
	
	if(i == -2) { // 2nd last char
		c = es[i];
		switch(c) {
			case '\r':
				if(state && es[i+1] == '\n') {
					*state = YDEC_STATE_CRLF;
					*src = es;
					*dest = p;
					return YDEC_END_NONE;
				}
				// Else fall-thru
			case '\n':
				break;
			case '=':
				c = es[i+1];
				*p++ = c - 42 - 64;
				i += (c != '\r');
				break;
			default:
				*p++ = c - 42;
		}
		i++;
	}
	
	// do final char; we process this separately to prevent an overflow if the final char is '='
	if(i == -1) {
		c = es[i];
		if(c != '\n' && c != '\r' && c != '=') {
			*p++ = c - 42;
		} else if(state) {
			if(c == '=') *state = YDEC_STATE_EQ;
			else if(c == '\r') *state = YDEC_STATE_CR;
			else *state = YDEC_STATE_NONE;
		}
	}
#undef YDEC_CHECK_END
	
	*src = es;
	*dest = p;
	return YDEC_END_NONE;
}

template<bool isRaw, bool searchEnd>
RapidYenc::YencDecoderEnd RapidYenc::do_decode_scalar(const unsigned char** src, unsigned char** dest, size_t len, RapidYenc::YencDecoderState* state) {
	if(searchEnd)
		return do_decode_end_scalar<isRaw>(src, dest, len, state);
	*dest += do_decode_noend_scalar<isRaw>(*src, *dest, len, state);
	*src += len;
	return YDEC_END_NONE;
}


namespace RapidYenc {
	YencDecoderEnd (*_do_decode)(const unsigned char**, unsigned char**, size_t, YencDecoderState*) = &do_decode_scalar<false, false>;
	YencDecoderEnd (*_do_decode_raw)(const unsigned char**, unsigned char**, size_t, YencDecoderState*) = &do_decode_scalar<true, false>;
	YencDecoderEnd (*_do_decode_end_raw)(const unsigned char**, unsigned char**, size_t, YencDecoderState*) = &do_decode_end_scalar<true>;
	
	int _decode_isa = ISA_GENERIC;
	
	template YencDecoderEnd do_decode_scalar<true, true>(const unsigned char**, unsigned char**, size_t, YencDecoderState*);
}


#if defined(PLATFORM_X86) && defined(YENC_BUILD_NATIVE) && YENC_BUILD_NATIVE!=0
# if defined(__AVX2__) && !defined(YENC_DISABLE_AVX256)
#  include "decoder_avx2_base.h"
static inline void decoder_set_native_funcs() {
	ALIGN_ALLOC(lookups, sizeof(*lookups), 16);
	using namespace RapidYenc;
	decoder_init_lut(lookups->compact);
	_do_decode = &do_decode_simd<false, false, sizeof(__m256i)*2, do_decode_avx2<false, false, ISA_NATIVE> >;
	_do_decode_raw = &do_decode_simd<true, false, sizeof(__m256i)*2, do_decode_avx2<true, false, ISA_NATIVE> >;
	_do_decode_end_raw = &do_decode_simd<true, true, sizeof(__m256i)*2, do_decode_avx2<true, true, ISA_NATIVE> >;
	_decode_isa = ISA_NATIVE;
}
# else
#  include "decoder_sse_base.h"
static inline void decoder_set_native_funcs() {
	using namespace RapidYenc;
	decoder_sse_init(lookups);
	decoder_init_lut(lookups->compact);
	_do_decode = &do_decode_simd<false, false, sizeof(__m128i)*2, do_decode_sse<false, false, ISA_NATIVE> >;
	_do_decode_raw = &do_decode_simd<true, false, sizeof(__m128i)*2, do_decode_sse<true, false, ISA_NATIVE> >;
	_do_decode_end_raw = &do_decode_simd<true, true, sizeof(__m128i)*2, do_decode_sse<true, true, ISA_NATIVE> >;
	_decode_isa = ISA_NATIVE;
}
# endif
#endif


#if defined(PLATFORM_X86) || defined(PLATFORM_ARM)
void RapidYenc::decoder_init_lut(void* compactLUT) {
	#ifdef YENC_DEC_USE_THINTABLE
	const int tableSize = 8;
	#else
	const int tableSize = 16;
	#endif
	for(int i=0; i<(tableSize==8?256:32768); i++) {
		int k = i;
		uint8_t* res = (uint8_t*)compactLUT + i*tableSize;
		int p = 0;
		for(int j=0; j<tableSize; j++) {
			if(!(k & 1)) {
				res[p++] = j;
			}
			k >>= 1;
		}
		for(; p<tableSize; p++)
			res[p] = 0x80;
	}
}
#endif


void RapidYenc::decoder_init() {
#ifdef PLATFORM_X86
# if defined(YENC_BUILD_NATIVE) && YENC_BUILD_NATIVE!=0
	decoder_set_native_funcs();
# else
	int use_isa = cpu_supports_isa();
	if(use_isa >= ISA_LEVEL_VBMI2)
		decoder_set_vbmi2_funcs();
	else if(use_isa >= ISA_LEVEL_AVX2)
		decoder_set_avx2_funcs();
	else if(use_isa >= ISA_LEVEL_AVX)
		decoder_set_avx_funcs();
	else if(use_isa >= ISA_LEVEL_SSSE3)
		decoder_set_ssse3_funcs();
	else
		decoder_set_sse2_funcs();
# endif
#endif
#ifdef PLATFORM_ARM
	if(cpu_supports_neon())
		decoder_set_neon_funcs();
#endif
#ifdef __riscv
	if(cpu_supports_rvv())
		decoder_set_rvv_funcs();
#endif
}
