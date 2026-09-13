#include "crc_common.h"

#if defined(__riscv) && defined(__GNUC__) && (defined(__riscv_zbkc) || defined(__riscv_zbc))

#if __has_include(<riscv_bitmanip.h>)
# include <riscv_bitmanip.h>
# if __riscv_xlen == 64
#  define rv_clmul __riscv_clmul_64
#  define rv_clmulh __riscv_clmulh_64
# else
#  define rv_clmul __riscv_clmul_32
#  define rv_clmulh __riscv_clmulh_32
# endif
#else
static HEDLEY_ALWAYS_INLINE uintptr_t rv_clmul(uintptr_t x, uintptr_t y) {
	uintptr_t r;
	__asm__("clmul %0, %1, %2\n"
		: "=r"(r)
		: "r"(x), "r"(y)
		:);
	return r;
}
static HEDLEY_ALWAYS_INLINE uintptr_t rv_clmulh(uintptr_t x, uintptr_t y) {
	uintptr_t r;
	__asm__("clmulh %0, %1, %2\n"
		: "=r"(r)
		: "r"(x), "r"(y)
		:);
	return r;
}
#endif

// TODO: test big-endian
#if __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
# if __riscv_xlen == 64
#  define SWAP __builtin_bswap64
# else
#  define SWAP __builtin_bswap32
# endif
#else
# define SWAP(d) (d)
#endif
static HEDLEY_ALWAYS_INLINE uintptr_t read_partial(const void* p, unsigned sz) {
	uintptr_t data = 0;
	memcpy(&data, p, sz);
	return SWAP(data);
}
static HEDLEY_ALWAYS_INLINE uintptr_t read_full(const uintptr_t* p) {
	return SWAP(*p);
}
#undef SWAP

static uint32_t rv_crc_calc(uint32_t crc, const unsigned char *src, long len) {
	uintptr_t accum[4] = {};
	
	// note: constants here are bit-reflected and shifted left by 1
	// Zbc does also have clmulr to avoid the shift, but:
	// - there's no clmulhr, so for XLEN=64, just shift the constant instead to get the same result
	// - it's unavailable in Zbkc
	// - for XLEN=32, 2x constants is likely worth it to avoid the additional XORs in the loop
	
#if __riscv_xlen == 64
	const uint64_t MUL_HI = 0x15a546366 /*2^224*/, MUL_LO = 0xf1da05aa /*2^288*/;
	#define CLMULL rv_clmul
	#define CLMULH rv_clmulh
	
	accum[3] = rv_clmul(crc, 0xb66b1fa6); // 2^-32
#elif __riscv_xlen == 32
	const uint64_t MUL_HI = 0x140d44a2e /*2^128*/,  MUL_LO = 0x1751997d0 /*2^160*/;
	#define CLMULL(x, k) rv_clmul(x, k & 0xffffffff)
	#define CLMULH(x, k) (rv_clmulh(x, k & 0xffffffff) ^ (k > 0xffffffffULL ? (x) : 0))
	
	accum[2] = rv_clmul(crc, 0xb66b1fa6);
	accum[3] = rv_clmulh(crc, 0xb66b1fa6);
#else
	#error "Unknown __riscv_xlen"
#endif
	const size_t WS = sizeof(uintptr_t);
	
	// if src isn't word-aligned, process until it is so
	long initial_alignment = ((uintptr_t)src & (WS-1));
	long initial_process = WS - initial_alignment;
	if(initial_alignment && len >= initial_process) {
		unsigned shl = initial_alignment * 8, shr = initial_process * 8;
#if __riscv_xlen == 64
		accum[2] = accum[3] << shl;
#else
		accum[1] = accum[2] << shl;
		accum[2] = (accum[3] << shl) | (accum[2] >> shr);
#endif
		accum[3] = (read_partial(src, initial_process) << shl) | (accum[3] >> shr);
		src += initial_process;
		len -= initial_process;
	}
	
	// main processing loop
	const uintptr_t* srcW = (const uintptr_t*)src;
	while((len -= WS*4) >= 0) {
		uintptr_t tmpHi, tmpLo;
		tmpLo = CLMULL(accum[0], MUL_LO) ^ CLMULL(accum[1], MUL_HI);
		tmpHi = CLMULH(accum[0], MUL_LO) ^ CLMULH(accum[1], MUL_HI);
		accum[0] = tmpLo ^ read_full(srcW++);
		accum[1] = tmpHi ^ read_full(srcW++);
		
		tmpLo = CLMULL(accum[2], MUL_LO) ^ CLMULL(accum[3], MUL_HI);
		tmpHi = CLMULH(accum[2], MUL_LO) ^ CLMULH(accum[3], MUL_HI);
		accum[2] = tmpLo ^ read_full(srcW++);
		accum[3] = tmpHi ^ read_full(srcW++);
	}
	
	// process trailing bytes
	if(len & (WS*2)) {
		uintptr_t tmpLo = CLMULL(accum[0], MUL_LO) ^ CLMULL(accum[1], MUL_HI);
		uintptr_t tmpHi = CLMULH(accum[0], MUL_LO) ^ CLMULH(accum[1], MUL_HI);
		accum[0] = accum[2];
		accum[1] = accum[3];
		accum[2] = tmpLo ^ read_full(srcW++);
		accum[3] = tmpHi ^ read_full(srcW++);
	}
	if(len & WS) {
		uintptr_t tmpLo = CLMULL(accum[0], MUL_HI);
		uintptr_t tmpHi = CLMULH(accum[0], MUL_HI);
		accum[0] = accum[1];
		accum[1] = accum[2];
		accum[2] = accum[3] ^ tmpLo;
		accum[3] = tmpHi ^ read_full(srcW++);
	}
	
	size_t tail = len & (WS-1);
	if(tail) {
		unsigned shl = ((WS - tail) * 8), shr = tail * 8;
		uintptr_t tmp = accum[0] << shl;
		uintptr_t tmpLo = CLMULL(tmp, MUL_HI);
		uintptr_t tmpHi = CLMULH(tmp, MUL_HI);
		accum[0] = (accum[0] >> shr) | (accum[1] << shl);
		accum[1] = (accum[1] >> shr) | (accum[2] << shl);
		accum[2] = (accum[2] >> shr) | (accum[3] << shl);
		accum[3] = (accum[3] >> shr) | (read_partial(srcW, tail) << shl);
		accum[2] ^= tmpLo;
		accum[3] ^= tmpHi;
	}
	
	
	// done processing: fold everything down
#if __riscv_xlen == 64
	// fold 0,1 -> 2,3
	accum[2] ^= rv_clmul(accum[0], 0x1751997d0) ^ rv_clmul(accum[1], 0xccaa009e);
	accum[3] ^= rv_clmulh(accum[0], 0x1751997d0) ^ rv_clmulh(accum[1], 0xccaa009e);
	
	// fold 2->3
	accum[0] = rv_clmulh(accum[2], 0xccaa009e);
	accum[3] ^= rv_clmul(accum[2], 0xccaa009e);
	
	// fold 64b->32b
	accum[1] = rv_clmul(accum[3] & 0xffffffff, 0x163cd6124);
	accum[0] ^= accum[1] >> 32;
	accum[3] = accum[1] ^ (accum[3] >> 32);
	accum[3] <<= 32;
#else
	// fold 0,1 -> 2,3
	accum[2] ^= rv_clmul(accum[0], 0xccaa009e) ^ CLMULL(accum[1], 0x163cd6124);
	accum[3] ^= rv_clmulh(accum[0], 0xccaa009e) ^ CLMULH(accum[1], 0x163cd6124);
	
	// fold 2->3
	accum[0] = CLMULH(accum[2], 0x163cd6124);
	accum[3] ^= CLMULL(accum[2], 0x163cd6124);
#endif
	
	// reduction
	accum[3] = CLMULL(accum[3], 0xf7011641);
	accum[3] = CLMULH(accum[3], 0x1db710640);  // maybe consider clmulr for XLEN=32
	crc = accum[0] ^ accum[3];
	return crc;
	#undef CLMULL
	#undef CLMULH
}

