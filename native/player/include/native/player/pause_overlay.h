// pause_overlay.h — SDL3 software rendering for the pause overlay.
//
// Draws a dimmed backdrop, a centered panel with the four PauseMenu items
// (Resume / Video Options / Controller Settings / Quit), the Video Options
// and Controller Settings submenus, the editable Physical Controller
// Settings binding list (12 RetroPad slots + Reset to Default, live from
// SdlInput's BindingTable) plus its capture dialog, and the "Quit game?"
// Yes/No dialog on top when the confirm state is active. Pure rectangles
// plus an embedded 5x7 bitmap font — deliberately NOT a Compose-styling
// replication; pause_menu.h owns the semantics, this class only draws them.
// All drawing happens in the renderer's logical presentation coordinates
// (the core's aspect space), so the overlay scales with the letterboxed
// frame and stays aligned to it.
#pragma once

#include "native/player/binding_table.h"

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
              int captureSecondsLeft) const;

private:
    // Draws `text` with its top-left at (x, y); each font pixel is a
    // scale x scale rectangle. Unknown glyphs render as blanks.
    void drawText(SDL_Renderer* renderer, float x, float y, const char* text,
                  float scale, unsigned r, unsigned g, unsigned b,
                  unsigned a) const;
};

}  // namespace romm::player
