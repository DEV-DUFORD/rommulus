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

// No OS headers here (Phase 2 step 1, plans/WINDOWS_IMPL.md section 5.1):
// every platform operation — dynamic loading, path security, session lock,
// home/XDG roots, executable location, signals/watchdog/re-exec, and health
// metrics — goes through the narrow contracts in native/player/include/,
// whose implementations are selected per platform at configure time under
// native/platform/{posix,windows}/src/ (this file names no platform type).
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <functional>
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
#include "native/player/dynamic_library_factory.h"
#include "native/player/hardware_core.h"
#include "native/player/health_metrics.h"
#include "native/player/keyboard_binding_sidecar.h"
#include "native/player/pause_menu.h"
#include "native/player/pause_overlay.h"
#include "native/player/path_security.h"
#include "native/player/platform_paths.h"
#include "native/player/process_control.h"
#include "native/player/presentation_dirty_state.h"
#include "native/player/protocol.h"
#include "native/player/save_metadata.h"
#include "native/player/session_lock.h"
#include "native/player/sdl_audio_sink.h"
#include "native/player/sdl_hardware_context.h"
#include "native/player/sdl_input.h"
#include "native/player/sdl_log_sink.h"
#include "native/player/sdl_video_sink.h"
#include "native/player/steam_deck.h"
#include "native/player/validation.h"

namespace {

// Termination signaling (SIGTERM/SIGINT flag), the session lock, and the
// teardown watchdog live behind process_control.h / session_lock.h; the
// POSIX implementations retain the original semantics verbatim (async-
// signal-safe flag handler, process-lifetime flock fd, _exit(0) alarm).

#ifndef ROMM_STEAM_DECK_PLAYER
// Rasterizes PauseOverlay off-window into RGBA8888 pixels for hardware-
// rendering cores (mupen64plus_next/dolphin/lrps2) on normal Linux.
//
// A hardware-rendering core's SDL_Window is SDL_WINDOW_OPENGL and its sole
// GL context/swap owner is SdlHardwareContext; no SDL_Renderer may ever
// target that window (creating or destroying one there has been observed to
// invalidate the core's EGL/GL context). SDL_CreateSoftwareRenderer instead
// draws PauseOverlay into a plain SDL_Surface that owns no window, and the
// resulting pixels are handed to SdlHardwareContext::setOverlayFrame(),
// which composites them over the retained game framebuffer the next time
// its own swapBuffers() runs on the emulation thread.
class HardwareOverlayRaster {
public:
    ~HardwareOverlayRaster() { reset(); }

    // Rasterizes `draw` at outputWidth x outputHeight and stages the result
    // on `context`. No-op (leaving whatever was previously staged) if the
    // surface/renderer cannot be (re)allocated at the requested size.
    void repaint(romm::player::SdlHardwareContext& context, int outputWidth, int outputHeight,
                 const std::function<void(SDL_Renderer*)>& draw) {
        if (outputWidth <= 0 || outputHeight <= 0) return;
        if (surface_ == nullptr || width_ != outputWidth || height_ != outputHeight) {
            reset();
            surface_ = SDL_CreateSurface(outputWidth, outputHeight, SDL_PIXELFORMAT_RGBA32);
            if (surface_ == nullptr) return;
            renderer_ = SDL_CreateSoftwareRenderer(surface_);
            if (renderer_ == nullptr) {
                SDL_DestroySurface(surface_);
                surface_ = nullptr;
                return;
            }
            width_ = outputWidth;
            height_ = outputHeight;
        }
        SDL_SetRenderDrawColor(renderer_, 0, 0, 0, 255);
        SDL_RenderClear(renderer_);
        draw(renderer_);
        SDL_RenderPresent(renderer_);
        // The overlay fills the whole surface opaquely, so a straight
        // row-by-row copy (honoring the surface's pitch) is all
        // SdlHardwareContext needs to upload the frame to its GL texture.
        if (SDL_LockSurface(surface_)) {
            context.setOverlayFrame(
                surface_->pixels, static_cast<unsigned>(surface_->w),
                static_cast<unsigned>(surface_->h), static_cast<size_t>(surface_->pitch));
            SDL_UnlockSurface(surface_);
        }
    }

