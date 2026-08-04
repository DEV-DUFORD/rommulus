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

// Phase 4: loads any core (a real one, e.g. SameBoy) with real, already
// staged-and-verified ROM content (LIBRETRO_REFACTOR.md sections 6 and 10).
// contentPath must be an absolute, app-private path the caller resolved
// through the Phase 3 download/cache pipeline in the main process — this
// function itself never touches the network or does any content
// negotiation, only loading a file that already exists on disk.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeLoadCoreWithContent(
        JNIEnv* env, jobject /*thiz*/, jstring corePath, jstring systemDir, jstring saveDir,
        jstring contentPath) {
    if (g_session != nullptr) {
        LOGE("nativeLoadCoreWithContent: a session already exists in this process");
        return JNI_FALSE;
    }

    auto session = std::make_unique<romm::EmulationSession>();
    if (!session->acquireProcessSlot()) {
        LOGE("nativeLoadCoreWithContent: another session is already the active process slot");
        return JNI_FALSE;
    }

    std::string path = jstringToStd(env, corePath);
    std::string sysDir = jstringToStd(env, systemDir);
    std::string savDir = jstringToStd(env, saveDir);
    std::string content = jstringToStd(env, contentPath);

    if (!session->start(path, sysDir, savDir, content)) {
        LOGE("nativeLoadCoreWithContent: start() failed: %s", session->lastError().c_str());
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

// Phase 6 pause/resume (LIBRETRO_REFACTOR.md section 13): freezes/resumes
// the emulation thread's retro_run() calls in place, without stopping or
// tearing down the session. See EmulationSession::setPaused for exactly
// what "paused" freezes (video/audio) and what it leaves untouched (input
// routing, the loaded core, SRAM).
extern "C"
JNIEXPORT void JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeSetPaused(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean paused) {
    if (g_session == nullptr) return;
    g_session->setPaused(paused == JNI_TRUE);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeIsPaused(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return (g_session != nullptr && g_session->isPaused()) ? JNI_TRUE : JNI_FALSE;
}

// Returns [frameCount, audioFramesProduced, lastWidth, lastHeight, pixelFormat, coreRequestedShutdown,
//          audioUnderrunFrames, audioOverrunFrames, port0ButtonMask, port1ButtonMask, port2ButtonMask, port3ButtonMask,
//          port0LeftX, port0LeftY, port1LeftX, port1LeftY, port2LeftX, port2LeftY, port3LeftX, port3LeftY,
//          skippedVideoFrames]
extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeGetDiagnostics(
        JNIEnv* env, jobject /*thiz*/) {
    jlongArray result = env->NewLongArray(21);
    if (g_session == nullptr) {
        jlong zeros[21] = {0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        env->SetLongArrayRegion(result, 0, 21, zeros);
        return result;
    }

    const romm::SessionDiagnostics& d = g_session->diagnostics();
    jlong values[21] = {
        static_cast<jlong>(d.frameCount.load()),
        static_cast<jlong>(d.audioFramesProduced.load()),
        static_cast<jlong>(d.lastWidth.load()),
        static_cast<jlong>(d.lastHeight.load()),
        static_cast<jlong>(d.pixelFormat.load()),
        static_cast<jlong>(d.coreRequestedShutdown.load() ? 1 : 0),
        static_cast<jlong>(d.audioUnderrunFrames.load()),
        static_cast<jlong>(d.audioOverrunFrames.load()),
        static_cast<jlong>(g_session->debugInputButtonMask(0)),
        static_cast<jlong>(g_session->debugInputButtonMask(1)),
        static_cast<jlong>(g_session->debugInputButtonMask(2)),
        static_cast<jlong>(g_session->debugInputButtonMask(3)),
        static_cast<jlong>(g_session->debugInputAnalogLeftX(0)),
        static_cast<jlong>(g_session->debugInputAnalogLeftY(0)),
        static_cast<jlong>(g_session->debugInputAnalogLeftX(1)),
        static_cast<jlong>(g_session->debugInputAnalogLeftY(1)),
        static_cast<jlong>(g_session->debugInputAnalogLeftX(2)),
        static_cast<jlong>(g_session->debugInputAnalogLeftY(2)),
        static_cast<jlong>(g_session->debugInputAnalogLeftX(3)),
        static_cast<jlong>(g_session->debugInputAnalogLeftY(3)),
        static_cast<jlong>(d.skippedVideoFrames.load()),
    };
    env->SetLongArrayRegion(result, 0, 21, values);
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

extern "C"
JNIEXPORT jlong JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeGetSramSizeBytes(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_session == nullptr) return 0;
    size_t size = g_session->memorySize(RETRO_MEMORY_SAVE_RAM);
    return static_cast<jlong>(size);
}

// Phase 8: serializes the retained SET_INPUT_DESCRIPTORS snapshot into a
// simple Kotlin-consumable text form. Wire format: one line per descriptor,
// `port|device|index|id|description`, joined with '\n'. The description
// field is escaped so '|' and newlines in a core's human-readable string
// cannot break the parsing (they are replaced with a space). Returns an empty
// string when no session is active or no descriptors have been set yet, so
// the Kotlin side never crashes on this call.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeGetInputDescriptorsSnapshot(
        JNIEnv* env, jobject /*thiz*/) {
    if (g_session == nullptr) return env->NewStringUTF("");

    std::string out;
    for (const auto& d : g_session->inputDescriptors()) {
        if (!out.empty()) out.push_back('\n');
        out += std::to_string(d.port);
        out.push_back('|');
        out += std::to_string(d.device);
        out.push_back('|');
        out += std::to_string(d.index);
        out.push_back('|');
        out += std::to_string(d.id);
        out.push_back('|');
        for (char c : d.description) {
            out.push_back((c == '\n' || c == '|') ? ' ' : c);
        }
    }
    return env->NewStringUTF(out.c_str());
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

// Pushes the latest four-port RetroPad input snapshot
// (LIBRETRO_REFACTOR.md section 9). buttonMasks has length 4 (one packed
// RETRO_DEVICE_ID_JOYPAD_MASK-shaped bitmask per port); analogValues has
// length 16 (4 ports * [leftX, leftY, rightX, rightY], already clamped to
// Libretro's signed 16-bit range by LibretroPadMapper on the Kotlin side).
// Safe to call from any thread other than the emulation thread itself —
// see InputState's thread-safety contract in input_state.h.
extern "C"
JNIEXPORT void JNICALL
Java_com_romm_androidtv_emulation_nativehost_NativeLibretroHost_nativeUpdateInputState(
        JNIEnv* env, jobject /*thiz*/, jintArray buttonMasks, jintArray analogValues) {
    if (g_session == nullptr) return;

    const jsize maskCount = env->GetArrayLength(buttonMasks);
    const jsize analogCount = env->GetArrayLength(analogValues);
    if (maskCount != romm::InputState::kPorts || analogCount != romm::InputState::kPorts * 4) {
        LOGE("nativeUpdateInputState: unexpected array lengths (masks=%d, analog=%d)",
             static_cast<int>(maskCount), static_cast<int>(analogCount));
        return;
    }

    jint masks[romm::InputState::kPorts];
    jint analog[romm::InputState::kPorts * 4];
    env->GetIntArrayRegion(buttonMasks, 0, maskCount, masks);
    env->GetIntArrayRegion(analogValues, 0, analogCount, analog);

    for (int port = 0; port < romm::InputState::kPorts; ++port) {
        const jint* a = &analog[port * 4];
        g_session->updateInputState(port, static_cast<int32_t>(masks[port]),
                                     static_cast<int16_t>(a[0]), static_cast<int16_t>(a[1]),
                                     static_cast<int16_t>(a[2]), static_cast<int16_t>(a[3]));
    }
}
