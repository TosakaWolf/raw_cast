cmake_minimum_required(VERSION 3.22.1)

project("raw_cast")

# Bundled lz4 (added as submodule under cpp/lz4)
set(LZ4_BUILD_CLI OFF CACHE BOOL "Build lz4 program" FORCE)
add_subdirectory(lz4/build/cmake)

add_library(${CMAKE_PROJECT_NAME} SHARED
        raw_cast.cpp
        pixel_convert.cpp)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
        -O3 -ffast-math -fvisibility=hidden -fno-rtti -fno-exceptions)

# NEON for arm32 (arm64 has it baked in)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -mfpu=neon)
endif()

target_link_libraries(${CMAKE_PROJECT_NAME} PUBLIC
        android log jnigraphics lz4)

target_link_options(${CMAKE_PROJECT_NAME}
        PRIVATE
        -Wl,--version-script,${CMAKE_SOURCE_DIR}/raw_cast.ver
        -Wl,--no-undefined-version
)

set_target_properties(${CMAKE_PROJECT_NAME}
        PROPERTIES
        LINK_DEPENDS ${CMAKE_SOURCE_DIR}/raw_cast.ver
)
