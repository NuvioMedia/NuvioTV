#include "crc_common.h"

#if defined(PLATFORM_X86) && !defined(__ILP32__) && !defined(YENC_DISABLE_CRCUTIL)
// Use crcutil for computing CRC32 (generic implementation)

#include "interface.h"
crcutil_interface::CRC* crc = NULL;
#define GENERIC_CRC_INIT crc = crcutil_interface::CRC::Create(0xEDB88320, 0, 32, true, 0, 0, 0, 0, NULL)
// instance never deleted... oh well...

static uint32_t do_crc32_incremental_generic(const void* data, size_t length, uint32_t init) {
	// use optimised ASM on x86 platforms
	crcutil_interface::UINT64 tmp = init;
	crc->Compute(data, length, &tmp);
	return (uint32_t)tmp;
}

#else
// don't use crcutil

static uint32_t* HEDLEY_RESTRICT crc_slice_table;
#if __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
# if defined(__GNUC__) || defined(__clang__)
#  define bswap32 __builtin_bswap32
# else
static inline uint32_t bswap32(uint32_t x) {
	return (x >> 24) | ((x >> 8) & 0x0000FF00) | ((x << 8) & 0x00FF0000) | (x << 24);
}
# endif
#endif

#define CRC32_GENERIC_CHAINS 4 // newer processors may prefer 8
static uint32_t do_crc32_incremental_generic(const void* data, size_t length, uint32_t init) {
	const uint32_t* crc_base_table = crc_slice_table + 4*256; // this also seems to help MSVC's optimiser, which otherwise keeps trying to add to crc_slice_table every time it's referenced
	uint32_t crc[CRC32_GENERIC_CHAINS]; // Clang seems to be more spill happy with an array over individual variables :(
	crc[0] = ~init;
	uint8_t* current8 = (uint8_t*)data;
	
	// align to multiple of 4
	if(((uintptr_t)current8 & 1) && length >= 1) {
		crc[0] = (crc[0] >> 8) ^ crc_base_table[(crc[0] & 0xFF) ^ *current8++];
		length--;
	}
	if(((uintptr_t)current8 & 2) && length >= 2) {
		crc[0] = (crc[0] >> 8) ^ crc_base_table[(crc[0] & 0xFF) ^ *current8++];
		crc[0] = (crc[0] >> 8) ^ crc_base_table[(crc[0] & 0xFF) ^ *current8++];
		length -= 2;
	}
	
	uint8_t* end8 = current8 + length;
	uint32_t* current = (uint32_t*)current8;
	if(length >= 8*CRC32_GENERIC_CHAINS-4) {
		size_t lenMain = ((length-(CRC32_GENERIC_CHAINS-1)*4) / 4);
		uint32_t* end = current + (lenMain / CRC32_GENERIC_CHAINS) * CRC32_GENERIC_CHAINS;
		for(int c=1; c<CRC32_GENERIC_CHAINS; c++)
			crc[c] = 0;
		while(current != end) {
#if __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
			#define CRC_PROC4(v, in) \
				v ^= bswap32(in); \
				v = crc_slice_table[v >> 24] ^ crc_slice_table[0x100L + ((v >> 16) & 0xff)] ^ crc_slice_table[0x200L + ((v >> 8) & 0xff)] ^ crc_slice_table[0x300L + (v & 0xff)]
#else
			#define CRC_PROC4(v, in) \
				v ^= (in); \
				v = crc_slice_table[v >> 24] ^ crc_slice_table[0x100L + ((v >> 16) & 0xff)] ^ crc_slice_table[0x200L + ((v >> 8) & 0xff)] ^ crc_slice_table[0x300L + (v & 0xff)]
#endif
			for(int c=0; c<CRC32_GENERIC_CHAINS; c++) {
				CRC_PROC4(crc[c], *current);
				current++;
			}
		}
		// aggregate accumulators
		current8 = (uint8_t*)current;
		#if (CRC32_GENERIC_CHAINS & (CRC32_GENERIC_CHAINS-1)) == 0
		// assume that lengths which are a multiple of 4/8/16/32 are common
		if((end8 - current8) & (CRC32_GENERIC_CHAINS*4)) {
			CRC_PROC4(crc[0], *current);
			current8 += 4;
			
			for(int c=1; c<CRC32_GENERIC_CHAINS; c++) {
				for(int i=0; i<4; i++)
					crc[c] = (crc[c] >> 8) ^ crc_base_table[(crc[c] & 0xff) ^ *current8++];
				crc[(c+1) & ~CRC32_GENERIC_CHAINS] ^= crc[c];
			}
		} else
		#endif
		#undef CRC_PROC4
		for(int c=1; c<CRC32_GENERIC_CHAINS; c++) {
			for(int i=0; i<4; i++)
				crc[0] = (crc[0] >> 8) ^ crc_base_table[(crc[0] & 0xff) ^ *current8++];
			crc[0] ^= crc[c];
		}
	}
	
	// tail loop
	while(current8 != end8) {
		crc[0] = (crc[0] >> 8) ^ crc_base_table[(crc[0] & 0xFF) ^ *current8++];
	}
	return ~crc[0];
}
static void generate_crc32_slice_table() {
	crc_slice_table = (uint32_t*)malloc(5*256*sizeof(uint32_t));
	// generate standard byte-by-byte table
	uint32_t* crc_base_table = crc_slice_table + 4*256;
	for(int v=0; v<256; v++) {
		uint32_t crc = v;
		for(int j = 0; j < 8; j++) {
			crc = (crc >> 1) ^ (-(int32_t)(crc & 1) & 0xEDB88320);
		}
		crc_base_table[v] = crc;
	}
	
	// generate slice-by-4 shifted across for X independent chains
	for(int v=0; v<256; v++) {
		uint32_t crc = crc_base_table[v];
		#if CRC32_GENERIC_CHAINS > 1
		for(int i=0; i<4*CRC32_GENERIC_CHAINS-5; i++)
			crc = (crc >> 8) ^ crc_base_table[crc & 0xff];
		for(int i=0; i<4; i++) {
			crc = (crc >> 8) ^ crc_base_table[crc & 0xff];
			crc_slice_table[i*256 + v] = crc;
		}
		#else
		for(int i=0; i<4; i++) {
			crc_slice_table[i*256 + v] = crc;
			crc = (crc >> 8) ^ crc_base_table[crc & 0xff];
		}
		#endif
	}
}

