-- Approved Tool Execution State & Outcome Persistence (FR4)
ALTER TABLE approval_requests
    ADD COLUMN IF NOT EXISTS execution_state VARCHAR(20) NOT NULL DEFAULT 'NOT_DISPATCHED',
    ADD COLUMN IF NOT EXISTS executed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS execution_result_json TEXT,
    ADD COLUMN IF NOT EXISTS execution_error TEXT;

CREATE INDEX IF NOT EXISTS idx_approval_requests_execution_state ON approval_requests(execution_state);
