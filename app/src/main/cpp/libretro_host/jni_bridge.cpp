// jni_bridge.cpp — JNI entry points for the native Libretro host.
//
// Phase 2 (initial commit): proves the NDK/CMake toolchain builds and runs on
// both armeabi-v7a and arm64-v8a, and specifically on the physical 32-bit
// Google TV Streamer that motivated this entire native pivot
// (LIBRETRO_REFACTOR.md section 1). Core loading, environment callbacks, and
// the emulation thread are added in follow-up commits within Phase 2.
//
// Architectural rule (section 5): capture JavaVM in JNI_OnLoad. No
// native-created thread exists yet in this commit, so there is nothing to
// attach; this is scaffolding for when the emulation thread is added.

#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "romm_libretro_host"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {
JavaVM* g_javaVm = nullptr;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_javaVm = vm;
    LOGI("romm_libretro_host loaded (JNI_OnLoad)");
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativePing(
        JNIEnv* env, jobject /*thiz*/) {
    LOGI("nativePing() called");
    std::string reply = "romm_libretro_host: ok";
    return env->NewStringUTF(reply.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeIsAvailable(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return JNI_TRUE;
}
