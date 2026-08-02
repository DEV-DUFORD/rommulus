#include "emulation_session.h"
#include "atomic_file_store.h"

#include <android/log.h>
#include <cstring>

#define LOG_TAG "romm_emulation_session"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace romm {

namespace {
// Only one session is ever active in this process (LIBRETRO_REFACTOR.md
// section 5). Libretro core callbacks carry no context pointer, so the
// trampolines below must dispatch through this single global.
std::atomic<EmulationSession*> g_active_session{nullptr};

constexpr int kMaxDrainWaitMs = 500;
constexpr int kDrainPollIntervalMs = 5;
}  // namespace

EmulationSession::EmulationSession() = default;

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

    if (systemInfo.library_name != nullptr &&
        std::strcmp(systemInfo.library_name, "PCSX-ReARMed") == 0) {
        // Slot 1 remains Libretro-managed and therefore participates in the
        // existing per-ROM SRAM restore/checkpoint/sync lifecycle. Upstream
        // exposes no Libretro-backed slot-2 mode, so disable its shared-file
        // default rather than leaking an unsynchronized card across ROMs.
        environment_.setCoreOptionOverride("pcsx_rearmed_memcard1", "libretro");
        environment_.setCoreOptionOverride("pcsx_rearmed_memcard2", "none");
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

    threadShouldRun_ = true;
    state_ = SessionState::kRunning;

    struct retro_system_av_info av {};
    fns.retro_get_system_av_info(&av);
    avFps_ = av.timing.fps > 0.0 ? av.timing.fps : 60.0;
    avSampleRate_ = av.timing.sample_rate > 0.0 ? av.timing.sample_rate : 44100.0;

    if (!audioOutput_.start(avSampleRate_)) {
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

    while (threadShouldRun_.load()) {
        if (paused_.load()) {
            // Skip retro_run() entirely: the core never advances, so the last
            // presented video frame stays on screen and no new audio samples
            // reach AudioOutput's ring buffer (which mutes via its existing
            // underrun-fills-silence path). Still pace this loop with the
            // scheduler rather than busy-spinning so an immediate resume
            // doesn't have to fight a saturated CPU core.
            scheduler.waitForNextFrame();
            continue;
        }

        core_.functions().retro_run();

        if (environment_.shutdownRequested()) {
            diagnostics_.coreRequestedShutdown = true;
            threadShouldRun_ = false;
            break;
        }

        scheduler.waitForNextFrame();
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
    // safe to close the audio stream (its own stop() blocks until Oboe's
    // realtime callback thread is done, too).
    audioOutput_.stop();

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
        core_.functions().retro_unload_game();
        core_.functions().retro_deinit();
    }
    core_.unload();

    state_ = SessionState::kStopped;
    releaseProcessSlot();
    LOGI("session stopped");
}

void* EmulationSession::memoryData(unsigned id) {
    if (!core_.isLoaded()) return nullptr;
    return core_.functions().retro_get_memory_data(id);
}

size_t EmulationSession::memorySize(unsigned id) {
    if (!core_.isLoaded()) return 0;
    return core_.functions().retro_get_memory_size(id);
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
    void* data = memoryData(RETRO_MEMORY_SAVE_RAM);
    size_t size = memorySize(RETRO_MEMORY_SAVE_RAM);
    if (data == nullptr || size == 0) {
        LOGE("restoreSaveRam: core exposes no RETRO_MEMORY_SAVE_RAM region");
        return false;
    }
    return readFileExact(savePath, data, size);
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

void EmulationSession::attachVideoWindow(ANativeWindow* window) {
    videoOutput_.attachWindow(window);
}

void EmulationSession::detachVideoWindow() {
    videoOutput_.detachWindow();
}

void EmulationSession::updateInputState(int port, int32_t buttonsMask, int16_t leftX,
                                         int16_t leftY, int16_t rightX, int16_t rightY) {
    inputState_.set(port, buttonsMask, leftX, leftY, rightX, rightY);
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
    self->videoOutput_.submitFrame(data, width, height, pitch, self->environment_.pixelFormat());
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
    self->audioOutput_.pushSamples(frame, 1);
    self->diagnostics_.audioUnderrunFrames.store(self->audioOutput_.underrunFrames(),
                                                  std::memory_order_relaxed);
    self->diagnostics_.audioOverrunFrames.store(self->audioOutput_.overrunFrames(),
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
    self->audioOutput_.pushSamples(data, frames);
    self->diagnostics_.audioUnderrunFrames.store(self->audioOutput_.underrunFrames(),
                                                  std::memory_order_relaxed);
    self->diagnostics_.audioOverrunFrames.store(self->audioOutput_.overrunFrames(),
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
