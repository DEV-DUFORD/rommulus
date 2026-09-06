#!/usr/bin/env python3
"""Isolated libretro PS1 fixture video/PCM gate; no proprietary firmware."""

import argparse
import ctypes as c
import json
import os

import pcsx_rearmed_rom as fixture


def require(condition, detail):
    if not condition:
        raise RuntimeError(detail)


def probe(core_path, workdir):
    core_path = os.path.abspath(core_path)
    os.makedirs(workdir, exist_ok=True)
    # Windows dependencies are next to the staged player, not on MSYS2 PATH.
    dll_directory = None
    if os.name == "nt":
        bin_dir = os.path.join(os.path.dirname(os.path.dirname(core_path)), "bin")
        if os.path.isdir(bin_dir):
            dll_directory = os.add_dll_directory(bin_dir)
    core = c.CDLL(core_path)
    env_type = c.CFUNCTYPE(c.c_bool, c.c_uint, c.c_void_p)
    video_type = c.CFUNCTYPE(None, c.c_void_p, c.c_uint, c.c_uint, c.c_size_t)
    audio_type = c.CFUNCTYPE(None, c.c_int16, c.c_int16)
    batch_type = c.CFUNCTYPE(c.c_size_t, c.POINTER(c.c_int16), c.c_size_t)
    poll_type = c.CFUNCTYPE(None)
    input_type = c.CFUNCTYPE(c.c_int16, c.c_uint, c.c_uint, c.c_uint, c.c_uint)

    class Variable(c.Structure):
        _fields_ = [("key", c.c_char_p), ("value", c.c_char_p)]

    class Game(c.Structure):
        _fields_ = [("path", c.c_char_p), ("data", c.c_void_p),
                    ("size", c.c_size_t), ("meta", c.c_char_p)]

    directory = os.fsencode(os.path.abspath(workdir))
    options = {b"pcsx_rearmed_bios": b"HLE",
               b"pcsx_rearmed_memcard1": b"libretro",
               b"pcsx_rearmed_memcard2": b"none"}
    stats = {"videoFrames": 0, "coloredFrames": 0, "audioFrames": 0,
             "nonzeroSamples": 0, "pixelFormat": 0}

    @env_type
    def environment(command, data):
        if command in (9, 31):  # SYSTEM_DIRECTORY / SAVE_DIRECTORY
            c.cast(data, c.POINTER(c.c_char_p))[0] = directory
            return True
        if command == 10:
            stats["pixelFormat"] = c.cast(data, c.POINTER(c.c_int))[0]
            return stats["pixelFormat"] in (0, 1, 2)
        if command == 15:
            variable = c.cast(data, c.POINTER(Variable)).contents
            variable.value = options.get(variable.key)
            return variable.value is not None
        if command == 17:
            c.cast(data, c.POINTER(c.c_bool))[0] = False
            return True
        if command == 3:  # GET_CAN_DUPE
            c.cast(data, c.POINTER(c.c_bool))[0] = True
            return True
        return False

    @video_type
    def video(data, width, height, pitch):
        stats["videoFrames"] += 1
        if data and width and height:
            # The original fill is RGB(A0,60,30), represented as RGB565.
            expected = {0: 0x5186, 1: 0xA06030, 2: 0xA306}[stats["pixelFormat"]]
            pixel_type = c.c_uint32 if stats["pixelFormat"] == 1 else c.c_uint16
            row = c.cast(data, c.POINTER(pixel_type))
            if sum(row[x] == expected for x in range(width)) >= width * 3 // 4:
                stats["coloredFrames"] += 1

    @audio_type
    def audio(left, right):
        stats["audioFrames"] += 1
        stats["nonzeroSamples"] += int(left != 0) + int(right != 0)

    @batch_type
    def batch(data, frames):
        stats["audioFrames"] += frames
        stats["nonzeroSamples"] += sum(data[i] != 0 for i in range(frames * 2))
        return frames

    poll = poll_type(lambda: None)
    input_state = input_type(lambda port, device, index, ident: 0)
    for name, callback, callback_type in (
        ("environment", environment, env_type), ("video_refresh", video, video_type),
        ("audio_sample", audio, audio_type), ("audio_sample_batch", batch, batch_type),
        ("input_poll", poll, poll_type), ("input_state", input_state, input_type),
    ):
        function = getattr(core, "retro_set_" + name)
        function.argtypes = [callback_type]
        function(callback)
    core.retro_load_game.argtypes = [c.POINTER(Game)]
    core.retro_load_game.restype = c.c_bool
    core.retro_get_memory_data.argtypes = [c.c_uint]
    core.retro_get_memory_data.restype = c.c_void_p
    core.retro_get_memory_size.argtypes = [c.c_uint]
    core.retro_get_memory_size.restype = c.c_size_t
    path = os.path.join(workdir, fixture.ROM_NAME)
    with open(path, "wb") as f:
        f.write(fixture.generate_rom())
    game = Game(os.fsencode(os.path.abspath(path)), None, 0, None)
    core.retro_init()
    loaded = False
    try:
        loaded = core.retro_load_game(c.byref(game))
        require(loaded, "PS-X EXE rejected")
        size = core.retro_get_memory_size(0)
        memory = core.retro_get_memory_data(0)
        require(size == fixture.CARD_SIZE and memory, "128 KiB card not exposed")
        # Default formatted and zero-filled cards must both remain untouched.
        initial = c.string_at(memory, size)
        for _ in range(10):
            core.retro_run()
        require(c.string_at(memory, size) == initial, "fixture mutated default card")
        c.memset(memory, 0, size)
        for _ in range(10):
            core.retro_run()
        require(c.string_at(memory, size) == bytes(size), "fixture mutated unrestored card")
        c.memmove(memory, fixture.blank_card(), size)
        for _ in range(180):
            core.retro_run()
        require(c.string_at(memory, size) == fixture.expected_card(1), "card oracle differs")
        require(stats["videoFrames"] >= 180, stats)
        require(stats["coloredFrames"] > 0, stats)
        require(stats["audioFrames"] >= 44100, stats)
        require(stats["nonzeroSamples"] > 100, stats)
        return stats
    finally:
        if loaded:
            core.retro_unload_game()
        core.retro_deinit()
        if dll_directory:
            dll_directory.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", required=True)
    parser.add_argument("--workdir", required=True)
    args = parser.parse_args()
    print(json.dumps(probe(args.core, args.workdir), sort_keys=True))
