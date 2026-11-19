package com.agentgate.gateway.audit.service;

import com.agentgate.gateway.audit.domain.AuditChainState;
import com.agentgate.gateway.audit.domain.AuditEvent;
import com.agentgate.gateway.audit.domain.AuditVerificationResult;
import com.agentgate.gateway.audit.repository.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Verification engine for cryptographically validating the audit hash chain (FR7).
 * <p>
 * Verifies:
 * 1. Genesis hash initialization (64 zeros).
 * 2. Contiguous, strictly increasing sequence numbers without gaps.
 * 3. Exact SHA-256 parent hash linkage (prev_record_hash).
 * 4. Exact recalculation of canonical SHA-256 record hashes across all fields.
 * 5. Concordance between the persisted chain state tip and the latest audit log entry.
 */
@Service
public class AuditVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AuditVerificationService.class);

    private final AuditRepository auditRepository;
    private final AuditHashEngine auditHashEngine;

    public AuditVerificationService(AuditRepository auditRepository, AuditHashEngine auditHashEngine) {
        this.auditRepository = auditRepository;
        this.auditHashEngine = auditHashEngine;
    }

    /**
     * Performs a full cryptographic verification of the audit trail.
     * Enclosed in a REPEATABLE_READ transaction snapshot to guarantee that concurrent appends
     * do not produce false tip or sequence discrepancies.
     *
     * @return AuditVerificationResult indicating validity, total checked records, and corruption details if tampered.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditVerificationResult verifyIntegrity() {
        AuditChainState tip = auditRepository.getChainState();
        List<AuditEvent> events = auditRepository.findAllOrderBySequence();

        if (events.isEmpty()) {
            if (tip.lastSequence() == 0 && AuditChainState.GENESIS_HASH.equals(tip.lastHash())) {
                return AuditVerificationResult.success(0);
            }
            log.error("Audit verification failed: log is empty but chain state tip is seq={}, hash={}",
                    tip.lastSequence(), tip.lastHash());
            return AuditVerificationResult.corrupted(0L,
                    "Empty audit log but tip sequence is " + tip.lastSequence(), 0);
        }

        AuditEvent lastEvent = events.get(events.size() - 1);
        if (tip.lastSequence() != lastEvent.sequenceNumber()) {
            log.error("Audit verification failed: tip sequence mismatch (state={}, log={})",
                    tip.lastSequence(), lastEvent.sequenceNumber());
            return AuditVerificationResult.corrupted(tip.lastSequence(),
                    "Tip sequence mismatch: state=" + tip.lastSequence() + ", log=" + lastEvent.sequenceNumber(),
                    events.size());
        }

        if (!Objects.equals(tip.lastHash(), lastEvent.recordHash())) {
            log.error("Audit verification failed: tip hash mismatch (state={}, log={})",
                    tip.lastHash(), lastEvent.recordHash());
            return AuditVerificationResult.corrupted(lastEvent.sequenceNumber(),
                    "Tip hash mismatch: state=" + tip.lastHash() + ", log=" + lastEvent.recordHash(),
                    events.size());
        }

        for (int i = 0; i < events.size(); i++) {
            AuditEvent event = events.get(i);
            long expectedSeq = i + 1;

            if (event.sequenceNumber() != expectedSeq) {
                log.error("Audit verification failed: sequence discontinuity at index {}, expected {}, found {}",
                        i, expectedSeq, event.sequenceNumber());
                return AuditVerificationResult.corrupted(event.sequenceNumber(),
                        "Sequence gap or discontinuity: expected " + expectedSeq + " but found " + event.sequenceNumber(),
                        i);
            }

            String expectedPrevHash = (i == 0)
                    ? AuditChainState.GENESIS_HASH
                    : events.get(i - 1).recordHash();

            if (!Objects.equals(event.prevRecordHash(), expectedPrevHash)) {
                log.error("Audit verification failed: broken hash chain at seq {}, expected prevHash={}, actual={}",
                        event.sequenceNumber(), expectedPrevHash, event.prevRecordHash());
                return AuditVerificationResult.corrupted(event.sequenceNumber(),
                        "Broken hash chain: expected prev_record_hash=" + expectedPrevHash + " but found " + event.prevRecordHash(),
                        i);
            }

            String computedHash = auditHashEngine.computeRecordHash(event);
            if (!Objects.equals(event.recordHash(), computedHash)) {
                log.error("Audit verification failed: tampered record at seq {}, computed={}, stored={}",
                        event.sequenceNumber(), computedHash, event.recordHash());
                return AuditVerificationResult.corrupted(event.sequenceNumber(),
                        "Tampered record: computed hash=" + computedHash + " but stored=" + event.recordHash(),
                        i);
            }
        }

        log.info("Audit hash chain integrity verified successfully ({} records checked)", events.size());
        return AuditVerificationResult.success(events.size());
    }
}
