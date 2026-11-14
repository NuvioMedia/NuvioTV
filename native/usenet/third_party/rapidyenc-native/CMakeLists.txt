cmake_minimum_required(VERSION 2.8.9...3.22)
project(rapidyenc)

option(BUILD_NATIVE "Optimise for and target only the build host's CPU; this build may not be re-distributable" OFF)
option(DISABLE_AVX256 "Disable the use of 256-bit AVX instructions on x86 processors" OFF)
option(DISABLE_CRCUTIL "Disable usage of crcutil library" OFF)
option(DISABLE_ENCODE "Exclude yEnc encoder from build" OFF)
option(DISABLE_DECODE "Exclude yEnc decoder from build" OFF)
option(DISABLE_CRC "Exclude CRC32 functions from build" OFF)

option(DISABLE_SHARED "Don't build shared library" OFF)
option(DISABLE_TOOL "Don't build tool applications" OFF)

include(CheckCXXCompilerFlag)
include(CheckIncludeFileCXX)
include(CheckCXXSymbolExists)

set(CMAKE_CXX_STANDARD 98)

if(NOT CMAKE_BUILD_TYPE)
	set(CMAKE_BUILD_TYPE Release)
endif()
if(NOT TARGET_ARCH)
	if(CMAKE_GENERATOR_PLATFORM)
		set(TARGET_ARCH ${CMAKE_GENERATOR_PLATFORM})
	else()
		set(TARGET_ARCH ${CMAKE_SYSTEM_PROCESSOR})
	endif()
endif()

if (${TARGET_ARCH} MATCHES "i386|i686|x86|x86_64|x64|amd64|AMD64|win32|Win32")
	set(IS_X86 TRUE)
	if(${TARGET_ARCH} MATCHES "x86_64|x64|amd64|AMD64")
		if(NOT MSVC)
			CHECK_CXX_SYMBOL_EXISTS(__ILP32__ "" IS_X32)
		endif()
	endif()
endif()
if (${TARGET_ARCH} MATCHES "arm|ARM|aarch64|arm64|ARM64")
	CHECK_CXX_SYMBOL_EXISTS(__aarch64__ "" COMPILER_HAS_AARCH64)
	CHECK_CXX_SYMBOL_EXISTS(_M_ARM64 "" COMPILER_HAS_ARM64)
	
	if(COMPILER_HAS_AARCH64 OR COMPILER_HAS_ARM64)
		set(IS_ARM64 TRUE)
	else()
		set(IS_ARM32 TRUE)
	endif()
endif()
if (${TARGET_ARCH} MATCHES "riscv64|rv64")
	set(IS_RISCV64 TRUE)
endif()
if (${TARGET_ARCH} MATCHES "riscv32|rv32")
	set(IS_RISCV32 TRUE)
endif()

if(DISABLE_AVX256)
	add_compile_definitions(YENC_DISABLE_AVX256=1)
endif()
if(DISABLE_ENCODE)
	add_compile_definitions(RAPIDYENC_DISABLE_ENCODE=1)
endif()
if(DISABLE_DECODE)
	add_compile_definitions(RAPIDYENC_DISABLE_DECODE=1)
endif()
if(DISABLE_CRC)
	add_compile_definitions(RAPIDYENC_DISABLE_CRC=1)
endif()
if(DISABLE_CRCUTIL OR DISABLE_CRC)
	add_compile_definitions(YENC_DISABLE_CRCUTIL=1)
endif()

if(NOT MSVC)
	if(BUILD_NATIVE)
		CHECK_CXX_COMPILER_FLAG("-march=native" COMPILER_SUPPORTS_NATIVE)
		if(COMPILER_SUPPORTS_NATIVE)
			add_compile_options(-march=native)
			add_compile_definitions(YENC_BUILD_NATIVE=1)
		endif()
	endif()
	if(NOT BUILD_NATIVE AND IS_X86)
		CHECK_CXX_COMPILER_FLAG("-mno-avx256-split-unaligned-load -mno-avx256-split-unaligned-store" COMPILER_SUPPORTS_NOSPLIT_AVX256)
		if(COMPILER_SUPPORTS_NOSPLIT_AVX256)
			add_compile_options(-mno-avx256-split-unaligned-load -mno-avx256-split-unaligned-store)
		endif()
	endif()
endif()

if(IS_ARM64)
	set(DECODER_NEON_FILE decoder_neon64.cc)
else()
	set(DECODER_NEON_FILE decoder_neon.cc)
endif()

