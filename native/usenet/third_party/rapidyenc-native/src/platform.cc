#include "common.h"
#ifdef PLATFORM_ARM
# if defined(_WIN32)
#  define WIN32_LEAN_AND_MEAN
#  define NOMINMAX
#  include <Windows.h>
# elif defined(__APPLE__)
#  include <sys/types.h>
#  include <sys/sysctl.h>
# elif defined(__has_include)
#  if __has_include(<sys/auxv.h>)
#   include <sys/auxv.h>
#   if __has_include(<asm/hwcap.h>)
#    include <asm/hwcap.h>
#   endif
#  endif
#  if defined(__ANDROID__) && __has_include(<cpu-features.h>)
#   include <cpu-features.h>
#  endif
# endif
bool RapidYenc::cpu_supports_neon() {
# if defined(AT_HWCAP)
#  if defined(__FreeBSD__) || defined(__OpenBSD__)
	unsigned long supported;
	elf_aux_info(AT_HWCAP, &supported, sizeof(supported));
#   ifdef __aarch64__
	return supported & HWCAP_ASIMD;
#   else
	return supported & HWCAP_NEON;
#   endif
#  else
#   ifdef __aarch64__
	return getauxval(AT_HWCAP) & HWCAP_ASIMD;
#   else
	return getauxval(AT_HWCAP) & HWCAP_NEON;
#   endif
#  endif
# elif defined(ANDROID_CPU_FAMILY_ARM)
#  ifdef __aarch64__
	return android_getCpuFeatures() & ANDROID_CPU_ARM64_FEATURE_ASIMD;
#  else
	return android_getCpuFeatures() & ANDROID_CPU_ARM_FEATURE_NEON;
#  endif
# elif defined(_WIN32)
	return IsProcessorFeaturePresent(PF_ARM_NEON_INSTRUCTIONS_AVAILABLE);
# elif defined(__APPLE__)
	int supported = 0;
	size_t len = sizeof(supported);
	if(sysctlbyname("hw.optional.neon", &supported, &len, NULL, 0))
		return false;
	return (bool)supported;
# endif
# ifdef __aarch64__
	return true; // assume NEON support on AArch64
# else
	return false;
# endif
}
#endif


#ifdef PLATFORM_X86
#ifdef _MSC_VER
# define _cpuid1(ar) __cpuid(ar, 1)
# define _cpuid1x(ar) __cpuid(ar, 0x80000001)
# if _MSC_VER >= 1600
#  define _cpuidX __cpuidex
#  include <immintrin.h>
#  define _GET_XCR() _xgetbv(_XCR_XFEATURE_ENABLED_MASK)
# else
// not supported
#  define _cpuidX(ar, eax, ecx) ar[0]=0, ar[1]=0, ar[2]=0, ar[3]=0
#  define _GET_XCR() 0
# endif
#else
# include <cpuid.h>
# define _cpuid1(ar) __cpuid(1, ar[0], ar[1], ar[2], ar[3])
# define _cpuid1x(ar) __cpuid(0x80000001, ar[0], ar[1], ar[2], ar[3])
# define _cpuidX(ar, eax, ecx) __cpuid_count(eax, ecx, ar[0], ar[1], ar[2], ar[3])
static inline int _GET_XCR() {
	int xcr0;
	__asm__ __volatile__("xgetbv" : "=a" (xcr0) : "c" (0) : "%edx");
	return xcr0;
}
#endif
// checks if CPU has 128-bit AVX units; currently not used as AVX2 is beneficial even on Zen1
// static bool cpu_has_slow_avx(cpuid1flag0) {
	// int family = ((cpuid1flag0>>8) & 0xf) + ((cpuid1flag0>>16) & 0xff0),
		// model = ((cpuid1flag0>>4) & 0xf) + ((cpuid1flag0>>12) & 0xf0);
	// return (
		   // family == 0x6f // AMD Bulldozer family
		// || family == 0x7f // AMD Jaguar/Puma family
		// || (family == 0x8f && (model == 0 /*Summit Ridge ES*/ || model == 1 /*Zen*/ || model == 8 /*Zen+*/ || model == 0x11 /*Zen APU*/ || model == 0x18 /*Zen+ APU*/ || model == 0x50 /*Subor Z+*/)) // AMD Zen1 family
		// || (family == 6 && model == 0xf) // Centaur/Zhaoxin; overlaps with Intel Core 2, but they don't support AVX
	// );
// }


