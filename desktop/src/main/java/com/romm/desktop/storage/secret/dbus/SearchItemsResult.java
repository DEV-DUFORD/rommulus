package com.romm.desktop.storage.secret.dbus;

import java.util.List;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.Tuple;
import org.freedesktop.dbus.annotations.Position;

public final class SearchItemsResult extends Tuple {
    @Position(0)
    public final List<DBusPath> unlocked;

    @Position(1)
    public final List<DBusPath> locked;

    public SearchItemsResult(List<DBusPath> unlocked, List<DBusPath> locked) {
        this.unlocked = unlocked;
        this.locked = locked;
    }
}
