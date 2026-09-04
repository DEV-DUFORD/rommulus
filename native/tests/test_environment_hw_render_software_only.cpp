// test_environment_hw_render_software_only.cpp — EnvironmentHandler fail-
// closed semantics WITH the ROMM_WIN32_SOFTWARE_ONLY compile definition (the
// temporary pre-ANGLE Windows boundary): every RETRO_ENVIRONMENT_SET_HW_RENDER
// request is rejected for every hosted OpenGL context type,
// GET_HW_RENDER_INTERFACE never returns an interface even when a provider is
// installed, SET_HW_SHARED_CONTEXT reports no active hardware rendering, and
// ordinary software commands keep working. Compiled with
// -DROMM_WIN32_SOFTWARE_ONLY=1 by native/tests/CMakeLists.txt on every host:
// the compile definition, not the platform, is the unit under test.
#include "romm_test.h"

#include "environment.h"

#ifndef ROMM_WIN32_SOFTWARE_ONLY
#error "this test must be compiled with -DROMM_WIN32_SOFTWARE_ONLY=1"
#endif

int main() {
    using romm::EnvironmentHandler;

    // SET_HW_RENDER is rejected for every hosted OpenGL context type...
    for (unsigned contextType : {romm::kHwContextOpenGl, romm::kHwContextEs2,
                                 romm::kHwContextOpenGlCore, romm::kHwContextEs3,
                                 romm::kHwContextEsVersion}) {
        EnvironmentHandler handler;
        struct retro_hw_render_callback cb{};
        cb.context_type = static_cast<retro_hw_context_type>(contextType);
        CHECK(!handler.handle(RETRO_ENVIRONMENT_SET_HW_RENDER, &cb));
        CHECK(!handler.isHardwareRendering());
    }

    // ...and for unsupported types and null callbacks too.
    {
        EnvironmentHandler handler;
        struct retro_hw_render_callback cb{};
        cb.context_type = static_cast<retro_hw_context_type>(99);
        CHECK(!handler.handle(RETRO_ENVIRONMENT_SET_HW_RENDER, &cb));
        CHECK(!handler.handle(RETRO_ENVIRONMENT_SET_HW_RENDER, nullptr));
        CHECK(!handler.isHardwareRendering());
    }

    // GET_HW_RENDER_INTERFACE never returns an interface — even with a
    // provider installed, the software-only build must not hand out a
    // render context.
    {
        EnvironmentHandler handler;
        struct retro_hw_render_interface* iface = nullptr;
        CHECK(!handler.handle(RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE, &iface));
        CHECK(iface == nullptr);

        handler.setRenderContextProvider([]() { return reinterpret_cast<void*>(0x1); });
        iface = reinterpret_cast<struct retro_hw_render_interface*>(0x1);
        CHECK(!handler.handle(RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE, &iface));
    }

    // SET_HW_SHARED_CONTEXT reports no active hardware rendering.
    {
        EnvironmentHandler handler;
        CHECK(!handler.handle(RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT, nullptr));
    }

    // Ordinary software commands are unaffected: pixel-format negotiation and
    // directory queries keep working (test_core's software path).
    {
        EnvironmentHandler handler;
        enum retro_pixel_format fmt = RETRO_PIXEL_FORMAT_XRGB8888;
        CHECK(handler.handle(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &fmt));
        CHECK_EQ(handler.pixelFormat(), fmt);

        handler.setSystemDirectory("/tmp");
        const char* dir = nullptr;
        CHECK(handler.handle(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY, &dir));
        CHECK(dir != nullptr);
        CHECK(std::string(dir) == "/tmp");
    }

    return rommtest::finish("test_environment_hw_render_software_only");
}
