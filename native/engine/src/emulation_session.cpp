#include "emulation_session.h"
#include "atomic_file_store.h"

#include <native/engine/LogSink.h>

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <thread>

namespace romm {

namespace {
// Engine diagnostics route through the platform-neutral log sink with the
// same tags and messages the host always used (LINUX_X64.md section 11).
void logPrint(romm::log::Severity severity, const char* tag, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    char buffer[512];
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    romm::log::sink().log(severity, tag, buffer);
}

// Only one session is ever active in this process (LIBRETRO_REFACTOR.md
// section 5). Libretro core callbacks carry no context pointer, so the
// trampolines below must dispatch through this single global.
std::atomic<EmulationSession*> g_active_session{nullptr};

constexpr int kMaxDrainWaitMs = 500;
constexpr int kDrainPollIntervalMs = 5;
constexpr auto kLrps2MaxCatchUpDebt = std::chrono::milliseconds(200);
constexpr unsigned kPcsxRearmedDualShockDevice =
    RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_ANALOG, 1);
constexpr unsigned kPlayStationControllerPorts = 2;
constexpr unsigned kGameCubeControllerPorts = 4;
}  // namespace

#define LOG_TAG "romm_emulation_session"
#define LOGI(...) logPrint(romm::log::Severity::Info, LOG_TAG, __VA_ARGS__)
#define LOGE(...) logPrint(romm::log::Severity::Error, LOG_TAG, __VA_ARGS__)

EmulationSession::EmulationSession() {
    environment_.setGeometryCallback([this](const struct retro_game_geometry& geometry) {
        const double aspect = geometry.aspect_ratio > 0.0f
                ? geometry.aspect_ratio
                : (geometry.base_height > 0
                        ? static_cast<double>(geometry.base_width) / geometry.base_height
                        : 0.0);
        diagnostics_.displayAspectRatioMicros.store(
                aspect > 0.0 ? static_cast<uint32_t>(aspect * 1000000.0 + 0.5) : 0,
                std::memory_order_relaxed);
        romm::video::sink().setDisplayAspectRatio(aspect);
    });
}

EmulationSession::~EmulationSession() {
    stop();
}

bool EmulationSession::acquireProcessSlot() {
    EmulationSession* expected = nullptr;
    return g_active_session.compare_exchange_strong(expected, this);
}

void EmulationSession::releaseProcessSlot() {
    EmulationSession* self = this;
    g_active_session.compare_exchange_strong(self, nullptr);
}