int RapidYenc::cpu_supports_isa() {
	int flags[4];
	_cpuid1(flags);
	int ret = 0;
	
	if(flags[2] & 0x800000)
		ret |= ISA_FEATURE_POPCNT;
	int flags2[4];
	_cpuid1x(flags2);
	if(flags2[2] & 0x20) // ABM
		ret |= ISA_FEATURE_LZCNT | ISA_FEATURE_POPCNT;
	
	int family = ((flags[0]>>8) & 0xf) + ((flags[0]>>16) & 0xff0);
	int model = ((flags[0]>>4) & 0xf) + ((flags[0]>>12) & 0xf0);
	
	if(family == 6 && (
		model == 0x1C || model == 0x26 || model == 0x27 || model == 0x35 || model == 0x36 || model == 0x37 || model == 0x4A || model == 0x4C || model == 0x4D || model == 0x5A || model == 0x5D
	))
		// Intel Bonnell/Silvermont CPU with very slow PSHUFB and PBLENDVB - pretend SSSE3 doesn't exist
		return ret | ISA_LEVEL_SSE2;
	
	if(family == 0x5f && (model == 0 || model == 1 || model == 2))
		// AMD Bobcat with slow SSSE3 instructions - pretend it doesn't exist
		return ret | ISA_LEVEL_SSE2;
	
	if((flags[2] & 0x200) == 0x200) { // SSSE3
		if(family == 6 && (model == 0x5c || model == 0x5f || model == 0x7a || model == 0x9c))
			// Intel Goldmont/plus / Tremont with slow PBLENDVB
			return ret | ISA_LEVEL_SSSE3;
		
		if(flags[2] & 0x80000) { // SSE4.1
			if((flags[2] & 0x1C800000) == 0x1C800000) { // POPCNT + OSXSAVE + XSAVE + AVX
				int xcr = _GET_XCR() & 0xff; // ignore unused bits
				if((xcr & 6) == 6) { // AVX enabled
					int cpuInfo[4];
					_cpuidX(cpuInfo, 7, 0);
					if((cpuInfo[1] & 0x128) == 0x128 && (ret & ISA_FEATURE_LZCNT)) { // BMI2 + AVX2 + BMI1
						if((xcr & 0xE0) == 0xE0 && (cpuInfo[1] & 0xC0010000) == 0xC0010000) { // AVX512BW + AVX512VL + AVX512F
							if(cpuInfo[2] & 0x40)
								return ret | ISA_LEVEL_VBMI2;
							return ret | ISA_LEVEL_AVX3;
						}
						// AVX2 is beneficial even on Zen1
						return ret | ISA_LEVEL_AVX2;
					}
					return ret | ISA_LEVEL_AVX;
				}
			}
			return ret | ISA_LEVEL_SSE41;
		}
		return ret | ISA_LEVEL_SSSE3;
	}
	return ret | ISA_LEVEL_SSE2;
}

int RapidYenc::cpu_supports_crc_isa() {
	int flags[4];
	_cpuid1(flags);
	
	if((flags[2] & 0x80202) == 0x80202) { // SSE4.1 + SSSE3 + CLMUL
		if((flags[2] & 0x1C000000) == 0x1C000000) { // AVX + OSXSAVE + XSAVE
			int xcr = _GET_XCR() & 0xff; // ignore unused bits
			if((xcr & 6) == 6) { // AVX enabled
				int cpuInfo[4];
				_cpuidX(cpuInfo, 7, 0);
				if((cpuInfo[1] & 0x20) == 0x20 && (cpuInfo[2] & 0x400) == 0x400) { // AVX2 + VPCLMULQDQ
					return 2;
				}
			}
		}
		return 1;
	}
	return 0;
}

#endif // PLATFORM_X86

#ifdef __riscv
# if defined(__has_include)
#  if __has_include(<sys/auxv.h>)
#   include <sys/auxv.h>
#   if __has_include(<asm/hwcap.h>)
#    include <asm/hwcap.h>
#   endif
#  endif
# endif
bool RapidYenc::cpu_supports_rvv() {
# if defined(AT_HWCAP)
	unsigned long ret;
#  if defined(__FreeBSD__) || defined(__OpenBSD__)
	elf_aux_info(AT_HWCAP, &ret, sizeof(ret));
#  else
	ret = getauxval(AT_HWCAP);
#  endif
	return (ret & 0x20112D) == 0x20112D; // IMAFDCV; TODO: how to detect Z* features of 'G'?
# endif
	return false;
}
#endif

