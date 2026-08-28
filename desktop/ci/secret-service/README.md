# Linux Secret Service token store (spike)

## Architecture

Three layers, all under `desktop/src/main/kotlin/com/romm/desktop/storage/secret/`:

- **Seam** — `SecretBackend.kt`: `state() / store() / retrieve() / delete() / deleteAll()`,
  synchronous, **never throws**. Failures map to `KeyringState` (`Available | Locked |
  Unavailable | Denied(reason)`) plus null/false results.
- **Adapter** — `SecretServiceClientTokenStore.kt`: implements the shared
  `ClientTokenStore` port on top of the seam (per-origin|username scoping, `scopeVersion` pinned to 2).
- **Transport** — `dbus/SecretServiceDbusBackend.kt`: freedesktop Secret Service
  (`org.freedesktop.secrets`) over the session bus via dbus-java 5.2.0 (pure Java + JNR unix-socket
  transport; inert on macOS). Calls: `NameHasOwner`, `ReadAlias`, `CreateCollection`,
  `OpenSession("plain")`, `Collection.CreateItem(replace=true)`, `Collection.SearchItems`,
  `Item.GetSecret`, `Item.Delete`, and the collection's `Locked` property. Secrets are UTF-8 with
  attributes `application=rommulus` + `scope=<origin|username>`.

## Flatpak access

There is **no secrets portal** — Flatpak talks to the host Secret Service directly over the session
bus, so the manifest must grant talk permission for that name specifically:

```json
{
  "finish-args": [
    "--talk-name=org.freedesktop.secrets"
  ]
}
```

Do **not** use `--socket=session-bus` (grants the whole session bus). If the permission is missing,
every call raises AccessDenied and the backend reports `KeyringState.Denied`.

## Running on Ubuntu (24.04)

```bash
desktop/ci/secret-service/run_conformance.sh    # mock daemon: available + locked modes
desktop/ci/secret-service/gnome-keyring-smoke.sh  # REAL gnome-keyring-daemon, then lock it
```

`run_conformance.sh` runs `mock_secret_service.py` (in-memory, env-driven via `ROM_SECRET_MODE`)
under `dbus-run-session`, then the Gradle conformance test in `available` and `locked` modes.
The smoke script starts a real `gnome-keyring-daemon --components=secrets`, exercises set/get/
delete through it, locks the collection, and verifies fail-closed behavior.

## Fail-closed matrix

| Condition | `state()` | read | write |
|---|---|---|---|
| no bus / name unowned / transport error | `Unavailable` | `null` | `Failure("secret service unavailable")` |
| collection locked (or a prompt would be needed) | `Locked` | `null` | `Failure("keyring locked")` |
| AccessDenied (e.g. Flatpak missing `--talk-name`) | `Denied(reason)` | `null` | `Failure("secret service denied: <reason>")` |

The state probe exists so the UI can show an actionable "unlock your keyring" error instead of a
silent token loss.

## Known limitations

- `scopeVersion` is **not persisted** as a D-Bus attribute; the adapter pins it to 2 on read.
- Unlocking is host-side (gnome-keyring password prompt). The backend never calls
  `Prompt.Prompt()` — a non-empty prompt path fails closed, and every D-Bus call is bounded by a
  5 s timeout so nothing can block the UI thread.