#define GENERIC_CRC_INIT generate_crc32_slice_table()
#endif


namespace RapidYenc {

// workaround MSVC complaining "unary minus operator applied to unsigned type, result still unsigned"
#define NEGATE(n) (uint32_t)(-((int32_t)(n)))
uint32_t crc32_multiply_generic(uint32_t a, uint32_t b) {
	uint32_t res = 0;
	for(int i=0; i<31; i++) {
		res ^= NEGATE(b>>31) & a;
		a = ((a >> 1) ^ (0xEDB88320 & NEGATE(a&1)));
		b <<= 1;
	}
	res ^= NEGATE(b>>31) & a;
	return res;
}
#undef NEGATE

const uint32_t crc_power[32] = { // pre-computed 2^(2^n)
	0x40000000, 0x20000000, 0x08000000, 0x00800000, 0x00008000, 0xedb88320, 0xb1e6b092, 0xa06a2517,
	0xed627dae, 0x88d14467, 0xd7bbfe6a, 0xec447f11, 0x8e7ea170, 0x6427800e, 0x4d47bae0, 0x09fe548f,
	0x83852d0f, 0x30362f1a, 0x7b5a9cc3, 0x31fec169, 0x9fec022a, 0x6c8dedc4, 0x15d6874d, 0x5fde7a4e,
	0xbad90e37, 0x2e4e5eef, 0x4eaba214, 0xa8a472c0, 0x429a969e, 0x148d302a, 0xc40ba6d0, 0xc4e22c3c
};

uint32_t crc32_shift_generic(uint32_t crc1, uint32_t n) {
	uint32_t result = crc1;
#ifdef __GNUC__
	while(n) {
		result = crc32_multiply_generic(result, crc_power[__builtin_ctz(n)]);
		n &= n-1;
	}
#elif defined(_MSC_VER)
	unsigned long power;
	while(_BitScanForward(&power, n)) {
		result = crc32_multiply_generic(result, crc_power[power]);
		n &= n-1;
	}
#else
	unsigned power = 0;
	while(n) {
		if(n & 1) {
			result = crc32_multiply_generic(result, crc_power[power]);
		}
		n >>= 1;
		power++;
	}
#endif
	return result;
}
} // namespace


namespace RapidYenc {
	crc_func _do_crc32_incremental = &do_crc32_incremental_generic;
	crc_mul_func _crc32_shift = &crc32_shift_generic;
	crc_mul_func _crc32_multiply = &crc32_multiply_generic;
	int _crc32_isa = ISA_GENERIC;
}



