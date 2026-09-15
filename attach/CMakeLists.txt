# Eclient Attach probe (phase 1) — SELF-CONTAINED native library.
#
# No LeviLaunchroid, no preloader SDK, no network-fetched dependencies. The
# only third-party code is And64InlineHook (MIT, github.com/Rprop/And64InlineHook),
# vendored in src/hook/ and compiled together with the probe into
# libeclient_attach.so, which the patcher script then injects into the
# Minecraft APK alongside a one-line System.loadLibrary call.
#
# Build (also done by .github/workflows/attach_build.yml):
#   cmake -S attach -B attach/build -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/<ver>/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
#     -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release
#   cmake --build attach/build
cmake_minimum_required(VERSION 3.22)
project(eclient_attach LANGUAGES CXX)

if(NOT ANDROID)
    message(FATAL_ERROR
            "eclient_attach must be built with an Android NDK toolchain. "
            "It is a native library for the game's process, not a host target.")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(eclient_attach SHARED
    src/probe.cpp
    src/sigscan.cpp
    src/hook/And64InlineHook.cpp
)
set_target_properties(eclient_attach PROPERTIES OUTPUT_NAME "eclient_attach")

target_include_directories(eclient_attach PRIVATE src)

target_compile_options(eclient_attach PRIVATE
    $<$<COMPILE_LANG_AND_ID:CXX,Clang,GNU>:
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -fPIC
    >
)

# 16 KB page-size support (newer Android devices / Play requirement).
target_link_options(eclient_attach PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log REQUIRED)
target_link_libraries(eclient_attach PRIVATE ${log-lib})

# Strip symbols — the game does not need to see any of this.
if(CMAKE_STRIP)
    add_custom_command(TARGET eclient_attach POST_BUILD
        COMMAND "${CMAKE_STRIP}" --strip-unneeded "$<TARGET_FILE:eclient_attach>"
        COMMENT "Stripping $<TARGET_FILE_NAME:eclient_attach>"
        VERBATIM
    )
endif()
