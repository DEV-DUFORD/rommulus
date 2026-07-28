// test_core.c — app-owned, copyright-safe synthetic Libretro core.
//
// Implements LIBRETRO_REFACTOR.md section 7.3 in full:
//   - Moving color bars, rendered correctly in whichever pixel format the
//     frontend negotiates (XRGB8888 preferred, RGB565 and 0RGB1555 supported
//     as fallbacks so the core is exercised honestly against all three).
//   - A deterministic stereo tone at a sample rate that does not match any
//     particular device's native rate (22050 Hz), so audio resampling/pacing
//     is actually exercised.
//   - An on-screen marker moved by RetroPad digital input and the left
//     analog stick.
//   - A small SRAM region (RETRO_MEMORY_SAVE_RAM) with a visible,
//     monotonically incrementing byte so save/restore round trips are
//     visually and programmatically verifiable.
//   - Deterministic retro_serialize/retro_unserialize of all mutable state.
//   - One geometry change partway through a session, to exercise frontend
//     handling of RETRO_ENVIRONMENT_SET_GEOMETRY.
//   - A request-shutdown input combo, to exercise RETRO_ENVIRONMENT_SHUTDOWN
//     lifecycle propagation.
//
// This is intentionally the *only* "core" content authored for Phase 2: no
// third-party emulator source is present here. It isolates frontend defects
// from third-party core behavior (section 7.3) and gives Phase 2 a
// copyright-safe, fully-understood core to validate the native host against
// before any licensed core is integrated.

#include "libretro.h"

#include <math.h>
#include <string.h>
#include <stdlib.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

#define TEST_CORE_BASE_WIDTH 320
#define TEST_CORE_BASE_HEIGHT 240
#define TEST_CORE_GROWN_WIDTH 384
#define TEST_CORE_GROWN_HEIGHT 288
#define TEST_CORE_FPS 60.0
#define TEST_CORE_SAMPLE_RATE 22050.0 /* deliberately not a common device-native rate */
#define TEST_CORE_TONE_HZ 440.0
#define TEST_CORE_SRAM_SIZE 64
#define TEST_CORE_GEOMETRY_CHANGE_FRAME 300
#define TEST_CORE_SHUTDOWN_HOLD_FRAMES 120 /* ~2s of START+SELECT held */

// ---------------------------------------------------------------------------
// Libretro callback storage
// ---------------------------------------------------------------------------

static retro_environment_t environ_cb;
static retro_video_refresh_t video_cb;
static retro_audio_sample_t audio_sample_cb;
static retro_audio_sample_batch_t audio_batch_cb;
static retro_input_poll_t input_poll_cb;
static retro_input_state_t input_state_cb;

// ---------------------------------------------------------------------------
// Core state (everything here must round-trip through serialize/unserialize)
// ---------------------------------------------------------------------------

typedef struct {
    uint32_t frame_count;
    int32_t marker_x;
    int32_t marker_y;
    double tone_phase;
    uint8_t sram[TEST_CORE_SRAM_SIZE];
    int32_t shutdown_hold_frames;
    int geometry_grown; /* 0 = base geometry, 1 = grown geometry (one-time change) */
} test_core_state_t;

static test_core_state_t g_state;

static enum retro_pixel_format g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
static uint32_t *g_framebuffer; /* always stored as 32-bit XRGB8888 internally, converted on flush */
static int g_width = TEST_CORE_BASE_WIDTH;
static int g_height = TEST_CORE_BASE_HEIGHT;

// ---------------------------------------------------------------------------
// Setup callbacks (called by the frontend before retro_init)
// ---------------------------------------------------------------------------

void retro_set_environment(retro_environment_t cb) {
    environ_cb = cb;

    bool no_game = true;
    cb(RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME, &no_game);
}

void retro_set_video_refresh(retro_video_refresh_t cb) { video_cb = cb; }
void retro_set_audio_sample(retro_audio_sample_t cb) { audio_sample_cb = cb; }
void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) { audio_batch_cb = cb; }
void retro_set_input_poll(retro_input_poll_t cb) { input_poll_cb = cb; }
void retro_set_input_state(retro_input_state_t cb) { input_state_cb = cb; }

// ---------------------------------------------------------------------------
// retro_api_version / retro_get_system_info / retro_get_system_av_info
// ---------------------------------------------------------------------------

unsigned retro_api_version(void) { return RETRO_API_VERSION; }

void retro_get_system_info(struct retro_system_info *info) {
    memset(info, 0, sizeof(*info));
    info->library_name = "RomM Synthetic Test Core";
    info->library_version = "1.0";
    info->valid_extensions = NULL; /* accepts no-content launches only */
    info->need_fullpath = false;
    info->block_extract = false;
}

