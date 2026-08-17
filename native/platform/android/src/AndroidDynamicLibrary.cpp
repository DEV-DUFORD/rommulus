// AndroidDynamicLibrary.cpp — Android's DynamicLibrary (Phase 7 Wave 2).
#include <native/platform/android/AndroidDynamicLibrary.h>

#include <dlfcn.h>
#include <memory>

namespace romm::android {

namespace {

// Registers AndroidDynamicLibrary as the engine's dynamic library backend
// at library load time: static initializers in a shared library run when
// the library is loaded, before JNI_OnLoad, so core_library.cpp's
// construction point needs no changes.
struct DynamicLibraryRegistrar {
    DynamicLibraryRegistrar() {
        romm::dynamiclib::setFactory([] { return std::make_unique<AndroidDynamicLibrary>(); });
    }
};
const DynamicLibraryRegistrar kDynamicLibraryRegistrar;

}  // namespace

bool AndroidDynamicLibrary::open(const std::string& path) {
    if (handle_ != nullptr) close();
    handle_ = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    return handle_ != nullptr;
}

std::optional<void*> AndroidDynamicLibrary::resolve(const std::string& symbol) {
    if (handle_ == nullptr) return std::nullopt;
    dlerror(); // clear any pending error
    void* sym = dlsym(handle_, symbol.c_str());
    if (dlerror() != nullptr || sym == nullptr) return std::nullopt;
    return sym;
}

void AndroidDynamicLibrary::close() {
    if (handle_ == nullptr) return;
    dlclose(handle_);
    handle_ = nullptr;
}

std::string AndroidDynamicLibrary::lastError() const {
    const char* err = dlerror();
    return err != nullptr ? std::string(err) : std::string();
}

}  // namespace romm::android
