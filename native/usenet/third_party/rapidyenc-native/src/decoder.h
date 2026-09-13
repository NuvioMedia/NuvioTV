#ifndef __YENC_DECODER_H
#define __YENC_DECODER_H

#include "hedley.h"

namespace RapidYenc {


// the last state that the decoder was in (i.e. last few characters processed)
// the state is needed for incremental decoders as its behavior is affected by what it processed last
// acronyms: CR = carriage return (\r), LF = line feed (\n), EQ = equals char, DT = dot char (.)
typedef enum {
	YDEC_STATE_CRLF, // default
	YDEC_STATE_EQ,
	YDEC_STATE_CR,
	YDEC_STATE_NONE,
	YDEC_STATE_CRLFDT,
	YDEC_STATE_CRLFDTCR,
	YDEC_STATE_CRLFEQ // may actually be "\r\n.=" in raw decoder
} YencDecoderState;

// end result for incremental processing (whether the end of the yEnc data was reached)
typedef enum {
	YDEC_END_NONE,    // end not reached
	YDEC_END_CONTROL, // \r\n=y sequence found, src points to byte after 'y'
	YDEC_END_ARTICLE  // \r\n.\r\n sequence found, src points to byte after last '\n'
} YencDecoderEnd;


extern YencDecoderEnd (*_do_decode)(const unsigned char**, unsigned char**, size_t, YencDecoderState*);
extern YencDecoderEnd (*_do_decode_raw)(const unsigned char**, unsigned char**, size_t, YencDecoderState*);
extern YencDecoderEnd (*_do_decode_end_raw)(const unsigned char**, unsigned char**, size_t, YencDecoderState*);
extern int _decode_isa;

static inline size_t decode(int isRaw, const void* src, void* dest, size_t len, YencDecoderState* state) {
	unsigned char* ds = (unsigned char*)dest;
	(*(isRaw ? _do_decode_raw : _do_decode))((const unsigned char**)&src, &ds, len, state);
	return ds - (unsigned char*)dest;
}

static inline YencDecoderEnd decode_end(const void** src, void** dest, size_t len, YencDecoderState* state) {
	return _do_decode_end_raw((const unsigned char**)src, (unsigned char**)dest, len, state);
}

void decoder_init();

static inline int decode_isa_level() {
	return _decode_isa;
}


} // namespace
#endif // defined(__YENC_DECODER_H)
