#include "environment.h"

#include <android/log.h>
#include <cstring>
#include <cstdarg>
#include <cstdio>

#define LOG_TAG "romm_environment"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace romm {

namespace {

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

        default: {
            if (loggedUnsupported_.insert(cmd).second) {
                LOGW("unsupported environment command: %u", cmd);
            }
            return false;
        }
    }
}

}  // namespace romm
