## This script was taken from https://github.com/Tectu/botan-cmake and modified slightly for SRT integration.
##
## This module will automagically download the tarball of the specified Botan version and invoke the configure.py
## python script to generate the amalgamation files (botan_all.cpp and botan_all.h).
##
## Usage:
##   find_package(
##       Botan 3.0.0
##       COMPONENTS
##           system_rng
##           argon2
##           sha3
##       REQUIRED
##    )
##
##    target_link_libraries(
##        MyTarget
##        PRIVATE
##            botan
##    )
##

cmake_minimum_required(VERSION 3.19)
# Find python
find_package(
    Python
    COMPONENTS
        Interpreter
    REQUIRED
)

# Assemble version string
set(Botan_VERSION_STRING ${Botan_FIND_VERSION_MAJOR}.${Botan_FIND_VERSION_MINOR}.${Botan_FIND_VERSION_PATCH})

# Assemble download URL
if (NOT DEFINED SRT_BOTAN_SOURCE_DIR)
    message(FATAL_ERROR "SRT_BOTAN_SOURCE_DIR must point to the pinned vendored Botan source")
endif()
get_filename_component(botan_upstream_SOURCE_DIR "${SRT_BOTAN_SOURCE_DIR}" ABSOLUTE)
if (NOT EXISTS "${botan_upstream_SOURCE_DIR}/configure.py")
    message(FATAL_ERROR "Invalid vendored Botan source: ${botan_upstream_SOURCE_DIR}")
endif()

# Heavy lifting by cmake
include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(Botan DEFAULT_MSG Botan_VERSION_STRING)

## Function to generate a target named 'TARGET_NAME' with specific Botan modules enabled.
function(botan_generate TARGET_NAME MODULES)
    # The last N arguments are considered to be the modules list.
    # Here, we collect those in a list and join them with a comma separator ready to be passed to the configure.py script.
    foreach(module_index RANGE 1 ${ARGC}-2)
        list(APPEND modules_list ${ARGV${module_index}})
    endforeach()
    list(JOIN modules_list "," ENABLE_MODULES_LIST)

    # Determine botan compiler ID (--cc parameter of configure.py)
    set(BOTAN_COMPILER_ID ${CMAKE_CXX_COMPILER_ID})
    string(TOLOWER ${BOTAN_COMPILER_ID} BOTAN_COMPILER_ID)
    if (BOTAN_COMPILER_ID STREQUAL "gnu")
        set(BOTAN_COMPILER_ID "gcc")
    endif()

    # Run the configure.py script
    add_custom_command(
        OUTPUT botan_all.cpp botan_all.h
        COMMENT "Generating Botan amalgamation files botan_all.cpp and botan_all.h"
        COMMAND ${Python_EXECUTABLE}
            ${botan_upstream_SOURCE_DIR}/configure.py
            --quiet
            --cc-bin=${CMAKE_CXX_COMPILER}
            --cc=${BOTAN_COMPILER_ID}
            --disable-cc-tests
            --os=${BOTAN_OS}
            --cpu=${BOTAN_CPU}
            --disable-shared
            --amalgamation
            --prefix=${CMAKE_CURRENT_BINARY_DIR}/botan-install
            --with-build-dir=botan
            --minimized-build
            --enable-modules=${ENABLE_MODULES_LIST}
    )

    # Create target
    set(TARGET ${TARGET_NAME})
    add_library(${TARGET} STATIC)
    target_sources(
        ${TARGET}
        PUBLIC
            ${CMAKE_CURRENT_BINARY_DIR}/botan_all.h
        PRIVATE
            ${CMAKE_CURRENT_BINARY_DIR}/botan_all.cpp
    )
    target_include_directories(
        ${TARGET}
        INTERFACE
            ${CMAKE_CURRENT_BINARY_DIR}/botan/build/include/public
    )
    set_target_properties(
        ${TARGET}
        PROPERTIES
            POSITION_INDEPENDENT_CODE ON
    )

    add_custom_command(
        TARGET ${TARGET_NAME}
        POST_BUILD
        COMMENT "Copying build.h file"
        COMMAND ${CMAKE_COMMAND} -E copy    
        "${CMAKE_CURRENT_BINARY_DIR}/botan/build/build.h"        
        "${CMAKE_CURRENT_BINARY_DIR}/botan/build.h"    
    )

    add_custom_command(
        TARGET ${TARGET_NAME}
        POST_BUILD
        COMMENT "Copying compiler.h file"
        COMMAND ${CMAKE_COMMAND} -E copy    
        "${CMAKE_CURRENT_BINARY_DIR}/botan/build/include/public/botan/compiler.h"        
        "${CMAKE_CURRENT_BINARY_DIR}/botan/compiler.h"    
    )

    add_custom_command(
        TARGET ${TARGET_NAME}
        POST_BUILD
        COMMENT "Copying ffi.h file"
        COMMAND ${CMAKE_COMMAND} -E copy    
        "${CMAKE_CURRENT_BINARY_DIR}/botan/build/include/public/botan/ffi.h"        
        "${CMAKE_CURRENT_BINARY_DIR}/botan/ffi.h"    
    )

endfunction()
