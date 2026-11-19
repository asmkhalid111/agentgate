package com.agentgate.gateway.approval.controller;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.dto.*;
import com.agentgate.gateway.approval.exception.ApprovalNotFoundException;
import com.agentgate.gateway.approval.exception.InvalidApprovalStateException;
import com.agentgate.gateway.approval.security.HumanApproverValidator;
import com.agentgate.gateway.approval.service.ApprovalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;

/**
 * REST API for Human Approval decisions on high-risk tool calls (FR4).
 * Restricted strictly to authenticated human approvers; automated agent service-account tokens are rejected.
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final HumanApproverValidator humanApproverValidator;

    public ApprovalController(ApprovalService approvalService, HumanApproverValidator humanApproverValidator) {
        this.approvalService = approvalService;
        this.humanApproverValidator = humanApproverValidator;
    }

    /**
     * Lists approval requests in paginated form. Defaults to PENDING requests.
     */
    @GetMapping
    public ResponseEntity<PagedApprovalResponse> listApprovals(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            Authentication authentication) {

        humanApproverValidator.verifyHumanApprover(authentication);

        ApprovalStatus approvalStatus = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                approvalStatus = ApprovalStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                approvalStatus = ApprovalStatus.PENDING;
            }
        }

        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(100, Math.max(1, size));

        PagedApprovalResponse response = approvalService.findPaged(approvalStatus, boundedPage, boundedSize);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves full details of a specific approval request, including structured original arguments.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApprovalDetailResponse> getApproval(
            @PathVariable String id,
            Authentication authentication) {

        humanApproverValidator.verifyHumanApprover(authentication);

        ApprovalRequest request = approvalService.findById(id)
                .orElseThrow(() -> new ApprovalNotFoundException("Approval request not found: " + id));

        return ResponseEntity.ok(approvalService.toDetailResponse(request));
    }

    /**
     * Approves a pending request. Approver identity is derived strictly from verified authentication.
     * Optionally accepts edited arguments, which are stored separately from original arguments.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApprovalDetailResponse> approve(
            @PathVariable String id,
            @RequestBody(required = false) ApproveDecisionRequest request,
            Authentication authentication) {

        String humanApprover = humanApproverValidator.verifyHumanApprover(authentication);

        ApprovalRequest approved = approvalService.approve(
                id,
                humanApprover,
                request != null ? request.editedArguments() : null
        );

        return ResponseEntity.ok(approvalService.toDetailResponse(approved));
    }

    /**
     * Rejects a pending request with a mandatory reason.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApprovalDetailResponse> reject(
            @PathVariable String id,
            @Valid @RequestBody RejectDecisionRequest request,
            Authentication authentication) {

        String humanApprover = humanApproverValidator.verifyHumanApprover(authentication);

        ApprovalRequest rejected = approvalService.reject(
                id,
                humanApprover,
                request.reason()
        );

        return ResponseEntity.ok(approvalService.toDetailResponse(rejected));
    }

    // --- Scoped ProblemDetail Exception Handlers (Reviewer Correction B) ---

    @ExceptionHandler(ApprovalNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ApprovalNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Approval Request Not Found");
        problem.setType(URI.create("https://agentgate.dev/errors/approval-not-found"));
        problem.setProperty("errorCode", "APPROVAL_NOT_FOUND");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(InvalidApprovalStateException.class)
    public ResponseEntity<ProblemDetail> handleInvalidState(InvalidApprovalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid Approval State Transition");
        problem.setType(URI.create("https://agentgate.dev/errors/invalid-approval-state"));
        problem.setProperty("errorCode", "INVALID_APPROVAL_STATE");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation Failed");
        problem.setType(URI.create("https://agentgate.dev/errors/bad-request"));
        problem.setProperty("errorCode", "VALIDATION_FAILED");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Input");
        problem.setType(URI.create("https://agentgate.dev/errors/bad-request"));
        problem.setProperty("errorCode", "INVALID_ARGUMENT");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }
}
