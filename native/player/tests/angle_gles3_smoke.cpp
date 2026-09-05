#include <SDL3/SDL.h>
#include <GLES3/gl32.h>

#include <cstdio>
#include <cstring>

namespace {

bool fail(const char* operation) {
    std::fprintf(stderr, "ANGLE_GLES3_SMOKE_FAIL operation=%s sdl_error=%s\n",
                 operation, SDL_GetError());
    return false;
}

bool compileShader(GLenum type, const char* source, GLuint* shaderOut) {
    const GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        GLchar log[1024] = {};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        std::fprintf(stderr, "ANGLE_GLES3_SMOKE_FAIL operation=compile_shader log=%s\n", log);
        glDeleteShader(shader);
        return false;
    }
    *shaderOut = shader;
    return true;
}

}  // namespace

int main() {
    SDL_SetAppMetadata("rommulus_angle_gles3_smoke", "0.1", "com.romm.angle-smoke");
    SDL_SetHint(SDL_HINT_OPENGL_ES_DRIVER, "1");
    SDL_SetHint(SDL_HINT_VIDEO_WIN_D3DCOMPILER, "none");
    if (!SDL_Init(SDL_INIT_VIDEO)) {
        std::fprintf(stderr, "ANGLE_GLES3_SMOKE_FAIL operation=SDL_Init error=%s\n",
                     SDL_GetError());
        return 1;
    }

    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
    SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);

    SDL_Window* window = SDL_CreateWindow(
        "RomMulus ANGLE GLES3 smoke", 64, 64,
        SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
    if (window == nullptr) {
        std::fprintf(stderr,
                     "ANGLE_GLES3_SMOKE_FAIL operation=SDL_CreateWindow error=%s\n",
                     SDL_GetError());
        SDL_Quit();
        return 1;
    }

    SDL_GLContext context = SDL_GL_CreateContext(window);
    if (context == nullptr || !SDL_GL_MakeCurrent(window, context)) {
        fail("SDL_GL_CreateContext");
        if (context != nullptr) SDL_GL_DestroyContext(context);
        SDL_DestroyWindow(window);
        SDL_Quit();
        return 1;
    }

    int profile = 0;
    int major = 0;
    int minor = 0;
    SDL_GL_GetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, &profile);
    SDL_GL_GetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, &major);
    SDL_GL_GetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, &minor);
    const char* vendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const char* shading = reinterpret_cast<const char*>(glGetString(GL_SHADING_LANGUAGE_VERSION));
    const bool isAngle = renderer != nullptr && std::strstr(renderer, "ANGLE") != nullptr;
    const bool isGles3 =
        profile == SDL_GL_CONTEXT_PROFILE_ES &&
        major >= 3;
    if (!isAngle || !isGles3) {
        std::fprintf(
            stderr,
            "ANGLE_GLES3_SMOKE_FAIL operation=context_identity profile=%d "
            "version=%d.%d vendor=%s renderer=%s gl_version=%s\n",
            profile, major, minor, vendor ? vendor : "(null)",
            renderer ? renderer : "(null)", version ? version : "(null)");
        SDL_GL_DestroyContext(context);
        SDL_DestroyWindow(window);
        SDL_Quit();
        return 1;
    }
    std::printf(
        "ANGLE_GLES3_CONTEXT profile=ES version=%d.%d vendor=\"%s\" "
        "renderer=\"%s\" gl_version=\"%s\" glsl=\"%s\"\n",
        major, minor, vendor ? vendor : "(null)", renderer ? renderer : "(null)",
        version ? version : "(null)", shading ? shading : "(null)");
    std::fflush(stdout);

    static constexpr const char* kVertexShader = R"(#version 300 es
        const vec2 positions[3] = vec2[3](
            vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0)
        );
        void main() { gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0); }
    )";
    static constexpr const char* kFragmentShader = R"(#version 300 es
        precision highp float;
        out vec4 color;
        void main() { color = vec4(0.25, 0.5, 0.75, 1.0); }
    )";

    GLuint vertexShader = 0;
    GLuint fragmentShader = 0;
    GLuint program = 0;
    GLuint framebuffer = 0;
    GLuint texture = 0;
    GLuint vertexArray = 0;
    bool ok = compileShader(GL_VERTEX_SHADER, kVertexShader, &vertexShader) &&
              compileShader(GL_FRAGMENT_SHADER, kFragmentShader, &fragmentShader);
    if (ok) {
        program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);
        GLint linked = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
        ok = linked == GL_TRUE;
    }
    if (ok) {
        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(
            GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA,
            GL_UNSIGNED_BYTE, nullptr);
        glGenFramebuffers(1, &framebuffer);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(
            GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    }
    if (ok) {
        glGenVertexArrays(1, &vertexArray);
        glBindVertexArray(vertexArray);
        glViewport(0, 0, 4, 4);
        glUseProgram(program);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        unsigned char pixel[4] = {};
        glReadPixels(2, 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        ok = glGetError() == GL_NO_ERROR &&
             pixel[0] >= 62 && pixel[0] <= 66 &&
             pixel[1] >= 126 && pixel[1] <= 130 &&
             pixel[2] >= 190 && pixel[2] <= 194 &&
             pixel[3] == 255;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, 4, 4, 0, 0, 64, 64, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        SDL_GL_SwapWindow(window);
        ok = ok && glGetError() == GL_NO_ERROR;
    }

    std::printf(
        "ANGLE_GLES3_SMOKE_%s profile=ES version=%d.%d vendor=\"%s\" "
        "renderer=\"%s\" gl_version=\"%s\" glsl=\"%s\"\n",
        ok ? "PASS" : "FAIL", major, minor, vendor ? vendor : "(null)",
        renderer ? renderer : "(null)", version ? version : "(null)",
        shading ? shading : "(null)");

    if (vertexArray != 0) glDeleteVertexArrays(1, &vertexArray);
    if (framebuffer != 0) glDeleteFramebuffers(1, &framebuffer);
    if (texture != 0) glDeleteTextures(1, &texture);
    if (program != 0) glDeleteProgram(program);
    if (vertexShader != 0) glDeleteShader(vertexShader);
    if (fragmentShader != 0) glDeleteShader(fragmentShader);
    SDL_GL_DestroyContext(context);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return ok ? 0 : 1;
}
