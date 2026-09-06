cmake_minimum_required(VERSION 3.24)
project(romm_n64_windows_build_probe LANGUAGES C CXX)
get_filename_component(ROMM_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(MUPEN64_DIR "${ROMM_REPO_ROOT}/third_party/cores/mupen64plus_next")
include("${ROMM_REPO_ROOT}/native/cmake/cores/mupen64plus_next-windows.cmake")
add_executable(n64_smoke n64_smoke.cpp)
target_include_directories(n64_smoke PRIVATE
    "${MUPEN64_DIR}/libretro-common/include" "${ROMM_ANGLE_INCLUDE_DIR}")
target_link_libraries(n64_smoke PRIVATE
    "${ROMM_ANGLE_EGL_LIBRARY}" "${ROMM_ANGLE_GLESV2_LIBRARY}")
target_link_options(n64_smoke PRIVATE -static-libgcc -static-libstdc++)
set_target_properties(n64_smoke PROPERTIES CXX_STANDARD 11 CXX_STANDARD_REQUIRED ON)
