cmake_minimum_required(VERSION 3.22.1 FATAL_ERROR)
project(nuvioHi10Video C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED FFMPEG_SOURCE_DIR OR NOT DEFINED FFMPEG_BUILD_DIR)
    message(FATAL_ERROR "FFMPEG_SOURCE_DIR and FFMPEG_BUILD_DIR are required")
endif()

foreach(ffmpeg_lib avcodec swscale avutil)
    add_library(${ffmpeg_lib} STATIC IMPORTED)
    set_target_properties(${ffmpeg_lib} PROPERTIES
        IMPORTED_LOCATION "${FFMPEG_BUILD_DIR}/lib/lib${ffmpeg_lib}.a")
endforeach()

if(NOT ANDROID_ABI STREQUAL "armeabi-v7a")
    message(FATAL_ERROR "High10 MC integration currently supports armeabi-v7a only")
endif()
# This source uses FFmpeg's internal 7.0.2 DSP-table layout, not a public ABI.
file(STRINGS "${FFMPEG_BUILD_DIR}/include/libavcodec/version_major.h" ffmpeg_major
    REGEX "^#define LIBAVCODEC_VERSION_MAJOR[ \t]+61$")
if(NOT ffmpeg_major)
    message(FATAL_ERROR "High10 MC requires the pinned FFmpeg 7.0.2 build")
endif()
file(SHA256 "${FFMPEG_SOURCE_DIR}/libavcodec/h264qpel.h" qpel_header_sha)
if(NOT qpel_header_sha STREQUAL "1b0db4eb363698e853020f5aa6e77c7a5d7287e631efd892c97925dd81e3ff54")
    message(FATAL_ERROR "High10 MC requires the FFmpeg 7.0.2 internal qpel layout")
endif()
set(hooked_archive "${CMAKE_CURRENT_BINARY_DIR}/high10/libavcodec.a")
add_custom_command(OUTPUT "${hooked_archive}"
    COMMAND "${CMAKE_COMMAND}"
        "-DINPUT_ARCHIVE=${FFMPEG_BUILD_DIR}/lib/libavcodec.a"
        "-DOUTPUT_ARCHIVE=${hooked_archive}"
        "-DAR=${CMAKE_AR}" "-DOBJCOPY=${CMAKE_OBJCOPY}" "-DNM=${CMAKE_NM}"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/prepare_qpel_archive.cmake"
    DEPENDS "${FFMPEG_BUILD_DIR}/lib/libavcodec.a" prepare_qpel_archive.cmake
    VERBATIM)
add_custom_target(high10_qpel_archive DEPENDS "${hooked_archive}")
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION "${hooked_archive}")
add_library(nuvioHi10Video SHARED nuvio_hi10_video_jni.cc)
add_dependencies(nuvioHi10Video high10_qpel_archive)
target_compile_definitions(nuvioHi10Video PRIVATE
    NUVIO_HI10_DIAGNOSTICS=$<IF:$<CONFIG:Debug>,1,0>)
target_sources(nuvioHi10Video PRIVATE high10_qpel_family_neon.c)
set_source_files_properties(high10_qpel_family_neon.c PROPERTIES
    COMPILE_OPTIONS "-O3;-marm;-mfpu=neon"
    INCLUDE_DIRECTORIES "${FFMPEG_SOURCE_DIR}")
set_target_properties(nuvioHi10Video PROPERTIES
    OUTPUT_NAME "nuvioHi10Video"
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON)

target_include_directories(nuvioHi10Video PRIVATE
    "${FFMPEG_BUILD_DIR}/include")

find_library(android_lib android)
find_library(log_lib log)
find_library(z_lib z)
find_library(m_lib m)
find_library(dl_lib dl)

target_link_libraries(nuvioHi10Video PRIVATE
    avcodec swscale avutil
    ${android_lib} ${log_lib} ${z_lib} ${m_lib} ${dl_lib})

target_link_options(nuvioHi10Video PRIVATE
    "-Wl,-soname,libnuvioHi10Video.so"
    "-Wl,--exclude-libs,ALL"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/nuvio_hi10.map"
    "-Wl,-Bsymbolic"
    "-Wl,-z,max-page-size=16384")