static void fill_av_info(struct retro_system_av_info *info) {
    memset(info, 0, sizeof(*info));
    info->geometry.base_width = (unsigned)g_width;
    info->geometry.base_height = (unsigned)g_height;
    info->geometry.max_width = TEST_CORE_GROWN_WIDTH;
    info->geometry.max_height = TEST_CORE_GROWN_HEIGHT;
    info->geometry.aspect_ratio = (float)g_width / (float)g_height;
    info->timing.fps = TEST_CORE_FPS;
    info->timing.sample_rate = TEST_CORE_SAMPLE_RATE;
}

void retro_get_system_av_info(struct retro_system_av_info *info) {
    fill_av_info(info);
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

static void negotiate_pixel_format(void) {
    /* Prefer XRGB8888 (most common for modern frontends), then RGB565, then
     * fall back to the libretro default of 0RGB1555. Whichever the frontend
     * accepts, retro_run() below renders correctly in that exact format —
     * this is the "render... in each initial pixel format" requirement. */
    enum retro_pixel_format fmt = RETRO_PIXEL_FORMAT_XRGB8888;
    if (environ_cb(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &fmt)) {
        g_pixel_format = fmt;
        return;
    }

    fmt = RETRO_PIXEL_FORMAT_RGB565;
    if (environ_cb(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &fmt)) {
        g_pixel_format = fmt;
        return;
    }

    g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
}

void retro_init(void) {
    memset(&g_state, 0, sizeof(g_state));
    g_state.marker_x = TEST_CORE_BASE_WIDTH / 2;
    g_state.marker_y = TEST_CORE_BASE_HEIGHT / 2;
    g_state.sram[0] = 0;

    g_width = TEST_CORE_BASE_WIDTH;
    g_height = TEST_CORE_BASE_HEIGHT;
    g_framebuffer = (uint32_t *)malloc((size_t)TEST_CORE_GROWN_WIDTH * TEST_CORE_GROWN_HEIGHT * sizeof(uint32_t));

    negotiate_pixel_format();
}

void retro_deinit(void) {
    free(g_framebuffer);
    g_framebuffer = NULL;
}

void retro_reset(void) {
    g_state.frame_count = 0;
    g_state.marker_x = TEST_CORE_BASE_WIDTH / 2;
    g_state.marker_y = TEST_CORE_BASE_HEIGHT / 2;
    g_state.tone_phase = 0.0;
    g_state.shutdown_hold_frames = 0;
}

bool retro_load_game(const struct retro_game_info *game) {
    (void)game; /* no-content core: supports launching without a ROM */
    struct retro_input_descriptor desc[] = {
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_LEFT, "Move marker left"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_RIGHT, "Move marker right"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_UP, "Move marker up"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_DOWN, "Move marker down"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START, "Hold with Select to request shutdown"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_SELECT, "Hold with Start to request shutdown"},
        {0, 0, 0, 0, NULL},
    };
    environ_cb(RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS, desc);
    return true;
}

void retro_unload_game(void) {}

unsigned retro_get_region(void) { return RETRO_REGION_NTSC; }

bool retro_load_game_special(unsigned type, const struct retro_game_info *info, size_t num_info) {
    (void)type; (void)info; (void)num_info;
    return false; /* this core never uses the special-content path */
}

// ---------------------------------------------------------------------------
// Rendering: moving color bars + on-screen marker, in the negotiated format
// ---------------------------------------------------------------------------

