#include "environment.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/log.h>
#include <cstring>
#include <cstdarg>
#include <cstdio>

#define LOG_TAG "romm_environment"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace romm {

namespace {

// Free functions used as callback implementations for the HW render callback.
// Lambdas cannot be assigned to C function-pointer types, so these must be
// plain functions with the correct signature.

uintptr_t hwGetCurrentFramebuffer(void) {
    // Default framebuffer (0) is what libretro-droid returns.
    return 0;
}

retro_proc_address_t hwGetProcAddress(const char* sym) {
    // eglGetProcAddress returns __eglMustCastToProperFunctionPointerType
    // (aka void(*)()), which is the same as retro_proc_address_t.
    return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(sym));
}

void retro_log_printf(enum retro_log_level level, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    char buffer[512];
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    switch (level) {
        case RETRO_LOG_ERROR:
            __android_log_print(ANDROID_LOG_ERROR, "romm_core", "%s", buffer);
            break;
        case RETRO_LOG_WARN:
            __android_log_print(ANDROID_LOG_WARN, "romm_core", "%s", buffer);
            break;
        default:
            __android_log_print(ANDROID_LOG_INFO, "romm_core", "%s", buffer);
            break;
    }
}

void registerLegacyOptions(
        std::unordered_map<std::string, std::string>& values,
        const struct retro_variable* variables) {
    if (variables == nullptr) return;

    for (const struct retro_variable* variable = variables;
         variable->key != nullptr;
         ++variable) {
        if (variable->value == nullptr) continue;

        const char* separator = std::strchr(variable->value, ';');
        if (separator == nullptr) continue;

        const char* defaultValue = separator + 1;
        while (*defaultValue == ' ') ++defaultValue;
        const char* end = std::strchr(defaultValue, '|');
        values[variable->key] =
                end == nullptr ? defaultValue : std::string(defaultValue, end);
    }
}

void registerV1Options(
        std::unordered_map<std::string, std::string>& values,
        const struct retro_core_option_definition* definitions) {
    if (definitions == nullptr) return;

    for (const struct retro_core_option_definition* definition = definitions;
         definition->key != nullptr;
         ++definition) {
        const char* defaultValue = definition->default_value != nullptr
                ? definition->default_value
                : definition->values[0].value;
        if (defaultValue != nullptr) values[definition->key] = defaultValue;
    }
}

void registerV2Options(
        std::unordered_map<std::string, std::string>& values,
        const struct retro_core_option_v2_definition* definitions) {
    if (definitions == nullptr) return;

    for (const struct retro_core_option_v2_definition* definition = definitions;
         definition->key != nullptr;
         ++definition) {
        const char* defaultValue = definition->default_value != nullptr
                ? definition->default_value
                : definition->values[0].value;
        if (defaultValue != nullptr) values[definition->key] = defaultValue;
    }
}

}  // namespace

EnvironmentHandler::EnvironmentHandler() = default;

void EnvironmentHandler::setCoreOptionOverride(
        const std::string& key, const std::string& value) {
    coreOptionOverrides_[key] = value;
    coreOptionValues_[key] = value;
}

