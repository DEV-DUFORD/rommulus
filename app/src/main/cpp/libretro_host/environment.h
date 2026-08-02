// environment.h — Libretro RETRO_ENVIRONMENT_* callback support.
//
// LIBRETRO_REFACTOR.md section 7.2: support and test at least the listed
// commands. Unknown commands return false and are logged once per command
// (never silently claimed as supported).
#pragma once

#include "libretro.h"
#include <EGL/egl.h>
#include <functional>
#include <string>
#include <unordered_map>
#include <unordered_set>

namespace romm {

// OpenGL ES render interface returned by RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE.
// The vendored libretro.h predates the upstream retro_hw_render_interface_opengles
// struct, so define the canonical layout here (interface_type, interface_version,
// then context + get_proc_address, matching retro_hw_render_callback's naming).
struct OpenGlEsHwRenderInterface {
    enum retro_hw_render_interface_type interface_type;
    unsigned interface_version;
    EGLContext context;
    retro_hw_get_proc_address_t get_proc_address;
};
// Upstream libretro assigns the next free enum slot after GSKIT_PS2 (= 5).
#define RETRO_HW_RENDER_INTERFACE_OPENGLES 6

// Handles the subset of RETRO_ENVIRONMENT_* commands a no-content synthetic
// core needs. Owned by one EmulationSession; not thread-safe by itself
// (only ever called from the single emulation thread, per the section 5
// architectural rule that all calls into one core occur on one thread).
class EnvironmentHandler {
public:
    EnvironmentHandler();

    // The actual per-command dispatch. Returns true if this host claims to
    // support (and correctly handled) the command.
    bool handle(unsigned cmd, void* data);

    enum retro_pixel_format pixelFormat() const { return pixelFormat_; }
    bool shutdownRequested() const { return shutdownRequested_; }
    bool supportsNoGame() const { return supportsNoGame_; }

    const std::string& systemDirectory() const { return systemDirectory_; }
    const std::string& saveDirectory() const { return saveDirectory_; }
    const std::string& contentDirectory() const { return contentDirectory_; }

    void setSystemDirectory(const std::string& dir) { systemDirectory_ = dir; }
    void setSaveDirectory(const std::string& dir) { saveDirectory_ = dir; }
    void setContentDirectory(const std::string& dir) { contentDirectory_ = dir; }
    void setCoreOptionOverride(const std::string& key, const std::string& value);

    // Hardware rendering (RETRO_ENVIRONMENT_SET_HW_RENDER)
    bool isHardwareRendering() const { return hwRenderActive_; }
    const struct retro_hw_render_callback& hwRenderCallback() const { return hwRenderCallback_; }
    struct retro_hw_render_callback& hwRenderCallbackMutable() { return hwRenderCallback_; }

    // Supplies the negotiated EGL context for RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE.
    // The EGL context is created by GlContextManager after the core issues
    // SET_HW_RENDER, so the handler fetches it lazily via this provider.
    void setGlContextProvider(std::function<EGLContext()> provider) {
        glContextProvider_ = std::move(provider);
    }

private:
    enum retro_pixel_format pixelFormat_ = RETRO_PIXEL_FORMAT_0RGB1555;
    bool shutdownRequested_ = false;
    bool supportsNoGame_ = false;
    std::string systemDirectory_;
    std::string saveDirectory_;
    std::string contentDirectory_;
    std::unordered_map<std::string, std::string> coreOptionValues_;
    std::unordered_map<std::string, std::string> coreOptionOverrides_;

    // Hardware rendering state (populated by SET_HW_RENDER)
    struct retro_hw_render_callback hwRenderCallback_{};
    bool hwRenderActive_ = false;

    // Lazily-fetched EGL context for GET_HW_RENDER_INTERFACE, and the owned
    // interface struct handed to the core (static so the pointer stays valid
    // for the core's current use of the interface).
    std::function<EGLContext()> glContextProvider_;
    OpenGlEsHwRenderInterface hwRenderInterface_{};

    // Commands we've already logged as unsupported, so a core hammering an
    // unsupported query every frame doesn't spam the log.
    std::unordered_set<unsigned> loggedUnsupported_;
};

}  // namespace romm
