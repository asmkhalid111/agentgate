package com.agentgate.gateway.killswitch.controller;

import com.agentgate.gateway.killswitch.domain.KillSwitch;
import com.agentgate.gateway.killswitch.domain.KillSwitchType;
import com.agentgate.gateway.killswitch.dto.KillSwitchRequest;
import com.agentgate.gateway.killswitch.dto.KillSwitchResponse;
import com.agentgate.gateway.killswitch.dto.PagedKillSwitchResponse;
import com.agentgate.gateway.identity.HumanAdminValidator;
import com.agentgate.gateway.killswitch.service.KillSwitchService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * REST API for Emergency Kill Switches (FR8).
 * Restricted strictly to verified human administrators with ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/admin/kill-switches")
public class KillSwitchAdminController {

    private final KillSwitchService killSwitchService;
    private final HumanAdminValidator humanAdminValidator;

    public KillSwitchAdminController(KillSwitchService killSwitchService, HumanAdminValidator humanAdminValidator) {
        this.killSwitchService = killSwitchService;
        this.humanAdminValidator = humanAdminValidator;
    }

    /**
     * Activates an emergency kill switch idempotently.
     */
    @PostMapping
    public ResponseEntity<KillSwitchResponse> activateSwitch(
            @Valid @RequestBody KillSwitchRequest request,
            Authentication authentication) {

        String adminUsername = humanAdminValidator.verifyHumanAdmin(authentication);
        KillSwitch ks = killSwitchService.activate(
                request.type(),
                request.target(),
                request.reason(),
                adminUsername
        );
        return ResponseEntity.ok(KillSwitchResponse.from(ks));
    }

    /**
     * Deactivates an emergency kill switch idempotently.
     */
    @DeleteMapping("/{type}/{target}")
    public ResponseEntity<KillSwitchResponse> deactivateSwitch(
            @PathVariable KillSwitchType type,
            @PathVariable String target,
            Authentication authentication) {

        String adminUsername = humanAdminValidator.verifyHumanAdmin(authentication);
        Optional<KillSwitch> deactivated = killSwitchService.deactivate(type, target, adminUsername);
        return deactivated
                .map(ks -> ResponseEntity.ok(KillSwitchResponse.from(ks)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Deactivates a switch when target is omitted (e.g. for GLOBAL_WRITE).
     */
    @DeleteMapping("/{type}")
    public ResponseEntity<KillSwitchResponse> deactivateSwitchWithoutTarget(
            @PathVariable KillSwitchType type,
            Authentication authentication) {
        return deactivateSwitch(type, "GLOBAL", authentication);
    }

    /**
     * Lists kill switches with optional active filter and pagination.
     */
    @GetMapping
    public ResponseEntity<PagedKillSwitchResponse> listSwitches(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            Authentication authentication) {

        humanAdminValidator.verifyHumanAdmin(authentication);

        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(100, Math.max(1, size));

        List<KillSwitch> list = killSwitchService.listSwitches(active, boundedPage, boundedSize);
        long total = killSwitchService.countSwitches(active);
        int totalPages = boundedSize > 0 ? (int) Math.ceil((double) total / boundedSize) : 0;

        List<KillSwitchResponse> content = list.stream()
                .map(KillSwitchResponse::from)
                .toList();

        return ResponseEntity.ok(new PagedKillSwitchResponse(content, boundedPage, boundedSize, total, totalPages));
    }

    /**
     * Retrieves status of a specific switch.
     */
    @GetMapping("/{type}/{target}")
    public ResponseEntity<KillSwitchResponse> getSwitch(
            @PathVariable KillSwitchType type,
            @PathVariable String target,
            Authentication authentication) {

        humanAdminValidator.verifyHumanAdmin(authentication);
        return killSwitchService.findSwitch(type, target)
                .map(ks -> ResponseEntity.ok(KillSwitchResponse.from(ks)))
                .orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Kill Switch Request");
        problem.setType(URI.create("https://agentgate.dev/errors/invalid-kill-switch-request"));
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation Failed");
        problem.setType(URI.create("https://agentgate.dev/errors/validation-failed"));
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.badRequest().body(problem);
    }
}
