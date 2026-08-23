// Native SDL rendering for the desktop in-game pause UI.
#include "native/player/pause_overlay.h"

#include <SDL3/SDL.h>

#include "native/player/pause_menu.h"

#define STB_TRUETYPE_IMPLEMENTATION
#include <stb/stb_truetype.h>
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
#endif
#include <stb/stb_image.h>
#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace romm::player {
namespace {

struct Color {
    Uint8 r;
    Uint8 g;
    Uint8 b;
    Uint8 a = 255;
};

// Android's default RomMulus palette.
constexpr Color kBlack{0, 0, 0};
constexpr Color kNightLo{15, 31, 33};
constexpr Color kRomm300{165, 200, 207};
constexpr Color kRomm500{63, 144, 153};
constexpr Color kRomm600{40, 97, 106};
constexpr Color kTextPrimary{255, 255, 255};
constexpr Color kTextSecondary{179, 198, 202};
constexpr Color kDialogScrim{0, 0, 0, 150};

void setColor(SDL_Renderer* renderer, Color color) {
    SDL_SetRenderDrawColor(renderer, color.r, color.g, color.b, color.a);
}

void fillRect(SDL_Renderer* renderer, float x, float y, float w, float h, Color color) {
    setColor(renderer, color);
    const SDL_FRect rect{x, y, std::max(0.0f, w), std::max(0.0f, h)};
    SDL_RenderFillRect(renderer, &rect);
}

void fillRoundedRect(
    SDL_Renderer* renderer,
    float x,
    float y,
    float w,
    float h,
    float radius,
    Color color
) {
    radius = std::clamp(radius, 0.0f, std::min(w, h) * 0.5f);
    if (radius < 1.0f) {
        fillRect(renderer, x, y, w, h, color);
        return;
    }

    fillRect(renderer, x + radius, y, w - radius * 2.0f, h, color);
    fillRect(renderer, x, y + radius, radius, h - radius * 2.0f, color);
    fillRect(renderer, x + w - radius, y + radius, radius, h - radius * 2.0f, color);

    setColor(renderer, color);
    const int rows = std::max(1, static_cast<int>(std::ceil(radius)));
    for (int row = 0; row < rows; ++row) {
        const float dy = radius - (static_cast<float>(row) + 0.5f);
        const float dx = std::sqrt(std::max(0.0f, radius * radius - dy * dy));
        const float inset = radius - dx;
        const SDL_FRect top{x + inset, y + static_cast<float>(row), w - inset * 2.0f, 1.1f};
        const SDL_FRect bottom{
            x + inset,
            y + h - static_cast<float>(row) - 1.1f,
            w - inset * 2.0f,
            1.1f,
        };
        SDL_RenderFillRect(renderer, &top);
        SDL_RenderFillRect(renderer, &bottom);
    }
}

void strokeRoundedRect(
    SDL_Renderer* renderer,
    float x,
    float y,
    float w,
    float h,
    float radius,
    float stroke,
    Color border,
    Color interior
) {
    fillRoundedRect(renderer, x, y, w, h, radius, border);
    fillRoundedRect(
        renderer,
        x + stroke,
        y + stroke,
        w - stroke * 2.0f,
        h - stroke * 2.0f,
        std::max(0.0f, radius - stroke),
        interior
    );
}

std::vector<unsigned char> readFile(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return {};
    const std::streamsize size = input.tellg();
    if (size <= 0) return {};
    input.seekg(0, std::ios::beg);
    std::vector<unsigned char> bytes(static_cast<std::size_t>(size));
    if (!input.read(reinterpret_cast<char*>(bytes.data()), size)) return {};
    return bytes;
}

std::vector<std::filesystem::path> fontCandidates() {
    std::vector<std::filesystem::path> paths;
    if (const char* base = SDL_GetBasePath(); base != nullptr) {
        paths.emplace_back(std::filesystem::path(base) / "../share/rommulus/font.ttf");
    }
#ifdef ROMM_PAUSE_FONT_PATH
    paths.emplace_back(ROMM_PAUSE_FONT_PATH);
#endif
    paths.emplace_back("/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf");
    paths.emplace_back("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
    paths.emplace_back("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf");
    return paths;
}

struct RasterText {
    float width = 0.0f;
    float height = 0.0f;
    std::array<std::vector<SDL_FRect>, 16> alphaRects;
};

class TrueTypeFont {
public:
    TrueTypeFont() {
        for (const auto& path : fontCandidates()) {
            data_ = readFile(path);
            if (!data_.empty() &&
                stbtt_InitFont(&font_, data_.data(), stbtt_GetFontOffsetForIndex(data_.data(), 0))) {
                ready_ = true;
                break;
            }
            data_.clear();
        }
    }

    bool ready() const { return ready_; }

    float width(const char* text, float pixelHeight) {
        if (!ready_ || text == nullptr || *text == '\0') return 0.0f;
        const float scale = stbtt_ScaleForPixelHeight(&font_, pixelHeight);
        float result = 0.0f;
        for (const char* p = text; *p != '\0'; ++p) {
            int advance = 0;
            int bearing = 0;
            stbtt_GetCodepointHMetrics(&font_, static_cast<unsigned char>(*p), &advance, &bearing);
            result += static_cast<float>(advance) * scale;
            if (p[1] != '\0') {
                result += static_cast<float>(
                    stbtt_GetCodepointKernAdvance(
                        &font_,
                        static_cast<unsigned char>(*p),
                        static_cast<unsigned char>(p[1])
                    )
                ) * scale;
            }
        }
        return result;
    }

    const RasterText& raster(const char* text, float pixelHeight) {
        const int quantizedHeight = std::max(4, static_cast<int>(std::lround(pixelHeight)));
        const std::string key = std::to_string(quantizedHeight) + '\n' + text;
        const auto found = cache_.find(key);
        if (found != cache_.end()) return found->second;

        RasterText raster;
        const float scale = stbtt_ScaleForPixelHeight(&font_, static_cast<float>(quantizedHeight));
        int ascent = 0;
        int descent = 0;
        int lineGap = 0;
        stbtt_GetFontVMetrics(&font_, &ascent, &descent, &lineGap);
        const int baseline = static_cast<int>(std::ceil(static_cast<float>(ascent) * scale));
        raster.height = std::ceil(static_cast<float>(ascent - descent) * scale);

        float penX = 0.0f;
        for (const char* p = text; *p != '\0'; ++p) {
            const int codepoint = static_cast<unsigned char>(*p);
            int glyphW = 0;
            int glyphH = 0;
            int offsetX = 0;
            int offsetY = 0;
            unsigned char* bitmap = stbtt_GetCodepointBitmap(
                &font_,
                0.0f,
                scale,
                codepoint,
                &glyphW,
                &glyphH,
                &offsetX,
                &offsetY
            );
            if (bitmap != nullptr) {
                for (int row = 0; row < glyphH; ++row) {
                    int col = 0;
                    while (col < glyphW) {
                        const int bucket =
                            (static_cast<int>(bitmap[row * glyphW + col]) * 15 + 127) / 255;
                        if (bucket == 0) {
                            ++col;
                            continue;
                        }
                        const int start = col++;
                        while (col < glyphW) {
                            const int next =
                                (static_cast<int>(bitmap[row * glyphW + col]) * 15 + 127) / 255;
                            if (next != bucket) break;
                            ++col;
                        }
                        raster.alphaRects[static_cast<std::size_t>(bucket)].push_back(
                            SDL_FRect{
                                penX + static_cast<float>(offsetX + start),
                                static_cast<float>(baseline + offsetY + row),
                                static_cast<float>(col - start),
                                1.0f,
                            }
                        );
                    }
                }
                stbtt_FreeBitmap(bitmap, nullptr);
            }

            int advance = 0;
            int bearing = 0;
            stbtt_GetCodepointHMetrics(&font_, codepoint, &advance, &bearing);
            penX += static_cast<float>(advance) * scale;
            if (p[1] != '\0') {
                penX += static_cast<float>(
                    stbtt_GetCodepointKernAdvance(
                        &font_,
                        codepoint,
                        static_cast<unsigned char>(p[1])
                    )
                ) * scale;
            }
        }
        raster.width = penX;
        return cache_.emplace(key, std::move(raster)).first->second;
    }

private:
    std::vector<unsigned char> data_;
    stbtt_fontinfo font_{};
    bool ready_ = false;
    std::unordered_map<std::string, RasterText> cache_;
};

TrueTypeFont& font() {
    static TrueTypeFont instance;
    return instance;
}

float textWidth(const char* text, float size) {
    return font().ready() ? font().width(text, size)
                          : static_cast<float>(SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE) *
                                static_cast<float>(std::char_traits<char>::length(text));
}

std::vector<std::string> wrapText(const char* text, float size, float maxWidth) {
    std::vector<std::string> lines;
    std::string current;
    std::string word;
    const std::string input(text);
    for (std::size_t i = 0; i <= input.size(); ++i) {
        if (i < input.size() && input[i] != ' ') {
            word += input[i];
            continue;
        }
        const std::string candidate = current.empty() ? word : current + " " + word;
        if (!current.empty() && textWidth(candidate.c_str(), size) > maxWidth) {
            lines.push_back(current);
            current = word;
        } else {
            current = candidate;
        }
        word.clear();
    }
    if (!current.empty()) lines.push_back(current);
    return lines;
}

void drawSwitch(
    SDL_Renderer* renderer,
    float x,
    float y,
    float scale,
    bool checked
) {
    const float w = 48.0f * scale;
    const float h = 28.0f * scale;
    const float padding = 4.0f * scale;
    const Color track = checked ? kRomm600 : Color{63, 73, 75};
    fillRoundedRect(renderer, x, y, w, h, h * 0.5f, track);
    const float thumb = h - padding * 2.0f;
    const float thumbX = checked ? x + w - padding - thumb : x + padding;
    fillRoundedRect(renderer, thumbX, y + padding, thumb, thumb, thumb * 0.5f,
                    checked ? kTextPrimary : Color{125, 139, 142});
}

void drawButton(
    const PauseOverlay& overlay,
    SDL_Renderer* renderer,
    float x,
    float y,
    float w,
    float h,
    const char* label,
    float scale,
    bool selected,
    bool primary = false
) {
    const float radius = h * 0.5f;
    const Color interior = primary ? kRomm500 : kBlack;
    const float stroke = (selected ? 3.0f : 1.0f) * scale;
    const Color border = selected ? kRomm300 : Color{125, 139, 142};
    if (primary && !selected) {
        fillRoundedRect(renderer, x, y, w, h, radius, interior);
    } else {
        strokeRoundedRect(renderer, x, y, w, h, radius, stroke, border, interior);
    }
    const float textSize = 14.0f * scale;
    overlay.drawText(
        renderer,
        x + (w - textWidth(label, textSize)) * 0.5f,
        y + (h - textSize) * 0.5f - scale,
        label,
        textSize,
        kTextPrimary.r,
        kTextPrimary.g,
        kTextPrimary.b,
        kTextPrimary.a
    );
}

bool toggleState(const PauseMenu& menu, int index) {
    if (index == PauseMenu::kScanlinesItem) return menu.scanlinesEnabled();
    if (index == PauseMenu::kIntegerScalingItem) return menu.integerScalingEnabled();
    return menu.sharpFilterEnabled();
}

const char* videoDescription(int index) {
    switch (index) {
        case PauseMenu::kScanlinesItem:
            return "Adds subtle horizontal lines for a classic CRT look.";
        case PauseMenu::kIntegerScalingItem:
            return "Scales by a clean integer factor for crisp, aspect-correct pixels.";
        case PauseMenu::kSharpFilterItem:
            return "Uses nearest-neighbor scaling to keep pixel art crisp.";
        default:
            return "";
    }
}

const char* consoleNameForCore(const char* coreId) {
    const std::string id = coreId != nullptr ? coreId : "";
    if (id == "genesis_plus_gx") return "Sega Systems";
    if (id == "snes9x") return "Super Nintendo";
    if (id == "fceumm") return "Nintendo Entertainment System";
    if (id == "mgba") return "Game Boy Advance";
    if (id == "stella") return "Atari 2600";
    if (id == "gambatte") return "Game Boy / Game Boy Color";
    if (id == "beetle_pce_fast") return "TurboGrafx-16";
    if (id == "mednafen_ngp") return "Neo Geo Pocket";
    if (id == "mednafen_wswan") return "WonderSwan";
    if (id == "handy") return "Atari Lynx";
    if (id == "prosystem") return "Atari 7800";
    if (id == "pcsx_rearmed") return "PlayStation";
    if (id == "mupen64plus_next") return "Nintendo 64";
    if (id == "dolphin") return "Nintendo GameCube";
    return "Controller Settings";
}

const char* controlLabelForCoreSlot(const char* coreId, int slot) {
    const std::string id = coreId != nullptr ? coreId : "";
    if (slot >= kSlotDpadUp && slot <= kSlotDpadRight) return retroPadSlotLabel(slot);
    if (id == "genesis_plus_gx") {
        static constexpr const char* labels[] = {
            "C", "B", "Y", "A", "Mode", "Start", "X", "Z",
        };
        if (slot >= 0 && slot < 8) return labels[slot];
    } else if (id == "stella") {
        if (slot == kSlotA) return "Trigger";
        if (slot == kSlotB) return "Fire";
        if (slot == kSlotY) return "Booster";
    } else if (id == "beetle_pce_fast") {
        static constexpr const char* labels[] = {
            "I", "II", "IV", "III", "Select", "Run", "V", "VI",
        };
        if (slot >= 0 && slot < 8) return labels[slot];
    } else if (id == "mednafen_ngp") {
        if (slot == kSlotA) return "B";
        if (slot == kSlotB) return "A";
        if (slot == kSlotStart) return "Option";
    } else if (id == "handy") {
        if (slot == kSlotLeftShoulder) return "Option 1";
        if (slot == kSlotRightShoulder) return "Option 2";
        if (slot == kSlotStart) return "Pause";
    } else if (id == "prosystem") {
        if (slot == kSlotA) return "Button 2";
        if (slot == kSlotB) return "Button 1";
        if (slot == kSlotStart) return "Pause";
    } else if (id == "pcsx_rearmed") {
        static constexpr const char* labels[] = {
            "Circle", "Cross", "Triangle", "Square", "Select", "Start", "L1", "R1",
        };
        if (slot >= 0 && slot < 8) return labels[slot];
    } else if (id == "mupen64plus_next") {
        static constexpr const char* labels[] = {
            "C-Down", "A Button", "C-Up", "B Button",
            "L Shoulder", "Start", "C-Left", "C-Right",
        };
        if (slot >= 0 && slot < 8) return labels[slot];
        if (slot == kSlotLeftTrigger) return "Z Trigger";
        if (slot == kSlotRightTrigger) return "R Shoulder";
    } else if (id == "dolphin") {
        if (slot == kSlotLeftTrigger) return "L";
        if (slot == kSlotRightTrigger) return "R";
        if (slot == kSlotRightShoulder) return "Z";
        if (slot == kSlotSelect) return "Control Stick X";
        if (slot == kSlotLeftShoulder) return "Control Stick Y";
        if (slot == kSlotLeftStick) return "C-Stick X";
        if (slot == kSlotRightStick) return "C-Stick Y";
    } else if (id == "mgba") {
        if (slot == kSlotLeftShoulder) return "L";
        if (slot == kSlotRightShoulder) return "R";
    }
    return retroPadSlotLabel(slot);
}

const char* keyboardControlLabel(const char* coreId, int target) {
    if (target >= 0 && target < kKeyboardDigitalTargetCount) {
        return controlLabelForCoreSlot(coreId, target);
    }
    return keyboardTargetLabel(target);
}

std::string keyboardScancodeDisplay(const std::optional<int>& scancode) {
    if (!scancode.has_value()) return "Unmapped";
    const char* name = SDL_GetScancodeName(static_cast<SDL_Scancode>(*scancode));
    if (name != nullptr && *name != '\0') return name;
    return "Scancode " + std::to_string(*scancode);
}

const char* artworkNameForCore(const char* coreId) {
    const std::string id = coreId != nullptr ? coreId : "";
    if (id == "genesis_plus_gx") return "controller_outline_genesis.png";
    if (id == "snes9x") return "controller_outline_snes.png";
    if (id == "fceumm") return "controller_outline_nes.png";
    if (id == "mgba") return "controller_outline_gba.png";
    if (id == "stella") return "controller_outline_atari2600.png";
    if (id == "gambatte") return "controller_outline_gb.png";
    if (id == "beetle_pce_fast") return "controller_outline_tg16.png";
    if (id == "mednafen_ngp") return "controller_outline_ngp.png";
    if (id == "mednafen_wswan") return "controller_outline_wswan.png";
    if (id == "handy") return "controller_outline_lynx.png";
    if (id == "prosystem") return "controller_outline_atari7800.png";
    if (id == "pcsx_rearmed") return "controller_outline_ps1.png";
    if (id == "mupen64plus_next") return "controller_outline_n64.png";
    if (id == "dolphin") return "controller_outline_gamecube.png";
    return "controller_outline_generic_gamepad.png";
}

std::vector<std::filesystem::path> artworkCandidates(const char* fileName) {
    std::vector<std::filesystem::path> paths;
    if (const char* base = SDL_GetBasePath(); base != nullptr) {
        paths.emplace_back(
            std::filesystem::path(base) / "../share/rommulus/controllers" / fileName
        );
    }
#ifdef ROMM_CONTROLLER_ART_PATH
    paths.emplace_back(std::filesystem::path(ROMM_CONTROLLER_ART_PATH) / fileName);
#endif
    return paths;
}

SDL_Texture* controllerTexture(SDL_Renderer* renderer, const char* coreId) {
    static std::unordered_map<std::string, SDL_Texture*> textures;
    const std::string name = artworkNameForCore(coreId);
    if (const auto found = textures.find(name); found != textures.end()) {
        return found->second;
    }

    for (const auto& path : artworkCandidates(name.c_str())) {
        int width = 0;
        int height = 0;
        int channels = 0;
        stbi_uc* pixels = stbi_load(path.string().c_str(), &width, &height, &channels, STBI_rgb_alpha);
        if (pixels == nullptr || width <= 0 || height <= 0) {
            stbi_image_free(pixels);
            continue;
        }
        SDL_Texture* texture = SDL_CreateTexture(
            renderer, SDL_PIXELFORMAT_RGBA32, SDL_TEXTUREACCESS_STATIC, width, height
        );
        if (texture != nullptr &&
            SDL_UpdateTexture(texture, nullptr, pixels, width * STBI_rgb_alpha)) {
            SDL_SetTextureBlendMode(texture, SDL_BLENDMODE_BLEND);
            stbi_image_free(pixels);
            textures.emplace(name, texture);
            return texture;
        }
        SDL_DestroyTexture(texture);
        stbi_image_free(pixels);
    }
    textures.emplace(name, nullptr);
    return nullptr;
}

void drawControllerArtwork(
    const PauseOverlay& overlay,
    SDL_Renderer* renderer,
    float x,
    float y,
    float w,
    float h,
    float scale,
    const char* consoleName,
    const char* coreId,
    const char* focusedControl
) {
    fillRoundedRect(renderer, x, y, w, h, 12.0f * scale, kNightLo);
    if (SDL_Texture* texture = controllerTexture(renderer, coreId); texture != nullptr) {
        const float labelSpace = 74.0f * scale;
        const float size = std::min(w - 32.0f * scale, h - labelSpace);
        const SDL_FRect destination{
            x + (w - size) * 0.5f,
            y + std::max(8.0f * scale, (h - labelSpace - size) * 0.5f),
            size,
            size,
        };
        SDL_RenderTexture(renderer, texture, nullptr, &destination);
        overlay.drawText(
            renderer, x + 20.0f * scale, y + h - 56.0f * scale, focusedControl,
            18.0f * scale, 255, 255, 255, 255
        );
        overlay.drawText(
            renderer, x + 20.0f * scale, y + h - 30.0f * scale, consoleName,
            13.0f * scale, kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255
        );
        return;
    }

    const float bodyW = std::min(w * 0.72f, 280.0f * scale);
    const float bodyH = std::min(h * 0.34f, 150.0f * scale);
    const float bodyX = x + (w - bodyW) * 0.5f;
    const float bodyY = y + (h - bodyH) * 0.52f;
    const Color outline{125, 151, 156};
    strokeRoundedRect(renderer, bodyX, bodyY, bodyW, bodyH, 34.0f * scale,
                      std::max(2.0f, 3.0f * scale), outline, Color{20, 45, 49});

    const float handleW = bodyW * 0.28f;
    const float handleH = bodyH * 0.55f;
    fillRoundedRect(renderer, bodyX + bodyW * 0.06f, bodyY + bodyH * 0.68f,
                    handleW, handleH, handleW * 0.45f, Color{20, 45, 49});
    fillRoundedRect(renderer, bodyX + bodyW * 0.66f, bodyY + bodyH * 0.68f,
                    handleW, handleH, handleW * 0.45f, Color{20, 45, 49});

    const float dpadX = bodyX + bodyW * 0.25f;
    const float dpadY = bodyY + bodyH * 0.52f;
    fillRoundedRect(renderer, dpadX - 20.0f * scale, dpadY - 6.0f * scale,
                    40.0f * scale, 12.0f * scale, 3.0f * scale, outline);
    fillRoundedRect(renderer, dpadX - 6.0f * scale, dpadY - 20.0f * scale,
                    12.0f * scale, 40.0f * scale, 3.0f * scale, outline);

    const float buttonSize = 14.0f * scale;
    const float buttonsX = bodyX + bodyW * 0.74f;
    const float buttonsY = bodyY + bodyH * 0.49f;
    fillRoundedRect(renderer, buttonsX, buttonsY - buttonSize, buttonSize, buttonSize,
                    buttonSize * 0.5f, kRomm300);
    fillRoundedRect(renderer, buttonsX + buttonSize * 1.25f, buttonsY, buttonSize, buttonSize,
                    buttonSize * 0.5f, kRomm500);
    fillRoundedRect(renderer, buttonsX, buttonsY + buttonSize, buttonSize, buttonSize,
                    buttonSize * 0.5f, kRomm600);
    fillRoundedRect(renderer, buttonsX - buttonSize * 1.25f, buttonsY, buttonSize, buttonSize,
                    buttonSize * 0.5f, outline);
}

}  // namespace

void PauseOverlay::drawText(
    SDL_Renderer* renderer,
    float x,
    float y,
    const char* text,
    float size,
    unsigned r,
    unsigned g,
    unsigned b,
    unsigned a
) const {
    if (!font().ready()) {
        setColor(
            renderer,
            Color{
                static_cast<Uint8>(r),
                static_cast<Uint8>(g),
                static_cast<Uint8>(b),
                static_cast<Uint8>(a),
            }
        );
        SDL_RenderDebugText(renderer, x, y, text);
        return;
    }

    const RasterText& raster = font().raster(text, size);
    std::array<std::vector<SDL_FRect>, 16> translated;
    for (std::size_t bucket = 1; bucket < raster.alphaRects.size(); ++bucket) {
        auto& target = translated[bucket];
        target.reserve(raster.alphaRects[bucket].size());
        for (const SDL_FRect& rect : raster.alphaRects[bucket]) {
            target.push_back(SDL_FRect{x + rect.x, y + rect.y, rect.w, rect.h});
        }
        if (target.empty()) continue;
        const unsigned alpha = (a * static_cast<unsigned>(bucket) + 7u) / 15u;
        SDL_SetRenderDrawColor(
            renderer,
            static_cast<Uint8>(r),
            static_cast<Uint8>(g),
            static_cast<Uint8>(b),
            static_cast<Uint8>(alpha)
        );
        SDL_RenderFillRects(renderer, target.data(), static_cast<int>(target.size()));
    }
}

void PauseOverlay::draw(
    SDL_Renderer* renderer,
    const PauseMenu& menu,
    const BindingTable& bindings,
    const BindingTable& secondaryBindings,
    const KeyboardBindingTable& keyboardBindings,
    int captureSecondsLeft,
    const char* coreId
) const {
    if (renderer == nullptr || !menu.isOpen()) return;

    int width = 0;
    int height = 0;
    SDL_RendererLogicalPresentation mode = SDL_LOGICAL_PRESENTATION_DISABLED;
    if (!SDL_GetRenderLogicalPresentation(renderer, &width, &height, &mode) ||
        width <= 0 || height <= 0) {
        SDL_GetRenderOutputSize(renderer, &width, &height);
    }
    if (width <= 0 || height <= 0) return;

    SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
    const float W = static_cast<float>(width);
    const float H = static_cast<float>(height);
    const float scale = std::max(0.12f, std::min(W / 1280.0f, H / 720.0f));
    const auto dp = [scale](float value) { return value * scale; };

    // Android intentionally replaces the frozen game with an opaque black pause screen.
    fillRect(renderer, 0.0f, 0.0f, W, H, kBlack);

    if (menu.state() == PauseMenuState::kVideoOptions) {
        const float panelW = std::min(dp(540.0f), W - dp(32.0f));
        const float panelH = std::min(dp(474.0f), H - dp(24.0f));
        const float x = (W - panelW) * 0.5f;
        const float y = (H - panelH) * 0.5f;
        strokeRoundedRect(renderer, x, y, panelW, panelH, dp(16), std::max(1.0f, dp(1)),
                          Color{56, 67, 69}, kNightLo);

        const float titleSize = dp(24);
        const char* title = "Video Options";
        drawText(renderer, x + (panelW - textWidth(title, titleSize)) * 0.5f, y + dp(27),
                 title, titleSize, 255, 255, 255, 255);

        const float rowX = x + dp(28);
        const float rowW = panelW - dp(56);
        const float rowH = dp(88);
        float rowY = y + dp(76);
        for (int i = 0; i < PauseMenu::kVideoOptionCount; ++i) {
            const bool selected = menu.selection() == i;
            if (selected) {
                strokeRoundedRect(renderer, rowX, rowY, rowW, rowH, dp(8), dp(3),
                                  kRomm300, kNightLo);
            }
            drawText(renderer, rowX + dp(18), rowY + dp(13),
                     PauseMenu::videoOptionLabel(i), dp(16), 255, 255, 255, 255);
            const auto lines = wrapText(videoDescription(i), dp(12), rowW - dp(100));
            float lineY = rowY + dp(39);
            for (const std::string& line : lines) {
                drawText(renderer, rowX + dp(18), lineY, line.c_str(), dp(12),
                         kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255);
                lineY += dp(15);
            }
            drawSwitch(renderer, rowX + rowW - dp(66), rowY + (rowH - dp(28)) * 0.5f,
                       scale, toggleState(menu, i));
            rowY += rowH + dp(12);
        }
        drawButton(*this, renderer, rowX, y + panelH - dp(76), rowW, dp(48), "Return",
                   scale, false);
        return;
    }

    if (menu.state() == PauseMenuState::kPhysicalBindings) {
        fillRect(renderer, 0, 0, W, H, Color{10, 23, 25});
        const char* consoleName = consoleNameForCore(coreId);
        const float marginX = dp(32);
        const float headerY = dp(24);
        drawButton(*this, renderer, marginX, headerY, dp(84), dp(42), "Back",
                   scale, false);
        const std::string title = std::string(consoleName) + " Controller";
        drawText(renderer, (W - textWidth(title.c_str(), dp(24))) * 0.5f, headerY + dp(5),
                 title.c_str(), dp(24), 255, 255, 255, 255);
        const float clearW = dp(142);
        const float resetW = dp(154);
        const float clearX = W - marginX - clearW;
        const float resetX = clearX - dp(8) - resetW;
        drawButton(*this, renderer, resetX, headerY, resetW, dp(42), "Reset Controller",
                   scale, menu.selection() == menu.resetDefaultItem());
        drawButton(*this, renderer, clearX, headerY, clearW, dp(42), "Clear Mappings",
                   scale, menu.selection() == menu.clearMappingsItem());

        const float tabY = dp(76);
        fillRoundedRect(renderer, marginX, tabY, W - marginX * 2.0f, dp(38), dp(8), kNightLo);
        fillRect(renderer, marginX, tabY + dp(35), W - marginX * 2.0f, dp(3), kRomm300);
        const char* playerTab = "Player 1  |  Active";
        drawText(renderer, marginX + dp(18), tabY + dp(10), playerTab, dp(14),
                 255, 255, 255, 255);

        const float contentY = dp(128);
        const float contentH = H - contentY - dp(46);
        const float contentW = W - marginX * 2.0f;
        const float artW = contentW * 0.4f - dp(10);
        const float listX = marginX + artW + dp(20);
        const float listW = contentW - artW - dp(20);
        const char* focusedControl = menu.selection() < menu.bindingSlotCount()
            ? controlLabelForCoreSlot(
                  coreId,
                  coreBindingSlotAt(coreId != nullptr ? coreId : "", menu.selection()))
            : "Controller mappings";
        drawControllerArtwork(*this, renderer, marginX, contentY, artW, contentH,
                              scale, consoleName, coreId, focusedControl);

        const float headerH = dp(26);
        const float labelW = listW * 0.34f;
        const float primaryW = listW * 0.33f;
        drawText(renderer, listX + dp(10), contentY + dp(3), "Control", dp(12),
                 kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255);
        drawText(renderer, listX + labelW + dp(10), contentY + dp(3), "Primary", dp(12),
                 kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255);
        drawText(renderer, listX + labelW + primaryW + dp(10), contentY + dp(3),
                 "Secondary", dp(12), kTextSecondary.r, kTextSecondary.g,
                 kTextSecondary.b, 255);
        fillRect(renderer, listX, contentY + headerH - dp(2), listW, dp(1),
                 Color{56, 67, 69});

        const float gap = dp(5);
        constexpr int kVisibleBindingRows = 8;
        const int firstRow = menu.bindingViewportStart(kVisibleBindingRows);
        const int lastRow =
            std::min(firstRow + kVisibleBindingRows, menu.bindingSlotCount());
        const float rowH = std::min(
            dp(48),
            (contentH - headerH -
             gap * static_cast<float>(kVisibleBindingRows - 1)) /
                static_cast<float>(kVisibleBindingRows)
        );
        for (int i = firstRow; i < lastRow; ++i) {
            const int slot = coreBindingSlotAt(coreId != nullptr ? coreId : "", i);
            const float rowY =
                contentY + headerH + static_cast<float>(i - firstRow) * (rowH + gap);
            const bool selected = menu.selection() == i;
            drawText(renderer, listX + dp(16), rowY + (rowH - dp(15)) * 0.5f - dp(1),
                     controlLabelForCoreSlot(coreId, slot), dp(15), 255, 255, 255, 255);

            const std::string value = bindings.get(slot).display();
            const std::string secondaryValue = secondaryBindings.get(slot).display();
            const float cellY = rowY + dp(3);
            const float cellH = rowH - dp(6);
            const float primaryX = listX + labelW;
            const float secondaryX = primaryX + primaryW + dp(6);
            const float secondaryW = listW - labelW - primaryW - dp(6);
            if (selected && menu.bindingColumn() == 0) {
                strokeRoundedRect(renderer, primaryX, cellY, primaryW - dp(6), cellH,
                                  dp(8), dp(2), kRomm300, Color{20, 45, 49});
            } else {
                strokeRoundedRect(renderer, primaryX, cellY, primaryW - dp(6), cellH,
                                  dp(8), dp(1), Color{56, 67, 69}, kNightLo);
            }
            if (selected && menu.bindingColumn() == 1) {
                strokeRoundedRect(renderer, secondaryX, cellY, secondaryW, cellH,
                                  dp(8), dp(2), kRomm300, Color{20, 45, 49});
            } else {
                strokeRoundedRect(renderer, secondaryX, cellY, secondaryW, cellH,
                                  dp(8), dp(1), Color{56, 67, 69}, kNightLo);
            }
            drawText(renderer, primaryX + dp(12), cellY + (cellH - dp(12)) * 0.5f - dp(1),
                     value.c_str(), dp(12), 255, 255, 255, 255);
            drawText(renderer, secondaryX + dp(12), cellY + (cellH - dp(12)) * 0.5f - dp(1),
                     secondaryValue.c_str(), dp(12), kTextSecondary.r, kTextSecondary.g,
                     kTextSecondary.b, 255);
        }
        if (firstRow > 0) {
            drawText(renderer, listX + listW - dp(20), contentY + dp(3), "^", dp(12),
                     kRomm300.r, kRomm300.g, kRomm300.b, 255);
        }
        if (lastRow < menu.bindingSlotCount()) {
            drawText(renderer, listX + listW - dp(20), contentY + contentH - dp(16), "v",
                     dp(12), kRomm300.r, kRomm300.g, kRomm300.b, 255);
        }
        const char* footer = "Select a control to remap it  |  Back to return";
        drawText(renderer, (W - textWidth(footer, dp(12))) * 0.5f, H - dp(28),
                 footer, dp(12), kTextSecondary.r, kTextSecondary.g,
                 kTextSecondary.b, 255);
        return;
    }

    if (menu.state() == PauseMenuState::kKeyboardBindings) {
        fillRect(renderer, 0, 0, W, H, Color{10, 23, 25});
        const float marginX = dp(48);
        const float headerY = dp(24);
        drawButton(*this, renderer, marginX, headerY, dp(84), dp(42), "Back",
                   scale, false);
        const char* title = "Keyboard Control Settings";
        drawText(renderer, (W - textWidth(title, dp(24))) * 0.5f, headerY + dp(5),
                 title, dp(24), 255, 255, 255, 255);
        const float clearW = dp(142);
        const float resetW = dp(154);
        const float clearX = W - marginX - clearW;
        const float resetX = clearX - dp(8) - resetW;
        drawButton(*this, renderer, resetX, headerY, resetW, dp(42), "Reset to Default",
                   scale, menu.selection() == menu.resetDefaultItem());
        drawButton(*this, renderer, clearX, headerY, clearW, dp(42), "Clear Mappings",
                   scale, menu.selection() == menu.clearMappingsItem());

        const float listX = marginX;
        const float listY = dp(92);
        const float listW = W - marginX * 2.0f;
        const float listH = H - listY - dp(48);
        const float headerH = dp(28);
        const float labelW = listW * 0.42f;
        const float primaryW = listW * 0.29f;
        drawText(renderer, listX + dp(12), listY + dp(4), "Control", dp(12),
                 kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255);
        drawText(renderer, listX + labelW + dp(12), listY + dp(4), "Primary", dp(12),
                 kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255);
        drawText(renderer, listX + labelW + primaryW + dp(12), listY + dp(4),
                 "Secondary", dp(12), kTextSecondary.r, kTextSecondary.g,
                 kTextSecondary.b, 255);
        fillRect(renderer, listX, listY + headerH - dp(2), listW, dp(1),
                 Color{56, 67, 69});

        constexpr int kVisibleRows = 10;
        const int firstRow = menu.bindingViewportStart(kVisibleRows);
        const int lastRow = std::min(firstRow + kVisibleRows, menu.keyboardRowCount());
        const float gap = dp(5);
        const float rowH = std::min(
            dp(48),
            (listH - headerH - gap * static_cast<float>(kVisibleRows - 1)) /
                static_cast<float>(kVisibleRows));
        for (int row = firstRow; row < lastRow; ++row) {
            const int target =
                coreKeyboardTargetAt(coreId != nullptr ? coreId : "", row);
            const KeyboardBinding& binding = keyboardBindings.get(target);
            const std::string primary =
                keyboardScancodeDisplay(binding.primaryScancode);
            const std::string secondary =
                keyboardScancodeDisplay(binding.secondaryScancode);
            const float rowY =
                listY + headerH + static_cast<float>(row - firstRow) * (rowH + gap);
            const float cellY = rowY + dp(3);
            const float cellH = rowH - dp(6);
            const float primaryX = listX + labelW;
            const float secondaryX = primaryX + primaryW + dp(6);
            const float secondaryW = listW - labelW - primaryW - dp(6);
            drawText(renderer, listX + dp(16), rowY + (rowH - dp(15)) * 0.5f - dp(1),
                     keyboardControlLabel(coreId, target), dp(15), 255, 255, 255, 255);
            strokeRoundedRect(
                renderer, primaryX, cellY, primaryW - dp(6), cellH, dp(8),
                menu.selection() == row && menu.bindingColumn() == 0 ? dp(2) : dp(1),
                menu.selection() == row && menu.bindingColumn() == 0
                    ? kRomm300 : Color{56, 67, 69},
                menu.selection() == row && menu.bindingColumn() == 0
                    ? Color{20, 45, 49} : kNightLo);
            strokeRoundedRect(
                renderer, secondaryX, cellY, secondaryW, cellH, dp(8),
                menu.selection() == row && menu.bindingColumn() == 1 ? dp(2) : dp(1),
                menu.selection() == row && menu.bindingColumn() == 1
                    ? kRomm300 : Color{56, 67, 69},
                menu.selection() == row && menu.bindingColumn() == 1
                    ? Color{20, 45, 49} : kNightLo);
            drawText(renderer, primaryX + dp(12), cellY + (cellH - dp(12)) * 0.5f - dp(1),
                     primary.c_str(), dp(12), 255, 255, 255, 255);
            drawText(renderer, secondaryX + dp(12),
                     cellY + (cellH - dp(12)) * 0.5f - dp(1),
                     secondary.c_str(), dp(12), kTextSecondary.r, kTextSecondary.g,
                     kTextSecondary.b, 255);
        }
        const char* footer =
            "Select a control, then press a keyboard key  |  Back to return";
        drawText(renderer, (W - textWidth(footer, dp(12))) * 0.5f, H - dp(28),
                 footer, dp(12), kTextSecondary.r, kTextSecondary.g,
                 kTextSecondary.b, 255);
        return;
    }

    if (menu.state() == PauseMenuState::kBindingCapture) {
        fillRect(renderer, 0, 0, W, H, kDialogScrim);
        const float dialogW = std::min(dp(480), W - dp(32));
        const float dialogH = dp(220);
        const float x = (W - dialogW) * 0.5f;
        const float y = (H - dialogH) * 0.5f;
        fillRoundedRect(renderer, x, y, dialogW, dialogH, dp(28), kNightLo);
        const std::string title =
            std::string("Map ") + controlLabelForCoreSlot(
                coreId,
                coreBindingSlotAt(coreId != nullptr ? coreId : "", menu.selection()));
        drawText(renderer, x + dp(28), y + dp(28), title.c_str(), dp(24),
                 255, 255, 255, 255);
        drawText(renderer, x + dp(28), y + dp(82), "Press a button or move an axis.", dp(16),
                 kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255);
        if (captureSecondsLeft >= 0) {
            char timeout[32];
            std::snprintf(timeout, sizeof(timeout), "Time left: %d seconds", captureSecondsLeft);
            drawText(renderer, x + dp(28), y + dp(116), timeout, dp(14),
                     kRomm300.r, kRomm300.g, kRomm300.b, 255);
        }
        drawText(renderer, x + dp(28), y + dp(166),
                 "Back cancels  |  Hold Back to clear", dp(13),
                 kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255);
        return;
    }

    if (menu.state() == PauseMenuState::kKeyboardCapture) {
        fillRect(renderer, 0, 0, W, H, kDialogScrim);
        const float dialogW = std::min(dp(480), W - dp(32));
        const float dialogH = dp(190);
        const float x = (W - dialogW) * 0.5f;
        const float y = (H - dialogH) * 0.5f;
        fillRoundedRect(renderer, x, y, dialogW, dialogH, dp(28), kNightLo);
        const int target = coreKeyboardTargetAt(
            coreId != nullptr ? coreId : "", menu.selection());
        const std::string title =
            std::string("Map ") + keyboardControlLabel(coreId, target);
        drawText(renderer, x + dp(28), y + dp(28), title.c_str(), dp(24),
                 255, 255, 255, 255);
        drawText(renderer, x + dp(28), y + dp(82),
                 "Press a keyboard key.", dp(16),
                 kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255);
        drawText(renderer, x + dp(28), y + dp(136),
                 "Escape cancels", dp(13),
                 kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255);
        return;
    }

    const float columnW = std::min(dp(420), W - dp(64));
    const float columnH = dp(376);
    const float x = (W - columnW) * 0.5f;
    const float y = (H - columnH) * 0.5f;
    drawText(renderer, x, y, "Paused", dp(24), 255, 255, 255, 255);
    float buttonY = y + dp(52);
    const int mainMenuSelection = menu.state() == PauseMenuState::kQuitConfirm
        ? PauseMenu::kQuitItem
        : menu.selection();
    for (int i = 0; i < PauseMenu::kItemCount; ++i) {
        drawButton(*this, renderer, x, buttonY, columnW, dp(48), PauseMenu::itemLabel(i),
                   scale, mainMenuSelection == i, i == PauseMenu::kResumeItem);
        buttonY += dp(56);
    }

    if (menu.state() != PauseMenuState::kQuitConfirm) return;

    fillRect(renderer, 0, 0, W, H, kDialogScrim);
    const float dialogW = std::min(dp(440), W - dp(32));
    const float dialogH = dp(210);
    const float dx = (W - dialogW) * 0.5f;
    const float dy = (H - dialogH) * 0.5f;
    fillRoundedRect(renderer, dx, dy, dialogW, dialogH, dp(28), kNightLo);
    drawText(renderer, dx + dp(28), dy + dp(28), "Quit game?", dp(24),
             255, 255, 255, 255);
    drawText(renderer, dx + dp(28), dy + dp(78), "Are you sure you want to quit?", dp(14),
             kTextSecondary.r, kTextSecondary.g, kTextSecondary.b, 255);

    const float buttonW = dp(88);
    const float buttonH = dp(44);
    const float noX = dx + dialogW - dp(28) - buttonW * 2.0f - dp(8);
    const float yesX = dx + dialogW - dp(28) - buttonW;
    drawButton(*this, renderer, noX, dy + dialogH - dp(64), buttonW, buttonH, "No", scale,
               menu.selection() == PauseMenu::kConfirmNo);
    drawButton(*this, renderer, yesX, dy + dialogH - dp(64), buttonW, buttonH, "Yes", scale,
               menu.selection() == PauseMenu::kConfirmYes);
}

}  // namespace romm::player
