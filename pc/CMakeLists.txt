cmake_minimum_required(VERSION 3.30)

project(local_camera_receiver VERSION 0.2.0 LANGUAGES C CXX)

if(NOT WIN32 OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "Local Camera Receiver v1 supports 64-bit Windows only")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")

option(LOCAL_CAMERA_BUILD_TESTS "Build receiver unit tests" ON)

set(OBS_COMMIT "0052d024fd6a5ff1aa04c76cbdffd3085a5dfacc")
set(OBS_SOURCE_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/../.install/obs-source/obs-studio-${OBS_COMMIT}"
    CACHE PATH "Path to the pinned OBS Studio 32.2.1 source tree")
set(SHARED_NATIVE_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/../mobile/app/src/main/cpp/third_party"
    CACHE PATH "Path to the repository's pinned SRT and Botan source trees")

if(NOT EXISTS "${OBS_SOURCE_ROOT}/libobs/obs-module.h")
    message(FATAL_ERROR "Pinned OBS headers are missing. Run pc/scripts/bootstrap-windows.ps1 first.")
endif()
if(NOT EXISTS "${SHARED_NATIVE_ROOT}/srt/CMakeLists.txt" OR
   NOT EXISTS "${SHARED_NATIVE_ROOT}/botan/configure.py")
    message(FATAL_ERROR "Pinned SRT 1.5.6/Botan 3.12.0 sources are missing")
endif()

configure_file(cmake/obsconfig.h.in generated/obsconfig.h @ONLY)

# Build a private, static SRT 1.5.6/Botan stack. OBS ships a different generic
# srt.dll, so the plugin must not import or overwrite that process-wide module.
set(BOTAN_OS "windows")
set(BOTAN_CPU "x86_64")
set(SRT_BOTAN_SOURCE_DIR "${SHARED_NATIVE_ROOT}/botan")
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
add_subdirectory("${SHARED_NATIVE_ROOT}/srt" srt EXCLUDE_FROM_ALL)
add_dependencies(srt_virtual botan)
# Botan's standalone FFI header defaults to dllimport under MSVC. SRT and
# Botan are both linked statically here, so declarations must remain local.
target_compile_definitions(srt_virtual PRIVATE BOTAN_IS_BEING_BUILT)

add_library(receiver_core STATIC
    src/bounded_packet_queue.cpp
    src/credential_codec.cpp
    src/credential_store.cpp
    src/network_addresses.cpp
    src/named_pipe_sink.cpp
    src/srt_listener.cpp
)
target_include_directories(receiver_core
    PUBLIC include
    PRIVATE
        "${SHARED_NATIVE_ROOT}/srt/srtcore"
        "${SHARED_NATIVE_ROOT}/srt/common"
        "${CMAKE_CURRENT_BINARY_DIR}/srt"
)
target_compile_definitions(receiver_core PRIVATE
    ENABLE_AEAD_API_PREVIEW=1
    UNICODE
    _UNICODE
    _WIN32_WINNT=0x0A00
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)
target_compile_options(receiver_core PRIVATE /W4 /WX /permissive- /utf-8 /guard:cf /sdl)
target_link_libraries(receiver_core PRIVATE
    srt_static
    botan
    advapi32
    bcrypt
    crypt32
    iphlpapi
    ole32
    secur32
    shell32
    ws2_32
)

add_library(local_camera_receiver MODULE
    src/obs_api.cpp
    src/obs_plugin.cpp
    src/receiver_source.cpp
    src/local-camera-receiver.def
)
set_target_properties(local_camera_receiver PROPERTIES
    PREFIX ""
    OUTPUT_NAME "local-camera-receiver"
)
target_include_directories(local_camera_receiver PRIVATE
    include
    "${CMAKE_CURRENT_BINARY_DIR}/generated"
)
target_include_directories(local_camera_receiver SYSTEM PRIVATE
    "${OBS_SOURCE_ROOT}"
    "${OBS_SOURCE_ROOT}/libobs"
)
target_compile_definitions(local_camera_receiver PRIVATE UNICODE _UNICODE _WIN32_WINNT=0x0A00 WIN32_LEAN_AND_MEAN NOMINMAX)
target_compile_options(local_camera_receiver PRIVATE /W4 /WX /permissive- /utf-8 /guard:cf /sdl)
target_link_options(local_camera_receiver PRIVATE /DYNAMICBASE /NXCOMPAT /CETCOMPAT)
target_link_libraries(local_camera_receiver PRIVATE receiver_core shell32)

add_executable(local_camera_pairing WIN32
    src/pairing_app.cpp
    third_party/qrcodegen/qrcodegen.cpp
)
set_target_properties(local_camera_pairing PROPERTIES OUTPUT_NAME "LocalCameraReceiver")
target_include_directories(local_camera_pairing PRIVATE include third_party/qrcodegen)
target_compile_definitions(local_camera_pairing PRIVATE UNICODE _UNICODE _WIN32_WINNT=0x0A00 WIN32_LEAN_AND_MEAN NOMINMAX)
target_compile_options(local_camera_pairing PRIVATE /W4 /WX /permissive- /utf-8 /guard:cf /sdl)
target_link_options(local_camera_pairing PRIVATE /DYNAMICBASE /NXCOMPAT /CETCOMPAT)
target_link_libraries(local_camera_pairing PRIVATE receiver_core comctl32 gdi32 shell32 user32)

set(PACKAGE_ROOT "${CMAKE_BINARY_DIR}/package/local-camera-receiver")
add_custom_command(TARGET local_camera_receiver POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${PACKAGE_ROOT}/bin/64bit"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${PACKAGE_ROOT}/data/locale"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:local_camera_receiver>" "${PACKAGE_ROOT}/bin/64bit/"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${CMAKE_CURRENT_SOURCE_DIR}/data/manifest.json" "${PACKAGE_ROOT}/data/"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${CMAKE_CURRENT_SOURCE_DIR}/data/locale/en-US.ini" "${PACKAGE_ROOT}/data/locale/"
)

add_custom_command(TARGET local_camera_pairing POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${PACKAGE_ROOT}/bin/64bit"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:local_camera_pairing>" "${PACKAGE_ROOT}/bin/64bit/"
)

add_dependencies(local_camera_receiver local_camera_pairing)

if(LOCAL_CAMERA_BUILD_TESTS)
    enable_testing()
    add_executable(receiver_tests
        tests/receiver_tests.cpp
    )
    target_include_directories(receiver_tests PRIVATE include "${CMAKE_CURRENT_BINARY_DIR}/generated")
    target_include_directories(receiver_tests SYSTEM PRIVATE "${OBS_SOURCE_ROOT}" "${OBS_SOURCE_ROOT}/libobs")
    target_compile_definitions(receiver_tests PRIVATE UNICODE _UNICODE _WIN32_WINNT=0x0A00 WIN32_LEAN_AND_MEAN NOMINMAX)
    target_compile_options(receiver_tests PRIVATE /W4 /WX /permissive- /utf-8 /guard:cf /sdl)
    target_link_libraries(receiver_tests PRIVATE receiver_core)
    add_test(NAME receiver_tests COMMAND receiver_tests)
endif()
