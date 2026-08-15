#!/usr/bin/env python3
"""In-memory `org.freedesktop.secrets` mock for the rommulus conformance test.

Implements ONLY the methods SecretServiceDbusBackend.kt actually invokes:

  org.freedesktop.Secret.Service (at /org/freedesktop/secrets)
    - ReadAlias(s) -> o
    - CreateCollection(a{sv}, s) -> (o collection, o prompt)
    - OpenSession(s, ay) -> o                      ("plain" only)
  org.freedesktop.DBus.Properties (on the collection object)
    - Get(ss) -> v                                 ("Locked", "Label")
    - GetAll(s) -> a{sv}
  org.freedesktop.Secret.Collection (on the collection object)
    - CreateItem(a{sv}, (oayays), b) -> (o item, o prompt)
    - SearchItems(a{ss}) -> (ao unlocked, ao locked)
  org.freedesktop.Secret.Item (on each item object)
    - Delete()
    - GetSecret(o session) -> (oayays)

Behavior is driven by $ROM_SECRET_MODE:
  unavailable -> exit(0) before owning the name (backend sees "name has no owner")
  locked      -> own the name; a pre-created "default" collection reports Locked=true,
                 CreateItem answers with a non-empty prompt, GetSecret/Delete fail
  available   -> normal in-memory storage keyed by item attributes (application, scope)

Requires: python3-dbus, python3-gi. Run under `dbus-run-session --` so this process
owns the session bus for the duration of the test run.
"""

import os
import sys

import dbus
import dbus.service
import dbus.mainloop.glib

# Must run BEFORE `dbus.SessionBus()` below: a connection is only attached to a main loop if
# one is already the default when it is created. Without this, constructing any
# dbus.service.Object (Service.__init__ -> add_to_connection) raises RuntimeError
# ("D-Bus connections must be attached to a main loop").
dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)

MODE = os.environ.get("ROM_SECRET_MODE", "available")
BUS_NAME = "org.freedesktop.secrets"
SERVICE_PATH = "/org/freedesktop/secrets"
COLLECTION_IFACE = "org.freedesktop.Secret.Collection"
PROMPT_PATH = "/org/freedesktop/secrets/prompt0"

ERROR_FAILED = "org.freedesktop.Secret.Error.Failed"
ERROR_INVALID_ARGS = "org.freedesktop.Secret.Error.InvalidArguments"


def log(msg: str) -> None:
    print(f"mock-secret-service: {msg}", file=sys.stderr, flush=True)


if MODE == "unavailable":
    # Do NOT own the name: the backend's NameHasOwner() must come back false.
    log("mode=unavailable: exiting without owning org.freedesktop.secrets")
    sys.exit(0)

if MODE not in ("available", "locked"):
    log(f"warning: unknown ROM_SECRET_MODE={MODE!r}, treating as available")
    MODE = "available"


class Item(dbus.service.Object):
    def __init__(self, bus_name, store, path):
        super().__init__(bus_name, path)
        # dbus-python 1.2.x Object exposes no public .object_path/.bus_name; track ours.
        self.path = path
        self._store = store
        self.label = ""
        self.attributes = {}
        self.value = b""
        self.content_type = "text/plain"

    @dbus.service.method("org.freedesktop.Secret.Item")
    def Delete(self):
        if self._store.locked:
            raise dbus.exceptions.DBusException(
                f"collection is locked", name=ERROR_FAILED)
        self._store.items.remove(self)
        log(f"deleted item {self.path}")

    @dbus.service.method("org.freedesktop.Secret.Item", in_signature="o",
                         out_signature="(oayays)")
    def GetSecret(self, session):
        if self._store.locked:
            raise dbus.exceptions.DBusException(
                "collection is locked", name=ERROR_FAILED)
        if str(session) not in self._store.sessions:
            raise dbus.exceptions.DBusException(
                f"invalid session {session}", name=ERROR_INVALID_ARGS)
        return (str(session), b"", self.value, self.content_type)


