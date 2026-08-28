// emulation_session.h — owns one loaded core and its emulation thread.
//
// Architectural rules from LIBRETRO_REFACTOR.md section 5 this class exists
// to enforce:
//   - Only one core session is active in the emulation process.
//   - All calls into one Libretro core occur on one emulation thread.
//   - Native callbacks never hold a JNI local reference across calls (this
//     class makes no JNI calls at all; see jni_bridge.cpp for the boundary).
//   - A trampoline/callback-gate protects against a core that keeps calling
//     back after retro_deinit() (section 7.1): teardown marks the gate
//     stopping, makes callbacks no-op, calls retro_deinit(), waits a bounded
//     interval for in-flight callbacks, then releases resources.
#pragma once

#include "environment.h"
#include "adaptive_frame_skip.h"
#include "core_library.h"
#include "frame_scheduler.h"
#include "input_state.h"

#include <native/engine/AudioSink.h>
#include <native/engine/HardwareContext.h>
#include <native/engine/VideoSink.h>

#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace romm {

enum class SessionState {
    kUninitialized,
    kLoaded,
    kRunning,
    kStopping,
    kStopped,
};

// Diagnostics a caller can poll without touching the emulation thread's
// internal state directly. All fields are atomic so this is safe to read
// from the JNI-calling thread while the emulation thread is running.
struct SessionDiagnostics {
    std::atomic<uint64_t> frameCount{0};
    std::atomic<uint64_t> audioFramesProduced{0};
    std::atomic<uint32_t> lastWidth{0};
    std::atomic<uint32_t> lastHeight{0};
    // Libretro's intended display aspect ratio, scaled by 1,000,000 for
    // lock-free transport through the integer-only JNI diagnostics array.
    std::atomic<uint32_t> displayAspectRatioMicros{0};
    std::atomic<int> pixelFormat{-1};
    std::atomic<bool> coreRequestedShutdown{false};
    // Frames of silence inserted because the ring buffer ran dry (the
    // audio callback outpaced the emulation thread) and frames dropped
    // because the ring buffer was full (emulation thread outpaced the
    // audio callback) — LIBRETRO_REFACTOR.md section 8.2's "track
    // underruns, overruns".
    std::atomic<uint64_t> audioUnderrunFrames{0};
    std::atomic<uint64_t> audioOverrunFrames{0};
    std::atomic<uint64_t> skippedVideoFrames{0};
};

class EmulationSession {
public:
    EmulationSession();
    ~EmulationSession();

    EmulationSession(const EmulationSession&) = delete;
    EmulationSession& operator=(const EmulationSession&) = delete;

    // Attempts to become *the* active session for this process. Returns
    // false if another EmulationSession instance is already active
    // (LIBRETRO_REFACTOR.md section 6: "the native host uses an atomic
    // compare-and-set guard that rejects a second active session").
    bool acquireProcessSlot();
    void releaseProcessSlot();

    // Loads corePath, runs retro_init(), then retro_load_game(). When
    // contentPath is empty, calls retro_load_game(nullptr) (a no-content
    // core, e.g. the Phase 2/3 synthetic test_core). When contentPath is
    // non-empty (Phase 4: a real core with real, already-staged-and-verified
    // ROM content — see LIBRETRO_REFACTOR.md sections 6 and 10), the entire
    // file is read into memory once here and handed to the core as a
    // populated retro_game_info{path, data, size}; the buffer is released
    // once retro_load_game() returns, since every conventional libretro core
    // (including SameBoy's GB_load_rom_from_buffer) copies whatever it needs
    // out of that buffer during the call rather than retaining the pointer.
    // Returns false on any failure; check lastError().
    bool start(const std::string& corePath, const std::string& systemDir, const std::string& saveDir,
               const std::string& contentPath = "");

    // Supplies a frontend-specific core option before start(). Platform
    // hosts use this for choices that must not alter another host's defaults.
    void setCoreOptionOverride(const std::string& key, const std::string& value) {
        environment_.setCoreOptionOverride(key, value);
    }

    // Stops the emulation thread (bounded wait for in-flight callbacks),
    // calls retro_unload_game()/retro_deinit(), and unloads the core.
    void stop();

    bool isRunning() const { return state_.load() == SessionState::kRunning; }
    const std::string& lastError() const { return lastError_; }
    const SessionDiagnostics& diagnostics() const { return diagnostics_; }

    // Deep-copied input descriptors retained from the core's
    // SET_INPUT_DESCRIPTORS calls (Phase 8). Empty until a core populates
    // them, and cleared on stop(). Safe to read only from the JNI-calling
    // thread (the emulation thread owns the EnvironmentHandler otherwise).
    const std::vector<EnvironmentHandler::RetainedInputDescriptor>& inputDescriptors() const {
        return environment_.inputDescriptors();
    }

    // SRAM access — valid only while a core is loaded. Returns nullptr/0 if
    // the core exposes no save RAM region.
    void* memoryData(unsigned id);
    size_t memorySize(unsigned id);

    // Atomically writes the current RETRO_MEMORY_SAVE_RAM region to
    // savePath (LIBRETRO_REFACTOR.md section 11.1). Returns false if the
    // core exposes no save RAM, or the write fails. Safe to call from the
    // caller's own thread — this does not run from inside a core callback.
    bool checkpointSaveRam(const std::string& savePath);

