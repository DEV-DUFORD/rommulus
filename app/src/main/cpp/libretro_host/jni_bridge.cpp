// jni_bridge.cpp — JNI entry points for the native Libretro host.
//
// LIBRETRO_REFACTOR.md section 5 architectural rule: capture JavaVM in
// JNI_OnLoad. The emulation thread itself never calls back into JNI/Java in
// this commit (diagnostics are polled from Kotlin instead), so there is
// nothing for it to attach; this stays scaffolding until a native->JVM
// callback is actually needed (e.g. for save-state completion signals).

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string>
#include <memory>

#include "emulation_session.h"

#define LOG_TAG "romm_libretro_host"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
JavaVM* g_javaVm = nullptr;

// One session per process, owned for the process lifetime (section 5: "Only
// one core session is active in the emulation process"). Created lazily so
// JNI_OnLoad itself stays trivial.
std::unique_ptr<romm::EmulationSession> g_session;

std::string jstringToStd(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_javaVm = vm;
    LOGI("romm_libretro_host loaded (JNI_OnLoad)");
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativePing(
        JNIEnv* env, jobject /*thiz*/) {
    std::string reply = "romm_libretro_host: ok";
    return env->NewStringUTF(reply.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeIsAvailable(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeLoadTestCore(
        JNIEnv* env, jobject /*thiz*/, jstring corePath, jstring systemDir, jstring saveDir) {
    if (g_session != nullptr) {
        LOGE("nativeLoadTestCore: a session already exists in this process");
        return JNI_FALSE;
    }

    auto session = std::make_unique<romm::EmulationSession>();
    if (!session->acquireProcessSlot()) {
        LOGE("nativeLoadTestCore: another session is already the active process slot");
        return JNI_FALSE;
    }

    std::string path = jstringToStd(env, corePath);
    std::string sysDir = jstringToStd(env, systemDir);
    std::string savDir = jstringToStd(env, saveDir);

    if (!session->start(path, sysDir, savDir)) {
        LOGE("nativeLoadTestCore: start() failed: %s", session->lastError().c_str());
        session->releaseProcessSlot();
        return JNI_FALSE;
    }

    g_session = std::move(session);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeStopSession(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_session == nullptr) return;
    g_session->stop();
    g_session.reset();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeIsRunning(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return (g_session != nullptr && g_session->isRunning()) ? JNI_TRUE : JNI_FALSE;
}

// Returns [frameCount, audioFramesProduced, lastWidth, lastHeight, pixelFormat, coreRequestedShutdown, audioUnderrunFrames, audioOverrunFrames]
extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeGetDiagnostics(
        JNIEnv* env, jobject /*thiz*/) {
    jlongArray result = env->NewLongArray(8);
    if (g_session == nullptr) {
        jlong zeros[8] = {0, 0, 0, 0, -1, 0, 0, 0};
        env->SetLongArrayRegion(result, 0, 8, zeros);
        return result;
    }

    const romm::SessionDiagnostics& d = g_session->diagnostics();
    jlong values[8] = {
        static_cast<jlong>(d.frameCount.load()),
        static_cast<jlong>(d.audioFramesProduced.load()),
        static_cast<jlong>(d.lastWidth.load()),
        static_cast<jlong>(d.lastHeight.load()),
        static_cast<jlong>(d.pixelFormat.load()),
        static_cast<jlong>(d.coreRequestedShutdown.load() ? 1 : 0),
        static_cast<jlong>(d.audioUnderrunFrames.load()),
        static_cast<jlong>(d.audioOverrunFrames.load()),
    };
    env->SetLongArrayRegion(result, 0, 8, values);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeGetLastError(
        JNIEnv* env, jobject /*thiz*/) {
    if (g_session == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(g_session->lastError().c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeCheckpointSaveRam(
        JNIEnv* env, jobject /*thiz*/, jstring savePath) {
    if (g_session == nullptr) return JNI_FALSE;
    std::string path = jstringToStd(env, savePath);
    return g_session->checkpointSaveRam(path) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeRestoreSaveRam(
        JNIEnv* env, jobject /*thiz*/, jstring savePath) {
    if (g_session == nullptr) return JNI_FALSE;
    std::string path = jstringToStd(env, savePath);
    return g_session->restoreSaveRam(path) ? JNI_TRUE : JNI_FALSE;
}

// Attaches (surface != null) or detaches (surface == null) the video output
// window. Called from the UI thread whenever EmulationActivity's Surface
// becomes available/is destroyed (LIBRETRO_REFACTOR.md section 8.1). This
// call is synchronous: on detach, the ANativeWindow reference is released
// before this function returns, matching SurfaceHolder.Callback's
// "don't touch the Surface after surfaceDestroyed() returns" contract.
extern "C"
JNIEXPORT void JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeSetSurface(
        JNIEnv* env, jobject /*thiz*/, jobject surface) {
    if (g_session == nullptr) return;

    if (surface == nullptr) {
        g_session->detachVideoWindow();
        return;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LOGE("nativeSetSurface: ANativeWindow_fromSurface returned null");
        return;
    }
    g_session->attachVideoWindow(window);
}
