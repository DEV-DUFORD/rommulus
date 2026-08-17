// main.cpp — rommulus-player entry point (LINUX_X64.md section 12).
//
// Reads a launch request JSON (`--request <file>`), validates it against
// trusted roots taken from the ROMM_PLAYER_* environment contract, loads
// the Libretro core through the platform-neutral engine, runs it with an
// SDL3 window/audio/input stack, and atomically writes a result JSON.
// Software-rendered cores only; no network, no tokens.
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

#include <sys/file.h>
#include <sys/param.h>
#include <fcntl.h>
#include <pwd.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "atomic_file_store.h"
#include "emulation_session.h"

#include "native/engine/AudioSink.h"
#include "native/engine/DynamicLibrary.h"
#include "native/engine/LogSink.h"
#include "native/engine/VideoSink.h"

#include "native/player/protocol.h"
#include "native/player/sdl_audio_sink.h"
#include "native/player/sdl_dynamic_library.h"
#include "native/player/sdl_input.h"
#include "native/player/sdl_log_sink.h"
#include "native/player/sdl_video_sink.h"
#include "native/player/validation.h"

namespace {

// SIGTERM/SIGINT flip this flag; the main loop checks it once per frame.
// The handler does nothing else (async-signal-safe by construction).
std::atomic<bool> g_signal_flag{false};

// The fd backing <stateRoot>/<sessionId>.lock, held open for the process
// lifetime so the flock survives until exit (the kernel releases the lock
// when the fd is closed at termination). Intentionally never closed.
// The .lock FILE itself is likewise left in stateRoot on purpose: the lock
// is kernel-held via this open fd, so unlinking the file would race with
// another process trying to create/flock the same name (it could steal our
// session id or deadlock behind a stale inode). Do NOT unlink it.
int g_sessionLockFd = -1;

void signalHandler(int) { g_signal_flag.store(true, std::memory_order_relaxed); }

std::string envVar(const char* name) {
    const char* value = std::getenv(name);
    return value != nullptr ? value : "";
}

std::string homeDirectory() {
    const std::string home = envVar("HOME");
    if (!home.empty()) return home;
    const struct passwd* entry = getpwuid(getuid());
    if (entry != nullptr && entry->pw_dir != nullptr) return entry->pw_dir;
    return ".";
}

// XDG base-directory lookup with the standard per-variable default under
// $HOME.
std::string xdgHome(const char* name, const std::string& home, const char* relativeDefault) {
    const std::string value = envVar(name);
    if (!value.empty()) return value;
    return home + relativeDefault;
}

std::string parentDirectory(const std::string& path) {
    const std::size_t pos = path.find_last_of('/');
    if (pos == std::string::npos) return ".";
    if (pos == 0) return "/";
    return path.substr(0, pos);
}

// Builds the TrustedRoots from the ROMM_PLAYER_* environment contract.
// Every root falls back to an XDG-based default under ~/.local/share,
// ~/.cache, and ~/.local/state so a manually launched player still has a
// sane (if narrow) trust policy:
//   coreRoot  $ROMM_PLAYER_CORE_ROOT   or $XDG_DATA_HOME/rommulus/cores
//   cacheRoot $ROMM_PLAYER_CACHE_ROOT  or $XDG_CACHE_HOME/rommulus
//   dataRoot  $ROMM_PLAYER_DATA_ROOT   or $XDG_DATA_HOME/rommulus
//   stateRoot $ROMM_PLAYER_STATE_ROOT  or $XDG_STATE_HOME/rommulus
romm::player::TrustedRoots trustedRootsFromEnv() {
    romm::player::TrustedRoots roots;
    const std::string home = homeDirectory();
    const std::string dataHome = xdgHome("XDG_DATA_HOME", home, "/.local/share");
    const std::string cacheHome = xdgHome("XDG_CACHE_HOME", home, "/.cache");
    const std::string stateHome = xdgHome("XDG_STATE_HOME", home, "/.local/state");

    roots.coreRoot = envVar("ROMM_PLAYER_CORE_ROOT");
    if (roots.coreRoot.empty()) roots.coreRoot = dataHome + "/rommulus/cores";
    roots.cacheRoot = envVar("ROMM_PLAYER_CACHE_ROOT");
    if (roots.cacheRoot.empty()) roots.cacheRoot = cacheHome + "/rommulus";
    roots.dataRoot = envVar("ROMM_PLAYER_DATA_ROOT");
    if (roots.dataRoot.empty()) roots.dataRoot = dataHome + "/rommulus";
    roots.stateRoot = envVar("ROMM_PLAYER_STATE_ROOT");
    if (roots.stateRoot.empty()) roots.stateRoot = stateHome + "/rommulus";

    // "coreId=revision;coreId=revision" — malformed entries are skipped.
    const std::string allowed = envVar("ROMM_PLAYER_ALLOWED_CORES");
    std::size_t pos = 0;
    while (pos <= allowed.size()) {
        const std::size_t sep = allowed.find(';', pos);
        const std::string entry = allowed.substr(pos, sep == std::string::npos ? sep : sep - pos);
        if (!entry.empty()) {
            const std::size_t eq = entry.find('=');
            if (eq != std::string::npos && eq > 0) {
                roots.allowedCores[entry.substr(0, eq)] = entry.substr(eq + 1);
            }
        }
        if (sep == std::string::npos) break;
        pos = sep + 1;
    }

    const std::string expectedHash = envVar("ROMM_PLAYER_EXPECTED_CONTENT_HASH");
    if (!expectedHash.empty()) roots.expectedContentHash = expectedHash;

    // Session lock: try to take an exclusive non-blocking flock on
    // <stateRoot>/<sessionId>.lock. If another process already holds it,
    // report the session as active (validation then rejects the request).
    // Defense in depth: O_NOFOLLOW prevents symlink-based escape, and the
    // composed lock path is verified to stay inside stateRoot BEFORE it is
    // opened; a session whose lock path escapes is rejected (reported as
    // active so the validator refuses to launch).
    // Canonicalize stateRoot ONCE (resolves symlinks/relative components) so
    // every containment check compares against the same real absolute path.
    // If the directory does not exist yet, fall back to the raw value with
    // any trailing slash stripped — open() below then fails harmlessly on
    // its own.
    const std::string stateRoot = roots.stateRoot;
    std::string canonicalStateRoot = stateRoot;
    char resolvedRoot[4096];
    if (::realpath(stateRoot.c_str(), resolvedRoot) != nullptr) {
        canonicalStateRoot.assign(resolvedRoot);
    } else if (!canonicalStateRoot.empty() && canonicalStateRoot.back() == '/') {
        canonicalStateRoot.pop_back();
    }
    roots.sessionActive = [canonicalStateRoot](const std::string& sessionId) -> bool {
        if (sessionId.empty()) return false;
        const std::string lockPath = canonicalStateRoot + "/" + sessionId + ".lock";
        // Containment check BEFORE opening (defense in depth on top of the
        // sessionId format validation done separately in validateRequest):
        // the composed path must stay inside the canonical state root.
        const std::string rootPrefix = canonicalStateRoot + "/";
        char resolvedLock[4096];
        if (::realpath(lockPath.c_str(), resolvedLock) != nullptr) {
            // Path exists: it must resolve back inside the state root (this
            // catches pre-planted symlinks and any ".." that lands outside).
            if (std::string(resolvedLock).rfind(rootPrefix, 0) != 0) {
                std::fprintf(stderr, "warning: session lock for %s escapes the state root; rejecting\n",
                             sessionId.c_str());
                return true;
            }
        } else if (errno == ENOENT) {
            // Not present yet: it must be a single path component directly
            // under the root (no '/', no '.'/'..').
            const std::string name = sessionId + ".lock";
            if (name.find('/') != std::string::npos || name == "." || name == "..") {
                std::fprintf(stderr, "warning: session lock name for %s escapes the state root; rejecting\n",
                             sessionId.c_str());
                return true;
            }
        } else {
            // ELOOP/EACCES/... — cannot verify containment; do not open a
            // path we cannot prove is inside the state root.
            std::fprintf(stderr, "warning: cannot resolve session lock for %s (%s); rejecting\n",
                         sessionId.c_str(), std::strerror(errno));
            return true;
        }
        const int fd = ::open(lockPath.c_str(), O_CREAT | O_RDWR | O_NOFOLLOW, 0600);
        if (fd < 0) {
            std::fprintf(stderr, "warning: could not create session lock %s: %s\n",
                         lockPath.c_str(), std::strerror(errno));
            return false;
        }
        if (::flock(fd, LOCK_EX | LOCK_NB) != 0) {
            if (errno == EWOULDBLOCK || errno == EAGAIN) {
                ::close(fd);
                return true;  // a live player already owns this session
            }
            std::fprintf(stderr, "warning: flock failed on %s: %s\n", lockPath.c_str(),
                         std::strerror(errno));
            ::close(fd);
            return false;
        }
        g_sessionLockFd = fd;  // held until process exit (deliberate leak)
        return false;
    };

    return roots;
}

// Serializes and atomically writes one result file. A missing/empty
// resultPath (unparseable request) means there is nowhere to report.
void writeResult(const romm::player::PlayerRequest& request, romm::player::ExitKind exitKind,
                 bool checkpointWritten, int64_t frames, int64_t underrunFrames,
                 int64_t overrunFrames, const std::string* errorMessage) {
    if (request.resultPath.empty()) return;
    romm::player::PlayerResult result;
    result.protocolVersion = romm::player::kProtocolVersion;
    result.sessionId = request.sessionId;
    result.exitKind = exitKind;
    result.checkpointWritten = checkpointWritten;
    result.candidateSavePath = request.candidateSavePath;
    result.frames = frames;
    result.audioUnderrunFrames = underrunFrames;
    result.audioOverrunFrames = overrunFrames;
    if (errorMessage != nullptr) result.errorMessage = *errorMessage;
    const std::string json = romm::player::serializeResult(result);
    if (!romm::atomicWriteFile(request.resultPath, json.data(), json.size())) {
        std::fprintf(stderr, "error: failed to write result file %s\n", request.resultPath.c_str());
    }
}

}  // namespace

