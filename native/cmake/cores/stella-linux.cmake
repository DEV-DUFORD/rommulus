# ---------------------------------------------------------------------------
# Stella (Atari 2600): vendored under third_party/cores/stella/, pinned to the
# upstream release tag `7.0`, commit d55b1ae
# (https://github.com/stella-emu/stella/tree/d55b1ae). See
# third_party/cores/stella/VENDORING.md for exactly what was vendored, why,
# and what was deliberately excluded (desktop GUIs, tools, docs, tests).
#
# Source list mirrors upstream's own libretro Android build
# (src/os/libretro/jni/Android.mk + src/os/libretro/Makefile.common) exactly:
# pure C++ (SOURCES_C is empty), -std=c++20, upstream defines. nanojpeg.c
# is compiled inline via #include from a header wrapper (nanojpeg_lib.hxx).
# ---------------------------------------------------------------------------

add_library(stella_core SHARED
    ${STELLA_DIR}/os/libretro/libretro.cxx
    ${STELLA_DIR}/os/libretro/FSNodeLIBRETRO.cxx
    ${STELLA_DIR}/os/libretro/StellaLIBRETRO.cxx
    ${STELLA_DIR}/common/AudioQueue.cxx
    ${STELLA_DIR}/common/AudioSettings.cxx
    ${STELLA_DIR}/common/Base.cxx
    ${STELLA_DIR}/common/Bezel.cxx
    ${STELLA_DIR}/common/DevSettingsHandler.cxx
    ${STELLA_DIR}/common/FpsMeter.cxx
    ${STELLA_DIR}/common/FSNodeZIP.cxx
    ${STELLA_DIR}/common/JoyMap.cxx
    ${STELLA_DIR}/common/KeyMap.cxx
    ${STELLA_DIR}/common/Logger.cxx
    ${STELLA_DIR}/common/MouseControl.cxx
    ${STELLA_DIR}/common/PaletteHandler.cxx
    ${STELLA_DIR}/common/PhosphorHandler.cxx
    ${STELLA_DIR}/common/PhysicalJoystick.cxx
    ${STELLA_DIR}/common/PJoystickHandler.cxx
    ${STELLA_DIR}/common/PKeyboardHandler.cxx
    ${STELLA_DIR}/common/RewindManager.cxx
    ${STELLA_DIR}/common/StaggeredLogger.cxx
    ${STELLA_DIR}/common/StateManager.cxx
    ${STELLA_DIR}/common/TimerManager.cxx
    ${STELLA_DIR}/common/VideoModeHandler.cxx
    ${STELLA_DIR}/common/tv_filters/AtariNTSC.cxx
    ${STELLA_DIR}/common/tv_filters/NTSCFilter.cxx
    ${STELLA_DIR}/common/repository/CompositeKeyValueRepository.cxx
    ${STELLA_DIR}/common/repository/CompositeKVRJsonAdapter.cxx
    ${STELLA_DIR}/common/repository/KeyValueRepositoryConfigfile.cxx
    ${STELLA_DIR}/common/repository/KeyValueRepositoryJsonFile.cxx
    ${STELLA_DIR}/common/repository/KeyValueRepositoryPropertyFile.cxx
    ${STELLA_DIR}/emucore/AtariVox.cxx
    ${STELLA_DIR}/emucore/Bankswitch.cxx
    ${STELLA_DIR}/emucore/Booster.cxx
    ${STELLA_DIR}/emucore/Cart.cxx
    ${STELLA_DIR}/emucore/CartCreator.cxx
    ${STELLA_DIR}/emucore/CartDetector.cxx
    ${STELLA_DIR}/emucore/CartEnhanced.cxx
    ${STELLA_DIR}/emucore/Cart03E0.cxx
    ${STELLA_DIR}/emucore/Cart0840.cxx
    ${STELLA_DIR}/emucore/Cart0FA0.cxx
    ${STELLA_DIR}/emucore/Cart2K.cxx
    ${STELLA_DIR}/emucore/Cart3E.cxx
    ${STELLA_DIR}/emucore/Cart3EPlus.cxx
    ${STELLA_DIR}/emucore/Cart3EX.cxx
    ${STELLA_DIR}/emucore/Cart3F.cxx
    ${STELLA_DIR}/emucore/Cart4A50.cxx
    ${STELLA_DIR}/emucore/Cart4K.cxx
    ${STELLA_DIR}/emucore/Cart4KSC.cxx
    ${STELLA_DIR}/emucore/CartAR.cxx
    ${STELLA_DIR}/emucore/CartARM.cxx
    ${STELLA_DIR}/emucore/CartBF.cxx
    ${STELLA_DIR}/emucore/CartBFSC.cxx
    ${STELLA_DIR}/emucore/CartBUS.cxx
    ${STELLA_DIR}/emucore/CartCDF.cxx
    ${STELLA_DIR}/emucore/CartCM.cxx
    ${STELLA_DIR}/emucore/CartCTY.cxx
    ${STELLA_DIR}/emucore/CartCV.cxx
    ${STELLA_DIR}/emucore/CartDF.cxx
    ${STELLA_DIR}/emucore/CartDFSC.cxx
    ${STELLA_DIR}/emucore/CartDPC.cxx
    ${STELLA_DIR}/emucore/CartDPCPlus.cxx
    ${STELLA_DIR}/emucore/CartE0.cxx
    ${STELLA_DIR}/emucore/CartE7.cxx
    ${STELLA_DIR}/emucore/CartEF.cxx
    ${STELLA_DIR}/emucore/CartEFSC.cxx
    ${STELLA_DIR}/emucore/CartELF.cxx
    ${STELLA_DIR}/emucore/CartF0.cxx
    ${STELLA_DIR}/emucore/CartF4.cxx
    ${STELLA_DIR}/emucore/CartF4SC.cxx
    ${STELLA_DIR}/emucore/CartF6.cxx
    ${STELLA_DIR}/emucore/CartF6SC.cxx
    ${STELLA_DIR}/emucore/CartF8.cxx
    ${STELLA_DIR}/emucore/CartF8SC.cxx
    ${STELLA_DIR}/emucore/CartFA2.cxx
    ${STELLA_DIR}/emucore/CartFA.cxx
    ${STELLA_DIR}/emucore/CartFC.cxx
    ${STELLA_DIR}/emucore/CartFE.cxx
    ${STELLA_DIR}/emucore/CartGL.cxx
    ${STELLA_DIR}/emucore/CartJANE.cxx
    ${STELLA_DIR}/emucore/CartMDM.cxx
    ${STELLA_DIR}/emucore/CartMVC.cxx
    ${STELLA_DIR}/emucore/CartSB.cxx
    ${STELLA_DIR}/emucore/CartTVBoy.cxx
    ${STELLA_DIR}/emucore/CartUA.cxx
    ${STELLA_DIR}/emucore/CartWD.cxx
    ${STELLA_DIR}/emucore/CartWF8.cxx
    ${STELLA_DIR}/emucore/CartX07.cxx
    ${STELLA_DIR}/emucore/CompuMate.cxx
    ${STELLA_DIR}/emucore/Console.cxx
    ${STELLA_DIR}/emucore/Control.cxx
    ${STELLA_DIR}/emucore/ControllerDetector.cxx
    ${STELLA_DIR}/emucore/CortexM0.cxx
    ${STELLA_DIR}/emucore/DispatchResult.cxx
    ${STELLA_DIR}/emucore/Driving.cxx
    ${STELLA_DIR}/emucore/EmulationTiming.cxx
    ${STELLA_DIR}/emucore/EmulationWorker.cxx
    ${STELLA_DIR}/emucore/EventHandler.cxx
    ${STELLA_DIR}/emucore/FBSurface.cxx
    ${STELLA_DIR}/emucore/FrameBuffer.cxx
    ${STELLA_DIR}/emucore/FSNode.cxx
    ${STELLA_DIR}/emucore/Genesis.cxx
    ${STELLA_DIR}/emucore/GlobalKeyHandler.cxx
    ${STELLA_DIR}/emucore/Joy2BPlus.cxx
    ${STELLA_DIR}/emucore/Joystick.cxx
    ${STELLA_DIR}/emucore/Keyboard.cxx
    ${STELLA_DIR}/emucore/KidVid.cxx
    ${STELLA_DIR}/emucore/Lightgun.cxx
    ${STELLA_DIR}/emucore/M6502.cxx
    ${STELLA_DIR}/emucore/M6532.cxx
    ${STELLA_DIR}/emucore/MD5.cxx
    ${STELLA_DIR}/emucore/MindLink.cxx
    ${STELLA_DIR}/emucore/MT24LC256.cxx
    ${STELLA_DIR}/emucore/OSystem.cxx
    ${STELLA_DIR}/emucore/Paddles.cxx
    ${STELLA_DIR}/emucore/PlusROM.cxx
    ${STELLA_DIR}/emucore/PointingDevice.cxx
    ${STELLA_DIR}/emucore/Props.cxx
    ${STELLA_DIR}/emucore/PropsSet.cxx
    ${STELLA_DIR}/emucore/QuadTari.cxx
    ${STELLA_DIR}/emucore/SaveKey.cxx
    ${STELLA_DIR}/emucore/Serializer.cxx
    ${STELLA_DIR}/emucore/Settings.cxx
    ${STELLA_DIR}/emucore/Switches.cxx
    ${STELLA_DIR}/emucore/System.cxx
    ${STELLA_DIR}/emucore/Thumbulator.cxx
    ${STELLA_DIR}/emucore/elf/BusTransactionQueue.cxx
    ${STELLA_DIR}/emucore/elf/ElfEnvironment.cxx
    ${STELLA_DIR}/emucore/elf/ElfLinker.cxx
    ${STELLA_DIR}/emucore/elf/ElfParser.cxx
    ${STELLA_DIR}/emucore/elf/ElfUtil.cxx
    ${STELLA_DIR}/emucore/elf/VcsLib.cxx
    ${STELLA_DIR}/emucore/tia/AudioChannel.cxx
    ${STELLA_DIR}/emucore/tia/Audio.cxx
    ${STELLA_DIR}/emucore/tia/Background.cxx
    ${STELLA_DIR}/emucore/tia/Ball.cxx
    ${STELLA_DIR}/emucore/tia/DrawCounterDecodes.cxx
    ${STELLA_DIR}/emucore/tia/frame-manager/AbstractFrameManager.cxx
    ${STELLA_DIR}/emucore/tia/frame-manager/FrameLayoutDetector.cxx
    ${STELLA_DIR}/emucore/tia/frame-manager/FrameManager.cxx
    ${STELLA_DIR}/emucore/tia/frame-manager/JitterEmulation.cxx
    ${STELLA_DIR}/emucore/tia/LatchedInput.cxx
    ${STELLA_DIR}/emucore/tia/Missile.cxx
    ${STELLA_DIR}/emucore/tia/AnalogReadout.cxx
    ${STELLA_DIR}/emucore/tia/Player.cxx
    ${STELLA_DIR}/emucore/tia/Playfield.cxx
    ${STELLA_DIR}/emucore/TIASurface.cxx
    ${STELLA_DIR}/emucore/tia/TIA.cxx
)

