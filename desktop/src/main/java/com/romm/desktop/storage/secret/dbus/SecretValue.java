package com.romm.desktop.storage.secret.dbus;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.Position;

public final class SecretValue extends Struct {
    @Position(0)
    public final DBusPath session;

    @Position(1)
    public final byte[] parameters;

    @Position(2)
    public final byte[] value;

    @Position(3)
    public final String contentType;

    public SecretValue(DBusPath session, byte[] parameters, byte[] value, String contentType) {
        this.session = session;
        this.parameters = parameters;
        this.value = value;
        this.contentType = contentType;
    }
}