int main(int argc, char* argv[]) {
    // 1. Parse arguments: exactly `--request <file>`.
    if (argc != 3 || std::strcmp(argv[1], "--request") != 0) {
        std::fprintf(stderr, "usage: %s --request <file>\n", argc > 0 ? argv[0] : "rommulus_player");
        return 2;
    }
    const std::string requestPath = argv[2];

    // 2. Read the request file.
    std::vector<uint8_t> requestBytes;
    if (!romm::readWholeFile(requestPath, requestBytes)) {
        std::fprintf(stderr, "error: failed to read request file: %s\n", requestPath.c_str());
        return 1;
    }
    const std::string requestText(requestBytes.begin(), requestBytes.end());

    // Parse up front so that even when validation fails below we still know
    // the resultPath/sessionId and can report launch_failed.
    std::string parseError;
    const std::optional<romm::player::PlayerRequest> parsed =
            romm::player::parseRequest(requestText, &parseError);

    // 3. Trusted roots from the environment contract.
    romm::player::PlayerConfig config;
    config.roots = trustedRootsFromEnv();

    // 4. Full section 12.4 validation of the request file.
    const romm::player::ValidationOutcome outcome =
            romm::player::validateRequestFile(requestPath, config);
    if (!outcome.ok) {
        std::fprintf(stderr, "error: request validation failed: %s\n", outcome.error.c_str());
        if (parsed.has_value()) {
            writeResult(*parsed, romm::player::ExitKind::LaunchFailed, false, 0, 0, 0, &outcome.error);
        }
        return 1;
    }
    if (!parsed.has_value()) {
        // validateRequestFile() re-parses internally, so this is unreachable
        // in practice — guard anyway.
        std::fprintf(stderr, "error: request did not parse: %s\n", parseError.c_str());
        return 1;
    }
    const romm::player::PlayerRequest request = *parsed;

    // 5. Install the platform sinks (before any engine code runs). Keep a
    // raw pointer to the video sink for the main loop's present() calls.
    auto videoSinkOwned = std::make_unique<romm::player::SdlVideoSink>();
    romm::player::SdlVideoSink* videoSink = videoSinkOwned.get();
    romm::log::setSink(std::make_unique<romm::player::SdlLogSink>());
    romm::dynamiclib::setFactory([] { return std::make_unique<romm::player::SdlDynamicLibrary>(); });
    romm::audio::setSink(std::make_unique<romm::player::SdlAudioSink>());
    romm::video::setSink(std::move(videoSinkOwned));

    // 6. Initialize SDL.
    SDL_SetAppMetadata("rommulus_player", "0.1", "com.romm.player");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_GAMEPAD)) {
        const std::string error = std::string("SDL_Init failed: ") + SDL_GetError();
        std::fprintf(stderr, "error: %s\n", error.c_str());
        writeResult(request, romm::player::ExitKind::LaunchFailed, false, 0, 0, 0, &error);
        return 1;
    }

    // SIGTERM/SIGINT → set the flag; the main loop exits cleanly and still
    // writes a result.
    struct sigaction sa {};
    sa.sa_handler = signalHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    ::sigaction(SIGTERM, &sa, nullptr);
    ::sigaction(SIGINT, &sa, nullptr);

    // 7. Create the window and apply the requested video settings.
    SDL_WindowFlags windowFlags = SDL_WINDOW_RESIZABLE;
    if (request.video.fullscreen) windowFlags |= SDL_WINDOW_FULLSCREEN;
    SDL_Window* window = SDL_CreateWindow("rommulus_player", 1280, 720, windowFlags);
    if (window == nullptr) {
        const std::string error = std::string("SDL_CreateWindow failed: ") + SDL_GetError();
        std::fprintf(stderr, "error: %s\n", error.c_str());
        writeResult(request, romm::player::ExitKind::LaunchFailed, false, 0, 0, 0, &error);
        SDL_Quit();
        return 1;
    }
    videoSink->setIntegerScaling(request.video.integerScaling);
    videoSink->setScanlines(request.video.scanlines);
    videoSink->setFullscreen(request.video.fullscreen);

    // Give the window manager one chance to deliver an early close/quit
    // before committing to loading the core — a user who already asked to
    // leave is reported as user_cancelled_before_start, not launch_failed.
    bool quitBeforeStart = g_signal_flag.load(std::memory_order_relaxed);
    SDL_Event earlyEvent;
    while (!quitBeforeStart && SDL_PollEvent(&earlyEvent)) {
        if (earlyEvent.type == SDL_EVENT_QUIT ||
            earlyEvent.type == SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
            quitBeforeStart = true;
        }
    }

    // 8. Load and start the core.
    romm::EmulationSession session;
    if (!session.acquireProcessSlot()) {
        const std::string error = "another emulation session is already active in this process";
        std::fprintf(stderr, "error: %s\n", error.c_str());
        writeResult(request, romm::player::ExitKind::LaunchFailed, false, 0, 0, 0, &error);
        SDL_Quit();
        return 1;
    }
    // The save directory is the directory containing the requested save
    // path (the core's SRAM file lives next to its .srm), falling back to
    // the system directory when no save path was requested.
    const std::string saveDir =
            request.savePath.empty() ? request.systemDir : parentDirectory(request.savePath);
    if (!session.start(request.corePath, request.systemDir, saveDir, request.contentPath)) {
        const std::string error = session.lastError().empty()
                                    ? "session start failed"
                                    : session.lastError();
        std::fprintf(stderr, "error: %s\n", error.c_str());
        writeResult(request, quitBeforeStart ? romm::player::ExitKind::UserCancelledBeforeStart
                                             : romm::player::ExitKind::LaunchFailed,
                    false, 0, 0, 0, &error);
        session.stop();               // cleans up a partially-loaded core
        session.releaseProcessSlot(); // stop() already released; idempotent
        SDL_Quit();
        return quitBeforeStart ? 0 : 1;
    }

    if (quitBeforeStart) {
        // The user closed the window while we were starting up: report a
        // clean cancellation rather than running a session nobody wants.
        writeResult(request, romm::player::ExitKind::UserCancelledBeforeStart, false, 0, 0, 0, nullptr);
        session.stop();
        session.releaseProcessSlot();
        SDL_Quit();
        return 0;
    }

    // 9. Attach the video surface. Software cores do not wait for this in
    // runLoop() (only HW-render cores spin on it), so attach promptly —
    // frames submitted before the attach is visible are simply staged/dropped
    // by the sink, but attaching immediately minimizes the blank window.
    session.attachVideoWindow(reinterpret_cast<romm::video::NativeWindowHandle>(window));

    // 10. Main loop.
    bool running = true;
    romm::player::SdlInput input;
    while (running) {
        SDL_Event event;
        while (SDL_PollEvent(&event)) {
            switch (event.type) {
                case SDL_EVENT_QUIT:
                case SDL_EVENT_WINDOW_CLOSE_REQUESTED:
                    running = false;
                    break;
                case SDL_EVENT_KEY_DOWN:
                    if (event.key.key == SDLK_ESCAPE) running = false;  // quit
                    break;
                case SDL_EVENT_WINDOW_FOCUS_LOST:
                    input.reset();
                    break;
                default:
                    input.handleEvent(event);
                    break;
            }
        }
        input.poll();
        input.updateSession(session);
        videoSink->present();
        if (session.diagnostics().coreRequestedShutdown.load()) running = false;
        if (g_signal_flag.load()) running = false;  // SIGTERM/SIGINT
        SDL_Delay(1);
    }

    // 11. Shutdown: capture audio diagnostics before stop() tears the sink
    // down, and write the SRAM checkpoint BEFORE stop() — SRAM access is
    // only valid while the core is loaded (stop() unloads it).
    input.reset();
    const int64_t underrunFrames = static_cast<int64_t>(romm::audio::sink().underrunFrames());
    const int64_t overrunFrames = static_cast<int64_t>(romm::audio::sink().overrunFrames());

    bool checkpointWritten = false;
    if (!request.savePath.empty()) {
        // The emulation thread may still be inside retro_run() when we get
        // here (user quit / SIGTERM exit paths); reading RETRO_MEMORY_SAVE_RAM
        // from this thread would race it and could capture a torn save. Pause
        // first — runLoop() skips retro_run() entirely while paused, so the
        // core stops advancing — then quiesce: poll the frame counter at ~30ms
        // intervals (bounded to ~500ms total) until it stops changing, which
        // proves the core is quiescent. On a core-requested shutdown the
        // thread has already returned, so this simply observes one stable
        // interval and costs ~30ms.
        session.setPaused(true);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
        uint64_t lastFrameCount = session.diagnostics().frameCount.load();
        for (;;) {
            if (std::chrono::steady_clock::now() >= deadline) break;
            SDL_Delay(30);
            const uint64_t frameCount = session.diagnostics().frameCount.load();
            if (frameCount == lastFrameCount) break;  // stable across ~30ms
            lastFrameCount = frameCount;
        }
        checkpointWritten = session.checkpointSaveRam(request.candidateSavePath);
    }

    session.stop();

    const romm::player::ExitKind exitKind = session.diagnostics().coreRequestedShutdown.load()
                                               ? romm::player::ExitKind::CoreRequestedShutdown
                                               : romm::player::ExitKind::Completed;
    writeResult(request, exitKind, checkpointWritten,
                static_cast<int64_t>(session.diagnostics().frameCount.load()), underrunFrames,
                overrunFrames, nullptr);

    // 12. Cleanup.
    session.releaseProcessSlot();  // stop() already released; idempotent no-op
    SDL_Quit();
    return 0;
}
