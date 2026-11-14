#include "decoder.h"
#include <string.h>

namespace RapidYenc {
	void decoder_set_sse2_funcs();
	void decoder_set_ssse3_funcs();
	void decoder_set_avx_funcs();
	void decoder_set_avx2_funcs();
	void decoder_set_vbmi2_funcs();
	void decoder_set_neon_funcs();
	void decoder_set_rvv_funcs();
	
	template<bool isRaw, bool searchEnd>
	YencDecoderEnd do_decode_scalar(const unsigned char** src, unsigned char** dest, size_t len, YencDecoderState* state);
}


#if defined(PLATFORM_ARM) && !defined(__aarch64__)
#define YENC_DEC_USE_THINTABLE 1
#endif

// TODO: need to support max output length somehow


template<typename T>
static inline T load_unaligned(const void* p) {
	T v;
	memcpy(&v, p, sizeof(T));
	return v;
}

template<bool isRaw, bool searchEnd, void(&kernel)(const uint8_t*, long&, unsigned char*&, unsigned char&, uint16_t&)>
static inline RapidYenc::YencDecoderEnd _do_decode_simd(size_t width, const unsigned char** src, unsigned char** dest, size_t len, RapidYenc::YencDecoderState* state) {
	using namespace RapidYenc;
	
	if(len <= width*2) return do_decode_scalar<isRaw, searchEnd>(src, dest, len, state);
	
	YencDecoderState tState = YDEC_STATE_CRLF;
	YencDecoderState* pState = state ? state : &tState;
	if((uintptr_t)(*src) & ((width-1))) {
		// find source memory alignment
		unsigned char* aSrc = (unsigned char*)(((uintptr_t)(*src) + (width-1)) & ~(width-1));
		int amount = (int)(aSrc - *src);
		len -= amount;
		YencDecoderEnd ended = do_decode_scalar<isRaw, searchEnd>(src, dest, amount, pState);
		if(ended) return ended;
	}
	
	size_t lenBuffer = width -1;
	if(searchEnd) lenBuffer += 3 + (isRaw?1:0);
	else if(isRaw) lenBuffer += 2;
	
	if(len > lenBuffer) {
		unsigned char *p = *dest; // destination pointer
		unsigned char escFirst = 0; // input character; first char needs escaping
		uint16_t nextMask = 0;
		// handle finicky case of special sequences straddled across initial boundary
		switch(*pState) {
			case YDEC_STATE_CRLF:
				if(isRaw && **src == '.') {
					nextMask = 1;
					if(searchEnd && load_unaligned<uint16_t>(*src +1) == UINT16_PACK('\r','\n')) {
						(*src) += 3;
						*pState = YDEC_STATE_CRLF;
						return YDEC_END_ARTICLE;
					}
					if(searchEnd && load_unaligned<uint16_t>(*src +1) == UINT16_PACK('=','y')) {
						(*src) += 3;
						*pState = YDEC_STATE_NONE;
						return YDEC_END_CONTROL;
					}
				}
				else if(searchEnd && load_unaligned<uint16_t>(*src) == UINT16_PACK('=','y')) {
					(*src) += 2;
					*pState = YDEC_STATE_NONE;
					return YDEC_END_CONTROL;
				}
				break;
			case YDEC_STATE_CR:
				if(isRaw && load_unaligned<uint16_t>(*src) == UINT16_PACK('\n','.')) {
					nextMask = 2;
					if(searchEnd && load_unaligned<uint16_t>(*src +2) == UINT16_PACK('\r','\n')) {
						(*src) += 4;
						*pState = YDEC_STATE_CRLF;
						return YDEC_END_ARTICLE;
					}
					if(searchEnd && load_unaligned<uint16_t>(*src +2) == UINT16_PACK('=','y')) {
						(*src) += 4;
						*pState = YDEC_STATE_NONE;
						return YDEC_END_CONTROL;
					}
				}
				else if(searchEnd && (load_unaligned<uint32_t>(*src) & 0xffffff) == UINT32_PACK('\n','=','y',0)) {
					(*src) += 3;
					*pState = YDEC_STATE_NONE;
					return YDEC_END_CONTROL;
				}
				break;
			case YDEC_STATE_CRLFDT:
				if(searchEnd && isRaw && load_unaligned<uint16_t>(*src) == UINT16_PACK('\r','\n')) {
					(*src) += 2;
					*pState = YDEC_STATE_CRLF;
					return YDEC_END_ARTICLE;
				}
				if(searchEnd && isRaw && load_unaligned<uint16_t>(*src) == UINT16_PACK('=','y')) {
					(*src) += 2;
					*pState = YDEC_STATE_NONE;
					return YDEC_END_CONTROL;
				}
				break;
			case YDEC_STATE_CRLFDTCR:
				if(searchEnd && isRaw && **src == '\n') {
					(*src) += 1;
					*pState = YDEC_STATE_CRLF;
					return YDEC_END_ARTICLE;
				}
				break;
			case YDEC_STATE_CRLFEQ:
				if(searchEnd && **src == 'y') {
					(*src) += 1;
					*pState = YDEC_STATE_NONE;
					return YDEC_END_CONTROL;
				}
				break;
			default: break; // silence compiler warning
		}
		escFirst = (*pState == YDEC_STATE_EQ || *pState == YDEC_STATE_CRLFEQ);
		
		// our algorithm may perform an aligned load on the next part, of which we consider 2 bytes (for \r\n. sequence checking)
		long dLen = (long)(len - lenBuffer);
		dLen = (dLen + (width-1)) & ~(width-1);
		
		kernel((const uint8_t*)(*src) + dLen, dLen, p, escFirst, nextMask);
		
		if(escFirst) *pState = YDEC_STATE_EQ; // escape next character
		else if(nextMask == 1) *pState = YDEC_STATE_CRLF; // next character is '.', where previous two were \r\n
		else if(nextMask == 2) *pState = YDEC_STATE_CR; // next characters are '\n.', previous is \r
		else *pState = YDEC_STATE_NONE;
		
		*src += dLen;
		len -= dLen;
		*dest = p;
	}
	
	// end alignment
	if(len)
		return do_decode_scalar<isRaw, searchEnd>(src, dest, len, pState);
	/** for debugging: ensure that the SIMD routine doesn't exit early
	if(len && !searchEnd) {
		const uint8_t* s = *src;
		unsigned char* p = *dest;
		int ended = do_decode_scalar<isRaw, searchEnd>(src, dest, len, pState);
		if(*src - s > width*2) {
			// this shouldn't happen, corrupt some data to fail the test
			while(p < *dest)
				*p++ = 0;
		}
		return ended;
	}
	*/
	return YDEC_END_NONE;
}