# Upstream's own libretro/jni/Android.mk builds with -std=c++20 (tag 7.0);
# master HEAD uses -std=c++23 but tag 7.0 is preferred for provenance.
set_target_properties(stella_core PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(stella_core SYSTEM PRIVATE
    ${STELLA_DIR}/os/libretro
    ${STELLA_DIR}
    ${STELLA_DIR}/emucore
    ${STELLA_DIR}/emucore/elf
    ${STELLA_DIR}/emucore/tia
    ${STELLA_DIR}/common
    ${STELLA_DIR}/common/audio
    ${STELLA_DIR}/common/tv_filters
    ${STELLA_DIR}/common/sdl_blitter
    ${STELLA_DIR}/common/repository/sqlite
    ${STELLA_DIR}/lib/json
    ${STELLA_DIR}/lib/nanojpeg
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(stella_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    __LIB_RETRO__
    HAVE_STRINGS_H
    SOUND_SUPPORT
    GIT_VERSION=\"d55b1ae\"
)

# Upstream's own libretro/jni/Application.mk sets APP_CPPFLAGS := -fexceptions;
# match that exactly. Vendored third-party source: not held to this project's
# own -Wall -Wextra (matches all prior core targets).
target_compile_options(stella_core PRIVATE -fexceptions)

# Linked with upstream's own version script so only the standard retro_*
# Libretro ABI is exported — never Stella's internal symbols.
# GNU-only linker flags (Linux per-core gate); Apple ld does not support them.
if(NOT APPLE)
target_link_options(stella_core PRIVATE
    "-Wl,--version-script=${STELLA_DIR}/link.T"
    "-Wl,--no-undefined"
)
endif()

target_link_libraries(stella_core
    m
)
