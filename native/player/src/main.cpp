// main.cpp — rommulus-player entry point (LINUX_X64.md section 12).
//
// Reads a launch request JSON (`--request <file>`), validates it against
// trusted roots taken from the ROMM_PLAYER_* environment contract, loads
// the Libretro core through the platform-neutral engine, runs it with an
// SDL3 window/audio/input stack, and atomically writes a result JSON.
// Supports software-rendered cores plus N64 and GameCube GLES3 paths; no
// network, no tokens.
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

#include <sys/file.h>
#include <sys/param.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <pwd.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include "atomic_file_store.h"
#include "emulation_session.h"

#include "native/engine/AudioSink.h"
#include "native/engine/DynamicLibrary.h"
#include "native/engine/LogSink.h"
#include "native/engine/VideoSink.h"

#include "native/player/binding_capture.h"
#include "native/player/binding_sidecar.h"
#include "native/player/display_geometry.h"
#include "native/player/keyboard_binding_sidecar.h"
#include "native/player/pause_menu.h"
#include "native/player/pause_overlay.h"
#include "native/player/protocol.h"
#include "native/player/save_metadata.h"
#include "native/player/sdl_audio_sink.h"
#include "native/player/sdl_dynamic_library.h"
#include "native/player/sdl_hardware_context.h"
#include "native/player/sdl_input.h"
#include "native/player/sdl_log_sink.h"
#include "native/player/sdl_video_sink.h"
#include "native/player/steam_deck.h"
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

// Teardown can block inside an uncooperative core or its dependencies. The
// result is committed before teardown starts, so this process-level timeout
// can terminate safely without leaving the desktop supervisor waiting.
void teardownTimeoutHandler(int) { ::_exit(0); }

// Identity control lists for BindingCaptureCoordinator::sample(): the level
// arrays in SdlInput::CapturePortSample are indexed by PadButton/PadAxis
// value, so the coordinator receives every control each frame.
constexpr romm::player::PadButton kAllPadButtons[romm::player::kPadButtonCount] = {
    romm::player::PadButton::kSouth,      romm::player::PadButton::kEast,
    romm::player::PadButton::kWest,       romm::player::PadButton::kNorth,
    romm::player::PadButton::kBack,       romm::player::PadButton::kStart,
    romm::player::PadButton::kLeftShoulder,   romm::player::PadButton::kRightShoulder,
    romm::player::PadButton::kDpadUp,     romm::player::PadButton::kDpadDown,
    romm::player::PadButton::kDpadLeft,   romm::player::PadButton::kDpadRight,
    romm::player::PadButton::kLeftStick,  romm::player::PadButton::kRightStick,
};
constexpr romm::player::PadAxis kAllPadAxes[romm::player::kPadAxisCount] = {
    romm::player::PadAxis::kLeftX,    romm::player::PadAxis::kLeftY,
    romm::player::PadAxis::kRightX,   romm::player::PadAxis::kRightY,
    romm::player::PadAxis::kLeftTrigger, romm::player::PadAxis::kRightTrigger,
};

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

std::optional<std::string> dolphinSystemDirectory() {
    std::error_code pathError;
    const std::filesystem::path executable =
        std::filesystem::read_symlink("/proc/self/exe", pathError);
    if (pathError) return std::nullopt;

    const std::array<std::filesystem::path, 2> roots = {
        executable.parent_path() / "share/rommulus",
        executable.parent_path() / "../share/rommulus",
    };
    for (const auto& root : roots) {
        if (std::filesystem::is_directory(root / "dolphin-emu/Sys")) {
            std::error_code canonicalError;
            const auto canonical = std::filesystem::weakly_canonical(root, canonicalError);
            if (!canonicalError) return canonical.string();
        }
    }
    return std::nullopt;
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
                 int64_t overrunFrames, const std::string* errorMessage,
                 const romm::player::VideoSettings* appliedVideo = nullptr) {
    if (request.resultPath.empty()) return;
    romm::player::PlayerResult result;
    result.protocolVersion = romm::player::kProtocolVersion;
    result.sessionId = request.sessionId;
    result.exitKind = exitKind;
    result.checkpointWritten = checkpointWritten;
    result.candidateSavePath = request.candidateSavePath;
    if (checkpointWritten) {
        const auto metadata = romm::player::readSaveMetadata(request.candidateSavePath);
        if (metadata) {
            result.saveHash = metadata->sha256;
            result.saveSize = metadata->size;
        } else {
            std::fprintf(stderr, "error: failed to read checkpoint metadata from %s\n",
                         request.candidateSavePath.c_str());
        }
    }
    result.frames = frames;
    result.audioUnderrunFrames = underrunFrames;
    result.audioOverrunFrames = overrunFrames;
    if (errorMessage != nullptr) result.errorMessage = *errorMessage;
    result.video = appliedVideo != nullptr ? *appliedVideo : request.video;
    const std::string json = romm::player::serializeResult(result);
    if (!romm::atomicWriteFile(request.resultPath, json.data(), json.size())) {
        std::fprintf(stderr, "error: failed to write result file %s\n", request.resultPath.c_str());
    }
}