    void reset() {
        if (renderer_ != nullptr) {
            romm::player::PauseOverlay::releaseRendererResources(renderer_);
            SDL_DestroyRenderer(renderer_);
            renderer_ = nullptr;
        }
        if (surface_ != nullptr) {
            SDL_DestroySurface(surface_);
            surface_ = nullptr;
        }
        width_ = 0;
        height_ = 0;
    }

private:
    SDL_Surface* surface_ = nullptr;
    SDL_Renderer* renderer_ = nullptr;
    int width_ = 0;
    int height_ = 0;
};
#endif  // !ROMM_STEAM_DECK_PLAYER

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

// The ROMM_PLAYER_* environment contract is strict UTF-8 on every platform.
// Read it through the platform path contract (platform_paths.h): POSIX maps
// that to getenv(); Win32 must use GetEnvironmentVariableW, because the ANSI
// getenv() decodes through the active code page and would corrupt non-ASCII
// trusted roots (e.g. a "тест état" state root) on a CP-1252 runner.
std::string envVar(const char* name) {
    return romm::player::utf8EnvironmentVariable(name);
}

std::string parentDirectory(const std::string& path) {
    const std::size_t pos = path.find_last_of('/');
    if (pos == std::string::npos) return ".";
    if (pos == 0) return "/";
    return path.substr(0, pos);
}

std::optional<std::string> dolphinSystemDirectory() {
    const auto executable = romm::player::executablePath();
    if (!executable.has_value()) return std::nullopt;

    const std::array<std::filesystem::path, 2> roots = {
        executable->parent_path() / "share/rommulus",
        executable->parent_path() / "../share/rommulus",
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

// Locates the packaged lrps2 GameIndex.yaml relative to the player
// executable, mirroring dolphinSystemDirectory(): share/rommulus under the
// bin directory or its parent. Returns nullopt when this build does not ship
// it (the core then falls back to its built-in compatibility database).
std::optional<std::string> lrps2GameIndexPath() {
    const auto executable = romm::player::executablePath();
    if (!executable.has_value()) return std::nullopt;

    const std::array<std::filesystem::path, 2> roots = {
        executable->parent_path() / "share/rommulus/lrps2/resources",
        executable->parent_path() / "../share/rommulus/lrps2/resources",
    };
    for (const auto& root : roots) {
        const std::filesystem::path candidate = root / "GameIndex.yaml";
        if (std::filesystem::is_regular_file(candidate)) {
            std::error_code canonicalError;
            const auto canonical = std::filesystem::weakly_canonical(candidate, canonicalError);
            if (!canonicalError) return canonical.string();
        }
    }
    return std::nullopt;
}

// Builds the TrustedRoots from the ROMM_PLAYER_* environment contract.
// Every root falls back to a platform default (XDG-based under
// ~/.local/share, ~/.cache, and ~/.local/state on POSIX) so a manually
// launched player still has a sane (if narrow) trust policy:
//   coreRoot  $ROMM_PLAYER_CORE_ROOT   or the platform core-root default
//   cacheRoot $ROMM_PLAYER_CACHE_ROOT  or the platform cache-root default
//   dataRoot  $ROMM_PLAYER_DATA_ROOT   or the platform data-root default
//   stateRoot $ROMM_PLAYER_STATE_ROOT  or the platform state-root default
romm::player::TrustedRoots trustedRootsFromEnv() {
    romm::player::TrustedRoots roots;
    const romm::player::DefaultTrustedRoots defaults = romm::player::defaultTrustedRoots();

    roots.coreRoot = envVar("ROMM_PLAYER_CORE_ROOT");
    if (roots.coreRoot.empty()) roots.coreRoot = defaults.coreRoot;
    roots.cacheRoot = envVar("ROMM_PLAYER_CACHE_ROOT");
    if (roots.cacheRoot.empty()) roots.cacheRoot = defaults.cacheRoot;
    roots.dataRoot = envVar("ROMM_PLAYER_DATA_ROOT");
    if (roots.dataRoot.empty()) roots.dataRoot = defaults.dataRoot;
    roots.stateRoot = envVar("ROMM_PLAYER_STATE_ROOT");
    if (roots.stateRoot.empty()) roots.stateRoot = defaults.stateRoot;

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

    // Session lock: the platform implementation (session_lock.h) takes an
    // exclusive non-blocking lock on <stateRoot>/<sessionId>.lock held for
    // the process lifetime, with fail-closed containment checks against the
    // canonical state root. If another live player already holds it — or
    // the lock path cannot be proven inside stateRoot — it reports the
    // session as active and validation rejects the request.
    const std::string stateRoot = roots.stateRoot;
    roots.sessionActive = [stateRoot](const std::string& sessionId) -> bool {
        return romm::player::sessionActive(stateRoot, sessionId);
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
        romm::player::isHardwareRenderingCore(parsed->coreId) &&
        romm::player::shouldUseSteamDeckPlayer()) {
        const auto executable = romm::player::executablePath();
        if (executable.has_value()) {
            const std::filesystem::path deckPlayer =
                executable->parent_path() / "rommulus-player-deck";
            if (std::filesystem::exists(deckPlayer)) {
                if (!romm::player::reexec(deckPlayer.string(), argc, argv)) {
                    std::fprintf(
                        stderr, "error: failed to start Steam Deck player: %s\n",
                        std::strerror(errno));
                    return 1;
                }
            }
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

#ifdef ROMM_PLAYER_QUALIFICATION
    // Qualification-only presented-frame bound (CI candidate builds, see
    // native/player/CMakeLists.txt ROMM_PLAYER_QUALIFICATION): when
    // ROMM_PLAYER_MAX_FRAMES names a positive integer, the main loop exits
    // cleanly as soon as the presented-frame count (diagnostics().frameCount
    // — incremented once per presented video frame by the engine's
    // video-refresh trampoline, i.e. the exact value reported as the
    // result's `frames` field) reaches it. The exit goes through the normal
    // shutdown path below: the SRAM checkpoint is written BEFORE session
    // stop() and exitKind is `completed` (player-initiated — the core never
    // requested shutdown). Strictly bounded semantics: the main loop polls
    // far faster than the core's ~16.7 ms frame period, so the reported
    // frame count lands in [limit, limit + 2] — the safe tolerance the
    // e2e harness asserts (assert_gambatte_result). Malformed or non-positive
    // values are ignored with a warning (qualification aid, never a launch
    // gate). In production builds this whole block is compiled out and the
    // environment variable has no effect.
    int64_t qualificationMaxFrames = 0;
    {
        const std::string maxFramesEnv = envVar("ROMM_PLAYER_MAX_FRAMES");
        if (!maxFramesEnv.empty()) {
            bool valid = maxFramesEnv.size() <= 9;
            for (const char c : maxFramesEnv) {
                if (c < '0' || c > '9') {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                const int64_t value = std::strtoll(maxFramesEnv.c_str(), nullptr, 10);
                if (value > 0) {
                    qualificationMaxFrames = value;
                }
            }
            if (qualificationMaxFrames == 0) {
                std::fprintf(stderr,
                             "warning: ROMM_PLAYER_MAX_FRAMES=%s is not a positive "
                             "integer; the qualification frame bound is disabled\n",
                             maxFramesEnv.c_str());
            }
        }
    }
    if (qualificationMaxFrames > 0) {
        std::fprintf(stderr,
                     "info: qualification frame bound active: %lld presented frames\n",
                     static_cast<long long>(qualificationMaxFrames));
    }
#endif  // ROMM_PLAYER_QUALIFICATION

#ifdef ROMM_WIN32_SOFTWARE_ONLY
    // Fail closed (ROMM_WIN32_SOFTWARE_ONLY, the temporary pre-ANGLE Windows
    // boundary): this build has no hardware render context — the GLES3/ANGLE
    // context source is excluded and only the no-op SdlHardwareContext is
    // linked — so a core classified as hardware-rendering can never launch.
    // Report launch_failed with a clear error before any SDL/GL work instead
    // of silently downgrading or reaching an unresolved GL path. Software
    // cores (e.g. test_core) continue through the normal video/audio/input
    // path below.
    if (romm::player::isHardwareRenderingCore(request.coreId)) {
        const std::string error = "core '" + request.coreId +
            "' requires hardware rendering, which is unavailable in this "
            "software-only Windows build (ROMM_WIN32_SOFTWARE_ONLY)";
        std::fprintf(stderr, "error: %s\n", error.c_str());
        writeResult(request, romm::player::ExitKind::LaunchFailed, false, 0, 0, 0, &error);
        return 1;
    }
#endif

    // 5. Install the platform sinks (before any engine code runs). Keep a
    // raw pointer to the video sink for the main loop's present() calls.
    auto videoSinkOwned = std::make_unique<romm::player::SdlVideoSink>();
    romm::player::SdlVideoSink* videoSink = videoSinkOwned.get();
    romm::log::setSink(std::make_unique<romm::player::SdlLogSink>());
    // Platform-neutral factory (dynamic_library_factory.h): the selected
    // implementation is compiled in per platform (POSIX dlopen wrapper today;
    // Win32 LoadLibraryExW wrapper in a later step). main.cpp names no
    // platform loader type.
    romm::dynamiclib::setFactory(&romm::player::createPlatformDynamicLibrary);
    romm::audio::setSink(std::make_unique<romm::player::SdlAudioSink>());
    romm::video::setSink(std::move(videoSinkOwned));

    // 6. Initialize SDL.
    SDL_SetAppMetadata("rommulus_player", "0.1", "com.romm.player");
    // Steam and Quick Access own their system buttons and overlays. Never
    // keep consuming gameplay input after the player loses focus to them.
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "0");
#if defined(_WIN32) && !defined(ROMM_WIN32_SOFTWARE_ONLY)
    // The Windows hardware build ships a pinned ANGLE runtime beside the
    // executable. Force SDL through EGL instead of accepting a vendor WGL ES
    // extension, so every supported GPU follows the same audited loader path.
    SDL_SetHint(SDL_HINT_OPENGL_ES_DRIVER, "1");
    SDL_SetHint(SDL_HINT_VIDEO_WIN_D3DCOMPILER, "none");
#endif
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_GAMEPAD)) {
        const std::string error = std::string("SDL_Init failed: ") + SDL_GetError();
        std::fprintf(stderr, "error: %s\n", error.c_str());
        writeResult(request, romm::player::ExitKind::LaunchFailed, false, 0, 0, 0, &error);
        return 1;
    }

    // SIGTERM/SIGINT → set the termination flag; the main loop exits cleanly
    // and still writes a result (POSIX: async-signal-safe flag handler).
    romm::player::installTerminationHandlers();

    // 7. Create the window and apply the requested video settings.
    const bool useHardwareRendering = romm::player::isHardwareRenderingCore(request.coreId);
#ifdef ROMM_STEAM_DECK_PLAYER
    const bool useSteamDeckHardwarePath = useHardwareRendering;
#else
    const bool useSteamDeckHardwarePath =
        useHardwareRendering && romm::player::shouldUseSteamDeckPlayer();
#endif
    SDL_WindowFlags windowFlags = SDL_WINDOW_RESIZABLE;
    if (useHardwareRendering) {
#if defined(_WIN32)
        // ANGLE exposes OpenGL ES, never desktop OpenGL. Request its highest
        // Libretro-relevant profile so Dolphin can use its GLES3 fallback and
        // a future GLideN64 Windows candidate can use its explicit GLES build.
        // lrps2 cannot use this path at its current pin (see the graphics
        // spike evidence); accepting its nominal ES3 request here does not
        // advertise or build that core.
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
#else
        // N64 (GLideN64) and lrps2 use desktop OpenGL 3.3 on Linux. GLideN64's
        // GLES path targets mobile GPUs and produces incorrect depth ordering
        // on desktop drivers. Dolphin uses desktop GL 4.5 so its backend can
        // use buffer-storage and other capabilities unavailable in GL 3.3.
        // lrps2's GS OpenGL renderer hard-requires desktop OpenGL 3.3: its feature gate
        // (GSDeviceOGL::CheckFeatures) accepts only GLAD_GL_VERSION_3_3 or
        // GLAD_GL_ES_VERSION_3_1, and this core build always loads glad's
        // DESKTOP loader (GLContext::m_version is never set, so IsGLES() is
        // false and the ES flags are never populated) — meaning no GLES
        // context (even 3.1/3.2) can pass the gate. Its shaders are likewise
        // "#version 330 core" with a required ARB_shading_language_420pack.
        // lrps2 still nominally negotiates OPENGLES3, but glad resolves
        // against its actual desktop context. Dolphin explicitly negotiates
        // OPENGL_CORE through the environment handler.
        const bool useDolphinDesktopGl = request.coreId == "dolphin";
        const bool useDesktopGlProfile =
            useDolphinDesktopGl || request.coreId == "lrps2" ||
            request.coreId == "mupen64plus_next";
        SDL_GL_SetAttribute(
            SDL_GL_CONTEXT_PROFILE_MASK,
            useDesktopGlProfile ? SDL_GL_CONTEXT_PROFILE_CORE
                                : SDL_GL_CONTEXT_PROFILE_ES);
        SDL_GL_SetAttribute(
            SDL_GL_CONTEXT_MAJOR_VERSION, useDolphinDesktopGl ? 4 : 3);
        SDL_GL_SetAttribute(
            SDL_GL_CONTEXT_MINOR_VERSION,
            useDolphinDesktopGl ? 5 : (useDesktopGlProfile ? 3 : 0));
#endif
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
    SDL_RaiseWindow(window);
    videoSink->setIntegerScaling(request.video.integerScaling);
    videoSink->setScanlines(request.video.scanlines);
    videoSink->setSharpFilter(request.video.sharpFilter);
    videoSink->setFullscreen(request.video.fullscreen);
    std::string hardwareRenderSize;
#ifndef ROMM_STEAM_DECK_PLAYER
    // Concrete pointer to the hardware render context this player owns (see
    // sdl_hardware_context.h): the pause overlay is rasterized off-window
    // and handed to it directly (main loop, below) — a clean typed seam
    // that avoids widening the platform-neutral romm::gl::HardwareContext
    // interface for a Linux-desktop-only feature. Stays null for
    // software-rendering cores AND for useSteamDeckHardwarePath (see
    // assignment below): the new raster+composite overlay path is strictly
    // scoped to useHardwareRendering && !useSteamDeckHardwarePath, so every
    // `hardwareContext != nullptr` check below is sufficient on its own to
    // keep the Steam Deck's existing attach/detach renderer flow untouched.
    romm::player::SdlHardwareContext* hardwareContext = nullptr;
#endif
    if (useHardwareRendering) {
        const bool useOffscreenPresentation = !useSteamDeckHardwarePath;
        if (useOffscreenPresentation && request.coreId == "mupen64plus_next") {
            int outputWidth = 0;
            int outputHeight = 0;
            SDL_GetWindowSizeInPixels(window, &outputWidth, &outputHeight);
            const auto renderSize =
                romm::player::n64RenderSizeForOutput(outputWidth, outputHeight);
            hardwareRenderSize =
                std::to_string(renderSize.first) + "x" + std::to_string(renderSize.second);
        }
        // No SDL_Renderer is ever created for this window: SdlVideoSink stays
        // detached for hardware-rendering cores (its window/pause-overlay
        // behavior is exclusively for software cores). The hardware core's
        // sole GL context/swap owner is SdlHardwareContext below; the pause
        // overlay is composited into it instead (see HardwareOverlayRaster
        // in the main loop).
        auto ownedHardwareContext = std::make_unique<romm::player::SdlHardwareContext>(
            window, useOffscreenPresentation);
#ifndef ROMM_STEAM_DECK_PLAYER
        // Strictly scoped to normal Linux offscreen presentation: on the
        // Steam Deck fallback (useSteamDeckHardwarePath true — normally
        // unreachable in this binary, see openPause() below) hardwareContext
        // stays null, so the raster+composite overlay path is never taken
        // and the existing release-context / attach-detach-renderer flow is
        // used unmodified.
        if (!useSteamDeckHardwarePath) hardwareContext = ownedHardwareContext.get();
#endif
        romm::gl::setContext(std::move(ownedHardwareContext));
        romm::gl::context().setScanlines(
            !useSteamDeckHardwarePath && request.video.scanlines);
        romm::gl::context().setIntegerScaling(request.video.integerScaling);
        romm::gl::context().setSharpFilter(request.video.sharpFilter);
        std::fprintf(
            stderr, "info: hardware presentation path: %s\n",
            useSteamDeckHardwarePath ? "Steam Deck direct framebuffer"
                                     : "offscreen compositor");
    }

    // Give the window manager one chance to deliver an early close/quit
    // before committing to loading the core — a user who already asked to
    // leave is reported as user_cancelled_before_start, not launch_failed.
    bool quitBeforeStart = romm::player::terminationRequested();
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
    // Only the isolated Steam Deck player's direct-framebuffer path releases
    // the render context while paused (its window may be reused by the
    // deck's own presentation between pause/resume). Normal Linux instead
    // keeps the context bound and re-presents the retained hardware
    // framebuffer plus the pause overlay every paused frame (see
    // EmulationSession::runLoop()) — releasing/reacquiring it here caused
    // resume to fail with EGL_BAD_CONTEXT.
    session.setReleaseHardwareContextWhenPaused(useSteamDeckHardwarePath);
#endif
    // GLideN64 runs the N64 RDP on the GPU; HLE avoids the much heavier cxd4
    // RSP path. Other cores ignore these Mupen64Plus-specific options.
    session.setCoreOptionOverride("mupen64plus-rdp-plugin", "gliden64");
    session.setCoreOptionOverride("mupen64plus-rsp-plugin", "hle");
    if (!hardwareRenderSize.empty()) {
        session.setCoreOptionOverride("mupen64plus-43screensize", hardwareRenderSize);
    }
    if (request.rendererOverride.has_value() &&
        *request.rendererOverride == romm::player::RendererOverride::SoftwareHw) {
        if (request.coreId != "lrps2") {
            const std::string error =
                "software_hw renderer override is only supported by lrps2";
            std::fprintf(stderr, "error: %s\n", error.c_str());
            writeResult(request, romm::player::ExitKind::LaunchFailed, false, 0, 0, 0, &error);
            SDL_Quit();
            return 1;
        }
        session.setCoreOptionOverride("pcsx2_renderer", "Software (HW)");
        std::fprintf(stderr, "info: compatibility renderer override: Software (HW)\n");
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
    if (request.coreId == "lrps2") {
        // lrps2 resolves every data path relative to <system>/pcsx2 inside
        // retro_load_game(): BIOS from pcsx2/bios (first valid file wins),
        // resources from pcsx2/resources, memory cards under pcsx2/memcards.
        // The desktop BIOS layer stages the PS2 BIOS as bios_PS2.bin directly
        // in systemDir, so mirror it into the layout the core scans —
        // overwritten on every launch so a re-staged BIOS wins. Unlike
        // dolphin, systemDir itself is NOT replaced: the core must see the
        // staged BIOS through the same tree.
        const std::filesystem::path lrps2Root = std::filesystem::path(systemDir) / "pcsx2";
        std::error_code fsError;
        std::filesystem::create_directories(lrps2Root / "bios", fsError);
        if (!fsError) {
            std::filesystem::create_directories(lrps2Root / "resources", fsError);
        }
        if (fsError) {
            const std::string error = "failed to create lrps2 system directories: " +
                                      fsError.message();
            std::fprintf(stderr, "error: %s\n", error.c_str());
            writeResult(request, romm::player::ExitKind::LaunchFailed, false, 0, 0, 0, &error);
            session.releaseProcessSlot();
            SDL_Quit();
            return 1;
        }
        const std::filesystem::path stagedBios = std::filesystem::path(systemDir) / "bios_PS2.bin";
        if (std::filesystem::is_regular_file(stagedBios, fsError)) {
            if (!std::filesystem::copy_file(
                        stagedBios, lrps2Root / "bios" / "bios_PS2.bin",
                        std::filesystem::copy_options::overwrite_existing, fsError) ||
                fsError) {
                const std::string error = "failed to stage PS2 BIOS into " +
                                          (lrps2Root / "bios").string() + ": " +
                                          fsError.message();
                std::fprintf(stderr, "error: %s\n", error.c_str());
                writeResult(request, romm::player::ExitKind::LaunchFailed, false, 0, 0, 0, &error);
                session.releaseProcessSlot();
                SDL_Quit();
                return 1;
            }
        } else {
            std::fprintf(stderr,
                         "info: no staged PS2 BIOS at %s; the core will fail to boot without one\n",
                         stagedBios.string().c_str());
        }
        const auto gameIndex = lrps2GameIndexPath();
        if (gameIndex.has_value()) {
            std::error_code copyError;
            if (!std::filesystem::copy_file(
                        *gameIndex, lrps2Root / "resources" / "GameIndex.yaml",
                        std::filesystem::copy_options::overwrite_existing, copyError) ||
                copyError) {
                std::fprintf(stderr,
                             "info: failed to stage lrps2 GameIndex.yaml (%s); the core will use its built-in database\n",
                             copyError.message().c_str());
            }
        } else {
            std::fprintf(stderr,
                         "info: packaged lrps2 GameIndex.yaml not found; the core will use its built-in database\n");
        }
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
        const auto saveSize = romm::player::fileSize(request.savePath);
        if (saveSize.has_value() && *saveSize > 0) {
            if (!session.restoreSaveRam(request.savePath)) {
                std::fprintf(stderr,
                             "error: SRAM restore-on-launch failed for %s (file is %lld bytes; "
                             "the core's SRAM region is %zu bytes, or the core rejected the "
                             "image); continuing with a fresh save\n",
                             request.savePath.c_str(), *saveSize,
                             session.memorySize(RETRO_MEMORY_SAVE_RAM));
            }
        } else {
            std::fprintf(stderr, "info: no existing save at %s; starting with fresh SRAM\n",
                         request.savePath.c_str());
        }
        session.configureAutosave(request.savePath, std::chrono::seconds(30));
    }

    // 10. Attach the video surface. Software cores do not wait for this in
    // runLoop() (only HW-render cores spin on it), so attach promptly —
    // frames submitted before the attach is visible are simply staged/dropped
    // by the sink, but attaching immediately minimizes the blank window.
    session.attachVideoWindow(reinterpret_cast<romm::video::NativeWindowHandle>(window));

    // 11. Main loop.
    bool running = true;
    bool windowHasFocus = true;
    bool pausedForFocusLoss = false;
    auto nextHealthLog = std::chrono::steady_clock::now() + std::chrono::minutes(1);
    romm::player::SdlInput input(request.controllerSlots);
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
    if (request.controllerBindings.has_value() &&
        request.controllerBindings->pauseMenuBindings.has_value()) {
        const auto& pauseBindings =
            *request.controllerBindings->pauseMenuBindings;
        input.setPauseMenuBindings(pauseBindings[0], pauseBindings[1]);
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
        request.coreId == "pcsx_rearmed" || request.coreId == "dolphin" ||
        request.coreId == "lrps2" ? 16 : 12
    );
    pauseMenu.setKeyboardRowCount(
        romm::player::coreKeyboardRowCount(request.coreId));
    romm::player::PauseOverlay pauseOverlay;
    // Seed the Video Options submenu with the launch request's settings so
    // its ON/OFF display matches what was applied to the sink at startup.
    pauseMenu.setVideoToggles(request.video.scanlines, request.video.integerScaling,
                              request.video.sharpFilter);

#ifndef ROMM_STEAM_DECK_PLAYER
    // Off-window rasterization of the pause overlay for hardware-rendering
    // cores (see HardwareOverlayRaster above). hardwareOverlayDirty mirrors
    // videoSink's own presentation-dirty gate so a repaint only re-rasterizes
    // (and re-uploads to the GPU) when the menu state, capture countdown, or
    // window actually changed — see requestRepaint() below.
    HardwareOverlayRaster hardwareOverlayRaster;
    romm::player::PresentationDirtyState hardwareOverlayDirty;
#endif
    // Marks both presentation paths dirty: videoSink's own repaint gate
    // (software cores, plus the coordinate space it retains even when
    // undrawn) and the hardware-core overlay raster above.
    const auto requestRepaint = [&]() {
        videoSink->requestRepaint();
#ifndef ROMM_STEAM_DECK_PLAYER
        hardwareOverlayDirty.request();
#endif
    };

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
        requestRepaint();
        switch (effect) {
            case romm::player::PauseMenuEffect::kResume:
                // The menu closed via Resume (or cancel): unfreeze the core.
#ifndef ROMM_STEAM_DECK_PLAYER
                if (useSteamDeckHardwarePath) videoSink->detachWindow();
                // Normal Linux offscreen path only (hardwareContext is null
                // for useSteamDeckHardwarePath, see its declaration above):
                // disable GL compositing immediately so gameplay presentation
                // returns on the very next swapBuffers() rather than racing
                // the main loop's next iteration.
                if (hardwareContext != nullptr) hardwareContext->clearOverlay();
#endif
                session.setPaused(false);
                break;
            case romm::player::PauseMenuEffect::kQuit:
                // Quit was confirmed: leave through the normal shutdown path,
                // which checkpoints and reports exitKind=completed.
#ifndef ROMM_STEAM_DECK_PLAYER
                if (useSteamDeckHardwarePath) videoSink->detachWindow();
                if (hardwareContext != nullptr) hardwareContext->clearOverlay();
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
        requestRepaint();
        takeCheckpoint(session, request);
#ifndef ROMM_STEAM_DECK_PLAYER
        // Only the Steam Deck direct-framebuffer path releases the hardware
        // render context on pause (see setReleaseHardwareContextWhenPaused()
        // above); normal Linux keeps it bound and composites the overlay
        // into it instead (main loop, below), so there is nothing to wait
        // for here. This branch is a defensive fallback for the (normally
        // unreachable — the player re-execs into rommulus-player-deck
        // before this point) case where useSteamDeckHardwarePath is true in
        // this binary.
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
                    "error: timed out waiting for the hardware render context to pause\n");
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
                            requestRepaint();
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
                    windowHasFocus = false;
                    input.reset();
                    input.updateSession(session);
                    pausedForFocusLoss = !pauseMenu.isOpen();
                    session.setPaused(true);
                    if (!request.savePath.empty() &&
                        session.memorySize(RETRO_MEMORY_SAVE_RAM) > 0 &&
                        !session.checkpointSaveRam(request.savePath)) {
                        std::fprintf(
                            stderr,
                            "error: focus-loss SRAM checkpoint failed for %s\n",
                            request.savePath.c_str());
                    }
                    break;
                case SDL_EVENT_WINDOW_FOCUS_GAINED:
                    windowHasFocus = true;
                    input.reset();
                    if (pausedForFocusLoss && !pauseMenu.isOpen()) {
                        session.setPaused(false);
                    }
                    pausedForFocusLoss = false;
                    break;
                case SDL_EVENT_WINDOW_EXPOSED:
                case SDL_EVENT_WINDOW_RESIZED:
                case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED:
                    requestRepaint();
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
                        requestRepaint();
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
                        requestRepaint();
                        break;
                    case romm::player::CaptureState::kCancelled:
                    case romm::player::CaptureState::kTimedOut:
                    case romm::player::CaptureState::kNoDeviceAssigned:
                        // Quick Back / 15 s timeout / no controller: back to
                        // the slot list, nothing saved.
                        pauseMenu.exitCapture();
                        requestRepaint();
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
        } else if (windowHasFocus) {
            if (input.pollPauseTrigger()) openPause();
            input.updateSession(session);
        }
        const int captureSecondsLeft = pauseMenu.isCapturingBinding()
            ? static_cast<int>(captureCoordinator.remainingTimeoutMs() / 1000)
            : -1;
        if (captureSecondsLeft != lastCaptureSecondsLeft) {
            lastCaptureSecondsLeft = captureSecondsLeft;
            requestRepaint();
        }
        // Draws the current menu/capture state in output-pixel coordinates.
        // Shared by every presentation path below so software cores, the
        // Steam Deck fallback, and the hardware-core raster overlay all stay
        // in sync with the same PauseOverlay/PauseMenu/binding state.
        const auto drawOverlay = [&](SDL_Renderer* renderer) {
            pauseOverlay.draw(
                renderer, pauseMenu, input.bindings(), input.secondaryBindings(),
                input.keyboardBindings(), captureSecondsLeft, request.coreId.c_str(),
                request.theme);
        };
        if (!useHardwareRendering) {
            // Software cores: unchanged — SdlVideoSink owns the window's
            // SDL_Renderer and presents the core frame plus (while paused)
            // the overlay every loop iteration.
            videoSink->present(drawOverlay);
#ifndef ROMM_STEAM_DECK_PLAYER
        } else if (useSteamDeckHardwarePath) {
            // Defensive fallback only (see openPause()/handlePauseEffect):
            // unreachable in this binary in practice, since a real Steam
            // Deck re-execs into rommulus-player-deck before this point. If
            // ever reached, the render context was released for pause, so
            // the reattached window renderer presents the overlay exactly
            // as it did before this fix.
            if (pauseMenu.isOpen()) videoSink->present(drawOverlay);
        } else if (pauseMenu.isOpen() && hardwareContext != nullptr &&
                   hardwareOverlayDirty.consume()) {
            // Normal Linux hardware rendering: no SDL_Renderer ever targets
            // this window. Rasterize the overlay off-window and hand the
            // RGBA frame to SdlHardwareContext, whose swapBuffers() (driven
            // by the emulation thread while paused, see runLoop()) composites
            // it over the retained game framebuffer.
            int outputWidth = 0;
            int outputHeight = 0;
            SDL_GetWindowSizeInPixels(window, &outputWidth, &outputHeight);
            hardwareOverlayRaster.repaint(*hardwareContext, outputWidth, outputHeight, drawOverlay);
#endif
        }
        if (session.diagnostics().coreRequestedShutdown.load()) running = false;
#ifdef ROMM_PLAYER_QUALIFICATION
        // Qualification frame bound (see the ROMM_PLAYER_MAX_FRAMES parse
        // above): presented-frame count reached → clean player-initiated
        // completed exit through the normal shutdown path (checkpoint
        // before stop, exitKind=completed).
        if (qualificationMaxFrames > 0 &&
            static_cast<int64_t>(session.diagnostics().frameCount.load()) >=
                    qualificationMaxFrames) {
            running = false;
        }
#endif  // ROMM_PLAYER_QUALIFICATION
        if (romm::player::terminationRequested()) running = false;  // SIGTERM/SIGINT
        const auto healthNow = std::chrono::steady_clock::now();
        if (healthNow >= nextHealthLog) {
            const romm::player::HealthSnapshot health = romm::player::processHealth();
            if (health.available) {
                std::fprintf(
                    stderr,
                    "health: frames=%llu max_rss_kib=%ld audio_underrun=%llu "
                    "audio_overrun=%llu controllers=%d\n",
                    static_cast<unsigned long long>(
                        session.diagnostics().frameCount.load()),
                    health.maxRssKib,
                    static_cast<unsigned long long>(
                        romm::audio::sink().underrunFrames()),
                    static_cast<unsigned long long>(
                        romm::audio::sink().overrunFrames()),
                    input.connectedGamepadCount());
            }
            nextHealthLog = healthNow + std::chrono::minutes(1);
        }
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
    // must not keep the desktop shell waiting forever. The result file was
    // committed above (step 12), so the teardown watchdog — a process-level
    // alarm on POSIX that _exit(0)s after five seconds, deliberately used
    // instead of another thread so it still fires when teardown deadlocks on
    // a runtime lock — can terminate safely from here.
    romm::player::armTeardownWatchdog(5);
    if (useHardwareRendering) videoSink->detachWindow();
    session.stop();
    romm::player::disarmTeardownWatchdog();

    session.releaseProcessSlot();  // stop() already released; idempotent no-op
    videoSink->detachWindow();
    SDL_Quit();
    return 0;
}
