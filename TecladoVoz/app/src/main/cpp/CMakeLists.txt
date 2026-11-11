cmake_minimum_required(VERSION 3.18.1)
project(whisperjni C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp se compila como biblioteca estática dentro de libwhisperjni.so
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# El Samsung A15 (Cortex-A76) soporta ARMv8.2 con fp16 y dotprod: mucho más rápido.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+fp16+dotprod")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16+dotprod")
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp ${CMAKE_CURRENT_BINARY_DIR}/whisper_build)

add_library(whisperjni SHARED whisper_jni.cpp)
target_link_libraries(whisperjni whisper log)