// Pauses the session, waits (bounded) for the core to quiesce, and writes an
// SRAM checkpoint to candidateSavePath. Shared by the pause menu's
// checkpoint-on-pause safety net and the shutdown path. Returns whether a
// checkpoint was written (false when no save path was requested).
bool takeCheckpoint(romm::EmulationSession& session,
                    const romm::player::PlayerRequest& request) {
    if (request.savePath.empty()) return false;

    // The emulation thread may still be inside retro_run() when we get here
    // (pause-open / user-quit / SIGTERM exit paths); reading
    // RETRO_MEMORY_SAVE_RAM from this thread would race it and could capture
    // a torn save. Pause first — runLoop() skips retro_run() entirely while
    // paused, so the core stops advancing — then quiesce: poll the frame
    // counter at ~30ms intervals (bounded to ~500ms total) until it stops
    // changing, which proves the core is quiescent. On a core-requested
    // shutdown the thread has already returned, so this simply observes one
    // stable interval and costs ~30ms.
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
    return session.checkpointSaveRam(request.candidateSavePath);
}

}  // namespace

int main(int argc, char* argv[]) {
    // 1. Parse arguments: exactly `--request <file>`.
    if (argc != 3 || std::strcmp(argv[1], "--request") != 0) {
        std::fprintf(stderr, "usage: %s --request <file>\n", argc > 0 ? argv[0] : "rommulus_player");
        return 2;
    }
#ifdef ROMM_STEAM_DECK_PLAYER
    std::fprintf(stderr, "info: isolated Steam Deck legacy player selected\n");
#endif
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

#ifndef ROMM_STEAM_DECK_PLAYER
    if (parsed.has_value() &&
        (parsed->coreId == "mupen64plus_next" || parsed->coreId == "dolphin") &&
        romm::player::shouldUseSteamDeckPlayer()) {
        std::error_code pathError;
        const std::filesystem::path executable =
            std::filesystem::read_symlink("/proc/self/exe", pathError);
        const std::filesystem::path deckPlayer =
            executable.parent_path() / "rommulus-player-deck";
        if (!pathError && std::filesystem::exists(deckPlayer)) {
            ::execv(deckPlayer.c_str(), argv);
            std::fprintf(
                stderr, "error: failed to start Steam Deck player: %s\n",
                std::strerror(errno));
            return 1;
        }
        std::fprintf(stderr, "error: Steam Deck player binary is missing\n");
        return 1;
    }
#endif

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
    // The Compose shell remains alive while this child player runs. Some
    // Linux window managers can return focus to the shell after the player
    // is created; SDL otherwise stops refreshing gamepad state until another
    // player-window interaction (such as resize) briefly restores focus.
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
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
    const bool useHardwareRendering =
        request.coreId == "mupen64plus_next" || request.coreId == "dolphin";
#ifdef ROMM_STEAM_DECK_PLAYER
    const bool useSteamDeckHardwarePath = useHardwareRendering;
#else
    const bool useSteamDeckHardwarePath =
        useHardwareRendering && romm::player::shouldUseSteamDeckPlayer();
#endif
    SDL_WindowFlags windowFlags = SDL_WINDOW_RESIZABLE;
    if (useHardwareRendering) {
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
        SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
        SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 16);
        SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);
        windowFlags |= SDL_WINDOW_OPENGL;
    }
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
    videoSink->setSharpFilter(request.video.sharpFilter);
    videoSink->setFullscreen(request.video.fullscreen);
    std::string hardwareRenderSize;
    if (useHardwareRendering) {
        const bool useOffscreenPresentation = !useSteamDeckHardwarePath;
        if (useOffscreenPresentation) {
            int outputWidth = 0;
            int outputHeight = 0;
            SDL_GetWindowSizeInPixels(window, &outputWidth, &outputHeight);
            if (request.coreId == "mupen64plus_next") {
                const auto renderSize =
                    romm::player::n64RenderSizeForOutput(outputWidth, outputHeight);
                hardwareRenderSize =
                    std::to_string(renderSize.first) + "x" + std::to_string(renderSize.second);
            }
            videoSink->attachWindow(
                reinterpret_cast<romm::video::NativeWindowHandle>(window));
        }

        romm::gl::setContext(std::make_unique<romm::player::SdlHardwareContext>(
            window, useOffscreenPresentation));
        romm::gl::context().setScanlines(
            !useSteamDeckHardwarePath && request.video.scanlines);
        romm::gl::context().setIntegerScaling(request.video.integerScaling);
        romm::gl::context().setSharpFilter(request.video.sharpFilter);
        std::fprintf(
            stderr, "info: hardware presentation path: %s\n",
            useSteamDeckHardwarePath ? "Steam Deck direct framebuffer"
                                     : "Linux offscreen compositor");
    }

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
#ifndef ROMM_STEAM_DECK_PLAYER
    session.setReleaseHardwareContextWhenPaused(useSteamDeckHardwarePath);
