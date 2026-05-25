cmake_minimum_required(VERSION 3.22.1)
project(lobby_vr)

include(FetchContent)
FetchContent_Declare(
    OpenXR-SDK
    URL https://github.com/KhronosGroup/OpenXR-SDK/archive/refs/tags/release-1.0.34.tar.gz
)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_API_LAYERS OFF CACHE BOOL "" FORCE)
set(DYNAMIC_LOADER OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(OpenXR-SDK)

add_library(lobby_vr SHARED main.cpp)

target_compile_definitions(lobby_vr PRIVATE
    XR_USE_PLATFORM_ANDROID
    XR_USE_GRAPHICS_API_OPENGL_ES
)

target_link_libraries(lobby_vr
    openxr_loader
    android
    log
    EGL
    GLESv3
)
