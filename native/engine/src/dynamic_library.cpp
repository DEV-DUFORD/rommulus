// dynamic_library.cpp — registry backing romm::dynamiclib::setFactory()/
// create() (Phase 7 Wave 2).
#include <native/engine/DynamicLibrary.h>

#include <mutex>

namespace romm::dynamiclib {

namespace {

std::mutex& registryMutex() {
    static std::mutex mutex;
    return mutex;
}

std::function<std::unique_ptr<DynamicLibrary>()>& activeFactory() {
    static std::function<std::unique_ptr<DynamicLibrary>()> factory;
    return factory;
}

}  // namespace

void setFactory(std::function<std::unique_ptr<DynamicLibrary>()> factory) {
    std::lock_guard<std::mutex> lock(registryMutex());
    activeFactory() = std::move(factory);
}

std::unique_ptr<DynamicLibrary> create() {
    std::lock_guard<std::mutex> lock(registryMutex());
    if (activeFactory() == nullptr) return nullptr;
    return activeFactory()();
}

}  // namespace romm::dynamiclib
