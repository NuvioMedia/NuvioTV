cmake_minimum_required(VERSION 3.22.1)
project(nuvio_mpv_ass C)

# ass/ holds libass's public headers, 0.17.4 as libmpv carries it; the library is loaded from libmpv.
add_library(nuvio_mpv_ass SHARED ass_surface.c)
target_compile_options(nuvio_mpv_ass PRIVATE -O3 -Wall)
target_link_options(nuvio_mpv_ass PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(nuvio_mpv_ass PRIVATE android dl log)
