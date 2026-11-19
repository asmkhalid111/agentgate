-- Emergency Kill Switches Schema (FR8)
CREATE TABLE IF NOT EXISTS kill_switches (
    id VARCHAR(64) PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    target VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    reason TEXT NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_kill_switch_type_target UNIQUE (type, target)
);

CREATE INDEX IF NOT EXISTS idx_kill_switches_lookup ON kill_switches(active, type, target);
