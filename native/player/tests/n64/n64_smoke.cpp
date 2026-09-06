// Standalone hardware core probe. Run under a process timeout: a dynarec
// regression can hang inside retro_run before control returns to the harness.
#include <windows.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>
#include <GLES3/gl3.h>
#include <libretro.h>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
retro_hw_render_callback hw{};
HMODULE gles;
std::string directory;
std::map<std::string, std::string> defaults;
unsigned video_frames = 0, colored_frames = 0, input_calls = 0;
size_t audio_frames = 0, nonzero_samples = 0;
bool button_pressed = true;
void require(bool success, const char* message) {
    if (!success) throw std::runtime_error(message);
}
void log_message(enum retro_log_level, const char* format, ...) {
    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);
}
uintptr_t framebuffer() { return 0; }
retro_proc_address_t proc(const char* name) {
    auto address = eglGetProcAddress(name);
    return reinterpret_cast<retro_proc_address_t>(
        address ? address : reinterpret_cast<__eglMustCastToProperFunctionPointerType>(
            GetProcAddress(gles, name)));
}
bool environment(unsigned command, void* data) {
    switch (command) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        static_cast<retro_log_callback*>(data)->log = log_message;
        return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
        *static_cast<const char**>(data) = directory.c_str();
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        return *static_cast<retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        auto* request = static_cast<retro_hw_render_callback*>(data);
        if (request->context_type != RETRO_HW_CONTEXT_OPENGLES3) return false;
        request->get_current_framebuffer = framebuffer;
        request->get_proc_address = proc;
        hw = *request;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES: {
        const auto* variable = static_cast<const retro_variable*>(data);
        for (; variable->key; ++variable) {
            const std::string description = variable->value;
            const auto separator = description.find("; ");
            if (separator == std::string::npos) continue;
            const auto start = separator + 2;
            const auto end = description.find('|', start);
            defaults[variable->key] = description.substr(start, end - start);
        }
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        auto* variable = static_cast<retro_variable*>(data);
        const char* key = variable->key;
        variable->value = nullptr;
        if (strstr(key, "-cpucore")) variable->value = "dynamic_recompiler";
        else if (strstr(key, "-rdp-plugin")) variable->value = "gliden64";
        else if (strstr(key, "-rsp-plugin")) variable->value = "hle";
        else if (strstr(key, "-ThreadedRenderer")) variable->value = "False";
        else if (strstr(key, "-EnableFBEmulation")) variable->value = "True";
        else if (strstr(key, "-EnableCopyColorFromRDRAM")) variable->value = "True";
        else if (strstr(key, "-43screensize")) variable->value = "320x240";
        else {
            const auto entry = defaults.find(key);
            if (entry != defaults.end()) variable->value = entry->second.c_str();
        }
        return variable->value != nullptr;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *static_cast<bool*>(data) = false;
        return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *static_cast<bool*>(data) = true;
        return true;
    default:
        return false;
    }
}
void video(const void* data, unsigned width, unsigned height, size_t) {
    if (data != RETRO_HW_FRAME_BUFFER_VALID || !width || !height) return;
    ++video_frames;
    GLint previous = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previous);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    unsigned char pixel[4]{};
    glReadPixels(width / 2, height / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    if (pixel[0] > 100 && pixel[1] < 100 && pixel[2] > 50) ++colored_frames;
    glBindFramebuffer(GL_READ_FRAMEBUFFER, previous);
}
void audio(int16_t left, int16_t right) {
    ++audio_frames;
    nonzero_samples += (left != 0) + (right != 0);
}
size_t audio_batch(const int16_t* data, size_t frames) {
    audio_frames += frames;
    for (size_t i = 0; i < frames * 2; ++i) nonzero_samples += data[i] != 0;
    return frames;
}
void poll() {}
int16_t input(unsigned port, unsigned device, unsigned, unsigned id) {
    ++input_calls;
    // The core's default mapping binds libretro B to the N64 A button.
    return button_pressed && port == 0 && device == RETRO_DEVICE_JOYPAD &&
           id == RETRO_DEVICE_ID_JOYPAD_B;
}
template <typename T> T symbol(HMODULE core, const char* name) {
    auto address = GetProcAddress(core, name);
    require(address != nullptr, name);
    return reinterpret_cast<T>(address);
}
bool open_context(bool warp, EGLDisplay& display, EGLSurface& surface, EGLContext& context) {
    const EGLAttrib platform_attrs[] = {
        EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
        EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE,
        warp ? EGL_PLATFORM_ANGLE_DEVICE_TYPE_D3D_WARP_ANGLE
             : EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE,
        EGL_NONE};
    display = eglGetPlatformDisplay(EGL_PLATFORM_ANGLE_ANGLE, nullptr, platform_attrs);
    surface = EGL_NO_SURFACE;
    context = EGL_NO_CONTEXT;
    bool initialized = display != EGL_NO_DISPLAY &&
                       eglInitialize(display, nullptr, nullptr);
    bool ready = false;
    if (initialized && eglBindAPI(EGL_OPENGL_ES_API)) {
        const EGLint attrs[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8, EGL_NONE};
        EGLConfig config = nullptr;
        EGLint count = 0;
        if (eglChooseConfig(display, attrs, &config, 1, &count) && count) {
            const EGLint surface_attrs[] = {EGL_WIDTH, 640, EGL_HEIGHT, 480, EGL_NONE};
            surface = eglCreatePbufferSurface(display, config, surface_attrs);
            const EGLint context_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
            context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attrs);
            ready = surface != EGL_NO_SURFACE && context != EGL_NO_CONTEXT &&
                    eglMakeCurrent(display, surface, surface, context);
        }
    }
    printf("N64 ANGLE backend=d3d11 device=%s initialized=%s\n",
           warp ? "warp (software)" : "hardware-requested", ready ? "true" : "false");
    if (!ready) {
        fprintf(stderr, "N64 ANGLE EGL error=0x%x\n", eglGetError());
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (initialized) eglTerminate(display);
    }
    return ready;
}
}