static uint32_t do_crc32_incremental_rv_zbc(const void* data, size_t length, uint32_t init) {
	return ~rv_crc_calc(~init, (const unsigned char*)data, (long)length);
}


#if __riscv_xlen == 64
	// note that prod is shifted by 1 place to the right, due to bit-reflection
static uint32_t crc32_reduce_rv_zbc(uint64_t prod) {
	uint64_t t = rv_clmul(prod << 33, 0xf7011641);
	t = rv_clmulh(t, 0x1db710640);
	t ^= prod >> 31;
	return t;
}
#endif
static uint32_t crc32_multiply_rv_zbc(uint32_t a, uint32_t b) {
#if __riscv_xlen == 64
	uint64_t t = crc32_reduce_rv_zbc(rv_clmul(a, b));
#else
	uint32_t prodLo = rv_clmul(a, b);
	uint32_t prodHi = rv_clmulh(a, b);
	
	// fix prodHi for bit-reflection (clmulr would be ideal here)
	prodHi += prodHi;
	prodHi |= prodLo >> 31;
	prodLo += prodLo;
	
	uint32_t t = rv_clmul(prodLo, 0xf7011641);
	t ^= rv_clmulh(t, 0xdb710640);
	t ^= prodHi;
#endif
	return t;
}

#if defined(__GNUC__) || defined(_MSC_VER)
static uint32_t crc32_shift_rv_zbc(uint32_t crc1, uint32_t n) {
	// TODO: require Zbb for ctz
	uint32_t result = crc1;
#if __riscv_xlen == 64
	// for n<32, can shift directly
	uint64_t prod = result;
	prod <<= 31 ^ (n&31);
	n &= ~31;
	result = crc32_reduce_rv_zbc(prod);
#endif
	if(!n) return result;
	
	uint32_t result2 = RapidYenc::crc_power[ctz32(n)];
	n &= n-1;
	
	while(n) {
		result = crc32_multiply_rv_zbc(result, RapidYenc::crc_power[ctz32(n)]);
		n &= n-1;
		
		if(n) {
			result2 = crc32_multiply_rv_zbc(result2, RapidYenc::crc_power[ctz32(n)]);
			n &= n-1;
		}
	}
	return crc32_multiply_rv_zbc(result, result2);
}
#endif


void RapidYenc::crc_riscv_set_funcs() {
	_do_crc32_incremental = &do_crc32_incremental_rv_zbc;
	_crc32_multiply = &crc32_multiply_rv_zbc;
#if defined(__GNUC__) || defined(_MSC_VER)
	_crc32_shift = &crc32_shift_rv_zbc;
#endif
	_crc32_isa = ISA_FEATURE_ZBC;
}
#else
void RapidYenc::crc_riscv_set_funcs() {}
#endif
