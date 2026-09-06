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
execute_process(
    COMMAND "${Python3_EXECUTABLE}" -c
        "import ctypes, os, sys; print(sys.executable, sys.version.split()[0], os.name, sys.platform, ctypes.sizeof(ctypes.c_void_p) * 8)"
    RESULT_VARIABLE ROMM_HANDHELD_PYTHON_STATUS
    OUTPUT_VARIABLE ROMM_HANDHELD_PYTHON_INFO
    ERROR_VARIABLE ROMM_HANDHELD_PYTHON_ERROR
    OUTPUT_STRIP_TRAILING_WHITESPACE
)
if(NOT ROMM_HANDHELD_PYTHON_STATUS EQUAL 0)
    message(FATAL_ERROR "Handheld Python/ctypes preflight failed: ${ROMM_HANDHELD_PYTHON_ERROR}")
endif()
message(STATUS "Handheld test Python ABI: ${ROMM_HANDHELD_PYTHON_INFO}")
if(WIN32 AND CMAKE_HOST_SYSTEM_NAME MATCHES "Windows|MSYS|CYGWIN"
        AND NOT ROMM_HANDHELD_PYTHON_INFO MATCHES " nt win32 64$")
    message(FATAL_ERROR
        "Handheld DLL tests require native 64-bit Windows Python; "
        "set -DPython3_EXECUTABLE=<native-python.exe>. Found: ${ROMM_HANDHELD_PYTHON_INFO}")
endif()
get_filename_component(ROMM_HANDHELD_DLL_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
add_test(NAME handheld_save_contract
    COMMAND "${CMAKE_COMMAND}" -E env
        "ROMM_HANDY_CORE=$<TARGET_FILE:handy_core>"
        "ROMM_NGP_CORE=$<TARGET_FILE:mednafen_ngp_core>"
        "ROMM_HANDHELD_TEST_ROOT=${CMAKE_CURRENT_BINARY_DIR}/fixtures"
        "ROMM_HANDHELD_DLL_DIR=${ROMM_HANDHELD_DLL_DIR}"
        "ROMM_HANDHELD_EXPECT_WINDOWS=$<IF:$<BOOL:${WIN32}>,1,0>"
        "${Python3_EXECUTABLE}" -X faulthandler -u
        "${CMAKE_CURRENT_LIST_DIR}/../e2e/test_handheld_saves.py" -v)
add_test(NAME handheld_player_save_integrity
    COMMAND "${Python3_EXECUTABLE}" -X faulthandler -u
        "${CMAKE_CURRENT_LIST_DIR}/../e2e/test_handheld_player_e2e.py" -v)