int main(int argc, char** argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: n64_smoke CORE.dll ORIGINAL.z64 SAVE_DIRECTORY\n");
        return 2;
    }
    try {
        directory = argv[3];
        auto core = LoadLibraryA(argv[1]);
        require(core != nullptr, "LoadLibrary(core) failed");
        gles = LoadLibraryA("libGLESv2.dll");
        require(gles != nullptr, "pinned ANGLE GLES DLL not found");
        const char* requested = getenv("ROMM_ANGLE_DEVICE");
        if (!requested || !requested[0]) requested = "auto";
        const bool automatic = strcmp(requested, "auto") == 0;
        const bool warp = strcmp(requested, "warp") == 0;
        require(automatic || warp || strcmp(requested, "hardware") == 0,
                "ROMM_ANGLE_DEVICE must be auto, hardware or warp");
        EGLDisplay display;
        EGLSurface surface;
        EGLContext context;
        bool ready = !warp && open_context(false, display, surface, context);
        if (!ready && (automatic || warp)) ready = open_context(true, display, surface, context);
        require(ready, "ANGLE D3D11 GLES3 context unavailable");
        printf("N64 renderer: %s\n", glGetString(GL_RENDERER));
#define LOAD(name) auto name = symbol<decltype(&::name)>(core, #name)
        LOAD(retro_set_environment);
        LOAD(retro_set_video_refresh);
        LOAD(retro_set_audio_sample);
        LOAD(retro_set_audio_sample_batch);
        LOAD(retro_set_input_poll);
        LOAD(retro_set_input_state);
        LOAD(retro_set_controller_port_device);
        LOAD(retro_init);
        LOAD(retro_load_game);
        LOAD(retro_run);
        LOAD(retro_get_memory_data);
        LOAD(retro_get_memory_size);
        LOAD(retro_unload_game);
        LOAD(retro_deinit);
#undef LOAD
        retro_set_environment(environment);
        retro_set_video_refresh(video);
        retro_set_audio_sample(audio);
        retro_set_audio_sample_batch(audio_batch);
        retro_set_input_poll(poll);
        retro_set_input_state(input);
        retro_init();
        retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
        std::ifstream file(argv[2], std::ios::binary);
        std::vector<char> rom((std::istreambuf_iterator<char>(file)), {});
        require(!rom.empty(), "original ROM missing");
        retro_game_info game{argv[2], rom.data(), rom.size(), nullptr};
        require(retro_load_game(&game), "core rejected original ROM");
        require(hw.context_reset != nullptr, "core did not request GLES3");
        hw.context_reset();
        for (unsigned i = 0; i < 180; ++i) retro_run();
        constexpr size_t sram_offset = 0x800 + 4 * 0x8000;
        require(retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) == 0x48800,
                "save memory layout changed");
        const auto* save = static_cast<const unsigned char*>(
            retro_get_memory_data(RETRO_MEMORY_SAVE_RAM));
        require(save != nullptr, "save memory not exposed");
        uint32_t words[3]{};
        memcpy(words, save + sram_offset, sizeof(words));
        printf("N64 frames=%u colored=%u audio=%zu nonzero=%zu input=%u SRAM=%08x %08x %08x\n",
               video_frames, colored_frames, audio_frames, nonzero_samples, input_calls,
               words[0], words[1], words[2]);
        require(words[0] == 0x524F4D4D && words[1] == 0x4E363431,
                "CPU/PI SRAM marker mismatch");
        require((words[2] & 0xFF) != 0, "controller press did not reach emulated PIF");
        button_pressed = false;
        for (unsigned i = 0; i < 30; ++i) retro_run();
        memcpy(words, save + sram_offset, sizeof(words));
        require((words[2] & 0xFF) == 0, "controller release did not reach emulated PIF");
        require(video_frames > 100 && colored_frames > 30, "GLES framebuffer missing");
        require(audio_frames > 1000 && nonzero_samples > 100, "AI PCM missing");
        if (hw.context_destroy) hw.context_destroy();
        retro_unload_game();
        retro_deinit();
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(display, context);
        eglDestroySurface(display, surface);
        eglTerminate(display);
        FreeLibrary(core);
        FreeLibrary(gles);
        puts("N64_WINDOWS_SMOKE_PASS");
        return 0;
    } catch (const std::exception& error) {
        fprintf(stderr, "N64_WINDOWS_SMOKE_FAIL: %s\n", error.what());
        return 1;
    }
}
