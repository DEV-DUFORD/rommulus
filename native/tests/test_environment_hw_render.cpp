// test_environment_hw_render.cpp — baseline EnvironmentHandler hardware-
// rendering semantics WITHOUT the ROMM_WIN32_SOFTWARE_ONLY compile
// definition (the normal, hardware-capable build): SET_HW_RENDER is accepted
// for the hosted OpenGL family and wires the host framebuffer/proc-address
// callbacks, GET_PREFERRED_HW_RENDER reports the ES3 preference, and
// GET_HW_RENDER_INTERFACE stays false until a render context exists. The
// software-only counterpart (test_environment_hw_render_software_only) pins
// the fail-closed refusal under the compile definition.
#include "romm_test.h"

#include "environment.h"

int main() {
    using romm::EnvironmentHandler;

    // GET_PREFERRED_HW_RENDER reports the ES3 preference before any
    // negotiation.
    {
        EnvironmentHandler handler;
        unsigned preferred = 0;
        CHECK(handler.handle(RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER, &preferred));
        CHECK_EQ(preferred, romm::kHwContextEs3);
    }

    // SET_HW_RENDER is accepted for the hosted OpenGL family and populates
    // the core-owned callback with the host implementations.
    for (unsigned contextType : {romm::kHwContextOpenGl, romm::kHwContextEs2,
                                 romm::kHwContextOpenGlCore, romm::kHwContextEs3,
                                 romm::kHwContextEsVersion}) {
        EnvironmentHandler handler;
        struct retro_hw_render_callback cb{};
        cb.context_type = static_cast<retro_hw_context_type>(contextType);
        CHECK(handler.handle(RETRO_ENVIRONMENT_SET_HW_RENDER, &cb));
        CHECK(handler.isHardwareRendering());
        CHECK(cb.get_current_framebuffer != nullptr);
        CHECK(cb.get_proc_address != nullptr);
    }

    // SET_HW_RENDER with a null callback is rejected.
    {
        EnvironmentHandler handler;
        CHECK(!handler.handle(RETRO_ENVIRONMENT_SET_HW_RENDER, nullptr));
        CHECK(!handler.isHardwareRendering());
    }

    // GET_HW_RENDER_INTERFACE returns false while no render context provider
    // is installed (and leaves the out-pointer untouched).
    {
        EnvironmentHandler handler;
        struct retro_hw_render_callback cb{};
        cb.context_type = static_cast<retro_hw_context_type>(romm::kHwContextEs3);
        CHECK(handler.handle(RETRO_ENVIRONMENT_SET_HW_RENDER, &cb));

        struct retro_hw_render_interface* iface = reinterpret_cast<struct retro_hw_render_interface*>(0x1);
        CHECK(!handler.handle(RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE, &iface));
    }

    // Ordinary software commands are unaffected.
    {
        EnvironmentHandler handler;
        enum retro_pixel_format fmt = RETRO_PIXEL_FORMAT_XRGB8888;
        CHECK(handler.handle(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &fmt));
        CHECK_EQ(handler.pixelFormat(), fmt);
    }

    return rommtest::finish("test_environment_hw_render");
}
