// pause_overlay.h — SDL3 rendering for the desktop pause overlay.
//
// Mirrors the Android Compose menu's opaque backdrop, Material surfaces,
// typography, pill buttons, focus rings, toggle rows, and dialogs. The video
// sink invokes it in output-pixel coordinates so UI is never upscaled from a
// low-resolution core canvas. Menu behavior remains owned by PauseMenu.
#pragma once

#include "native/player/binding_table.h"
#include "native/player/keyboard_binding_table.h"

struct SDL_Renderer;

namespace romm::player {

class PauseMenu;

class PauseOverlay {
public:
    // Draws the current menu state onto `renderer`. No-op when the menu is
    // closed or no drawable coordinate space can be determined (e.g. before
    // the renderer has any presentation geometry). `bindings` supplies the
    // live per-slot binding labels for the Physical Controller Settings
    // list; `captureSecondsLeft` is the remaining capture timeout while the
    // menu is in its binding-capture state (-1 otherwise).
    void draw(SDL_Renderer* renderer, const PauseMenu& menu, const BindingTable& bindings,
              const BindingTable& secondaryBindings,
              const KeyboardBindingTable& keyboardBindings,
              int captureSecondsLeft,
              const char* coreId) const;

    // Public only so the translation unit's shared Material-control helpers
    // can use the same cached typeface renderer.
    // Draws anti-aliased TrueType text with its top-left at (x, y).
    void drawText(SDL_Renderer* renderer, float x, float y, const char* text,
                  float size, unsigned r, unsigned g, unsigned b,
                  unsigned a) const;
};

}  // namespace romm::player
