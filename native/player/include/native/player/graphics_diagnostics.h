#pragma once

#include <string_view>

namespace romm::player {

// Renderer strings identify known CPU implementations, not a guarantee that
// every unrecognized adapter is a physical GPU. Always log the full identity.
inline bool isKnownSoftwareGlRenderer(std::string_view renderer) {
    return renderer.find("Microsoft Basic Render Driver") != std::string_view::npos ||
           renderer.find("Software Adapter") != std::string_view::npos ||
           renderer.find("SwiftShader") != std::string_view::npos ||
           renderer.find("llvmpipe") != std::string_view::npos ||
           renderer.find("softpipe") != std::string_view::npos;
}

}  // namespace romm::player
