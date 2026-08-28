-- Desktop SQLite schema version 1.
-- Independent of the Android Room schema (version 4); plans/LINUX_X64.md §10.2 rule 1.
-- Table/column names mirror the Android Room entities for a 1:1 mental mapping, but this
-- file is desktop-owned and evolves only through numbered forward-only migrations.
-- Applied exactly once, in its own transaction, after a pre-migration .bak backup.

CREATE TABLE save_replicas (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_key TEXT NOT NULL,
    user_key TEXT NOT NULL,
    rom_id INTEGER NOT NULL,
    rom_hash TEXT NOT NULL,
    slot TEXT NOT NULL,
    core_id TEXT NOT NULL,
    core_build_revision TEXT NOT NULL,
    expected_sram_size_bytes INTEGER,
    local_hash TEXT,
    local_size_bytes INTEGER,
    local_written_at_epoch_ms INTEGER,
    romm_save_id INTEGER,
    server_hash TEXT,
    server_size_bytes INTEGER,
    server_updated_at_epoch_ms INTEGER,
    sync_status TEXT NOT NULL DEFAULT 'UNSYNCED',
    last_error TEXT,
    CONSTRAINT uq_save_replicas_scope UNIQUE (server_key, user_key, rom_id, rom_hash, slot)
);

-- findByStatus(serverKey, userKey, status) lookup.
CREATE INDEX idx_save_replicas_status ON save_replicas (server_key, user_key, sync_status);

CREATE TABLE pending_operations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_key TEXT NOT NULL,
    user_key TEXT NOT NULL,
    rom_id INTEGER NOT NULL,
    rom_hash TEXT NOT NULL,
    slot TEXT NOT NULL,
    operation_type TEXT NOT NULL,
    local_generation_epoch_ms INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    last_http_code INTEGER,
    origin TEXT,
    upload_file_name TEXT,
    session_id INTEGER,
    negotiate_file_name TEXT,
    negotiate_core_id TEXT,
    negotiate_core_build_revision TEXT,
    created_at_epoch_ms INTEGER NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL
);

-- Deliberately NON-unique: mirrors the Android Room pending_operations index and allows
-- multiple generations of the same scope+type to coexist until deleteStaleForScope prunes
-- older non-terminal ones (the "replace, not stack" rule is enforced by callers).
CREATE INDEX idx_pending_operations_scope ON pending_operations (server_key, user_key, rom_id, rom_hash, slot, operation_type);

-- findByStatus(status) lookup.
CREATE INDEX idx_pending_operations_status ON pending_operations (status);

CREATE TABLE controller_bindings (
    core_id TEXT NOT NULL,
    player_index INTEGER NOT NULL,
    control_id TEXT NOT NULL,
    binding_slot INTEGER NOT NULL,
    binding_type TEXT NOT NULL,
    input_code INTEGER NOT NULL,
    polarity INTEGER,
    schema_version INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (core_id, player_index, control_id, binding_slot)
);

-- loadForCore / loadForPlayer lookups.
CREATE INDEX idx_controller_bindings_core ON controller_bindings (core_id);
CREATE INDEX idx_controller_bindings_player ON controller_bindings (core_id, player_index);
