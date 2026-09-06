cmake_minimum_required(VERSION 3.22)
project(romm_handheld_save_tests LANGUAGES C CXX)

get_filename_component(ROMM_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(ROMM_LIBRETRO_INCLUDE "${ROMM_REPO_ROOT}/third_party/libretro")
set(HANDY_DIR "${ROMM_REPO_ROOT}/third_party/cores/handy")
set(MEDNAFEN_NGP_DIR "${ROMM_REPO_ROOT}/third_party/cores/mednafen_ngp")
if(WIN32)
    include("${ROMM_REPO_ROOT}/native/cmake/cores/handy-windows.cmake")
    include("${ROMM_REPO_ROOT}/native/cmake/cores/mednafen_ngp-windows.cmake")
else()
    include("${ROMM_REPO_ROOT}/native/cmake/cores/handy-linux.cmake")
    include("${ROMM_REPO_ROOT}/native/cmake/cores/mednafen_ngp-linux.cmake")
endif()

include(CTest)
find_package(Python3 COMPONENTS Interpreter REQUIRED)
get_filename_component(ROMM_HANDHELD_DLL_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
add_test(NAME handheld_save_contract
    COMMAND "${CMAKE_COMMAND}" -E env
        "ROMM_HANDY_CORE=$<TARGET_FILE:handy_core>"
        "ROMM_NGP_CORE=$<TARGET_FILE:mednafen_ngp_core>"
        "ROMM_HANDHELD_TEST_ROOT=${CMAKE_CURRENT_BINARY_DIR}/fixtures"
        "ROMM_HANDHELD_DLL_DIR=${ROMM_HANDHELD_DLL_DIR}"
        "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_LIST_DIR}/../e2e/test_handheld_saves.py")
