/* core_load_smoke.c — repeated native load / retro_api_version / init /
 * deinit smoke for a Libretro core DLL (Windows x86_64, MinGW-w64 UCRT64).
 *
 * Part of the Windows player artifact gate (.github/workflows/windows-x64.yml,
 * job native-player-win64): it proves the staged core DLL is not merely a
 * well-formed PE image with the right export table, but that the exported
 * entry points are actually callable and stable across repeated
 * LoadLibrary/FreeLibrary cycles. NO ROM content is required or loaded: the
 * core is loaded, retro_api_version() is checked, and retro_init()/
 * retro_deinit() are cycled — exactly the load/init/deinit surface the
 * player's CoreLibrary uses before any game is ever opened.
 *
 * Environment callback: both cores in this gate dereference their stored
 * environment callback during init with no NULL check (test_core negotiates
 * its pixel format via RETRO_ENVIRONMENT_SET_PIXEL_FORMAT; gambatte probes
 * SET_PERFORMANCE_LEVEL / GET_LOG_INTERFACE / core options, and even inside
 * retro_set_environment() itself). A smoke harness that calls init() without
 * ever installing a callback crashes the core with a NULL dereference. This
 * file therefore installs a safe no-op frontend before every retro_init() —
 * mirroring the player's CoreLibrary/EmulationSession ordering, which always
 * calls retro_set_environment() before retro_init():
 *   - returns false for every command (a libretro core must tolerate a
 *     frontend that accepts nothing), with one exception:
 *   - RETRO_ENVIRONMENT_GET_LOG_INTERFACE is answered with a minimal logger
 *     writing to stderr, so cores that log during init (gambatte) have a
 *     working sink instead of silently dropping diagnostics.
 * The callback is deliberately NOT cleared (retro_set_environment(NULL))
 * before FreeLibrary: gambatte's retro_set_environment() invokes the passed
 * callback unconditionally, so a NULL clear would crash it; and the trampoline
 * below is a static function of THIS executable — not of the core image — so
 * the pointer the core stores can never dangle across cycles.
 *
 * Usage:  core_load_smoke.exe <core.dll> [cycles]
 * Exit:   0 = all cycles passed, 1 = failure (diagnostic on stderr),
 *         2 = usage error.
 *
 * Deliberately plain C with only the Win32 API (no CRT++/no C++ runtime):
 * it compiles as a single translation unit with the same UCRT64 toolchain
 * that builds the player, so the smoke binary itself has no DLL closure of
 * its own beyond kernel32.
 */
#include <windows.h>

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

typedef unsigned (*retro_api_version_fn)(void);
typedef void (*retro_init_fn)(void);
typedef void (*retro_deinit_fn)(void);
typedef int (*retro_environment_fn)(unsigned cmd, void *data);
typedef int (*retro_set_environment_fn)(retro_environment_fn cb);

/* Environment command values — kept as literals (this file deliberately does
 * not include libretro.h; the smoke must stay dependency-free). Verified
 * against the vendored libretro.h at third_party/cores/gambatte. */
#define SMOKE_ENV_GET_LOG_INTERFACE 27u

enum { SMOKE_LOG_DEBUG = 0, SMOKE_LOG_INFO, SMOKE_LOG_WARN, SMOKE_LOG_ERROR };

/* Minimal log sink handed to cores that ask for RETRO_ENVIRONMENT_GET_LOG_
 * INTERFACE; prefixing the level keeps candidate-core init diagnostics
 * readable in the workflow log. */
static void smoke_log(int level, const char *fmt, ...) {
    static const char *names[] = {"DEBUG", "INFO", "WARN ", "ERROR"};
    const char *name = (level >= SMOKE_LOG_DEBUG && level <= SMOKE_LOG_ERROR)
                           ? names[level] : "LOG  ";
    va_list args;
    va_start(args, fmt);
    fprintf(stderr, "[core:%s] ", name);
    vfprintf(stderr, fmt, args);
    /* Core log lines are not guaranteed to end with a newline. */
    fputc('\n', stderr);
    va_end(args);
}