set(SRC_DIR ./src)
set(RAPIDYENC_SOURCES
	${SRC_DIR}/platform.cc
)
if(NOT DISABLE_ENCODE)
	set(RAPIDYENC_SOURCES ${RAPIDYENC_SOURCES}
		${SRC_DIR}/encoder.cc
		${SRC_DIR}/encoder_sse2.cc
		${SRC_DIR}/encoder_ssse3.cc
		${SRC_DIR}/encoder_avx.cc
		${SRC_DIR}/encoder_avx2.cc
		${SRC_DIR}/encoder_vbmi2.cc
		${SRC_DIR}/encoder_neon.cc
		${SRC_DIR}/encoder_rvv.cc
	)
endif()
if(NOT DISABLE_DECODE)
	set(RAPIDYENC_SOURCES ${RAPIDYENC_SOURCES}
		${SRC_DIR}/decoder.cc
		${SRC_DIR}/decoder_sse2.cc
		${SRC_DIR}/decoder_ssse3.cc
		${SRC_DIR}/decoder_avx.cc
		${SRC_DIR}/decoder_avx2.cc
		${SRC_DIR}/decoder_vbmi2.cc
		${SRC_DIR}/${DECODER_NEON_FILE}
		${SRC_DIR}/decoder_rvv.cc
	)
endif()
if(NOT DISABLE_CRC)
	set(RAPIDYENC_SOURCES ${RAPIDYENC_SOURCES}
		${SRC_DIR}/crc.cc
		${SRC_DIR}/crc_folding.cc
		${SRC_DIR}/crc_folding_256.cc
		${SRC_DIR}/crc_arm.cc
		${SRC_DIR}/crc_arm_pmull.cc
		${SRC_DIR}/crc_riscv.cc
	)
	
	if(NOT DISABLE_CRCUTIL)
		set(CRCUTIL_DIR ./crcutil-1.0)
		set(RAPIDYENC_SOURCES ${RAPIDYENC_SOURCES}
			${CRCUTIL_DIR}/code/crc32c_sse4.cc 
			${CRCUTIL_DIR}/code/multiword_64_64_cl_i386_mmx.cc 
			${CRCUTIL_DIR}/code/multiword_64_64_gcc_amd64_asm.cc 
			${CRCUTIL_DIR}/code/multiword_64_64_gcc_i386_mmx.cc 
			${CRCUTIL_DIR}/code/multiword_64_64_intrinsic_i386_mmx.cc 
			${CRCUTIL_DIR}/code/multiword_128_64_gcc_amd64_sse2.cc 
			${CRCUTIL_DIR}/examples/interface.cc
		)
		
		include_directories(${CRCUTIL_DIR}/code ${CRCUTIL_DIR}/examples ${CRCUTIL_DIR}/tests)
		add_compile_definitions(CRCUTIL_USE_MM_CRC32=0)
	endif()
endif()


if(MSVC)
	set(RELEASE_COMPILE_FLAGS /GS- /Gy /sdl- /Oy /Oi)
	set(RELEASE_LINK_FLAGS /OPT:REF /OPT:ICF)
	add_compile_options(/W2 "$<$<NOT:$<CONFIG:Debug>>:${RELEASE_COMPILE_FLAGS}>")
	add_link_options("$<$<NOT:$<CONFIG:Debug>>:${RELEASE_LINK_FLAGS}>")
else()
	add_compile_options(-Wall -Wextra -Wno-unused-function -fno-exceptions -fwrapv -Wno-expansion-to-defined -fvisibility=hidden)
	set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti")
	if(NOT ${CMAKE_BUILD_TYPE} MATCHES "Debug")
		add_compile_options(-fomit-frame-pointer)
	endif()
	
	# posix_memalign may require _POSIX_C_SOURCE, but MacOS might not like it
	include(CheckSymbolExists)
	check_symbol_exists(posix_memalign "stdlib.h" HAVE_MEMALIGN)
	if(NOT HAVE_MEMALIGN)
		add_compile_definitions(_POSIX_C_SOURCE=200112L)
	endif()
endif()

add_library(rapidyenc OBJECT ${RAPIDYENC_SOURCES})
set_property(TARGET rapidyenc PROPERTY POSITION_INDEPENDENT_CODE 1)  # needed for shared build

