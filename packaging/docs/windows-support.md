# RomMulus for Windows x64

Download the Windows ZIP or EXE installer from the **Windows x64 CI** workflow
on the `zd/windows-build` branch. These development artifacts are unsigned.
Windows may display a SmartScreen warning; verify the download's SHA-256
against `SHA256SUMS.txt`.

For the portable version, extract the **entire ZIP** and launch
`RomMulus/RomMulus.exe`. Do not move the EXE out of its directory. Java, the
native player, core DLLs, and graphics libraries are bundled; no separate
Java or emulator installation is needed.

The Windows build includes Game Boy/Color, NES, SNES, GBA, Atari 2600/7800,
Atari Lynx, WonderSwan/Color, Neo Geo Pocket/Color, PC Engine, Genesis-family
systems, PlayStation, and Nintendo 64. GameCube and PlayStation 2 are not
included. Firmware-dependent systems still require your own BIOS files,
configured through the app; no games or BIOS images are bundled.

The app and player select GPU-backed rendering when available. N64 uses
OpenGL ES through bundled ANGLE/Direct3D; software-rendered emulation cores
still emulate their consoles on the CPU and use the GPU for presentation.
Keep the Windows GPU driver current. Hardware availability and performance
are recorded in the player's session log; hosted CI's software graphics
adapter does not establish performance on a physical GPU.

For graphics diagnostics, `ROMM_ANGLE_DEVICE=hardware` disables the explicit
WARP retry, and `ROMM_ANGLE_DEVICE=warp` selects CPU rendering. The default
`auto` tries hardware first. These are optional environment variables, not
requirements for normal gameplay.

Sign in to your RomM server through the normal onboarding screen. Settings
and credentials remain in your Windows profile, not the installation.
Logs and session diagnostics are under
`%LOCALAPPDATA%\RomMulus\state`.
Before reporting a launch problem, include its `player.log`, the Windows
version, GPU/driver, core, and game format, but remove private server details.

This branch is experimental. Real-game compatibility, performance,
controllers, audio, save synchronization, and sleep/resume still need to be
exercised on physical Windows hardware before merging or releasing.