static inline uint16_t pack_rgb565(uint8_t r, uint8_t g, uint8_t b) {
    return (uint16_t)(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

static inline uint16_t pack_0rgb1555(uint8_t r, uint8_t g, uint8_t b) {
    return (uint16_t)(((r & 0xF8) << 7) | ((g & 0xF8) << 2) | (b >> 3));
}

static void render_frame(void) {
    const int bars = 8;
    const int bar_width = g_width / bars;
    const uint32_t phase = g_state.frame_count;

    for (int y = 0; y < g_height; y++) {
        for (int x = 0; x < g_width; x++) {
            int bar_index = ((x + (int)phase) / (bar_width > 0 ? bar_width : 1)) % bars;
            uint8_t r = (uint8_t)((bar_index * 255) / bars);
            uint8_t g = (uint8_t)(((bar_index + 3) * 255 / bars) % 256);
            uint8_t b = (uint8_t)(((bar_index + 6) * 255 / bars) % 256);
            g_framebuffer[y * g_width + x] = ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
        }
    }

    /* Draw a small solid marker square driven by input. */
    const int marker_size = 12;
    for (int dy = -marker_size / 2; dy < marker_size / 2; dy++) {
        int py = g_state.marker_y + dy;
        if (py < 0 || py >= g_height) continue;
        for (int dx = -marker_size / 2; dx < marker_size / 2; dx++) {
            int px = g_state.marker_x + dx;
            if (px < 0 || px >= g_width) continue;
            g_framebuffer[py * g_width + px] = 0x00FFFFFFu;
        }
    }
}

static void flush_frame(void) {
    switch (g_pixel_format) {
        case RETRO_PIXEL_FORMAT_XRGB8888: {
            video_cb(g_framebuffer, (unsigned)g_width, (unsigned)g_height,
                     (size_t)g_width * sizeof(uint32_t));
            break;
        }
        case RETRO_PIXEL_FORMAT_RGB565: {
            static uint16_t *scratch;
            static size_t scratch_capacity;
            size_t needed = (size_t)g_width * (size_t)g_height;
            if (scratch_capacity < needed) {
                free(scratch);
                scratch = (uint16_t *)malloc(needed * sizeof(uint16_t));
                scratch_capacity = needed;
            }
            for (size_t i = 0; i < needed; i++) {
                uint32_t px = g_framebuffer[i];
                scratch[i] = pack_rgb565((uint8_t)(px >> 16), (uint8_t)(px >> 8), (uint8_t)px);
            }
            video_cb(scratch, (unsigned)g_width, (unsigned)g_height, (size_t)g_width * sizeof(uint16_t));
            break;
        }
        case RETRO_PIXEL_FORMAT_0RGB1555:
        default: {
            static uint16_t *scratch;
            static size_t scratch_capacity;
            size_t needed = (size_t)g_width * (size_t)g_height;
            if (scratch_capacity < needed) {
                free(scratch);
                scratch = (uint16_t *)malloc(needed * sizeof(uint16_t));
                scratch_capacity = needed;
            }
            for (size_t i = 0; i < needed; i++) {
                uint32_t px = g_framebuffer[i];
                scratch[i] = pack_0rgb1555((uint8_t)(px >> 16), (uint8_t)(px >> 8), (uint8_t)px);
            }
            video_cb(scratch, (unsigned)g_width, (unsigned)g_height, (size_t)g_width * sizeof(uint16_t));
            break;
        }
    }
}

// ---------------------------------------------------------------------------
// Audio: deterministic stereo tone at a non-device-native sample rate
// ---------------------------------------------------------------------------

static void render_audio_frame(void) {
    /* Bresenham-style fractional pacing: TEST_CORE_SAMPLE_RATE / TEST_CORE_FPS
     * (367.5 for the default 22050Hz/60fps constants) is not an integer, so
     * naively truncating it every frame would silently drop half a sample's
     * worth of audio per frame -- a real, measurable long-run rate deficit
     * (367*60 = 22020Hz actual vs. the 22050Hz this core declares in
     * retro_get_system_av_info). Carrying the fractional remainder across
     * frames keeps the long-run average sample count exactly on target
     * (alternating 367/368-sample frames), matching the declared rate. */
    static double samples_emitted_total = 0.0;
    static unsigned samples_emitted_rounded = 0;

    samples_emitted_total += TEST_CORE_SAMPLE_RATE / TEST_CORE_FPS;
    unsigned target_total = (unsigned)(samples_emitted_total + 0.5);
    unsigned samples_this_frame = target_total - samples_emitted_rounded;
    samples_emitted_rounded = target_total;

    static int16_t *audio_scratch;
    static unsigned audio_scratch_capacity;
    /* Fractional pacing only ever varies the per-frame count by one sample
     * around the truncated value, so reserve a couple of samples' headroom
     * once rather than resizing every frame. */
    const unsigned scratch_capacity_needed =
        (unsigned)(TEST_CORE_SAMPLE_RATE / TEST_CORE_FPS) + 2;

    if (audio_scratch_capacity < scratch_capacity_needed) {
        free(audio_scratch);
        audio_scratch = (int16_t *)malloc((size_t)scratch_capacity_needed * 2 * sizeof(int16_t));
        audio_scratch_capacity = scratch_capacity_needed;
    }

    const double phase_step = 2.0 * M_PI * TEST_CORE_TONE_HZ / TEST_CORE_SAMPLE_RATE;
    for (unsigned i = 0; i < samples_this_frame; i++) {
        int16_t sample = (int16_t)(sin(g_state.tone_phase) * 8000.0);
        audio_scratch[i * 2 + 0] = sample; /* left */
        audio_scratch[i * 2 + 1] = sample; /* right, in phase (deterministic, easy to verify) */
        g_state.tone_phase += phase_step;
        if (g_state.tone_phase > 2.0 * M_PI) g_state.tone_phase -= 2.0 * M_PI;
    }

    if (audio_batch_cb) {
        audio_batch_cb(audio_scratch, samples_this_frame);
    }
}

// ---------------------------------------------------------------------------
// Input: move the marker, detect the shutdown-request combo
// ---------------------------------------------------------------------------

static void poll_input(void) {
    input_poll_cb();

    int16_t left = input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_LEFT);
    int16_t right = input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_RIGHT);
    int16_t up = input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_UP);
    int16_t down = input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_DOWN);
    int16_t start = input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START);
    int16_t select = input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_SELECT);

    int16_t analog_x = input_state_cb(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_X);
    int16_t analog_y = input_state_cb(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_Y);

    const int speed = 3;
    if (left) g_state.marker_x -= speed;
    if (right) g_state.marker_x += speed;
    if (up) g_state.marker_y -= speed;
    if (down) g_state.marker_y += speed;

    /* Analog stick range is roughly [-32768, 32767]; apply a deadzone. */
    const int16_t deadzone = 8000;
    if (analog_x > deadzone || analog_x < -deadzone) {
        g_state.marker_x += analog_x / 8192;
    }
    if (analog_y > deadzone || analog_y < -deadzone) {
        g_state.marker_y += analog_y / 8192;
    }

    if (g_state.marker_x < 0) g_state.marker_x = 0;
    if (g_state.marker_x >= g_width) g_state.marker_x = g_width - 1;
    if (g_state.marker_y < 0) g_state.marker_y = 0;
    if (g_state.marker_y >= g_height) g_state.marker_y = g_height - 1;

    if (start && select) {
        g_state.shutdown_hold_frames++;
        if (g_state.shutdown_hold_frames >= TEST_CORE_SHUTDOWN_HOLD_FRAMES) {
            environ_cb(RETRO_ENVIRONMENT_SHUTDOWN, NULL);
        }
    } else {
        g_state.shutdown_hold_frames = 0;
    }
}

