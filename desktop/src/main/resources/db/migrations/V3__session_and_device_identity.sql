-- Desktop SQLite schema version 3.
-- Adds the durable session-record and device-identity tables backing the Phase 6 portable
-- RomM client storage seams (plans/PHASE6.md §5 decision 2): session + device identity
-- persist in SQLite — authoritative state per LINUX_X64.md §9 rule 1 — NOT settings JSON.
-- No secret material is stored here: client tokens live exclusively in the platform Secret
-- Service (SecretServiceClientTokenStore); these tables hold only non-secret session facts
-- and UUIDs. Table/column names mirror the Android SessionStore/DeviceIdentityStore fields
-- for a 1:1 mental mapping, but this file is desktop-owned and evolves only through
-- numbered forward-only migrations.
-- Applied exactly once, forward-only, in its own transaction, after the pre-migration .bak backup.

-- Single-row store of the last verified RomM session (SessionStorage/SessionRecordStore).
CREATE TABLE session_records (
    origin TEXT PRIMARY KEY,
    username TEXT,
    verified_at_epoch_millis INTEGER NOT NULL,
    kiosk_mode INTEGER NOT NULL DEFAULT 0
);

-- Durable, server-scoped device identity: local installation UUID (generated once), the
-- pre-pairing (anonymous QR) installation UUID, and the RomM-assigned device id. One row
-- per canonical origin; username records the paired account scope.
CREATE TABLE device_identity (
    origin TEXT PRIMARY KEY,
    username TEXT,
    installation_id TEXT NOT NULL,
    pairing_installation_id TEXT,
    romm_device_id TEXT,
    updated_at_epoch_millis INTEGER
);
