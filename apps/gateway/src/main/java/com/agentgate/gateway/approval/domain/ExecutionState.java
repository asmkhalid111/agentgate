package com.agentgate.gateway.approval.domain;

/**
 * Execution states for approved tool calls (FR4).
 * Kept strictly separate from human approval decision status.
 */
public enum ExecutionState {
    /**
     * Initial state after approval; awaiting background dispatch.
     */
    NOT_DISPATCHED,

    /**
     * Execution ownership claimed atomically via CAS; downstream dispatch in progress.
     */
    DISPATCHED,

    /**
     * Downstream tool executed successfully; sanitized result persisted.
     */
    COMPLETED,

    /**
     * Pre-dispatch governance checks failed, or downstream tool returned an error.
     */
    FAILED,

    /**
     * Ambiguous execution state (e.g. timeout, network disconnect).
     * Never automatically retried.
     */
    INDETERMINATE
}
