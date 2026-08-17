# ---------------------------------------------------------------------------
# romm_libretro_host: the JNI-facing native library. Core loading, environment
# callbacks, the emulation thread, video-to-Surface, and Oboe audio output all
# live here (LIBRETRO_REFACTOR.md section 13, Phase 2).
# ---------------------------------------------------------------------------
add_library(romm_libretro_host SHARED
    ${ROMM_NATIVE_ROOT}/bridges/jni/jni_bridge.cpp
    ${ROMM_NATIVE_ROOT}/engine/src/core_library.cpp
    ${ROMM_NATIVE_ROOT}/engine/src/environment.cpp
    ${ROMM_NATIVE_ROOT}/engine/src/emulation_session.cpp
    ${ROMM_NATIVE_ROOT}/engine/src/atomic_file_store.cpp
    ${ROMM_NATIVE_ROOT}/engine/src/log.cpp
    ${ROMM_NATIVE_ROOT}/engine/src/dynamic_library.cpp
    ${ROMM_NATIVE_ROOT}/engine/src/audio_sink.cpp
    ${ROMM_NATIVE_ROOT}/engine/src/video_sink.cpp
    ${ROMM_NATIVE_ROOT}/engine/src/hardware_context.cpp
    ${ROMM_NATIVE_ROOT}/engine/src/pixel_format.cpp
    ${ROMM_NATIVE_ROOT}/platform/android/src/AndroidLogSink.cpp
    ${ROMM_NATIVE_ROOT}/platform/android/src/AndroidDynamicLibrary.cpp
    ${ROMM_NATIVE_ROOT}/platform/android/src/OboeAudioSink.cpp
    ${ROMM_NATIVE_ROOT}/platform/android/src/AndroidVideoSink.cpp
    ${ROMM_NATIVE_ROOT}/platform/android/src/AndroidHardwareContext.cpp
)

# Include directories are spelled relative to ROMM_APP_CPP_DIR with the
# `../../../../` prefix (rather than normalized) so the -I/-isystem strings
# passed to the compiler are byte-for-byte the ones the former monolith
# produced: clang records those paths verbatim in DWARF, and the Wave 8 gate
# is byte-identical .so output.
target_include_directories(romm_libretro_host PRIVATE
    ${ROMM_APP_CPP_DIR}
    ${ROMM_APP_CPP_DIR}/../../../../native/engine/src
    ${ROMM_APP_CPP_DIR}/../../../../third_party/libretro
    ${ROMM_APP_CPP_DIR}/../../../../native/engine/include
    ${ROMM_APP_CPP_DIR}/../../../../native/platform/android/include
)

# Marked SYSTEM (independent of Oboe's own PUBLIC include declaration) so
# -Wall -Wextra below only applies to our own code, not to warnings that
# originate from inline definitions in Oboe's public headers.
target_include_directories(romm_libretro_host SYSTEM PRIVATE
    ${ROMM_APP_CPP_DIR}/../../../../third_party/oboe/include
)

target_link_libraries(romm_libretro_host
    log
    android
    oboe
    EGL
    GLESv3
)

target_compile_options(romm_libretro_host PRIVATE ${ROMM_WARNING_FLAGS})
