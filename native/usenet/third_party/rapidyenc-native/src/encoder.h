#ifndef __YENC_ENCODER_H
#define __YENC_ENCODER_H

#include "hedley.h"

namespace RapidYenc {



extern size_t (*_do_encode)(int, int*, const unsigned char* HEDLEY_RESTRICT, unsigned char* HEDLEY_RESTRICT, size_t, int);
extern int _encode_isa;
static inline size_t encode(int line_size, int* colOffset, const void* HEDLEY_RESTRICT src, void* HEDLEY_RESTRICT dest, size_t len, int doEnd) {
	return (*_do_encode)(line_size, colOffset, (const unsigned char* HEDLEY_RESTRICT)src, (unsigned char*)dest, len, doEnd);
}
void encoder_init();
static inline int encode_isa_level() {
	return _encode_isa;
}



}
#endif
