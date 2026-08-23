execute_process(
    COMMAND "${GIT_EXECUTABLE}" apply --check "${PATCH_FILE}"
    WORKING_DIRECTORY "${SOURCE_DIR}"
    RESULT_VARIABLE PATCH_CHECK
    OUTPUT_QUIET
    ERROR_QUIET
)

if(PATCH_CHECK EQUAL 0)
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" apply --whitespace=nowarn "${PATCH_FILE}"
        WORKING_DIRECTORY "${SOURCE_DIR}"
        RESULT_VARIABLE PATCH_RESULT
    )
    if(NOT PATCH_RESULT EQUAL 0)
        message(FATAL_ERROR "Failed to apply ${PATCH_FILE}")
    endif()
else()
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" apply --reverse --check "${PATCH_FILE}"
        WORKING_DIRECTORY "${SOURCE_DIR}"
        RESULT_VARIABLE REVERSE_CHECK
        OUTPUT_QUIET
        ERROR_QUIET
    )
    if(NOT REVERSE_CHECK EQUAL 0)
        message(FATAL_ERROR "${PATCH_FILE} neither applies cleanly nor is already applied")
    endif()
endif()
