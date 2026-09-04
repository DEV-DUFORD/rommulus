// atomic_file_store.h — write-temp, fsync, rename durable file writes.
//
// LIBRETRO_REFACTOR.md section 11.1: "Write a temporary file, hash, fsync,
// atomically rename, then update metadata. Do not perform disk I/O on a core
// or audio callback." This is the native-side primitive that guarantee; the
// no-disk-IO-on-callback rule is enforced by callers (checkpoints happen
// from the emulation thread's own control flow between retro_run() calls,
// not from inside a libretro callback itself).
//
// Platform contract (Phase 2 step 1, plans/WINDOWS_IMPL.md section 5.1):
// this header is the stable, platform-neutral API. The implementation is
// selected at CMake configure time per build site — POSIX hosts compile
// native/platform/posix/src/posix_atomic_file_store.cpp; a Win32
// implementation will be added alongside it in a later Phase 2 step. The
// engine tree itself carries no file-system code.
//
// Phase 2 scope: used only for the synthetic core's SRAM region. The full
// server-key/user-key/rom-hash directory layout from section 11.1 is a
// later phase; Phase 2 just needs the atomic write/read primitive proven
// end-to-end on the physical device.
#pragma once

#include <string>
#include <vector>
#include <cstdint>

namespace romm {

// Atomically writes `size` bytes from `data` to `path`:
// open a `<path>.tmp` file, write, fsync, close, then rename over `path`.
// Returns false (leaving any existing `path` untouched) on any failure.
bool atomicWriteFile(const std::string& path, const void* data, size_t size);

// Reads exactly `size` bytes from `path` into `data`. Returns false if the
// file doesn't exist, can't be read, or is not exactly `size` bytes — a
// size mismatch is treated as an incompatible/foreign file, not silently
// truncated or zero-padded.
bool readFileExact(const std::string& path, void* data, size_t size);

// Reads the entirety of `path` into `out`, replacing its previous contents.
// Returns false (leaving `out` unspecified) if the file doesn't exist or
// can't be fully read. Used to load real ROM content into memory once
// before handing it to a core's retro_load_game() (LIBRETRO_REFACTOR.md
// section 6, step 9) — this never touches the network, only an already
// validated, app-private path a caller resolved beforehand (section 10's
// download/cache pipeline runs entirely in the main process, never here).
bool readWholeFile(const std::string& path, std::vector<uint8_t>& out);

}  // namespace romm
