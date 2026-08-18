// VideoSink.h — platform-neutral software video output seam for the
// RomMulus native engine.
//
// LINUX_X64.md sections 11.2/11.6/11.7, Phase 7 Wave 4: the engine tree
// (native/engine/) must never include platform headers (no Android window
// headers, no desktop toolkit headers). Platform code registers a VideoSink
// implementation at startup; the emulation session submits the core's
// software framebuffer exclusively through romm::video::sink().
//
// The default sink (used when no platform sink is registered) is a no-op:
// frames are discarded, so a session without a registered video platform
// simply renders nothing without an error log.
#pragma once

#include "libretro.h"

#include <cstddef>
#include <memory>

namespace romm::video {

// Opaque handle to a platform-native window surface. The engine never
// dereferences it — it only forwards it to the registered sink, which
// knows the platform type behind it (on Android, the platform window
// object acquired from the activity's surface at the JNI boundary).
using NativeWindowHandle = void*;

// A destination for the core's software-rendered frames.
//
// Thread-safety contract (mirrored by the Android implementation):
//   - attachWindow()/detachWindow() are called from the UI thread whenever
//     the platform surface becomes available/is destroyed.
//   - submitFrame() is called only from the emulation thread, inside the
//     session's video refresh trampoline.
//   - Implementations must serialize these calls so the UI thread can never
//     release a window the emulation thread is mid-blit on, and the
//     emulation thread never writes into a window that has already been
//     released.
class VideoSink {
public:
    virtual ~VideoSink() = default;

    // Takes ownership of a window reference already acquired by the caller.
    // Releases any previously attached window first. Pass nullptr to just
    // detach.
    virtual void attachWindow(NativeWindowHandle window) = 0;

    // Releases the currently attached window, if any. Safe to call when
    // nothing is attached.
    virtual void detachWindow() = 0;

    // Updates the core's intended display aspect ratio. A value <= 0 means
    // the sink should derive the ratio from the submitted frame geometry.
    // Sinks that delegate aspect correction to their platform may ignore it.
    virtual void setDisplayAspectRatio(double /*aspectRatio*/) {}

    // Converts and presents one frame, honoring width/height/pitch and the
    // negotiated pixel format. A null `data` means "duplicate the last
    // frame" (RETRO_ENVIRONMENT_GET_CAN_DUPE) — a deliberate no-op, not an
    // error, and it must not clear the screen. Implementations must never
    // block the emulation thread for an unbounded time: a transient blit
    // failure just drops the frame.
    virtual void submitFrame(const void* data, unsigned width, unsigned height, size_t pitch,
                             enum retro_pixel_format format) = 0;
};

// Replaces the active sink (takes ownership); pass nullptr to fall back to
// the default no-op sink. Must complete before the first session starts —
// on Android the platform sink is installed by a static initializer at
// library load time, before JNI_OnLoad.
void setSink(std::unique_ptr<VideoSink> sink);

// The active sink, or the shared default no-op sink when none is
// registered. Never returns a null reference.
VideoSink& sink();

}  // namespace romm::video