#if defined(PLATFORM_ARM) && defined(_WIN32)
# define WIN32_LEAN_AND_MEAN
# include <Windows.h>
#endif
#ifdef PLATFORM_ARM
# if defined(__APPLE__)
#  include <sys/types.h>
#  include <sys/sysctl.h>
# elif defined(__has_include)
#  if __has_include(<sys/auxv.h>)
#   include <sys/auxv.h>
#   if defined(__FreeBSD__) || defined(__OpenBSD__)
static unsigned long getauxval(unsigned long cap) {
	unsigned long ret;
	elf_aux_info(cap, &ret, sizeof(ret));
	return ret;
}
#   endif
#   if __has_include(<asm/hwcap.h>)
#    include <asm/hwcap.h>
#   endif
#  endif
#  if defined(__ANDROID__) && __has_include(<cpu-features.h>)
#   include <cpu-features.h>
#  endif
# endif
#endif
#if defined(__riscv) && defined(__has_include)
# if __has_include(<asm/hwprobe.h>)
#  include <asm/hwprobe.h>
#  include <asm/unistd.h>
#  include <unistd.h>
# endif
#endif

void RapidYenc::crc32_init() {
	GENERIC_CRC_INIT;
	
#ifdef PLATFORM_X86
	int support = cpu_supports_crc_isa();
	if(support == 2)
		crc_clmul256_set_funcs();
	else if(support == 1)
		crc_clmul_set_funcs();
#endif
#ifdef PLATFORM_ARM
# ifdef __APPLE__
	int supports_crc = 0;
	int supports_pmull = 0;
	size_t len = sizeof(supports_crc);
	if(sysctlbyname("hw.optional.armv8_crc32", &supports_crc, &len, NULL, 0))
		supports_crc = 0;
	if(sysctlbyname("hw.optional.arm.FEAT_PMULL", &supports_pmull, &len, NULL, 0))
		supports_pmull = 0;
# else
	bool supports_crc = false;
	bool supports_pmull = false;
#  if defined(AT_HWCAP2) && defined(HWCAP2_CRC32)
	supports_crc = getauxval(AT_HWCAP2) & HWCAP2_CRC32;
#  elif defined(AT_HWCAP) && defined(HWCAP_CRC32)
	supports_crc = getauxval(AT_HWCAP) & HWCAP_CRC32;
#  elif defined(ANDROID_CPU_FAMILY_ARM) && defined(__aarch64__)
	supports_crc = android_getCpuFeatures() & ANDROID_CPU_ARM64_FEATURE_CRC32;
	supports_pmull = android_getCpuFeatures() & ANDROID_CPU_ARM64_FEATURE_PMULL;
#  elif defined(ANDROID_CPU_FAMILY_ARM) /* aarch32 */
	supports_crc = android_getCpuFeatures() & ANDROID_CPU_ARM_FEATURE_CRC32;
	supports_pmull = android_getCpuFeatures() & ANDROID_CPU_ARM_FEATURE_PMULL;
#  elif defined(_WIN32)
	supports_crc = IsProcessorFeaturePresent(PF_ARM_V8_CRC32_INSTRUCTIONS_AVAILABLE);
	supports_pmull = IsProcessorFeaturePresent(PF_ARM_V8_CRYPTO_INSTRUCTIONS_AVAILABLE);
#  else
	#ifdef __ARM_FEATURE_CRC32
	supports_crc = true; /* assume available if compiled as such */
	#endif
	#ifdef __ARM_FEATURE_CRYPTO
	supports_pmull = true;
	#endif
#  endif
#  if defined(AT_HWCAP2) && defined(HWCAP2_PMULL)
	supports_pmull = getauxval(AT_HWCAP2) & HWCAP2_PMULL;
#  elif defined(AT_HWCAP) && defined(HWCAP_PMULL)
	supports_pmull = getauxval(AT_HWCAP) & HWCAP_PMULL;
#  endif
# endif
	
	if(supports_crc) {
		crc_arm_set_funcs();
		if(supports_pmull) crc_pmull_set_funcs();
	}
#endif
#ifdef __riscv
# if defined(RISCV_HWPROBE_KEY_IMA_EXT_0) && defined(__NR_riscv_hwprobe)
	const int rv_hwprobe_ext_zbc = 1 << 7, rv_hwprobe_ext_zbkc = 1 << 9;
	struct riscv_hwprobe p;
	p.key = RISCV_HWPROBE_KEY_IMA_EXT_0;
	if(!syscall(__NR_riscv_hwprobe, &p, 1, 0, NULL, 0)) {
		if(p.value & (rv_hwprobe_ext_zbc | rv_hwprobe_ext_zbkc)) {
			crc_riscv_set_funcs();
		}
	}
# endif
#endif
}