if(MSVC)
	if(IS_X86)
		set_source_files_properties(${SRC_DIR}/encoder_avx.cc PROPERTIES COMPILE_OPTIONS /arch:AVX)
		set_source_files_properties(${SRC_DIR}/decoder_avx.cc PROPERTIES COMPILE_OPTIONS /arch:AVX)
		set_source_files_properties(${SRC_DIR}/encoder_avx2.cc PROPERTIES COMPILE_OPTIONS /arch:AVX2)
		set_source_files_properties(${SRC_DIR}/decoder_avx2.cc PROPERTIES COMPILE_OPTIONS /arch:AVX2)
		set_source_files_properties(${SRC_DIR}/encoder_vbmi2.cc PROPERTIES COMPILE_OPTIONS /arch:AVX512)
		set_source_files_properties(${SRC_DIR}/decoder_vbmi2.cc PROPERTIES COMPILE_OPTIONS /arch:AVX512)
		set_source_files_properties(${SRC_DIR}/crc_folding_256.cc PROPERTIES COMPILE_OPTIONS /arch:AVX2)
	endif()
endif()
if(NOT MSVC OR CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
	if(IS_X86)
		set_source_files_properties(${SRC_DIR}/encoder_sse2.cc PROPERTIES COMPILE_OPTIONS -msse2)
		set_source_files_properties(${SRC_DIR}/decoder_sse2.cc PROPERTIES COMPILE_OPTIONS -msse2)
		set_source_files_properties(${SRC_DIR}/encoder_ssse3.cc PROPERTIES COMPILE_OPTIONS -mssse3)
		set_source_files_properties(${SRC_DIR}/decoder_ssse3.cc PROPERTIES COMPILE_OPTIONS -mssse3)
		set_source_files_properties(${SRC_DIR}/encoder_avx.cc PROPERTIES COMPILE_OPTIONS "-mavx;-mpopcnt")
		set_source_files_properties(${SRC_DIR}/decoder_avx.cc PROPERTIES COMPILE_OPTIONS "-mavx;-mpopcnt")
		set_source_files_properties(${SRC_DIR}/encoder_avx2.cc PROPERTIES COMPILE_OPTIONS "-mavx2;-mpopcnt;-mbmi;-mbmi2;-mlzcnt")
		set_source_files_properties(${SRC_DIR}/decoder_avx2.cc PROPERTIES COMPILE_OPTIONS "-mavx2;-mpopcnt;-mbmi;-mbmi2;-mlzcnt")
		CHECK_CXX_COMPILER_FLAG("-mavx512vbmi2 -mavx512vl -mavx512bw" COMPILER_SUPPORTS_VBMI2)
		if(COMPILER_SUPPORTS_VBMI2)
			set_source_files_properties(${SRC_DIR}/encoder_vbmi2.cc PROPERTIES COMPILE_OPTIONS "-mavx512vbmi2;-mavx512vl;-mavx512bw;-mpopcnt;-mbmi;-mbmi2;-mlzcnt")
			set_source_files_properties(${SRC_DIR}/decoder_vbmi2.cc PROPERTIES COMPILE_OPTIONS "-mavx512vbmi2;-mavx512vl;-mavx512bw;-mpopcnt;-mbmi;-mbmi2;-mlzcnt")
		endif()
		set_source_files_properties(${SRC_DIR}/crc_folding.cc PROPERTIES COMPILE_OPTIONS "-mssse3;-msse4.1;-mpclmul")
		CHECK_CXX_COMPILER_FLAG("-mvpclmulqdq" COMPILER_SUPPORTS_VPCLMULQDQ)
		if(COMPILER_SUPPORTS_VPCLMULQDQ)
			set_source_files_properties(${SRC_DIR}/crc_folding_256.cc PROPERTIES COMPILE_OPTIONS "-mavx2;-mvpclmulqdq;-mpclmul")
		endif()
		
		if(IS_X32)
			add_compile_definitions(CRCUTIL_USE_ASM=0)
		endif()
	endif()
	
	if(IS_ARM32)
		CHECK_CXX_COMPILER_FLAG("-mfpu=neon -march=armv7-a" COMPILER_SUPPORTS_ARM32_NEON)
		if(COMPILER_SUPPORTS_ARM32_NEON)
			set_source_files_properties(${SRC_DIR}/encoder_neon.cc PROPERTIES COMPILE_OPTIONS "-mfpu=neon;-march=armv7-a")
			set_source_files_properties(${SRC_DIR}/${DECODER_NEON_FILE} PROPERTIES COMPILE_OPTIONS "-mfpu=neon;-march=armv7-a")
		endif()
		CHECK_CXX_COMPILER_FLAG("-march=armv8-a+crc -mfpu=fp-armv8" COMPILER_SUPPORTS_ARM_CRC)
		if(COMPILER_SUPPORTS_ARM_CRC)
			set_source_files_properties(${SRC_DIR}/crc_arm.cc PROPERTIES COMPILE_OPTIONS "-march=armv8-a+crc;-mfpu=fp-armv8")
		endif()
		CHECK_CXX_COMPILER_FLAG("-march=armv8-a+crypto+crc -mfpu=neon" COMPILER_SUPPORTS_ARM_CRYPTO)
		if(COMPILER_SUPPORTS_ARM_CRYPTO)
			set_source_files_properties(${SRC_DIR}/crc_arm_pmull.cc PROPERTIES COMPILE_OPTIONS "-march=armv8-a+crypto+crc;-mfpu=neon")
		endif()
	endif()
	if(IS_ARM64)
		CHECK_CXX_COMPILER_FLAG("-march=armv8-a+crc" COMPILER_SUPPORTS_ARM_CRC)
		if(COMPILER_SUPPORTS_ARM_CRC)
			set_source_files_properties(${SRC_DIR}/crc_arm.cc PROPERTIES COMPILE_OPTIONS -march=armv8-a+crc)
		endif()
		CHECK_CXX_COMPILER_FLAG("-march=armv8-a+crypto+crc" COMPILER_SUPPORTS_ARM_CRYPTO)
		if(COMPILER_SUPPORTS_ARM_CRYPTO)
			set_source_files_properties(${SRC_DIR}/crc_arm_pmull.cc PROPERTIES COMPILE_OPTIONS -march=armv8-a+crypto+crc)
		endif()
	endif()
	
	if(IS_RISCV64)
		CHECK_CXX_COMPILER_FLAG("-march=rv64gcv" COMPILER_SUPPORTS_RVV)
		if(COMPILER_SUPPORTS_RVV)
			set_source_files_properties(${SRC_DIR}/encoder_rvv.cc PROPERTIES COMPILE_OPTIONS -march=rv64gcv)
			set_source_files_properties(${SRC_DIR}/decoder_rvv.cc PROPERTIES COMPILE_OPTIONS -march=rv64gcv)
		endif()
		CHECK_CXX_COMPILER_FLAG("-march=rv64gc_zbkc" COMPILER_SUPPORTS_ZBKC)
		if(COMPILER_SUPPORTS_ZBKC)
			set_source_files_properties(${SRC_DIR}/crc_riscv.cc PROPERTIES COMPILE_OPTIONS -march=rv64gc_zbkc)
		endif()
	endif()
	if(IS_RISCV32)
		CHECK_CXX_COMPILER_FLAG("-march=rv32gcv" COMPILER_SUPPORTS_RVV)
		if(COMPILER_SUPPORTS_RVV)
			set_source_files_properties(${SRC_DIR}/encoder_rvv.cc PROPERTIES COMPILE_OPTIONS -march=rv32gcv)
			set_source_files_properties(${SRC_DIR}/decoder_rvv.cc PROPERTIES COMPILE_OPTIONS -march=rv32gcv)
		endif()
		CHECK_CXX_COMPILER_FLAG("-march=rv32gc_zbkc" COMPILER_SUPPORTS_ZBKC)
		if(COMPILER_SUPPORTS_ZBKC)
			set_source_files_properties(${SRC_DIR}/crc_riscv.cc PROPERTIES COMPILE_OPTIONS -march=rv32gc_zbkc)
		endif()
	endif()
endif()


add_library(rapidyenc_static STATIC $<TARGET_OBJECTS:rapidyenc> ./rapidyenc.cc)
# suggestion from https://stackoverflow.com/a/67027398/459150
set_target_properties(rapidyenc_static PROPERTIES OUTPUT_NAME rapidyenc ARCHIVE_OUTPUT_DIRECTORY rapidyenc_static)
if(NOT DISABLE_SHARED)
	add_library(rapidyenc_shared SHARED $<TARGET_OBJECTS:rapidyenc> ./rapidyenc.cc)
	target_compile_definitions(rapidyenc_shared PUBLIC BUILD_SHARED=1)
	set_target_properties(rapidyenc_shared PROPERTIES OUTPUT_NAME rapidyenc ARCHIVE_OUTPUT_DIRECTORY rapidyenc_shared)
endif()


# binaries
if(NOT DISABLE_TOOL)
	add_executable(rapidyenc_cli tool/cli.c)
	target_link_libraries(rapidyenc_cli rapidyenc_static)
	add_executable(rapidyenc_bench tool/bench.cc)
	target_link_libraries(rapidyenc_bench rapidyenc_static)
	target_compile_features(rapidyenc_bench PUBLIC cxx_std_11)
endif()