bool EmulationSession::start(const std::string& corePath, const std::string& systemDir,
                              const std::string& saveDir, const std::string& contentPath) {
    if (state_.load() != SessionState::kUninitialized) {
        lastError_ = "session already started";
        return false;
    }

    if (!core_.load(corePath)) {
        lastError_ = core_.lastError();
        LOGE("core load failed: %s", lastError_.c_str());
        return false;
    }

    const CoreFunctions& fns = core_.functions();
    struct retro_system_info systemInfo {};
    fns.retro_get_system_info(&systemInfo);

    std::vector<uint8_t> contentBuffer;
    if (!contentPath.empty() && !systemInfo.need_fullpath &&
        !readWholeFile(contentPath, contentBuffer)) {
        lastError_ = "failed to read content file: " + contentPath;
        LOGE("%s", lastError_.c_str());
        core_.unload();
        return false;
    }

    environment_.setSystemDirectory(systemDir);
    environment_.setSaveDirectory(saveDir);
    // A real core's content lives wherever the caller staged it (an
    // app-private cache directory from LIBRETRO_REFACTOR.md section 10), not
    // saveDir; contentPath's own parent directory is the honest answer for
    // both the no-content and real-content cases.
    environment_.setContentDirectory(
        contentPath.empty() ? saveDir : contentPath.substr(0, contentPath.find_last_of('/')));

    const bool isPcsxRearmed = systemInfo.library_name != nullptr &&
        std::strcmp(systemInfo.library_name, "PCSX-ReARMed") == 0;
    if (isPcsxRearmed) {
        // Slot 1 remains Libretro-managed and therefore participates in the
        // existing per-ROM SRAM restore/checkpoint/sync lifecycle. Upstream
        // exposes no Libretro-backed slot-2 mode, so disable its shared-file
        // default rather than leaking an unsynchronized card across ROMs.
        environment_.setCoreOptionOverride("pcsx_rearmed_memcard1", "libretro");
        environment_.setCoreOptionOverride("pcsx_rearmed_memcard2", "none");
    }

    const bool isMupen64PlusNext = systemInfo.library_name != nullptr &&
        std::strcmp(systemInfo.library_name, "Mupen64Plus-Next") == 0;
    const bool isDolphin = systemInfo.library_name != nullptr &&
        std::strcmp(systemInfo.library_name, "dolphin-emu") == 0;
    const bool isLrps2 = systemInfo.library_name != nullptr &&
        std::strcmp(systemInfo.library_name, "LRPS2") == 0;
    adaptiveFrameSkipEnabled_ = isMupen64PlusNext;
    catchUpAfterStall_ = isLrps2;
    if (isMupen64PlusNext) {
        // The libretro threaded path keeps render-context commands on this
        // context-owning frontend thread while moving N64 emulation to a
        // worker thread.
        // The app's N64 controller profile targets the core's independent
        // C-button layout (C-Right=RetroPad R, R Shoulder=RetroPad R2, etc.).
        // That layout only applies with "Independent C-button Controls"
        // (mupen64plus-alt-map) enabled; the default (False) map instead wires
        // RetroPad R to the N64 R trigger and RetroPad R2 to a C-buttons mode
        // toggle, so user mappings would fire the wrong control.
        environment_.setCoreOptionOverride("mupen64plus-alt-map", "True");
        environment_.setCoreOptionOverride("mupen64plus-ThreadedRenderer", "True");
#ifdef ROMM_STEAM_DECK_PLAYER
        // Preview 19's Deck path configured GLideN64 at its native size.
        // The Ubuntu compositor path sizes rendering from the desktop output.
        environment_.setCoreOptionOverride("mupen64plus-43screensize", "320x240");
#endif
        environment_.setCoreOptionOverride("mupen64plus-HybridFilter", "False");
        environment_.setCoreOptionOverride("mupen64plus-EnableLODEmulation", "False");
        environment_.setCoreOptionOverride("mupen64plus-EnableCopyColorToRDRAM", "Off");
        environment_.setCoreOptionOverride("mupen64plus-EnableCopyDepthToRDRAM", "Off");
    }
    if (isLrps2) {
        // lrps2 embeds a GameIndex.yaml compatibility database in the core
        // and only reads <system>/pcsx2/resources/GameIndex.yaml when this
        // option is enabled. The player stages the packaged copy there at
        // launch (main.cpp), so opt in to keep the database current with
        // releases instead of the one frozen into the .so. Everything else
        // defaults correctly for integration: the BIOS is auto-detected
        // from pcsx2/bios and memory cards default to shared storage under
        // pcsx2/memcards (a stable slot-0 image for save sync).
        environment_.setCoreOptionOverride("pcsx2_use_external_gameindex", "enabled");
    }

    fns.retro_set_environment(&EmulationSession::environmentTrampoline);
    fns.retro_set_video_refresh(&EmulationSession::videoRefreshTrampoline);
    fns.retro_set_audio_sample(&EmulationSession::audioSampleTrampoline);
    fns.retro_set_audio_sample_batch(&EmulationSession::audioSampleBatchTrampoline);
    fns.retro_set_input_poll(&EmulationSession::inputPollTrampoline);
    fns.retro_set_input_state(&EmulationSession::inputStateTrampoline);

    state_ = SessionState::kLoaded;

    fns.retro_init();

    bool loadedOk;
    if (contentPath.empty()) {
        // No-content core (the Phase 2/3 synthetic test_core).
        loadedOk = fns.retro_load_game(nullptr);
    } else {
        struct retro_game_info info {};
        info.path = contentPath.c_str();
        info.data = systemInfo.need_fullpath ? nullptr : contentBuffer.data();
        info.size = systemInfo.need_fullpath ? 0 : contentBuffer.size();
        info.meta = nullptr;
        loadedOk = fns.retro_load_game(&info);
    }

    if (!loadedOk) {
        lastError_ = "retro_load_game failed";
        LOGE("%s", lastError_.c_str());
        fns.retro_deinit();
        core_.unload();
        state_ = SessionState::kUninitialized;
        return false;
    }

    if (isPcsxRearmed) {
        // PCSX-ReARMed resets every port to a digital pad during retro_load_game().
        // Select its advertised DualShock subclass afterward so the analog values
        // already supplied by InputState are actually polled by PlayStation games.
        for (unsigned port = 0; port < kPlayStationControllerPorts; ++port) {
            fns.retro_set_controller_port_device(port, kPcsxRearmedDualShockDevice);
        }
        LOGI("configured PCSX-ReARMed ports 1-2 as DualShock");
    }
    if (isDolphin) {
        // Dolphin constructs the GameCube pad's button and analog expressions
        // only when the frontend selects a device for the port.
        for (unsigned port = 0; port < kGameCubeControllerPorts; ++port) {
            fns.retro_set_controller_port_device(port, RETRO_DEVICE_JOYPAD);
        }
        LOGI("configured Dolphin ports 1-4 as GameCube controllers");
    }

    threadShouldRun_ = true;
    state_ = SessionState::kRunning;

    struct retro_system_av_info av {};
    fns.retro_get_system_av_info(&av);
    avFps_ = av.timing.fps > 0.0 ? av.timing.fps : 60.0;
    avSampleRate_ = av.timing.sample_rate > 0.0 ? av.timing.sample_rate : 44100.0;
    const double displayAspect = av.geometry.aspect_ratio > 0.0f
            ? av.geometry.aspect_ratio
            : (av.geometry.base_height > 0
                    ? static_cast<double>(av.geometry.base_width) / av.geometry.base_height
                    : 0.0);
    diagnostics_.displayAspectRatioMicros.store(
            displayAspect > 0.0
                    ? static_cast<uint32_t>(displayAspect * 1000000.0 + 0.5)
                    : 0,
            std::memory_order_relaxed);
    romm::video::sink().setDisplayAspectRatio(displayAspect);
    romm::gl::context().setBufferGeometry(av.geometry.base_width, av.geometry.base_height);

    romm::audio::StartConfig audioConfig;
    audioConfig.sampleRate = avSampleRate_;
    audioConfig.prebufferSeconds = isMupen64PlusNext ? 0.1 : 0.0;
    if (!romm::audio::sink().start(audioConfig)) {
        // Audio failing to open is not fatal to the session — video and
        // input still work, and diagnostics/logs make the failure visible.
        LOGE("audio output failed to start; continuing without audio");
    }

    thread_ = std::thread(&EmulationSession::runLoop, this);
    LOGI("session started, core=%s, content=%s", corePath.c_str(),
         contentPath.empty() ? "(none)" : contentPath.c_str());
    return true;
}

