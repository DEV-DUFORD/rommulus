#pragma once

namespace romm::player {

class PresentationDirtyState {
public:
    void request() { dirty_ = true; }

    bool consume() {
        if (!dirty_) return false;
        dirty_ = false;
        return true;
    }

private:
    bool dirty_ = true;
};

}  // namespace romm::player
