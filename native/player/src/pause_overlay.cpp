// pause_overlay.cpp — SDL3 software rendering for the pause overlay.
//
// See pause_overlay.h for the contract. Deliberately minimal: rectangles
// plus an embedded 5x7 bitmap font (no SDL_ttf dependency). Everything is
// drawn in the renderer's logical presentation coordinates so the overlay
// scales and letterboxes together with the game frame.
#include "native/player/pause_overlay.h"

#include <SDL3/SDL.h>

#include "native/player/pause_menu.h"

#include <algorithm>
#include <cstddef>

namespace romm::player {
namespace {

struct Glyph5x7 {
    char c;           // the character this glyph stands for (documentation + validation)
    uint8_t rows[7];  // top to bottom; bit 4 = leftmost pixel
};

// Classic 5x7 bitmap font. Only the glyphs the pause overlay can show are
// defined; undefined entries render as blanks. Indexed by char - 32 for
// printable ASCII (32..126).
const Glyph5x7 kFont[95] = {
    {' ', {0, 0, 0, 0, 0, 0, 0}},
    {'!', {4, 4, 4, 4, 4, 0, 4}},
    {'"', {0, 0, 0, 0, 0, 0, 0}},
    {'#', {0, 0, 0, 0, 0, 0, 0}},
    {'$', {0, 0, 0, 0, 0, 0, 0}},
    {'%', {0, 0, 0, 0, 0, 0, 0}},
    {'&', {0, 0, 0, 0, 0, 0, 0}},
    {'\'', {4, 4, 2, 0, 0, 0, 0}},
    {'(', {2, 4, 4, 4, 4, 4, 2}},
    {')', {8, 4, 4, 4, 4, 4, 8}},
    {'*', {0, 0, 0, 0, 0, 0, 0}},
    {'+', {0, 0, 4, 14, 4, 0, 0}},
    {',', {0, 0, 0, 0, 0, 4, 4}},
    {'-', {0, 0, 0, 14, 0, 0, 0}},
    {'.', {0, 0, 0, 0, 0, 0, 4}},
    {'/', {1, 1, 2, 4, 8, 16, 16}},
    {'0', {14, 17, 19, 21, 25, 17, 14}},
    {'1', {4, 12, 4, 4, 4, 4, 14}},
    {'2', {14, 17, 1, 2, 4, 8, 31}},
    {'3', {14, 17, 1, 6, 1, 17, 14}},
    {'4', {2, 6, 10, 18, 31, 2, 2}},
    {'5', {31, 16, 30, 1, 1, 17, 14}},
    {'6', {6, 8, 16, 30, 17, 17, 14}},
    {'7', {31, 1, 2, 4, 8, 8, 8}},
    {'8', {14, 17, 17, 14, 17, 17, 14}},
    {'9', {14, 17, 17, 15, 1, 2, 12}},
    {':', {0, 4, 4, 0, 4, 4, 0}},
    {';', {0, 4, 4, 0, 4, 4, 4}},
    {'<', {0, 0, 1, 2, 4, 2, 1}},
    {'=', {0, 0, 30, 0, 30, 0, 0}},
    {'>', {0, 0, 16, 8, 4, 8, 16}},
    {'?', {14, 17, 1, 2, 4, 0, 4}},
    {'@', {0, 0, 0, 0, 0, 0, 0}},
    {'A', {14, 17, 17, 31, 17, 17, 17}},
    {'B', {30, 17, 17, 30, 17, 17, 30}},
    {'C', {14, 17, 16, 16, 16, 17, 14}},
    {'D', {30, 17, 17, 17, 17, 17, 30}},
    {'E', {31, 16, 16, 30, 16, 16, 31}},
    {'F', {31, 16, 16, 30, 16, 16, 16}},
    {'G', {14, 17, 16, 23, 17, 17, 15}},
    {'H', {17, 17, 17, 31, 17, 17, 17}},
    {'I', {14, 4, 4, 4, 4, 4, 14}},
    {'J', {7, 2, 2, 2, 2, 18, 12}},
    {'K', {17, 18, 20, 24, 20, 18, 17}},
    {'L', {16, 16, 16, 16, 16, 16, 31}},
    {'M', {17, 27, 21, 21, 17, 17, 17}},
    {'N', {17, 19, 21, 25, 17, 17, 17}},
    {'O', {14, 17, 17, 17, 17, 17, 14}},
    {'P', {30, 17, 17, 30, 16, 16, 16}},
    {'Q', {14, 17, 17, 17, 21, 18, 11}},
    {'R', {30, 17, 17, 30, 20, 18, 17}},
    {'S', {14, 17, 16, 14, 1, 17, 14}},
    {'T', {31, 4, 4, 4, 4, 4, 4}},
    {'U', {17, 17, 17, 17, 17, 17, 14}},
    {'V', {17, 17, 17, 17, 17, 10, 4}},
    {'W', {17, 17, 17, 21, 21, 21, 10}},
    {'X', {17, 17, 10, 4, 10, 17, 17}},
    {'Y', {17, 17, 10, 4, 4, 4, 4}},
    {'Z', {31, 1, 2, 4, 8, 16, 31}},
    {'[', {14, 16, 16, 16, 16, 16, 14}},
    {'\\', {16, 16, 8, 4, 2, 1, 1}},
    {']', {14, 16, 16, 16, 16, 16, 14}},
    {'^', {0, 0, 0, 0, 0, 0, 0}},
    {'_', {0, 0, 0, 0, 0, 0, 31}},
    {'`', {4, 2, 0, 0, 0, 0, 0}},
    {'a', {0, 0, 14, 1, 14, 17, 14}},
    {'b', {16, 16, 19, 17, 17, 17, 14}},
    {'c', {0, 0, 14, 16, 16, 16, 14}},
    {'d', {1, 1, 11, 19, 17, 19, 11}},
    {'e', {0, 0, 14, 1, 31, 16, 14}},
    {'f', {6, 9, 8, 28, 8, 8, 8}},
    {'g', {0, 14, 17, 17, 15, 1, 14}},
    {'h', {16, 16, 22, 25, 17, 17, 17}},
    {'i', {4, 0, 6, 4, 4, 4, 14}},
    {'j', {2, 0, 6, 2, 2, 18, 12}},
    {'k', {16, 16, 18, 20, 24, 20, 18}},
    {'l', {6, 4, 4, 4, 4, 4, 14}},
    {'m', {0, 0, 22, 21, 21, 21, 21}},
    {'n', {0, 0, 19, 21, 17, 17, 17}},
    {'o', {0, 0, 14, 17, 17, 17, 14}},
    {'p', {0, 30, 17, 17, 30, 16, 16}},
    {'q', {0, 15, 17, 17, 15, 1, 1}},
    {'r', {0, 0, 23, 22, 16, 16, 16}},
    {'s', {0, 0, 14, 16, 14, 1, 30}},
    {'t', {2, 2, 30, 2, 2, 10, 12}},
    {'u', {0, 0, 17, 17, 17, 19, 14}},
    {'v', {0, 0, 17, 17, 17, 10, 4}},
    {'w', {0, 0, 17, 17, 21, 21, 10}},
    {'x', {0, 0, 17, 10, 4, 10, 17}},
    {'y', {0, 17, 17, 17, 15, 1, 14}},
    {'z', {0, 0, 31, 2, 4, 8, 31}},
    {'{', {2, 4, 4, 8, 4, 4, 2}},
    {'|', {4, 4, 4, 4, 4, 4, 4}},
    {'}', {8, 4, 4, 2, 4, 4, 8}},
    {'~', {0, 0, 0, 0, 0, 0, 0}},
};

const Glyph5x7* glyphFor(char c) {
    const int index = static_cast<unsigned char>(c) - 32;
    if (index < 0 || index >= 95) return nullptr;
    if (kFont[index].c != c) return nullptr;  // undefined slot renders blank
    return &kFont[index];
}

// Colors (packed RGBA).
constexpr unsigned kDimAlpha = 200;        // ~78% black backdrop over the frozen frame
constexpr unsigned kPanelBgR = 20, kPanelBgG = 24, kPanelBgB = 29;
constexpr unsigned kAccentR = 47, kAccentG = 111, kAccentB = 237;  // selection highlight
constexpr unsigned kDialogBgR = 26, kDialogBgG = 32, kDialogBgB = 38;
constexpr unsigned kButtonIdleR = 42, kButtonIdleG = 49, kButtonIdleB = 56;

void fillRect(SDL_Renderer* renderer, float x, float y, float w, float h,
              unsigned r, unsigned g, unsigned b, unsigned a) {
    SDL_SetRenderDrawColor(renderer, static_cast<Uint8>(r), static_cast<Uint8>(g),
                           static_cast<Uint8>(b), static_cast<Uint8>(a));
    const SDL_FRect rect{x, y, w, h};
    SDL_RenderFillRect(renderer, &rect);
}

float textWidth(const char* text, float scale) {
    std::size_t length = 0;
    while (text[length] != '\0') ++length;
    // 5 font columns + 1 tracking column per character, minus the trailing
    // tracking of the last one.
    return static_cast<float>(length) * 6.0f * scale - scale;
}

}  // namespace

void PauseOverlay::drawText(SDL_Renderer* renderer, float x, float y, const char* text,
                            float scale, unsigned r, unsigned g, unsigned b,
                            unsigned a) const {
    SDL_SetRenderDrawColor(renderer, static_cast<Uint8>(r), static_cast<Uint8>(g),
                           static_cast<Uint8>(b), static_cast<Uint8>(a));
    for (const char* p = text; *p != '\0'; ++p) {
        const Glyph5x7* glyph = glyphFor(*p);
        if (glyph != nullptr) {
            for (int row = 0; row < 7; ++row) {
                const unsigned char bits = glyph->rows[row];
                int col = 0;
                while (col < 5) {
                    if ((bits & (1u << (4 - col))) == 0) {
                        ++col;
                        continue;
                    }
                    int runStart = col;
                    while (col < 5 && (bits & (1u << (4 - col))) != 0) ++col;
                    const SDL_FRect rect{x + static_cast<float>(runStart) * scale,
                                         y + static_cast<float>(row) * scale,
                                         static_cast<float>(col - runStart) * scale, scale};
                    SDL_RenderFillRect(renderer, &rect);
                }
            }
        }
        x += 6.0f * scale;
    }
}

void PauseOverlay::draw(SDL_Renderer* renderer, const PauseMenu& menu) const {
    if (renderer == nullptr || !menu.isOpen()) return;

    // Prefer the logical presentation space (the core's aspect, letterboxed);
    // fall back to the raw window size before any frame has set it up.
    int W = 0;
    int H = 0;
    SDL_RendererLogicalPresentation mode = SDL_LOGICAL_PRESENTATION_DISABLED;
    if (!SDL_GetRenderLogicalPresentation(renderer, &W, &H, &mode) || W <= 0 || H <= 0) {
        SDL_GetRenderOutputSize(renderer, &W, &H);
    }
    if (W <= 0 || H <= 0) return;

    const float Wf = static_cast<float>(W);
    const float Hf = static_cast<float>(H);
    // One layout unit: keeps the overlay proportional at any core resolution
    // (240p handheld cores up to 720p+).
    const float s = std::clamp(std::min(Wf / 560.0f, Hf / 160.0f), 1.0f, 8.0f);

    // 1. Dim backdrop — the frozen last frame shows through.
    fillRect(renderer, 0.0f, 0.0f, Wf, Hf, 0, 0, 0, kDimAlpha);

    // 2. Menu panel: title + four items (Video Options / Controller Settings
    // are disabled placeholders drawn dimmed; see PauseMenu::itemEnabled).
    const float pad = 6.0f * s;
    const float rowH = 9.0f * s;
    const float titleH = 8.0f * s;
    const float gap = 2.0f * s;
    const float panelW = std::min(Wf * 0.7f, 130.0f * s);
    const float panelH = pad * 2.0f + titleH + gap +
                         static_cast<float>(PauseMenu::kItemCount) * rowH;
    const float px = (Wf - panelW) / 2.0f;
    const float py = (Hf - panelH) / 2.0f;
    fillRect(renderer, px, py, panelW, panelH, kPanelBgR, kPanelBgG, kPanelBgB, 255);

    {
        const char* title = "PAUSED";
        drawText(renderer, px + (panelW - textWidth(title, s)) / 2.0f, py + pad, title, s,
                 255, 255, 255, 255);
    }

    const float itemsX = px + pad;
    const float itemsW = panelW - 2.0f * pad;
    const float itemsY = py + pad + titleH + gap;
    for (int i = 0; i < PauseMenu::kItemCount; ++i) {
        const bool selected = menu.state() == PauseMenuState::kOpen && menu.selection() == i;
        const bool enabled = PauseMenu::itemEnabled(i);
        if (selected) {
            fillRect(renderer, itemsX, itemsY + static_cast<float>(i) * rowH, itemsW, rowH,
                     kAccentR, kAccentG, kAccentB, 255);
        }
        const char* label = PauseMenu::itemLabel(i);
        const float tx = itemsX + (itemsW - textWidth(label, s)) / 2.0f;
        const float ty = itemsY + static_cast<float>(i) * rowH + (rowH - 7.0f * s) / 2.0f;
        drawText(renderer, tx, ty, label, s, 255, 255, 255, selected || enabled ? 255 : 96);
    }

    // 3. "Quit game?" confirm dialog on top of the menu (extra scrim + box).
    if (menu.state() == PauseMenuState::kQuitConfirm) {
        fillRect(renderer, 0.0f, 0.0f, Wf, Hf, 0, 0, 0, 128);

        const char* title = "Quit game?";
        const char* body = "Are you sure you want to quit?";
        const float buttonW = 26.0f * s;
        const float buttonH = 8.0f * s;
        const float gapX = 4.0f * s;
        const float buttonsW = 2.0f * buttonW + gapX;
        const float innerW = std::max(textWidth(title, s), textWidth(body, s));
        const float dialogW = std::min(Wf * 0.9f, std::max(innerW, buttonsW) + 2.0f * pad);
        const float vgap = 3.0f * s;
        const float dialogH = 2.0f * pad + titleH + vgap + titleH + vgap + buttonH;
        const float dx = (Wf - dialogW) / 2.0f;
        const float dy = (Hf - dialogH) / 2.0f;
        fillRect(renderer, dx, dy, dialogW, dialogH, kDialogBgR, kDialogBgG, kDialogBgB, 255);

        drawText(renderer, dx + pad, dy + pad, title, s, 255, 255, 255, 255);
        drawText(renderer, dx + pad, dy + pad + titleH + vgap, body, s, 189, 189, 189, 255);

        // No (left) / Yes (right), matching the Android dialog's button order.
        const float bx = dx + (dialogW - buttonsW) / 2.0f;
        const float by = dy + dialogH - pad - buttonH;
        for (int i = 0; i < 2; ++i) {
            const int option = i == 0 ? PauseMenu::kConfirmNo : PauseMenu::kConfirmYes;
            const bool selected = menu.selection() == option;
            fillRect(renderer, bx + static_cast<float>(i) * (buttonW + gapX), by, buttonW,
                     buttonH, selected ? kAccentR : kButtonIdleR,
                     selected ? kAccentG : kButtonIdleG,
                     selected ? kAccentB : kButtonIdleB, 255);
            const char* label = PauseMenu::confirmOptionLabel(option);
            drawText(renderer, bx + static_cast<float>(i) * (buttonW + gapX) +
                                    (buttonW - textWidth(label, s)) / 2.0f,
                     by + (buttonH - 7.0f * s) / 2.0f, label, s, 255, 255, 255, 255);
        }
    }
}

}  // namespace romm::player
