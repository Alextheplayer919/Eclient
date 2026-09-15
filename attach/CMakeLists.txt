# Eclient Attach probe (phase 1) — native LeviLaunchroid module build.
#
# This is a SEPARATE CMake project from the Android app (../app): it is only
# configured/built by .github/workflows/attach-build.yml with an NDK toolchain,
# or on your machine with:
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
            "It is a native module for the game's process, "
            "not a host/desktop target.")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ── LeviLaunchroid preloader SDK ─────────────────────────────────────────────
# Same approach as the official mod template (nilasdev/libblockoutline):
# fetch the SDK at a pinned tag; its headers give us pl::mod lifecycle,
# pl::memory::{resolveSignature, hook}, and PL_REGISTER_MOD.
include(FetchContent)
FetchContent_Declare(
    preloader_android
    GIT_REPOSITORY https://github.com/LiteLDev/preloader-android.git
    GIT_TAG 0.2.2
)
FetchContent_GetProperties(preloader_android)
if(NOT preloader_android_POPULATED)
    FetchContent_Populate(preloader_android)
    # preloader-android 0.2.2 keeps its CMakeLists at the repo root and
    # defines the `preloader` shared-lib target there; add it as a subproject
    # so we link the runtime directly (recommended by the mod template).
    add_subdirectory(
        "${preloader_android_SOURCE_DIR}"
        "${preloader_android_BINARY_DIR}"
        EXCLUDE_FROM_ALL
    )
endif()
set(PRELOADER_INCLUDE_DIR "${preloader_android_SOURCE_DIR}/include")

add_library(eclient_attach SHARED
    src/main.cpp
    src/EclientAttachMod.cpp
)
set_target_properties(eclient_attach PROPERTIES OUTPUT_NAME "eclient_attach")

target_include_directories(eclient_attach PRIVATE
    src
    "${PRELOADER_INCLUDE_DIR}"
)

target_compile_options(eclient_attach PRIVATE
    $<$<COMPILE_LANG_AND_ID:CXX,Clang,GNU>:-fvisibility=hidden -fvisibility-inlines-hidden>
)

# 16 KB page-size support (required by newer Android devices / Play policy).
target_link_options(eclient_attach PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log REQUIRED)
target_link_libraries(eclient_attach PRIVATE ${log-lib})

if(TARGET preloader)
    target_link_libraries(eclient_attach PRIVATE preloader)
else()
    # No preloader build available: leave pl:: symbols undefined for the
    # loader to resolve from the runtime preloader at dlopen time.
    target_link_options(eclient_attach PRIVATE "-Wl,--unresolved-symbols=ignore-all")
endif()

# ─────────────────────────────────────────────────────────────────────────
# Package as a LeviLaunchroid mod package (.levipack = a zip containing
# manifest.json + lib<mod>.so, per BedrockTools' package layout).
# ─────────────────────────────────────────────────────────────────────────
set(MOD_ID "eclient_attach")
set(MOD_VERSION "0.1.0")

configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/manifest.json.in"
    "${CMAKE_CURRENT_BINARY_DIR}/manifest.json"
    @ONLY
)

set(PACKAGE_ROOT "${CMAKE_CURRENT_BINARY_DIR}/package")
set(PACKAGE_FILE "${CMAKE_CURRENT_BINARY_DIR}/${MOD_ID}-${MOD_VERSION}-${CMAKE_ANDROID_ARCH_ABI}.levipack")

add_custom_target(levipack ALL
    COMMAND "${CMAKE_COMMAND}" -E rm -rf "${PACKAGE_ROOT}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${PACKAGE_ROOT}"
    COMMAND "${CMAKE_COMMAND}" -E copy "$<TARGET_FILE:eclient_attach>" "${PACKAGE_ROOT}/lib${MOD_ID}.so"
    COMMAND "${CMAKE_COMMAND}" -E copy "${CMAKE_CURRENT_BINARY_DIR}/manifest.json" "${PACKAGE_ROOT}/manifest.json"
    COMMAND "${CMAKE_COMMAND}" -E chdir "${PACKAGE_ROOT}" "${CMAKE_COMMAND}" -E tar "cf" "${PACKAGE_FILE}" --format=zip "manifest.json" "lib${MOD_ID}.so"
    DEPENDS eclient_attach
    COMMENT "Packaging ${MOD_ID} for LeviLaunchroid -> ${PACKAGE_FILE}"
    VERBATIM
)
