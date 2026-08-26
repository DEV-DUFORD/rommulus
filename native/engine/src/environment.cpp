#include "environment.h"

#include <native/engine/HardwareContext.h>
#include <native/engine/LogSink.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>

namespace romm {

namespace {

constexpr unsigned kRetroEnvironmentGetClearAllThreadWaitsCallback = 0x800003;

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

// Free functions used as callback implementations for the HW render callback.
// Lambdas cannot be assigned to C function-pointer types, so these must be
// plain functions with the correct signature.

uintptr_t hwGetCurrentFramebuffer(void) {
#ifdef ROMM_FORCE_DEFAULT_FRAMEBUFFER
    return 0;
#else
    return romm::gl::context().currentFramebuffer();
#endif
}

retro_proc_address_t hwGetProcAddress(const char* sym) {
    // Resolved through the platform hardware-context registry; the platform
    // implementation forwards to its native proc-address resolver and returns
    // nullptr for unknown names (the engine must never call the platform
    // graphics loader directly — LINUX_X64.md section 11).
    return romm::gl::context().getProcAddress(sym);
}

bool clearAllThreadWaits(unsigned, void*) {
    // This frontend has no core-facing thread waits of its own. GLideN64 uses
    // the callback as a shutdown coordination hook for its own worker.
    return true;
}

void retro_log_printf(enum retro_log_level level, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    char buffer[512];
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    switch (level) {
        case RETRO_LOG_ERROR:
            romm::log::sink().log(romm::log::Severity::Error, "romm_core", buffer);
            break;
        case RETRO_LOG_WARN:
            romm::log::sink().log(romm::log::Severity::Warn, "romm_core", buffer);
            break;
        default:
            romm::log::sink().log(romm::log::Severity::Info, "romm_core", buffer);
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

#define LOG_TAG "romm_environment"
#define LOGI(...) logPrint(romm::log::Severity::Info, LOG_TAG, __VA_ARGS__)
#define LOGW(...) logPrint(romm::log::Severity::Warn, LOG_TAG, __VA_ARGS__)

EnvironmentHandler::EnvironmentHandler() = default;

void EnvironmentHandler::retainInputDescriptors(
        const struct retro_input_descriptor* descriptors) {
    // Replace any previously retained copy: a core may issue
    // SET_INPUT_DESCRIPTORS more than once (e.g. per new-game-load), and each
    // new call invalidates the previous one.
    inputDescriptors_.clear();
    if (descriptors == nullptr) return;

    for (const struct retro_input_descriptor* desc = descriptors;
         desc->description != nullptr;
         ++desc) {
        inputDescriptors_.push_back(RetainedInputDescriptor{
            desc->port,
            desc->device,
            desc->index,
            desc->id,
            desc->description == nullptr ? std::string() : std::string(desc->description),
        });
    }
}

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
            // Phase 8: retain a deep copy of the descriptors for validation /
            // JNI snapshot exposure (LIBRETRO_REFACTOR.md section 7.2 keeps
            // this real support). The array is null-terminated (a core emits a
            // trailing all-zero entry with description == nullptr, e.g. the
            // snes9x init_descriptors() sentinel), and the core's description
            // pointers are only valid until retro_unload_game(), so we must
            // copy the strings into host-owned memory.
            retainInputDescriptors(static_cast<const struct retro_input_descriptor*>(data));
            return true;
        }

        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            *static_cast<bool*>(data) = true;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: {
            if (data == nullptr) return false;
            int flags = RETRO_AV_ENABLE_AUDIO;
            if (videoEnabled_) flags |= RETRO_AV_ENABLE_VIDEO;
            *static_cast<int*>(data) = flags;
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
            if (data == nullptr) return false;
            if (geometryCallback_) {
                geometryCallback_(*static_cast<const struct retro_game_geometry*>(data));
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            if (data == nullptr) return false;
            const auto* avInfo = static_cast<const struct retro_system_av_info*>(data);
            if (geometryCallback_) geometryCallback_(avInfo->geometry);
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
                case kHwContextEs2:
                case kHwContextEs3:
                case kHwContextEsVersion:
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
            // render context and proc-address function. The core casts the
            // base struct to the ES-specific OpenGlEsHwRenderInterface layout.
            auto** interface = static_cast<struct retro_hw_render_interface**>(data);
            if (interface == nullptr) return false;

            // Only meaningful for an OpenGL ES context that we actually host.
            if (!hwRenderActive_) return false;
            if (hwRenderCallback_.context_type != kHwContextEs3 &&
                hwRenderCallback_.context_type != kHwContextEsVersion &&
                hwRenderCallback_.context_type != kHwContextEs2) {
                LOGW("GET_HW_RENDER_INTERFACE: unsupported context type %u",
                     hwRenderCallback_.context_type);
                return false;
            }

            // The render context is created lazily after SET_HW_RENDER; fetch
            // it now (returns nullptr until the platform context is ready).
            if (!renderContextProvider_) return false;
            void* renderContext = renderContextProvider_();
            if (renderContext == nullptr) return false;

            hwRenderInterface_.interface_type =
                static_cast<enum retro_hw_render_interface_type>(kHwRenderInterfaceEs);
            hwRenderInterface_.interface_version = 1;
            hwRenderInterface_.context = renderContext;
            hwRenderInterface_.get_proc_address = hwGetProcAddress;

            *interface = reinterpret_cast<struct retro_hw_render_interface*>(&hwRenderInterface_);
            LOGI("GET_HW_RENDER_INTERFACE: returning ES interface (version 1)");
            return true;
        }

        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: {
            auto* type = static_cast<unsigned*>(data);
            if (type == nullptr) return false;
            *type = kHwContextEs3;
            LOGI("GET_PREFERRED_HW_RENDER: returning ES3");
            return true;
        }

        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT: {
            // The platform frontend owns one context that remains current for
            // the core's render thread, satisfying the shared-context contract.
            return hwRenderActive_;
        }

        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
            // GLideN64 does not use context negotiation; Vulkan cores may.
            // Log and reject so the core knows we don't support it.
            return false;
        }

        case kRetroEnvironmentGetClearAllThreadWaitsCallback: {
            auto* callback = static_cast<retro_environment_t*>(data);
            if (callback == nullptr) return false;
            *callback = clearAllThreadWaits;
            return true;
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
