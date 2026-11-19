package com.agentgate.gateway.audit.repository;

import com.agentgate.gateway.audit.domain.AuditChainState;
import com.agentgate.gateway.audit.domain.AuditEvent;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for append-only audit log persistence and chain tip state management (FR7).
 */
public interface AuditRepository {

    /**
     * Acquires an exclusive row-level lock on the chain state tip (id = 1) for the duration of the transaction.
     */
    AuditChainState getChainStateForUpdate();

    /**
     * Reads current chain state tip without locking.
     */
    AuditChainState getChainState();

    /**
     * Inserts an immutable audit event into audit_log.
     */
    void insertAuditEvent(AuditEvent event);

    /**
     * Updates the sequence and tip hash of the chain state.
     */
    void updateChainState(long newSequence, String newHash);

    /**
     * Retrieves an audit event by sequence number.
     */
    Optional<AuditEvent> findBySequenceNumber(long sequenceNumber);

    /**
     * Retrieves all audit events ordered by sequence number ascending.
     */
    List<AuditEvent> findAllOrderBySequence();

    /**
     * Retrieves all audit events correlated by approval ID ordered by sequence ascending.
     */
    List<AuditEvent> findByApprovalId(String approvalId);

    /**
     * Counts total recorded audit events.
     */
    long count();

    /**
     * Cleans up all audit records and resets chain state to genesis. Intended for test isolation.
     */
    void resetToGenesis();
}
