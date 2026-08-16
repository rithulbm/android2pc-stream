cmake_minimum_required(VERSION 3.31)
project(local_camera_sender LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(LOCAL_SENDER_ENABLE_TEST_SEAM "Expose bounded native self-tests to androidTest" OFF)

if(NOT ANDROID)
    message(FATAL_ERROR "The mobile native sender is Android-only")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(BOTAN_CPU "arm64")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(BOTAN_CPU "x86_64")
else()
    message(FATAL_ERROR "Unsupported Android ABI: ${ANDROID_ABI}")
endif()

set(BOTAN_OS "android")
set(SRT_BOTAN_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/botan")
set(ENABLE_APPS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_UNITTESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(ENABLE_LOGGING OFF CACHE BOOL "" FORCE)
set(ENABLE_HEAVY_LOGGING OFF CACHE BOOL "" FORCE)
set(ENABLE_HAICRYPT_LOGGING OFF CACHE BOOL "" FORCE)
set(ENABLE_ENCRYPTION ON CACHE BOOL "" FORCE)
set(ENABLE_AEAD_API_PREVIEW ON CACHE BOOL "" FORCE)
set(ENABLE_BONDING OFF CACHE BOOL "" FORCE)
set(ENABLE_MAXREXMITBW OFF CACHE BOOL "" FORCE)
set(USE_ENCLIB "botan" CACHE STRING "" FORCE)

add_subdirectory(third_party/srt EXCLUDE_FROM_ALL)
add_dependencies(srt_virtual botan)

add_library(
    local_sender
    SHARED
    bounded_packet_queue.cpp
    jni_bridge.cpp
    mpeg_ts_muxer.cpp
    native_sender.cpp
)

target_include_directories(
    local_sender
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/srt/srtcore"
    "${CMAKE_CURRENT_BINARY_DIR}/third_party/srt"
)
target_compile_options(
    local_sender
    PRIVATE
    -Wall
    -Wextra
    -Werror
    -Wconversion
    -Wsign-conversion
    -Wshadow
    -Wformat=2
)
target_compile_definitions(
    local_sender
    PRIVATE
    ENABLE_AEAD_API_PREVIEW=1
    $<$<BOOL:${LOCAL_SENDER_ENABLE_TEST_SEAM}>:LOCAL_SENDER_ENABLE_TEST_SEAM=1>
)
target_link_libraries(local_sender PRIVATE srt_static android log)
