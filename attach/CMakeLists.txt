# Eclient Attach probe (phase 1) — SELF-CONTAINED native library.
#
# No LeviLaunchroid, no preloader SDK. The only external dependency is Dobby
# (MIT-licensed inline-hook engine), fetched as source and statically linked
# into the final libeclient_attach.so, which the patcher script then injects
# into the Minecraft APK alongside a one-line System.loadLibrary call.
#
# Build (also done by .github/workflows/attach_build.yml):
#   cmake -S attach -B attach/build -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/<ver>/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
#     -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release
#   cmake --build attach/build
cmake_minimum_required(VERSION 3.22)
project(eclient_attach LANGUAGES CXX ASM)

if(NOT ANDROID)
    message(FATAL_ERROR
            "eclient_attach must be built with an Android NDK toolchain. "
            "It is a native library for the game's process, not a host target.")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ── Dobby (inline hook engine) ───────────────────────────────────────────────
# Pinned to the 'latest' release commit (2024-03-14) for reproducibility.
include(FetchContent)
FetchContent_Declare(
    dobby
    GIT_REPOSITORY https://github.com/jmpews/Dobby.git
    GIT_TAG        5dfc8546954ce3b3198132ab13fddb89ee92cdd7
)
set(DOBBY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_TEST    OFF CACHE BOOL "" FORCE)
set(DOBBY_DEBUG         OFF CACHE BOOL "" FORCE)

# Dobby's own source at this commit has small drift bugs (MemRange field →
# start() method, RuntimeModule.load_address → .base) — patched in-tree.
FetchContent_GetProperties(dobby)
if(NOT dobby_POPULATED)
    FetchContent_Populate(dobby)
    execute_process(
        COMMAND git apply --ignore-whitespace
                "${CMAKE_CURRENT_SOURCE_DIR}/patches/dobby-memrange-fix.patch"
        WORKING_DIRECTORY "${dobby_SOURCE_DIR}"
        RESULT_VARIABLE patch_rc
    )
    if(NOT patch_rc EQUAL 0)
        message(FATAL_ERROR "failed to apply patches/dobby-memrange-fix.patch to Dobby (rc=${patch_rc})")
    endif()
    # Register Dobby's CMakeLists (defines `dobby` + `dobby_static` targets).
    # Do NOT use FetchContent_MakeAvailable after a manual Populate — the
    # combination silently skips add_subdirectory on some CMake versions,
    # leaving dobby_static undefined and the link/include path broken.
    add_subdirectory("${dobby_SOURCE_DIR}" "${dobby_BINARY_DIR}" EXCLUDE_FROM_ALL)
endif()

add_library(eclient_attach SHARED
    src/probe.cpp
    src/sigscan.cpp
)
set_target_properties(eclient_attach PROPERTIES OUTPUT_NAME "eclient_attach")

target_include_directories(eclient_attach PRIVATE
    src
    "${dobby_SOURCE_DIR}/include"
)

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
target_link_libraries(eclient_attach PRIVATE ${log-lib} dobby_static)

# Strip symbols — the game does not need to see any of this.
if(CMAKE_STRIP)
    add_custom_command(TARGET eclient_attach POST_BUILD
        COMMAND "${CMAKE_STRIP}" --strip-unneeded "$<TARGET_FILE:eclient_attach>"
        COMMENT "Stripping $<TARGET_FILE_NAME:eclient_attach>"
        VERBATIM
    )
endif()