#endif
    // GLideN64 runs the N64 RDP on the GPU; HLE avoids the much heavier cxd4
    // RSP path. Other cores ignore these Mupen64Plus-specific options.
    session.setCoreOptionOverride("mupen64plus-rdp-plugin", "gliden64");
    session.setCoreOptionOverride("mupen64plus-rsp-plugin", "hle");
    if (!hardwareRenderSize.empty()) {
        session.setCoreOptionOverride("mupen64plus-43screensize", hardwareRenderSize);
    }
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
    std::string systemDir = request.systemDir;
    if (request.coreId == "dolphin") {
        const auto dolphinSystem = dolphinSystemDirectory();
        if (!dolphinSystem.has_value()) {
            const std::string error = "Dolphin system data is missing";
            std::fprintf(stderr, "error: %s\n", error.c_str());
            writeResult(request, romm::player::ExitKind::LaunchFailed, false, 0, 0, 0, &error);
            session.releaseProcessSlot();
            SDL_Quit();
            return 1;
        }
        systemDir = *dolphinSystem;
    }
    if (!session.start(request.corePath, systemDir, saveDir, request.contentPath)) {
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

    // 9. Restore-on-launch: now that the core is loaded and running, load
    // the existing save back into the core's RETRO_MEMORY_SAVE_RAM (mirrors
    // the Android host, which calls nativeRestoreSaveRam right after
    // loadCore). Without this, a checkpoint written on the previous quit
    // would never be applied and real saves would not survive a relaunch.
    //   - Missing or empty file: first launch — skip the restore, start fresh.
    //   - Present file whose size does not exactly match the core's
    //     post-retro_load_game() SRAM size (or a core-rejected image): the
    //     engine's restoreSaveRam() rejects it fail-closed and leaves the
    //     core's SRAM untouched rather than applying a partial/truncated
    //     image; we log a clear error and continue with a fresh save —
    //     never crash, never abort the launch.
    if (!request.savePath.empty()) {
        struct stat saveStat {};
        if (::stat(request.savePath.c_str(), &saveStat) == 0 && saveStat.st_size > 0) {
            if (!session.restoreSaveRam(request.savePath)) {
                std::fprintf(stderr,
                             "error: SRAM restore-on-launch failed for %s (file is %lld bytes; "
                             "the core's SRAM region is %zu bytes, or the core rejected the "
                             "image); continuing with a fresh save\n",
                             request.savePath.c_str(), (long long) saveStat.st_size,
                             session.memorySize(RETRO_MEMORY_SAVE_RAM));
            }
        } else {
            std::fprintf(stderr, "info: no existing save at %s; starting with fresh SRAM\n",
                         request.savePath.c_str());
        }
    }

    // 10. Attach the video surface. Software cores do not wait for this in
    // runLoop() (only HW-render cores spin on it), so attach promptly —
    // frames submitted before the attach is visible are simply staged/dropped
    // by the sink, but attaching immediately minimizes the blank window.
    session.attachVideoWindow(reinterpret_cast<romm::video::NativeWindowHandle>(window));

    // 11. Main loop.
    bool running = true;
    romm::player::SdlInput input;
    input.configureForCore(request.coreId);
    // v2: apply the stored controller bindings from the launch request so
    // they are active from the FIRST FRAME (the desktop supervisor ingests
    // the previous session's sidecar and serializes it into this field).
    // The player keeps one global BindingTable applied to every port, so a
    // multi-device request seeds from the first device entry; an absent or
    // empty field keeps the built-in defaults.
    if (request.controllerBindings.has_value() &&
        !request.controllerBindings->devices.empty()) {
        input.setBindings(
            request.controllerBindings->devices.front().table,
            request.controllerBindings->devices.front().secondaryTable
        );
    }
    if (request.keyboardBindings.has_value()) {
        input.setKeyboardBindings(request.keyboardBindings->table);
    }
    bool controllerBindingsDirty = false;
    const auto persistControllerBindings = [&]() {
        if (request.resultPath.empty()) return;
        std::vector<romm::player::DeviceBindings> devices;
        for (int port = 0; port < romm::player::SdlInput::kPorts; ++port) {
            if (!input.hasGamepad(port)) continue;
            romm::player::DeviceBindings device;
            device.guid = input.joystickGuidString(port);
            device.identity = romm::player::normalizedDeviceIdentity(device.guid);
            device.table = input.bindings();
            device.secondaryTable = input.secondaryBindings();
            devices.push_back(std::move(device));
        }
        // The table is global. Keep a controller-independent entry if Steam
        // detaches its virtual gamepad before or during the write.
        if (devices.empty()) {
            devices.push_back(romm::player::globalBindingDevice(
                input.bindings(), input.secondaryBindings()));
        }
        if (!romm::player::writeBindingSidecar(
                parentDirectory(request.resultPath) + "/controller-bindings.json", devices)) {
            std::fprintf(stderr, "warning: failed to write controller-bindings.json\n");
        }
    };
    bool keyboardBindingsDirty = false;
    const auto persistKeyboardBindings = [&]() {
        if (request.resultPath.empty()) return;
        if (!romm::player::writeKeyboardBindingSidecar(
                parentDirectory(request.resultPath) + "/keyboard-bindings.json",
                input.keyboardBindings())) {
            keyboardBindingsDirty = true;
            std::fprintf(stderr, "warning: failed to write keyboard-bindings.json\n");
        } else {
            keyboardBindingsDirty = false;
        }
    };
    romm::player::PauseMenu pauseMenu;
    pauseMenu.setBindingSlotCount(
        request.coreId == "mupen64plus_next" ? 14 :
        request.coreId == "pcsx_rearmed" || request.coreId == "dolphin" ? 16 : 12
    );
    pauseMenu.setKeyboardRowCount(
        romm::player::coreKeyboardRowCount(request.coreId));
    romm::player::PauseOverlay pauseOverlay;
    // Seed the Video Options submenu with the launch request's settings so
    // its ON/OFF display matches what was applied to the sink at startup.
    pauseMenu.setVideoToggles(request.video.scanlines, request.video.integerScaling,
                              request.video.sharpFilter);

    // Binding editor (Physical Controller Settings): the capture coordinator
    // owns gamepad input while the menu is in kBindingCapture; the devices
    // eligible for the current capture are the ports that had pads when it
    // began (a mid-capture unplug cancels via onDeviceRemoved).
    romm::player::BindingCaptureCoordinator captureCoordinator;
    std::vector<int> captureDevices;
    auto lastFrameTime = std::chrono::steady_clock::now();
    int lastCaptureSecondsLeft = -1;

    // Executes one pause-menu effect on the session (main thread).
    auto handlePauseEffect = [&](romm::player::PauseMenuEffect effect) {
        videoSink->requestRepaint();
        switch (effect) {
            case romm::player::PauseMenuEffect::kResume:
                // The menu closed via Resume (or cancel): unfreeze the core.
#ifndef ROMM_STEAM_DECK_PLAYER
                if (useSteamDeckHardwarePath) videoSink->detachWindow();
#endif
                session.setPaused(false);
                break;
            case romm::player::PauseMenuEffect::kQuit:
                // Quit was confirmed: leave through the normal shutdown path,
                // which checkpoints and reports exitKind=completed.
#ifndef ROMM_STEAM_DECK_PLAYER
                if (useSteamDeckHardwarePath) videoSink->detachWindow();
#endif
                running = false;
                break;
            // Video Options toggles: apply the menu's NEW state to the sink
            // immediately (runtime toggle — no relaunch needed).
            case romm::player::PauseMenuEffect::kToggleScanlines:
                videoSink->setScanlines(pauseMenu.scanlinesEnabled());
                if (useHardwareRendering) {
                    romm::gl::context().setScanlines(
                        !useSteamDeckHardwarePath && pauseMenu.scanlinesEnabled());
                }
                break;
            case romm::player::PauseMenuEffect::kToggleIntegerScaling:
                videoSink->setIntegerScaling(pauseMenu.integerScalingEnabled());
                if (useHardwareRendering) {
                    romm::gl::context().setIntegerScaling(
                        pauseMenu.integerScalingEnabled());
                }
                break;
            case romm::player::PauseMenuEffect::kToggleSharpFilter:
                videoSink->setSharpFilter(pauseMenu.sharpFilterEnabled());
                if (useHardwareRendering) {
                    romm::gl::context().setSharpFilter(
                        pauseMenu.sharpFilterEnabled());
                }
                break;
            case romm::player::PauseMenuEffect::kBeginCapture: {
                // A slot row was confirmed. Every connected pad is eligible — the first
                // qualifying input wins (Android's coordinator semantics).
                captureDevices.clear();
                for (int port = 0; port < romm::player::SdlInput::kPorts; ++port) {
                    if (input.hasGamepad(port)) captureDevices.push_back(port);
                }
                // Seed edge history from live levels so the opening button
                // cannot become another action when capture finishes.
                input.resetMenuEdges();
                const int target =
                    romm::player::coreBindingSlotAt(request.coreId, pauseMenu.selection());
                captureCoordinator.begin(
                    target,
                    captureDevices.data(), static_cast<int>(captureDevices.size()),
                    request.coreId == "dolphin" &&
                            romm::player::isGameCubeAnalogSlot(target)
                        ? romm::player::CaptureTarget::kAnalog
                        : romm::player::CaptureTarget::kDigital);
                break;
            }
            case romm::player::PauseMenuEffect::kResetDefault:
                // Reset to Default: restore the built-in mapping.
                input.resetBindings();
                controllerBindingsDirty = true;
                persistControllerBindings();
                break;
            case romm::player::PauseMenuEffect::kClearMappings:
                input.clearBindings();
                controllerBindingsDirty = true;
                persistControllerBindings();
                break;
            case romm::player::PauseMenuEffect::kBeginKeyboardCapture:
                // The event loop owns keyboard capture; controller input is
                // ignored until a key is accepted or Escape cancels.
                input.resetMenuEdges();
                break;
            case romm::player::PauseMenuEffect::kResetKeyboardDefault:
                input.resetKeyboardBindings();
                keyboardBindingsDirty = true;
                persistKeyboardBindings();
                break;
            case romm::player::PauseMenuEffect::kClearKeyboardMappings:
                input.clearKeyboardBindings();
                keyboardBindingsDirty = true;
                persistKeyboardBindings();
                break;
            default:
                break;
        }
    };

    // Opens the pause menu (Android's quick-Back / pause-combo behavior):
    // freeze the core (the nativeSetPaused equivalent — runLoop() skips
    // retro_run(), video holds the last frame, audio drains to silence),
    // clear any held input so nothing leaks into the core on resume, and
    // take a silent local-only checkpoint as a safety net (Android's
    // checkpointForPauseMenu: "checkpoint on pause or quit").
    auto openPause = [&]() {
        if (pauseMenu.isOpen()) return;
        input.reset();
        session.setPaused(true);
        pauseMenu.open();
        videoSink->requestRepaint();
        takeCheckpoint(session, request);
#ifndef ROMM_STEAM_DECK_PLAYER
        if (useSteamDeckHardwarePath) {
            const auto deadline =
                std::chrono::steady_clock::now() + std::chrono::seconds(1);
            while (!session.hardwareContextReleasedForPause() &&
                   std::chrono::steady_clock::now() < deadline) {
                SDL_Delay(1);
            }
            if (session.hardwareContextReleasedForPause()) {
                videoSink->attachWindow(
                    reinterpret_cast<romm::video::NativeWindowHandle>(window));
            } else {
                std::fprintf(
                    stderr,
                    "error: timed out waiting for the N64 render context to pause\n");
            }
        }
#endif
    };

    while (running) {
        SDL_Event event;
        while (SDL_PollEvent(&event)) {
            switch (event.type) {
                case SDL_EVENT_QUIT:
                case SDL_EVENT_WINDOW_CLOSE_REQUESTED:
                    running = false;  // window close = requested quit (unchanged)
                    break;
                case SDL_EVENT_KEY_DOWN: {
                    // Skip auto-repeat events: holding Escape (or an overlay
                    // arrow/confirm key) must not re-toggle the menu or
                    // navigate repeatedly.
                    if (event.key.repeat) break;
                    if (event.key.key == SDLK_ESCAPE) {
                        // Escape is the keyboard Back button: it opens the
                        // pause menu, and closes/resumes when the menu (or its
                        // confirm dialog) is already open. It no longer quits
                        // directly — quitting happens only via the menu's Quit
                        // item (with confirm) or window close / SIGTERM.
                        if (pauseMenu.isOpen()) {
                            const bool wasCapturing = pauseMenu.isCapturingBinding();
                            romm::player::PauseMenuActions cancel{};
                            cancel.cancel = true;
                            handlePauseEffect(pauseMenu.handle(cancel));
                            // Keyboard quick-Back during capture: cancel the
                            // coordinator too (the gamepad's held-Back clear
                            // is handled via its own edges).
                            if (wasCapturing) captureCoordinator.cancel();
                        } else {
                            openPause();
                        }
                    } else if (pauseMenu.isCapturingKeyboard()) {
                        const std::optional<int> scancode =
                            romm::player::SdlInput::captureKeyboardScancode(event);
                        if (scancode.has_value()) {
                            const int target = romm::player::coreKeyboardTargetAt(
                                request.coreId, pauseMenu.selection());
                            input.setKeyboardBinding(
                                target, pauseMenu.bindingColumn(), scancode);
                            keyboardBindingsDirty = true;
                            persistKeyboardBindings();
                            pauseMenu.exitKeyboardCapture();
                            input.resetMenuEdges();
                            videoSink->requestRepaint();
                        }
                    } else if (pauseMenu.isOpen()) {
                        // The overlay owns keyboard input: arrows navigate,
                        // Enter/Space confirm. Everything else is consumed so
                        // it cannot leak into the core on resume.
                        romm::player::PauseMenuActions actions{};
                        switch (event.key.scancode) {
                            case SDL_SCANCODE_UP: actions.up = true; break;
                            case SDL_SCANCODE_DOWN: actions.down = true; break;
                            case SDL_SCANCODE_LEFT: actions.left = true; break;
                            case SDL_SCANCODE_RIGHT: actions.right = true; break;
                            case SDL_SCANCODE_RETURN:
                            case SDL_SCANCODE_KP_ENTER:
                            case SDL_SCANCODE_SPACE:
                                actions.confirm = true;
                                break;
                            default:
                                break;  // consumed by the overlay
                        }
                        if (actions.any()) handlePauseEffect(pauseMenu.handle(actions));
                    } else {
                        input.handleEvent(event);
                    }
                    break;
                }
                case SDL_EVENT_WINDOW_FOCUS_LOST:
                    input.reset();
                    break;
                case SDL_EVENT_WINDOW_EXPOSED:
                case SDL_EVENT_WINDOW_RESIZED:
                case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED:
                    videoSink->requestRepaint();
                    input.handleEvent(event);
                    break;
                default:
                    input.handleEvent(event);
                    break;
            }
        }
        input.poll();  // refresh per-port snapshots from live device state
        if (pauseMenu.isOpen()) {
            if (pauseMenu.isCapturingBinding()) {
                // Binding capture mode: the coordinator owns gamepad input.
                // Feed it one frame of levels per connected pad, drive its
                // clock with the measured frame delta, and act on terminal
                // states by updating the BindingTable / leaving capture.
                const romm::player::SdlInput::CaptureFrame frame = input.captureFrame();
                for (int i = 0; i < frame.count; ++i) {
                    const romm::player::SdlInput::CapturePortSample& p = frame.ports[i];
                    if (p.backDown) captureCoordinator.onBackDown();
                    if (p.backUp) captureCoordinator.onBackUp();
                    captureCoordinator.sample(p.port, kAllPadButtons, p.buttons,
                                              romm::player::kPadButtonCount, kAllPadAxes,
                                              p.axes, romm::player::kPadAxisCount);
                }
                // A pad that unplugged mid-capture drops out of the frame;
                // tell the coordinator (it cancels when none remain).
                for (int port : captureDevices) {
                    if (!input.hasGamepad(port)) captureCoordinator.onDeviceRemoved(port);
                }
                const auto now = std::chrono::steady_clock::now();
                captureCoordinator.advanceTime(
                    std::chrono::duration_cast<std::chrono::milliseconds>(now - lastFrameTime)
                        .count());
                switch (captureCoordinator.state()) {
                    case romm::player::CaptureState::kResult: {
                        const romm::player::CapturedBinding* result = captureCoordinator.result();
                        if (result != nullptr) {
                            romm::player::BindingSource source =
                                result->kind == romm::player::CapturedBinding::Kind::kButton
                                    ? romm::player::BindingSource::ofButton(result->button)
                                    : result->kind ==
                                              romm::player::CapturedBinding::Kind::kAxis
                                        ? romm::player::BindingSource::ofAxis(result->axis)
                                    : romm::player::BindingSource::axisDirection(
                                          result->axis,
                                          result->kind ==
                                                  romm::player::CapturedBinding::Kind::kAxisDirection
                                              ? result->polarity
                                              : 1);
                            input.setBinding(
                                romm::player::coreBindingSlotAt(
                                    request.coreId, pauseMenu.selection()),
                                source, pauseMenu.bindingColumn()
                            );
                            controllerBindingsDirty = true;
                            persistControllerBindings();
                        }
                        pauseMenu.exitCapture();
                        videoSink->requestRepaint();
                        break;
                    }
                    case romm::player::CaptureState::kCleared:
                        // Held Back: clear the selected slot's binding.
                        input.setBinding(
                            romm::player::coreBindingSlotAt(
                                request.coreId, pauseMenu.selection()),
                            romm::player::BindingSource::unbound(),
                            pauseMenu.bindingColumn()
                        );
                        controllerBindingsDirty = true;
                        persistControllerBindings();
                        pauseMenu.exitCapture();
                        videoSink->requestRepaint();
                        break;
                    case romm::player::CaptureState::kCancelled:
                    case romm::player::CaptureState::kTimedOut:
                    case romm::player::CaptureState::kNoDeviceAssigned:
                        // Quick Back / 15 s timeout / no controller: back to
                        // the slot list, nothing saved.
                        pauseMenu.exitCapture();
                        videoSink->requestRepaint();
                        break;
                    default:
                        break;  // still capturing (or idle)
                }
                if (!pauseMenu.isCapturingBinding()) {
                    // Seed from live levels: the newly mapped button may
                    // still be held and must not navigate out of the editor.
                    input.resetMenuEdges();
                }
            } else if (!pauseMenu.isCapturingKeyboard()) {
                // The overlay owns controller input too (the core is paused,
                // so nothing reaches the session while it is open).
                const auto actions = input.pollMenuActions();
                if (actions.any()) handlePauseEffect(pauseMenu.handle(actions));
            }
        } else {
            if (input.pollPauseTrigger()) openPause();
            input.updateSession(session);
        }
        const int captureSecondsLeft = pauseMenu.isCapturingBinding()
            ? static_cast<int>(captureCoordinator.remainingTimeoutMs() / 1000)
            : -1;
        if (captureSecondsLeft != lastCaptureSecondsLeft) {
            lastCaptureSecondsLeft = captureSecondsLeft;
            videoSink->requestRepaint();
        }
        if (!useHardwareRendering || pauseMenu.isOpen()) {
            videoSink->present([&](SDL_Renderer* renderer) {
                pauseOverlay.draw(
                    renderer, pauseMenu, input.bindings(), input.secondaryBindings(),
                    input.keyboardBindings(),
                    captureSecondsLeft,
                    request.coreId.c_str());
            });
        }
        if (session.diagnostics().coreRequestedShutdown.load()) running = false;
        if (g_signal_flag.load()) running = false;  // SIGTERM/SIGINT
        SDL_Delay(1);
        lastFrameTime = std::chrono::steady_clock::now();
    }

    // 12. Shutdown: capture audio diagnostics before stop() tears the sink
    // down, and write the SRAM checkpoint BEFORE stop() — SRAM access is
    // only valid while the core is loaded (stop() unloads it).
    input.reset();
    const int64_t underrunFrames = static_cast<int64_t>(romm::audio::sink().underrunFrames());
    const int64_t overrunFrames = static_cast<int64_t>(romm::audio::sink().overrunFrames());

    // Pause + quiesce + checkpoint BEFORE stop() — SRAM access is only valid
    // while the core is loaded (stop() unloads it). If the pause menu was
    // open, the session is already paused and this simply re-checkpoints.
    const bool checkpointWritten = takeCheckpoint(session, request);

    // Every edit is checkpointed immediately. Retry at clean shutdown in case
    // an earlier atomic write failed transiently.
    if (controllerBindingsDirty) persistControllerBindings();
    if (keyboardBindingsDirty) persistKeyboardBindings();

    const romm::player::ExitKind exitKind = session.diagnostics().coreRequestedShutdown.load()
                                               ? romm::player::ExitKind::CoreRequestedShutdown
                                               : romm::player::ExitKind::Completed;
    romm::player::VideoSettings finalVideo = request.video;
    finalVideo.integerScaling = pauseMenu.integerScalingEnabled();
    finalVideo.scanlines = pauseMenu.scanlinesEnabled();
    finalVideo.sharpFilter = pauseMenu.sharpFilterEnabled();
    writeResult(request, exitKind, checkpointWritten,
                static_cast<int64_t>(session.diagnostics().frameCount.load()), underrunFrames,
                overrunFrames, nullptr, &finalVideo);

    // 13. Cleanup. A core is allowed to take time to deinitialize, but it
    // must not keep the desktop shell waiting forever. A process-level alarm
    // is deliberately used instead of another thread: it still fires when
    // teardown deadlocks on a runtime lock.
    struct sigaction teardownTimeout {};
    teardownTimeout.sa_handler = teardownTimeoutHandler;
    sigemptyset(&teardownTimeout.sa_mask);
    ::sigaction(SIGALRM, &teardownTimeout, nullptr);
    ::alarm(5);
    if (useHardwareRendering) videoSink->detachWindow();
    session.stop();
    ::alarm(0);

    session.releaseProcessSlot();  // stop() already released; idempotent no-op
    videoSink->detachWindow();
    SDL_Quit();
    return 0;
}
