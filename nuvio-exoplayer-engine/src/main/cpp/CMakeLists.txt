cmake_minimum_required(VERSION 3.22.1)

project("nuvio-exoplayer-engine")

add_library(media3_exoplayer_jni SHARED
        exoplayer_jni.cpp
)

find_library(log-lib log)

target_link_libraries(media3_exoplayer_jni
        android
        log
        m
)