// ---------------------------------------------------------------------------
// retro_run: one frame
// ---------------------------------------------------------------------------

void retro_run(void) {
    poll_input();

    /* Visible incrementing SRAM byte, roughly once per second. */
    if (g_state.frame_count % (unsigned)TEST_CORE_FPS == 0) {
        g_state.sram[0]++;
    }

    /* One-time geometry change partway through a long-running session, to
     * exercise RETRO_ENVIRONMENT_SET_GEOMETRY handling in the frontend. */
    if (!g_state.geometry_grown && g_state.frame_count == TEST_CORE_GEOMETRY_CHANGE_FRAME) {
        g_width = TEST_CORE_GROWN_WIDTH;
        g_height = TEST_CORE_GROWN_HEIGHT;
        g_state.geometry_grown = 1;

        struct retro_game_geometry geometry;
        memset(&geometry, 0, sizeof(geometry));
        geometry.base_width = (unsigned)g_width;
        geometry.base_height = (unsigned)g_height;
        geometry.max_width = TEST_CORE_GROWN_WIDTH;
        geometry.max_height = TEST_CORE_GROWN_HEIGHT;
        geometry.aspect_ratio = (float)g_width / (float)g_height;
        environ_cb(RETRO_ENVIRONMENT_SET_GEOMETRY, &geometry);
    }

    render_frame();
    flush_frame();
    render_audio_frame();

    g_state.frame_count++;
}

// ---------------------------------------------------------------------------
// Serialize / unserialize: deterministic, covers all mutable state
// ---------------------------------------------------------------------------

size_t retro_serialize_size(void) { return sizeof(test_core_state_t); }

bool retro_serialize(void *data, size_t size) {
    if (size < sizeof(test_core_state_t)) return false;
    memcpy(data, &g_state, sizeof(test_core_state_t));
    return true;
}

bool retro_unserialize(const void *data, size_t size) {
    if (size < sizeof(test_core_state_t)) return false;
    memcpy(&g_state, data, sizeof(test_core_state_t));
    /* geometry may have changed as part of the restored state */
    g_width = g_state.geometry_grown ? TEST_CORE_GROWN_WIDTH : TEST_CORE_BASE_WIDTH;
    g_height = g_state.geometry_grown ? TEST_CORE_GROWN_HEIGHT : TEST_CORE_BASE_HEIGHT;
    return true;
}

// ---------------------------------------------------------------------------
// Memory (SRAM)
// ---------------------------------------------------------------------------

void *retro_get_memory_data(unsigned id) {
    if (id == RETRO_MEMORY_SAVE_RAM) return g_state.sram;
    return NULL;
}

size_t retro_get_memory_size(unsigned id) {
    if (id == RETRO_MEMORY_SAVE_RAM) return TEST_CORE_SRAM_SIZE;
    return 0;
}

// ---------------------------------------------------------------------------
// Unused optional API surface — explicit no-ops, not silently unimplemented.
// ---------------------------------------------------------------------------

void retro_cheat_reset(void) {}
void retro_cheat_set(unsigned index, bool enabled, const char *code) {
    (void)index; (void)enabled; (void)code;
}

void retro_set_controller_port_device(unsigned port, unsigned device) {
    (void)port; (void)device;
}
