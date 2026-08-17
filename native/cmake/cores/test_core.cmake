# ---------------------------------------------------------------------------
# test_core: an app-owned, copyright-safe synthetic Libretro core
# (LIBRETRO_REFACTOR.md section 7.3). Built as its own shared library and
# loaded the same way a real core would be (dlopen + retro_* symbol
# resolution), so the loading path is exercised honestly before any
# third-party core is integrated.
# ---------------------------------------------------------------------------
add_library(test_core SHARED
    ${ROMM_APP_CPP_DIR}/test_core/test_core.c)

target_include_directories(test_core PRIVATE
    ${ROMM_APP_CPP_DIR}/../../../../third_party/libretro
)

target_link_libraries(test_core
    log
    m
)

target_compile_options(test_core PRIVATE ${ROMM_WARNING_FLAGS})