/* No-op frontend: reject every environment command except the log interface
 * (see file header). Returning false is the libretro contract for "frontend
 * does not support this"; both test_core and gambatte degrade gracefully. */
static int smoke_environment(unsigned cmd, void *data) {
    if (cmd == SMOKE_ENV_GET_LOG_INTERFACE && data != NULL) {
        struct retro_log_callback {
            void (*log)(int level, const char *fmt, ...);
        } *log = (struct retro_log_callback *)data;
        log->log = &smoke_log;
        return 1;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <core.dll> [cycles]\n", argv[0]);
        return 2;
    }
    const char *path = argv[1];
    int cycles = 50;
    if (argc > 2) {
        cycles = atoi(argv[2]);
        if (cycles < 1) {
            fprintf(stderr, "FAIL: cycles must be >= 1 (got %s)\n", argv[2]);
            return 2;
        }
    }

    for (int i = 0; i < cycles; i++) {
        HMODULE h = LoadLibraryA(path);
        if (h == NULL) {
            fprintf(stderr, "FAIL cycle %d: LoadLibrary(%s) failed (error %lu)\n",
                    i, path, GetLastError());
            return 1;
        }

        /* Resolve every required export individually and report each missing
         * symbol BY NAME: a single lumped "%p" dump makes it hard to tell
         * which entry point the build dropped (e.g. a version script or .def
         * that stopped exporting retro_set_environment). */
        retro_api_version_fn api_version =
            (retro_api_version_fn)(void *)GetProcAddress(h, "retro_api_version");
        retro_init_fn init = (retro_init_fn)(void *)GetProcAddress(h, "retro_init");
        retro_deinit_fn deinit = (retro_deinit_fn)(void *)GetProcAddress(h, "retro_deinit");
        retro_set_environment_fn set_environment =
            (retro_set_environment_fn)(void *)GetProcAddress(h, "retro_set_environment");

        const char *missing[4] = {0};
        int n_missing = 0;
        if (api_version == NULL) missing[n_missing++] = "retro_api_version";
        if (init == NULL) missing[n_missing++] = "retro_init";
        if (deinit == NULL) missing[n_missing++] = "retro_deinit";
        if (set_environment == NULL) missing[n_missing++] = "retro_set_environment";
        if (n_missing > 0) {
            fprintf(stderr,
                    "FAIL cycle %d: %s is missing required export(s):", i, path);
            for (int m = 0; m < n_missing; m++)
                fprintf(stderr, " %s", missing[m]);
            fprintf(stderr,
                    "\n  a libretro core must export every entry point the player's"
                    " CoreLibrary resolves (native/engine/src/core_library.cpp);"
                    " check that the build still links with its version script /"
                    " .def exporting all retro_* symbols.\n");
            FreeLibrary(h);
            return 1;
        }

        unsigned version = api_version();
        if (version != 1) {
            fprintf(stderr, "FAIL cycle %d: retro_api_version() = %u (expected 1)\n",
                    i, version);
            FreeLibrary(h);
            return 1;
        }

        /* CoreLibrary ordering: the environment callback is installed BEFORE
         * init — both cores in this gate dereference it during init without a
         * NULL check, so installing it here (every cycle, on the freshly
         * loaded module) is what keeps test_core and gambatte from crashing.
         * See the file header for why it is never cleared with NULL before
         * FreeLibrary. */
        set_environment(smoke_environment);

        /* The player's CoreLibrary calls init/deinit around every session.
         * Cycling them without any content is the no-ROM smoke surface: a
         * core that corrupts its own state on init/deinit fails here. */
        init();
        deinit();
        FreeLibrary(h);
    }

    printf("PASS %s: %d repeated load/retro_api_version/set_environment/init/deinit cycles\n",
           path, cycles);
    return 0;
}
