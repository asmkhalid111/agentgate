package com.agentgate.gateway.audit.util;

import com.agentgate.gateway.audit.domain.AuditEvent;

import java.util.List;

/**
 * Utility for formatting audit logs as CSV compliant with RFC 4180
 * with defenses against Spreadsheet Formula Injection (CWE-1236).
 */
public final class CsvExportUtil {

    private CsvExportUtil() {}

    public static final String CSV_HEADER = "sequenceNumber,eventTimestamp,eventType,agentId,userId,actorId,toolName,redactedArgumentsJson,policyRuleId,policyRuleName,decision,approvalId,outcome,latencyMs,errorMessage,prevRecordHash,recordHash";

    /**
     * Serializes a list of audit events to safe CSV format.
     */
    public static String toCsv(List<AuditEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append(CSV_HEADER).append("\r\n");
        if (events != null) {
            for (AuditEvent e : events) {
                sb.append(e.sequenceNumber()).append(",")
                        .append(escapeCell(e.eventTimestamp() != null ? e.eventTimestamp().toString() : "")).append(",")
                        .append(escapeCell(e.eventType())).append(",")
                        .append(escapeCell(e.agentId())).append(",")
                        .append(escapeCell(e.userId())).append(",")
                        .append(escapeCell(e.actorId())).append(",")
                        .append(escapeCell(e.toolName())).append(",")
                        .append(escapeCell(e.redactedArgumentsJson())).append(",")
                        .append(escapeCell(e.policyRuleId())).append(",")
                        .append(escapeCell(e.policyRuleName())).append(",")
                        .append(escapeCell(e.decision())).append(",")
                        .append(escapeCell(e.approvalId())).append(",")
                        .append(escapeCell(e.outcome())).append(",")
                        .append(e.latencyMs()).append(",")
                        .append(escapeCell(e.errorMessage())).append(",")
                        .append(escapeCell(e.prevRecordHash())).append(",")
                        .append(escapeCell(e.recordHash())).append("\r\n");
            }
        }
        return sb.toString();
    }

    /**
     * Escapes a single cell according to RFC 4180 rules and neutralizes spreadsheet formula injection (CWE-1236).
     * <p>
     * If the cell begins with dangerous formula triggers ('=', '+', '-', '@', '|', '%', '\t', '\r'),
     * even after leading whitespace or control characters, a single quote (') is prepended to force
     * spreadsheet applications (Excel, Calc, Sheets) to treat the cell as literal plain text.
     */
    public static String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        String s = value;

        // Check for formula triggers, accounting for leading whitespace or control characters
        String stripped = s.stripLeading();
        boolean isFormula = false;
        if (!stripped.isEmpty()) {
            char firstChar = stripped.charAt(0);
            if (firstChar == '=' || firstChar == '+' || firstChar == '-' ||
                    firstChar == '@' || firstChar == '|' || firstChar == '%') {
                isFormula = true;
            }
        } else if (!s.isEmpty()) {
            char rawFirst = s.charAt(0);
            if (rawFirst == '\t' || rawFirst == '\r' || rawFirst == '\n') {
                isFormula = true;
            }
        }

        if (isFormula) {
            // Neutralize formula execution by prefixing with a single quote
            s = "'" + s;
        }

        // Apply RFC 4180 quoting if the cell contains commas, quotes, newlines, or was formula-neutralized
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || isFormula) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
