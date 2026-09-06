# Read the target compiler's embedded structure offsets, never host offsets.
# This is the portable equivalent of upstream gen_asm_script.sh; it also
# avoids shell/path conversion when Ninja is launched outside an MSYS shell.
file(STRINGS "${OBJECT}" definitions REGEX "@ASM_DEFINE [A-Za-z0-9_]+ 0x[0-9a-f]+")
list(SORT definitions)
set(contents "")
foreach(definition IN LISTS definitions)
    string(REGEX MATCH "@ASM_DEFINE ([A-Za-z0-9_]+) (0x[0-9a-f]+)" match "${definition}")
    string(APPEND contents "%define ${CMAKE_MATCH_1} (${CMAKE_MATCH_2})\n")
endforeach()
if(contents STREQUAL "")
    message(FATAL_ERROR "No dynarec structure offsets found in ${OBJECT}; disable LTO for asm_defines.c.")
endif()
get_filename_component(directory "${OUTPUT}" DIRECTORY)
file(MAKE_DIRECTORY "${directory}")
file(WRITE "${OUTPUT}" "${contents}")