template<bool isRaw, bool searchEnd, size_t width, void(&kernel)(const uint8_t*, long&, unsigned char*&, unsigned char&, uint16_t&)>
static RapidYenc::YencDecoderEnd do_decode_simd(const unsigned char** src, unsigned char** dest, size_t len, RapidYenc::YencDecoderState* state) {
	return _do_decode_simd<isRaw, searchEnd, kernel>(width, src, dest, len, state);
}
template<bool isRaw, bool searchEnd, size_t(&getWidth)(), void(&kernel)(const uint8_t*, long&, unsigned char*&, unsigned char&, uint16_t&)>
static RapidYenc::YencDecoderEnd do_decode_simd(const unsigned char** src, unsigned char** dest, size_t len, RapidYenc::YencDecoderState* state) {
	return _do_decode_simd<isRaw, searchEnd, kernel>(getWidth(), src, dest, len, state);
}


#if defined(PLATFORM_X86) || defined(PLATFORM_ARM)
namespace RapidYenc {
	void decoder_init_lut(void* compactLUT);
}
#endif

template<bool isRaw>
static inline void decoder_set_nextMask(const uint8_t* src, size_t len, uint16_t& nextMask) {
	if(isRaw) {
		if(len != 0) { // have to gone through at least one loop cycle
			if(src[-2] == '\r' && src[-1] == '\n' && src[0] == '.')
				nextMask = 1;
			else if(src[-1] == '\r' && src[0] == '\n' && src[1] == '.')
				nextMask = 2;
			else
				nextMask = 0;
		}
	} else
		nextMask = 0;
}

// without backtracking
template<bool isRaw>
static inline uint16_t decoder_set_nextMask(const uint8_t* src, unsigned mask) {
	if(isRaw) {
		if(src[0] == '.')
			return mask & 1;
		if(src[1] == '.')
			return mask & 2;
	}
	return 0;
}

// resolve invalid sequences of = to deal with cases like '===='
// bit hack inspired from simdjson: https://youtu.be/wlvKAT7SZIQ?t=33m38s
template<typename T>
static inline T fix_eqMask(T mask, T maskShift1) {
	// isolate the start of each consecutive bit group (e.g. 01011101 -> 01000101)
	T start = mask & ~maskShift1;
	
	// this strategy works by firstly separating groups that start on even/odd bits
	// generally, it doesn't matter which one (even/odd) we pick, but clearing even groups specifically allows the escFirst bit in maskShift1 to work
	// (this is because the start of the escFirst group is at index -1, an odd bit, but we can't clear it due to being < 0, so we just retain all odd groups instead)
	
	const T even = (T)0x5555555555555555; // every even bit (01010101...)
	
	// obtain groups which start on an odd bit (clear groups that start on an even bit, but this leaves an unwanted trailing bit)
	T oddGroups = mask + (start & even);
	
	// clear even bits in odd groups, whilst conversely preserving even bits in even groups
	// the `& mask` also conveniently gets rid of unwanted trailing bits
	return (oddGroups ^ even) & mask;
}
