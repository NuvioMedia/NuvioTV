#include "common.h"
#include <stddef.h> // for size_t
#include "crc.h"

#ifdef __GNUC__
# define ctz32 __builtin_ctz
#elif defined(_MSC_VER)
static HEDLEY_ALWAYS_INLINE unsigned ctz32(uint32_t n) {
	unsigned long r;
	_BitScanForward(&r, n);
	return r;
}
#endif

namespace RapidYenc {
	void crc_clmul_set_funcs();
	void crc_clmul256_set_funcs();
	void crc_arm_set_funcs();
	void crc_pmull_set_funcs();
	void crc_riscv_set_funcs();
	
	extern const uint32_t crc_power[32];
	uint32_t crc32_multiply_generic(uint32_t a, uint32_t b);
	uint32_t crc32_shift_generic(uint32_t crc1, uint32_t n);
	
}