void EmulationSession::runLoop() {
    FrameScheduler scheduler(avFps_);
    AdaptiveFrameSkip frameSkip(avFps_);

    // For hardware-rendering cores (GLideN64), we need a hardware render
    // context current on this thread. Create it now and make it current
    // before entering the loop. The surface may attach its window
    // slightly after the thread starts, so spin-wait briefly.
    const bool hwRender = environment_.isHardwareRendering();
    bool coreContextInitialized = false;
    if (hwRender) {
        if (!romm::gl::context().createContext()) {
            LOGE("HW render: failed to create render context");
            return;
        }

        // Make the negotiated render context available to the environment
        // handler for RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE queries.
        environment_.setRenderContextProvider(
                []() { return romm::gl::context().currentContext(); });

        // Wait up to ~5 seconds for the UI thread to queue the window. The
        // emulation thread must perform the actual surface attach and invoke
        // the core callback because render contexts cannot be current on two
        // threads.
        auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (!romm::gl::context().hasPendingWindowUpdate() && threadShouldRun_.load()) {
            if (std::chrono::steady_clock::now() >= deadline) {
                LOGE("HW render: timed out waiting for surface attachment");
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }

        if (romm::gl::context().applyPendingWindowUpdate() ==
            romm::gl::HardwareContext::WindowUpdateResult::kAttached) {
            auto& cb = environment_.hwRenderCallbackMutable();
            if (cb.context_reset) {
                cb.context_reset();
            }
            coreContextInitialized = true;
        }
    }

    bool contextReleasedForPause = false;
    hardwareContextReleasedForPause_.store(false);
    while (threadShouldRun_.load()) {
        if (hwRender) {
            const auto update = romm::gl::context().applyPendingWindowUpdate();
            if (update == romm::gl::HardwareContext::WindowUpdateResult::kAttached &&
                !coreContextInitialized) {
                auto& cb = environment_.hwRenderCallbackMutable();
                if (cb.context_reset) {
                    cb.context_reset();
                }
                coreContextInitialized = true;
            }
            if (!romm::gl::context().hasSurface()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }
        }

        if (paused_.load()) {
            if (hwRender && releaseHardwareContextWhenPaused_.load() &&
                !contextReleasedForPause) {
                romm::gl::context().unmakeCurrent();
                contextReleasedForPause = true;
                hardwareContextReleasedForPause_.store(true);
            }
            // Skip retro_run() entirely: the core never advances, so the last
            // presented video frame stays on screen and no new audio samples
            // reach the audio sink's ring buffer (which mutes via its
            // existing underrun-fills-silence path). Still pace this loop
            // with the
            // scheduler rather than busy-spinning so an immediate resume
            // doesn't have to fight a saturated CPU core.
            scheduler.waitForNextFrame();
            continue;
        }

        if (contextReleasedForPause) {
            if (!romm::gl::context().makeCurrent()) {
                LOGE("HW render: failed to reacquire context after pause");
                diagnostics_.coreRequestedShutdown = true;
                threadShouldRun_ = false;
                break;
            }
            contextReleasedForPause = false;
            hardwareContextReleasedForPause_.store(false);
        }

        const bool videoEnabled =
            !adaptiveFrameSkipEnabled_ || frameSkip.shouldRenderFrame();
        // A skipped frame must retain the existing timeline so it can catch
        // emulation back up. Resetting the schedule here would pace cheap
        // skipped runs as new frames and preserve the slowdown we are trying
        // to recover from.
        // lrps2 compiles new GS shaders synchronously. Preserve bounded pacing
        // debt across those temporary stalls so subsequent frames refill the
        // audio queue without allowing a long stall to cause prolonged
        // fast-forward.
        scheduler.waitForNextFrame(
            videoEnabled && !catchUpAfterStall_,
            catchUpAfterStall_ ? kLrps2MaxCatchUpDebt
                               : std::chrono::steady_clock::duration::zero());
        presentVideoFrame_.store(videoEnabled, std::memory_order_relaxed);
        environment_.setVideoEnabled(videoEnabled);
        const auto frameWorkStarted = std::chrono::steady_clock::now();
        core_.functions().retro_run();
        const auto frameWorkDuration = std::chrono::steady_clock::now() - frameWorkStarted;
        scheduler.reportFrameWorkDuration(frameWorkDuration);
        if (adaptiveFrameSkipEnabled_) {
            frameSkip.reportFrameWorkDuration(frameWorkDuration, videoEnabled);
            if (!videoEnabled) {
                diagnostics_.skippedVideoFrames.fetch_add(1, std::memory_order_relaxed);
            }
        }

        if (environment_.shutdownRequested()) {
            diagnostics_.coreRequestedShutdown = true;
            threadShouldRun_ = false;
            break;
        }
    }

    if (hwRender) {
        if (contextReleasedForPause && !romm::gl::context().makeCurrent()) {
            LOGE("HW render: failed to reacquire context for teardown");
        }
        hardwareContextReleasedForPause_.store(false);
        auto& cb = environment_.hwRenderCallbackMutable();
        if (cb.context_destroy) {
            cb.context_destroy();
        }
        romm::gl::context().unmakeCurrent();
    }
}

void EmulationSession::stop() {
    SessionState expected = SessionState::kRunning;
    if (!state_.compare_exchange_strong(expected, SessionState::kStopping)) {
        // Not running (never started, already stopped, or stop() raced) —
        // still make sure a partially-started session is cleaned up.
        if (expected == SessionState::kLoaded) {
            state_ = SessionState::kStopping;
        } else {
            return;
        }
    }

    threadShouldRun_ = false;
    if (thread_.joinable()) {
        thread_.join();
    }

    // The producer (emulation thread) is now guaranteed stopped, so it's
    // safe to close the audio stream (the sink's stop() blocks until the
    // realtime audio callback thread is done, too).
    romm::audio::sink().stop();

    // Bounded wait for any callback that might still be finishing on another
    // thread (defensive; join() above already guarantees the emulation
    // thread itself is done — see LIBRETRO_REFACTOR.md section 7.1).
    int waited = 0;
    while (inFlightCallbacks_.load() > 0 && waited < kMaxDrainWaitMs) {
        std::this_thread::sleep_for(std::chrono::milliseconds(kDrainPollIntervalMs));
        waited += kDrainPollIntervalMs;
    }
    if (inFlightCallbacks_.load() > 0) {
        LOGE("timed out waiting for in-flight core callbacks to drain");
    }

    if (core_.isLoaded()) {
        // The emulation thread already invoked the core's context_destroy
        // callback while its GL context was current.
        if (environment_.isHardwareRendering()) {
            romm::gl::context().destroyContext();
        }

        core_.functions().retro_unload_game();
        core_.functions().retro_deinit();
    }
    core_.unload();

    // The core's descriptor strings are invalid after retro_unload_game().
    environment_.clearInputDescriptors();

    state_ = SessionState::kStopped;
    releaseProcessSlot();
    LOGI("session stopped");
}

void* EmulationSession::memoryData(unsigned id) {
    if (!core_.isLoaded()) return nullptr;
    const CoreFunctions& fns = core_.functions();
    if (id == RETRO_MEMORY_SAVE_RAM && fns.romm_get_save_memory_data != nullptr &&
        fns.romm_get_save_memory_size != nullptr && fns.romm_get_save_memory_size() > 0) {
        return fns.romm_get_save_memory_data();
    }
    return fns.retro_get_memory_data(id);
}

size_t EmulationSession::memorySize(unsigned id) {
    if (!core_.isLoaded()) return 0;
    const CoreFunctions& fns = core_.functions();
    if (id == RETRO_MEMORY_SAVE_RAM && fns.romm_get_save_memory_size != nullptr) {
        const size_t extendedSize = fns.romm_get_save_memory_size();
        if (extendedSize > 0) return extendedSize;
    }
    return fns.retro_get_memory_size(id);
}

bool EmulationSession::checkpointSaveRam(const std::string& savePath) {
    void* data = memoryData(RETRO_MEMORY_SAVE_RAM);
    size_t size = memorySize(RETRO_MEMORY_SAVE_RAM);
    if (data == nullptr || size == 0) {
        LOGE("checkpointSaveRam: core exposes no RETRO_MEMORY_SAVE_RAM region");
        return false;
    }
    return atomicWriteFile(savePath, data, size);
}

bool EmulationSession::restoreSaveRam(const std::string& savePath) {
    const CoreFunctions& fns = core_.functions();
    if (fns.romm_restore_save_memory != nullptr) {
        std::vector<uint8_t> saveImage;
        if (!readWholeFile(savePath, saveImage) || saveImage.empty()) return false;
        if (!fns.romm_restore_save_memory(saveImage.data(), saveImage.size())) {
            LOGE("restoreSaveRam: core rejected app save memory image");
            return false;
        }
        return true;
    }

    void* data = memoryData(RETRO_MEMORY_SAVE_RAM);
    size_t size = memorySize(RETRO_MEMORY_SAVE_RAM);
    if (data == nullptr || size == 0) {
        LOGE("restoreSaveRam: core exposes no RETRO_MEMORY_SAVE_RAM region");
        return false;
    }
    if (!readFileExact(savePath, data, size)) return false;

    if (fns.romm_get_save_memory_size != nullptr &&
        fns.romm_get_save_memory_size() > 0) {
        if (fns.romm_apply_save_memory == nullptr || !fns.romm_apply_save_memory()) {
            LOGE("restoreSaveRam: core rejected extended save memory image");
            return false;
        }
    }
    return true;
}

bool EmulationSession::serialize(void* buffer, size_t size) {
    if (!core_.isLoaded()) return false;
    return core_.functions().retro_serialize(buffer, size);
}

bool EmulationSession::unserialize(const void* buffer, size_t size) {
    if (!core_.isLoaded()) return false;
    return core_.functions().retro_unserialize(buffer, size);
}

size_t EmulationSession::serializeSize() {
    if (!core_.isLoaded()) return 0;
    return core_.functions().retro_serialize_size();
}

void EmulationSession::attachVideoWindow(romm::video::NativeWindowHandle window) {
    if (environment_.isHardwareRendering()) {
        romm::gl::context().attachWindow(window);
    } else {
        romm::video::sink().attachWindow(window);
    }
}

void EmulationSession::detachVideoWindow() {
    if (environment_.isHardwareRendering()) {
        romm::gl::context().detachWindow();
    } else {
        romm::video::sink().detachWindow();
    }
}

void EmulationSession::updateInputState(int port, int32_t buttonsMask, int16_t leftX,
                                         int16_t leftY, int16_t rightX, int16_t rightY,
                                         int16_t leftTrigger, int16_t rightTrigger) {
    inputState_.set(
        port, buttonsMask, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger);
}

int32_t EmulationSession::debugInputButtonMask(int port) const {
    return static_cast<int32_t>(
        inputState_.query(static_cast<unsigned>(port), RETRO_DEVICE_JOYPAD, 0,
                           RETRO_DEVICE_ID_JOYPAD_MASK) &
        0xFFFF);
}

int16_t EmulationSession::debugInputAnalogLeftX(int port) const {
    return inputState_.query(static_cast<unsigned>(port), RETRO_DEVICE_ANALOG,
                              RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_X);
}

int16_t EmulationSession::debugInputAnalogLeftY(int port) const {
    return inputState_.query(static_cast<unsigned>(port), RETRO_DEVICE_ANALOG,
                              RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_Y);
}

// ---------------------------------------------------------------------------
// Trampolines — the callback gate. Each checks the active session and its
// state before doing any real work, and tracks in-flight calls so stop() can
// wait for them to drain (LIBRETRO_REFACTOR.md section 7.1).
// ---------------------------------------------------------------------------

bool EmulationSession::environmentTrampoline(unsigned cmd, void* data) {
    EmulationSession* self = g_active_session.load();
    if (self == nullptr) return false;
    SessionState s = self->state_.load();
    if (s == SessionState::kStopping || s == SessionState::kStopped) return false;

    self->inFlightCallbacks_.fetch_add(1, std::memory_order_relaxed);
    bool result = self->environment_.handle(cmd, data);
    self->inFlightCallbacks_.fetch_sub(1, std::memory_order_relaxed);
    return result;
}

void EmulationSession::videoRefreshTrampoline(const void* data, unsigned width, unsigned height, size_t pitch) {
    EmulationSession* self = g_active_session.load();
    if (self == nullptr) return;
    SessionState s = self->state_.load();
    if (s == SessionState::kStopping || s == SessionState::kStopped) return;

    self->inFlightCallbacks_.fetch_add(1, std::memory_order_relaxed);
    self->diagnostics_.frameCount.fetch_add(1, std::memory_order_relaxed);
    self->diagnostics_.lastWidth.store(width, std::memory_order_relaxed);
    self->diagnostics_.lastHeight.store(height, std::memory_order_relaxed);
    self->diagnostics_.pixelFormat.store(static_cast<int>(self->environment_.pixelFormat()),
                                           std::memory_order_relaxed);

    if (!self->presentVideoFrame_.load(std::memory_order_relaxed)) {
        // Defensive fallback for cores that do not honor
        // RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE themselves.
    } else if (self->environment_.isHardwareRendering()) {
        // Hardware-rendering core (GLideN64/lrps2): the core drew directly to
        // the render backbuffer. data is nullptr for HW rendering (the core
        // doesn't pass pixel data — it's already in the framebuffer). width/
        // height describe the actual rendered content region; forward them so
        // a compositing frontend can center content that doesn't fill the full
        // buffer (e.g. lrps2 reports a fixed 640x448 base geometry but renders
        // the game's native resolution). Then swap the buffers to present.
        romm::gl::context().setContentGeometry(width, height);
        if (!romm::gl::context().swapBuffers()) {
            LOGE("HW render: swapBuffers failed — context may be lost");
        }
    } else {
        // Software-rendering core: convert pixels and blit to the platform
        // window through the registered video sink.
        romm::video::sink().submitFrame(data, width, height, pitch, self->environment_.pixelFormat());
    }
    self->inFlightCallbacks_.fetch_sub(1, std::memory_order_relaxed);
}

void EmulationSession::audioSampleTrampoline(int16_t left, int16_t right) {
    EmulationSession* self = g_active_session.load();
    if (self == nullptr) return;
    SessionState s = self->state_.load();
    if (s == SessionState::kStopping || s == SessionState::kStopped) return;

    self->inFlightCallbacks_.fetch_add(1, std::memory_order_relaxed);
    self->diagnostics_.audioFramesProduced.fetch_add(1, std::memory_order_relaxed);
    const int16_t frame[2] = {left, right};
    auto& audioSink = romm::audio::sink();
    audioSink.pushSamples(frame, 1);
    self->diagnostics_.audioUnderrunFrames.store(audioSink.underrunFrames(),
                                                  std::memory_order_relaxed);
    self->diagnostics_.audioOverrunFrames.store(audioSink.overrunFrames(),
                                                 std::memory_order_relaxed);
    self->inFlightCallbacks_.fetch_sub(1, std::memory_order_relaxed);
}

size_t EmulationSession::audioSampleBatchTrampoline(const int16_t* data, size_t frames) {
    EmulationSession* self = g_active_session.load();
    if (self == nullptr) return 0;
    SessionState s = self->state_.load();
    if (s == SessionState::kStopping || s == SessionState::kStopped) {
        // Libretro defines the return value as frames consumed. Some cores,
        // including SameBoy, retry the unconsumed tail until this returns a
        // non-zero count. Discard during teardown but report consumption so
        // retro_run() can return and the emulation thread can be joined.
        return frames;
    }

    self->inFlightCallbacks_.fetch_add(1, std::memory_order_relaxed);
    self->diagnostics_.audioFramesProduced.fetch_add(frames, std::memory_order_relaxed);
    auto& audioSink = romm::audio::sink();
    audioSink.pushSamples(data, frames);
    self->diagnostics_.audioUnderrunFrames.store(audioSink.underrunFrames(),
                                                  std::memory_order_relaxed);
    self->diagnostics_.audioOverrunFrames.store(audioSink.overrunFrames(),
                                                 std::memory_order_relaxed);
    self->inFlightCallbacks_.fetch_sub(1, std::memory_order_relaxed);
    return frames;
}

void EmulationSession::inputPollTrampoline() {
    EmulationSession* self = g_active_session.load();
    if (self == nullptr) return;
    SessionState s = self->state_.load();
    if (s == SessionState::kStopping || s == SessionState::kStopped) return;
    // No-op: InputState already holds the latest snapshot (updated
    // independently by updateInputState() from the JNI-calling thread), so
    // there is nothing to "poll" here — inputStateTrampoline below always
    // reads the freshest available values directly.
}

int16_t EmulationSession::inputStateTrampoline(unsigned port, unsigned device, unsigned index, unsigned id) {
    EmulationSession* self = g_active_session.load();
    if (self == nullptr) return 0;
    SessionState s = self->state_.load();
    if (s == SessionState::kStopping || s == SessionState::kStopped) return 0;
    return self->inputState_.query(port, device, index, id);
}

}  // namespace romm