    // Restores RETRO_MEMORY_SAVE_RAM from savePath if it exists and is
    // exactly the size the core currently reports. Returns false (and
    // leaves the core's SRAM untouched) if the file is missing or an exact
    // size match fails — this is a deliberate, honest "incompatible/unknown
    // provenance" rejection rather than a partial or truncated restore
    // (section 11.1: "Never apply an existing save solely because its ROM
    // ID and slot match... verify the exact post-retro_load_game() SRAM
    // size").
    bool restoreSaveRam(const std::string& savePath);

    // Enables changed-only, crash-safe SRAM checkpoints from the emulation
    // thread. The first check occurs after interval; subsequent writes only
    // happen when the core's persistent memory differs from the last durable
    // image. An empty path disables periodic checkpointing.
    void configureAutosave(const std::string& savePath,
                           std::chrono::seconds interval = std::chrono::seconds(30));

    bool serialize(void* buffer, size_t size);
    bool unserialize(const void* buffer, size_t size);
    size_t serializeSize();

    // Takes ownership of a native window reference already acquired by the
    // caller (e.g. from the activity's surface in the JNI bridge, passed as
    // the engine's opaque handle). Pass nullptr to detach. Safe to call
    // from the UI thread at any time; see the platform VideoSink's
    // thread-safety contract.
    void attachVideoWindow(romm::video::NativeWindowHandle window);
    void detachVideoWindow();

    // Producer-side entry point for the latest four-port input snapshot
    // (LIBRETRO_REFACTOR.md section 9). Safe to call from any thread other
    // than the emulation thread itself; see InputState's thread-safety
    // contract.
    void updateInputState(int port, int32_t buttonsMask, int16_t leftX, int16_t leftY,
                           int16_t rightX, int16_t rightY, int16_t leftTrigger = 0,
                           int16_t rightTrigger = 0);

    // Debug/diagnostics-only read of a port's current button mask — lets
    // the debug UI show live per-port state so a physical controller can be
    // verified regardless of which of the four ports the OS/router
    // assigned it to (the synthetic test core itself only ever reads
    // port 0). Safe to call from any thread.
    int32_t debugInputButtonMask(int port) const;

    // Debug/diagnostics-only read of a port's left-stick X/Y — same
    // rationale as debugInputButtonMask, extended to analog so partial
    // stick/trigger movement is visible even when no digital button
    // threshold has been crossed. Safe to call from any thread.
    int16_t debugInputAnalogLeftX(int port) const;
    int16_t debugInputAnalogLeftY(int port) const;

    // Phase 6 pause/resume (LIBRETRO_REFACTOR.md section 13): while paused,
    // runLoop() skips retro_run() entirely — video freezes on the last
    // presented frame (videoRefreshTrampoline is never called), and audio
    // mutes naturally through the audio sink's existing underrun-fills-
    // silence path (no new samples are pushed to the ring buffer, so the
    // audio callback reads silence and counts it as underrun, same as any
    // other stall). No separate mute/freeze mechanism was needed. Safe to
    // call from any thread; does not touch the core or any callback state.
    void setPaused(bool paused) { paused_.store(paused); }
    bool isPaused() const { return paused_.load(); }
    void setReleaseHardwareContextWhenPaused(bool enabled) {
        releaseHardwareContextWhenPaused_.store(enabled);
    }
    bool hardwareContextReleasedForPause() const {
        return hardwareContextReleasedForPause_.load();
    }

private:
    void runLoop();
    void checkpointAutosaveIfChanged();
    void* memoryDataUnlocked(unsigned id);
    size_t memorySizeUnlocked(unsigned id);

    // Static trampolines: libretro core callbacks carry no context pointer,
    // and only one session is ever active per process, so these dispatch to
    // the single active instance guarded by the callback gate below.
    static bool environmentTrampoline(unsigned cmd, void* data);
    static void videoRefreshTrampoline(const void* data, unsigned width, unsigned height, size_t pitch);
    static void audioSampleTrampoline(int16_t left, int16_t right);
    static size_t audioSampleBatchTrampoline(const int16_t* data, size_t frames);
    static void inputPollTrampoline();
    static int16_t inputStateTrampoline(unsigned port, unsigned device, unsigned index, unsigned id);

    // Callback gate: while state_ is kStopping/kStopped, trampolines must be
    // no-ops. inFlightCallbacks_ lets teardown wait for any callback that was
    // already in progress when stop() was called.
    std::atomic<int> inFlightCallbacks_{0};
    std::atomic<SessionState> state_{SessionState::kUninitialized};

    CoreLibrary core_;
    EnvironmentHandler environment_;
    SessionDiagnostics diagnostics_;
    std::string lastError_;

    InputState inputState_;
    double avFps_ = 60.0;
    double avSampleRate_ = 44100.0;
    bool dynamicTimingEnabled_ = false;
    bool adaptiveFrameSkipEnabled_ = false;
    bool catchUpAfterStall_ = false;
    std::atomic<bool> presentVideoFrame_{true};

    std::thread thread_;
    std::atomic<bool> threadShouldRun_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> releaseHardwareContextWhenPaused_{false};
    std::atomic<bool> hardwareContextReleasedForPause_{false};

    // Libretro has a single-threaded core contract. UI/player-thread save
    // operations must never overlap retro_run() or they can capture torn
    // SRAM. Periodic checkpoints run on the emulation thread but use the
    // same boundary for consistency with explicit pause/quit checkpoints.
    mutable std::mutex coreExecutionMutex_;

    std::mutex autosaveConfigMutex_;
    std::string autosavePath_;
    std::chrono::seconds autosaveInterval_{30};
    std::atomic<bool> autosaveEnabled_{false};
    std::string lastAutosavePath_;
    std::vector<uint8_t> lastAutosaveImage_;
};

}  // namespace romm
