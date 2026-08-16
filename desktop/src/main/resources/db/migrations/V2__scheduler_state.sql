-- Desktop SQLite schema version 2.
-- Adds a small key-value table for the in-process background sync scheduler's durable
-- retry state: persisted attempt count and next-attempt epoch ms (plans/LINUX_X64.md §10.5,
-- PHASE5.md item 7). The durable SQLite queue is the source of truth — closing the app may
-- delay an upload but must never lose the retry schedule, so it is persisted here.
-- Applied exactly once, forward-only, in its own transaction, after the pre-migration .bak backup.

CREATE TABLE scheduler_state (
    key TEXT PRIMARY KEY,
    value INTEGER NOT NULL
);