bool EnvironmentHandler::handle(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto* fmt = static_cast<const enum retro_pixel_format*>(data);
            pixelFormat_ = *fmt;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            *static_cast<const char**>(data) =
                systemDirectory_.empty() ? nullptr : systemDirectory_.c_str();
            return !systemDirectory_.empty();
        }

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            *static_cast<const char**>(data) =
                saveDirectory_.empty() ? nullptr : saveDirectory_.c_str();
            return !saveDirectory_.empty();
        }

        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY: {
            *static_cast<const char**>(data) =
                contentDirectory_.empty() ? nullptr : contentDirectory_.c_str();
            return !contentDirectory_.empty();
        }

        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME: {
            supportsNoGame_ = *static_cast<const bool*>(data);
            return true;
        }

        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            registerLegacyOptions(
                    coreOptionValues_,
                    static_cast<const struct retro_variable*>(data));
            for (const auto& overrideValue : coreOptionOverrides_) {
                coreOptionValues_[overrideValue.first] = overrideValue.second;
            }
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            if (data == nullptr) return true;

            auto* var = static_cast<struct retro_variable*>(data);
            var->value = nullptr;
            if (var->key != nullptr) {
                const auto value = coreOptionValues_.find(var->key);
                if (value != coreOptionValues_.end()) {
                    var->value = value->second.c_str();
                }
            }
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            *static_cast<bool*>(data) = false;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: {
            if (data == nullptr) return false;
            *static_cast<unsigned*>(data) = 2;
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS: {
            registerV1Options(
                    coreOptionValues_,
                    static_cast<const struct retro_core_option_definition*>(data));
            for (const auto& overrideValue : coreOptionOverrides_) {
                coreOptionValues_[overrideValue.first] = overrideValue.second;
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
            if (data == nullptr) return true;
            const auto* options = static_cast<const struct retro_core_options_intl*>(data);
            registerV1Options(coreOptionValues_, options->us);
            for (const auto& overrideValue : coreOptionOverrides_) {
                coreOptionValues_[overrideValue.first] = overrideValue.second;
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
            if (data == nullptr) return true;
            const auto* options = static_cast<const struct retro_core_options_v2*>(data);
            registerV2Options(coreOptionValues_, options->definitions);
            for (const auto& overrideValue : coreOptionOverrides_) {
                coreOptionValues_[overrideValue.first] = overrideValue.second;
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            if (data == nullptr) return true;
            const auto* options = static_cast<const struct retro_core_options_v2_intl*>(data);
            if (options->us != nullptr) {
                registerV2Options(coreOptionValues_, options->us->definitions);
            }
            for (const auto& overrideValue : coreOptionOverrides_) {
                coreOptionValues_[overrideValue.first] = overrideValue.second;
            }
            return true;
        }

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            auto* cb = static_cast<struct retro_log_callback*>(data);
            cb->log = retro_log_printf;
            return true;
        }

        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS: {
            // Accepted; the host doesn't need to display them yet, but this is
            // real support, not a silent claim (LIBRETRO_REFACTOR.md section 7.2).
            return true;
        }

        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            *static_cast<bool*>(data) = true;
            return true;
        }

        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL: {
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO: {
            return true;
        }

        case RETRO_ENVIRONMENT_GET_LANGUAGE: {
            *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;
        }

        case RETRO_ENVIRONMENT_SHUTDOWN: {
            shutdownRequested_ = true;
            LOGI("core requested RETRO_ENVIRONMENT_SHUTDOWN");
            return true;
        }

        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            // Frontend acknowledges; actual surface/AV info re-negotiation is
            // handled by the video pipeline (added in a later Phase 2 commit).
            return true;
        }

        // -------------------------------------------------------------------
        // Phase 4: SameBoy (and real cores generally) probe these. None of
        // them are needed for correct gameplay/save behavior in this build,
        // so each is handled explicitly (real, honest "not supported" rather
        // than falling into the generic unsupported-command warning) instead
        // of silently claiming support it doesn't have (LIBRETRO_REFACTOR.md
        // section 7.2's "environment callback support" principle).
        // -------------------------------------------------------------------

        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: {
            // No rumble output pipeline exists in this build.
            return false;
        }

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: {
            // Per-button input_state queries (the existing path) are already
            // fully supported; the bitmask fast-path is a pure optimization
            // this host doesn't implement.
            return false;
        }

        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS: {
            // No RetroAchievements-style raw memory map consumer exists.
            return false;
        }

        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO: {
            // No multi-content subsystem launch path (e.g. GBC link cable)
            // exists; every launch is a single piece of content.
            return false;
        }

        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS: {
            // No achievements feature exists in this product.
            return false;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY: {
            // Defaults are registered, but this frontend has no core-options
            // UI whose per-option visibility can be changed.
            return false;
        }

        // -------------------------------------------------------------------
        // Hardware rendering (RETRO_ENVIRONMENT_SET_HW_RENDER) — required by
        // GLideN64 (Mupen64Plus-Next). The glsm library in the core calls
        // SET_HW_RENDER with a retro_hw_render_callback; we store it by value
        // and wire in get_current_framebuffer / get_proc_address.
        // -------------------------------------------------------------------

        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto* cb = static_cast<struct retro_hw_render_callback*>(data);
            if (cb == nullptr) return false;

            // Only accept OpenGL ES contexts.
            switch (cb->context_type) {
                case RETRO_HW_CONTEXT_OPENGLES2:
                case RETRO_HW_CONTEXT_OPENGLES3:
                case RETRO_HW_CONTEXT_OPENGLES_VERSION:
                    break;
                default:
                    LOGW("SET_HW_RENDER: unsupported context type %u", cb->context_type);
                    return false;
            }

            // Libretro requires the frontend to populate these fields in the
            // core-owned callback struct. Keeping only a patched host copy
            // leaves the core's own function pointers null.
            cb->get_current_framebuffer = hwGetCurrentFramebuffer;
            cb->get_proc_address = hwGetProcAddress;
            hwRenderCallback_ = *cb;
            hwRenderActive_ = true;

            LOGI("SET_HW_RENDER accepted: context_type=%u, version=%u.%u",
                 hwRenderCallback_.context_type,
                 hwRenderCallback_.version_major,
                 hwRenderCallback_.version_minor);
            return true;
        }

        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE: {
            // GLideN64/glsm may query this after SET_HW_RENDER to obtain the
            // EGL context and proc-address function. The core casts the base
            // struct to the GL-specific OpenGlEsHwRenderInterface layout.
            auto** interface = static_cast<struct retro_hw_render_interface**>(data);
            if (interface == nullptr) return false;

            // Only meaningful for an OpenGL ES context that we actually host.
            if (!hwRenderActive_) return false;
            if (hwRenderCallback_.context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
                hwRenderCallback_.context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION &&
                hwRenderCallback_.context_type != RETRO_HW_CONTEXT_OPENGLES2) {
                LOGW("GET_HW_RENDER_INTERFACE: unsupported context type %u",
                     hwRenderCallback_.context_type);
                return false;
            }

            // The EGL context is created lazily after SET_HW_RENDER; fetch it
            // now (returns EGL_NO_CONTEXT until GlContextManager is ready).
            if (!glContextProvider_) return false;
            EGLContext eglContext = glContextProvider_();
            if (eglContext == EGL_NO_CONTEXT) return false;

            hwRenderInterface_.interface_type =
                static_cast<enum retro_hw_render_interface_type>(RETRO_HW_RENDER_INTERFACE_OPENGLES);
            hwRenderInterface_.interface_version = 1;
            hwRenderInterface_.context = eglContext;
            hwRenderInterface_.get_proc_address = hwGetProcAddress;

            *interface = reinterpret_cast<struct retro_hw_render_interface*>(&hwRenderInterface_);
            LOGI("GET_HW_RENDER_INTERFACE: returning OPENGLES interface (version 1)");
            return true;
        }

        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: {
            auto* type = static_cast<unsigned*>(data);
            if (type == nullptr) return false;
            *type = RETRO_HW_CONTEXT_OPENGLES3;
            LOGI("GET_PREFERRED_HW_RENDER: returning OPENGLES3");
            return true;
        }

        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
            // GLideN64 does not use context negotiation; Vulkan cores may.
            // Log and reject so the core knows we don't support it.
            return false;
        }

        default: {
            if (loggedUnsupported_.insert(cmd).second) {
                LOGW("unsupported environment command: %u", cmd);
            }
            return false;
        }
    }
}

}  // namespace romm