class Collection(dbus.service.Object):
    def __init__(self, bus_name, store, path, label, locked):
        super().__init__(bus_name, path)
        # dbus-python 1.2.x Object exposes no public .object_path/.bus_name; track ours.
        self.path = path
        self._store = store
        self.label = label
        self.locked = locked

    # --- org.freedesktop.DBus.Properties -------------------------------------

    @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="ss",
                         out_signature="v")
    def Get(self, interface_name, property_name):
        if interface_name != COLLECTION_IFACE:
            raise dbus.exceptions.DBusException(
                f"unknown interface {interface_name}", name=ERROR_INVALID_ARGS)
        if property_name == "Locked":
            return dbus.Boolean(self.locked)
        if property_name == "Label":
            return dbus.String(self.label)
        raise dbus.exceptions.DBusException(
            f"unknown property {property_name}",
            name="org.freedesktop.DBus.Error.PropertyNotFound")

    @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="s",
                         out_signature="a{sv}")
    def GetAll(self, interface_name):
        if interface_name != COLLECTION_IFACE:
            raise dbus.exceptions.DBusException(
                f"unknown interface {interface_name}", name=ERROR_INVALID_ARGS)
        return {"Locked": dbus.Boolean(self.locked), "Label": dbus.String(self.label)}

    # --- org.freedesktop.Secret.Collection ------------------------------------

    @dbus.service.method("org.freedesktop.Secret.Collection",
                         in_signature="a{sv}(oayays)b", out_signature="(oo)")
    def CreateItem(self, properties, secret, replace):
        session, _parameters, value, content_type = secret
        if self.locked:
            # A real daemon would demand an unlock prompt before writing.
            return ("/", PROMPT_PATH)
        if str(session) not in self._store.sessions:
            raise dbus.exceptions.DBusException(
                f"invalid session {session}", name=ERROR_INVALID_ARGS)

        label = properties.get("Label")
        attributes = dict(properties.get("Attributes") or {})
        label = str(label) if label is not None else ""
        attributes = {str(k): str(v) for k, v in attributes.items()}

        # replace=true: an existing item with identical attributes is overwritten.
        existing = next(
            (i for i in self._store.items if i.attributes == attributes), None)
        if existing is not None:
            item = existing
            log(f"replaced item {item.path} attrs={attributes}")
        else:
            item = Item(BUS_NAME_OBJ, self._store,
                        f"/org/freedesktop/secrets/item{self._store.next_item_id()}")
            self._store.items.append(item)
            log(f"created item {item.path} attrs={attributes}")
        item.label = label
        item.attributes = attributes
        item.value = bytes(value)
        item.content_type = str(content_type)
        return (item.path, "/")

    @dbus.service.method("org.freedesktop.Secret.Collection", in_signature="a{ss}",
                         out_signature="(aoao)")
    def SearchItems(self, attributes):
        if self.locked:
            # Metadata is unreadable while locked; nothing matches.
            return ([], [])
        wanted = {str(k): str(v) for k, v in attributes.items()}
        unlocked = [i.path for i in self._store.items
                    if all(i.attributes.get(k) == v for k, v in wanted.items())]
        log(f"search {wanted} -> {unlocked}")
        return (unlocked, [])


class SecretServiceMock:
    """Holds state shared by the service/collection/item objects."""

    def __init__(self):
        self.sessions = set()
        self._session_counter = 0
        self._item_counter = 0
        self.items = []
        self.aliases = {}
        self.collection = None
        # In locked mode a pre-existing "default" collection exists and is locked,
        # mirroring a keyring that was created earlier and then locked.
        if MODE == "locked":
            self._create_collection("rommulus", "default", locked=True)

    def next_item_id(self):
        self._item_counter += 1
        return self._item_counter

    @property
    def locked(self):
        return bool(self.collection is not None and self.collection.locked)

    def _create_collection(self, label, alias=None, locked=False):
        path = f"/org/freedesktop/secrets/collection{len(self.aliases)}"
        # Must be a BusName (not the BUS_NAME string): dbus-python 1.2.x Object.__init__
        # hands its first argument to add_to_connection(), and a str has no
        # _register_object_path -> AttributeError on every CreateCollection/CreateItem.
        col = Collection(BUS_NAME_OBJ, self, path, label, locked=locked)
        if alias:
            self.aliases[alias] = col.path
        if self.collection is None:
            self.collection = col
        log(f"created collection {path} alias={alias!r} label={label!r} locked={locked}")
        return col

    def open_session(self, algorithm, _input):
        if str(algorithm) != "plain":
            raise dbus.exceptions.DBusException(
                f"unsupported session algorithm {algorithm}", name=ERROR_INVALID_ARGS)
        self._session_counter += 1
        path = f"/org/freedesktop/secrets/session{self._session_counter}"
        self.sessions.add(path)
        log(f"opened session {path}")
        return path


BUS = dbus.SessionBus()
BUS_NAME_OBJ = dbus.service.BusName(BUS_NAME, BUS)
STORE = SecretServiceMock()  # must come after the bus exists (locked mode pre-creates a collection)


class Service(dbus.service.Object):
    def __init__(self):
        super().__init__(BUS_NAME_OBJ, SERVICE_PATH)

    @dbus.service.method("org.freedesktop.Secret.Service", in_signature="s",
                         out_signature="o")
    def ReadAlias(self, alias):
        path = STORE.aliases.get(str(alias))
        log(f"ReadAlias({alias!r}) -> {path or '/'}")
        return path or "/"

    @dbus.service.method("org.freedesktop.Secret.Service", in_signature="a{sv}s",
                         out_signature="(oo)")
    def CreateCollection(self, properties, alias):
        label = properties.get("Label")
        label = str(label) if label is not None else "new collection"
        alias = str(alias)
        if alias in STORE.aliases:
            return (STORE.aliases[alias], "/")
        col = STORE._create_collection(label, alias=alias or None, locked=False)
        return (col.path, "/")

    @dbus.service.method("org.freedesktop.Secret.Service", in_signature="say",
                         out_signature="o")
    def OpenSession(self, algorithm, _input):
        return STORE.open_session(algorithm, _input)


Service()
log(f"ready on {BUS_NAME} at {SERVICE_PATH} (mode={MODE})")

import gi  # noqa: E402
gi.require_version("GLib", "2.0")
from gi.repository import GLib  # noqa: E402

GLib.MainLoop().run()
