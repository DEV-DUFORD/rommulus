#pragma once

#include <SDL3/SDL.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>

#include <cstdio>
#include <cstring>

namespace romm::player {

namespace angle_detail {

inline EGLint deviceType = EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE;

inline SDL_EGLAttrib* SDLCALL displayAttributes(void*) {
    auto* attributes = static_cast<SDL_EGLAttrib*>(SDL_malloc(5 * sizeof(SDL_EGLAttrib)));
    if (attributes != nullptr) {
        attributes[0] = EGL_PLATFORM_ANGLE_TYPE_ANGLE;
        attributes[1] = EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE;
        attributes[2] = EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE;
        attributes[3] = deviceType;
        attributes[4] = EGL_NONE;
    }
    return attributes;  // SDL owns and frees the returned array.
}

inline bool supportsGles3(EGLint device, const char* name) {
    const EGLAttrib attributes[] = {
        EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
        EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE, device, EGL_NONE
    };
    EGLDisplay display = eglGetPlatformDisplay(
        EGL_PLATFORM_ANGLE_ANGLE, nullptr, attributes);
    const char* operation = "eglGetPlatformDisplay";
    bool initialized = false;
    bool supported = false;
    EGLContext context = EGL_NO_CONTEXT;
    if (display != EGL_NO_DISPLAY) {
        operation = "eglInitialize";
        initialized = eglInitialize(display, nullptr, nullptr) == EGL_TRUE;
        if (initialized) {
            const EGLint configAttributes[] = {
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                EGL_DEPTH_SIZE, 16, EGL_STENCIL_SIZE, 8, EGL_NONE
            };
            EGLConfig config = nullptr;
            EGLint count = 0;
            operation = "eglChooseConfig(GLES3)";
            if (eglChooseConfig(display, configAttributes, &config, 1, &count) == EGL_TRUE &&
                count > 0) {
                operation = "eglBindAPI";
                if (eglBindAPI(EGL_OPENGL_ES_API) == EGL_TRUE) {
                    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
                    operation = "eglCreateContext(GLES3)";
                    context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
                    supported = context != EGL_NO_CONTEXT;
                }
            }
        }
    }
    if (!supported) {
        std::fprintf(stderr, "[windows_angle] backend=d3d11 device=%s failed: %s EGL=0x%x\n",
                     name, operation, static_cast<unsigned>(eglGetError()));
    }
    if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
    if (initialized) eglTerminate(display);
    return supported;
}

}  // namespace angle_detail

// Run before creating any GL window/context. Probe the exact GLES3 requirements
// before SDL loads EGL: a failed SDL display initialization cannot safely be
// retried by changing attributes on an already loaded display.
// ROMM_ANGLE_DEVICE=auto (default) tries hardware then WARP; hardware disables
// that retry and warp explicitly selects CPU rendering for diagnostics.
// ANGLE may itself return the Basic Render Driver for a hardware request when
// Windows exposes no other adapter, so the actual GL_RENDERER is logged too.
inline bool configureWindowsAngle() {
    const char* requested = SDL_getenv("ROMM_ANGLE_DEVICE");
    if (requested == nullptr || requested[0] == '\0') requested = "auto";
    const bool automatic = std::strcmp(requested, "auto") == 0;
    const bool hardware = std::strcmp(requested, "hardware") == 0;
    const bool warp = std::strcmp(requested, "warp") == 0;
    if (!automatic && !hardware && !warp) {
        return SDL_SetError("ROMM_ANGLE_DEVICE must be auto, hardware, or warp");
    }
    std::fprintf(stderr, "[windows_angle] requested=%s backend=d3d11 profile=GLES3\n", requested);
    bool selectedWarp = warp;
    if (!warp && !angle_detail::supportsGles3(
            EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE, "hardware")) {
        if (hardware) return SDL_SetError("ANGLE D3D11 hardware GLES3 initialization failed");
        selectedWarp = true;
        std::fprintf(stderr, "[windows_angle] hardware unavailable; trying WARP software fallback\n");
    }
    if (selectedWarp && !angle_detail::supportsGles3(
            EGL_PLATFORM_ANGLE_DEVICE_TYPE_D3D_WARP_ANGLE, "warp")) {
        return SDL_SetError("ANGLE D3D11 WARP GLES3 initialization failed");
    }
    angle_detail::deviceType = selectedWarp
        ? EGL_PLATFORM_ANGLE_DEVICE_TYPE_D3D_WARP_ANGLE
        : EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE;
    SDL_SetHintWithPriority(SDL_HINT_OPENGL_ES_DRIVER, "1", SDL_HINT_OVERRIDE);
    SDL_SetHintWithPriority(SDL_HINT_VIDEO_WIN_D3DCOMPILER, "none", SDL_HINT_OVERRIDE);
    // A fallback to eglGetDisplay would discard the explicit device policy.
    SDL_SetHintWithPriority(SDL_HINT_VIDEO_EGL_ALLOW_GETDISPLAY_FALLBACK, "0", SDL_HINT_OVERRIDE);
    SDL_EGL_SetAttributeCallbacks(angle_detail::displayAttributes, nullptr, nullptr, nullptr);
    if (!SDL_GL_SetAttribute(SDL_GL_EGL_PLATFORM, EGL_PLATFORM_ANGLE_ANGLE)) return false;
    std::fprintf(stderr, "[windows_angle] selected=d3d11 device=%s rendering=%s\n",
                 selectedWarp ? "warp" : "hardware",
                 selectedWarp ? "software (not GPU emulation)" : "hardware-requested");
    return true;
}

}  // namespace romm::player
