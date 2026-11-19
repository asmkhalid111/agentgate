package com.agentgate.gateway.audit.util;

import com.agentgate.gateway.audit.domain.AuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CsvExportUtil Unit Tests (FR7 & CWE-1236 Formula Injection Defense)")
class CsvExportUtilTest {

    @Test
    @DisplayName("Should serialize audit events with correct CSV headers")
    void shouldSerializeHeadersAndRecords() {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        AuditEvent event = new AuditEvent(
                1L,
                now,
                "DIRECT_EXECUTION",
                "agent-finance-bot",
                "user-human-10",
                "agent-finance-bot",
                "orders_refund",
                "{\"order_id\":\"ord-123\",\"amount\":100}",
                "rule-refund-limit",
                "Refund Limit Rule",
                "ALLOW",
                null,
                "SUCCESS",
                25L,
                null,
                "0000000000000000000000000000000000000000000000000000000000000000",
                "hash123"
        );

        String csv = CsvExportUtil.toCsv(List.of(event));

        assertThat(csv).startsWith(CsvExportUtil.CSV_HEADER);
        assertThat(csv).contains("1,2026-11-20T00:00:00Z,DIRECT_EXECUTION,agent-finance-bot,user-human-10,agent-finance-bot,orders_refund");
        assertThat(csv).contains("\"{\"\"order_id\"\":\"\"ord-123\"\",\"\"amount\"\":100}\"");
        assertThat(csv).contains("rule-refund-limit,Refund Limit Rule,ALLOW,,SUCCESS,25,,0000000000000000000000000000000000000000000000000000000000000000,hash123");
    }

    @Test
    @DisplayName("Should escape commas, quotes, and newlines per RFC 4180")
    void shouldEscapeSpecialCsvCharacters() {
        assertThat(CsvExportUtil.escapeCell("simple text")).isEqualTo("simple text");
        assertThat(CsvExportUtil.escapeCell("text with, comma")).isEqualTo("\"text with, comma\"");
        assertThat(CsvExportUtil.escapeCell("text with \"double quotes\"")).isEqualTo("\"text with \"\"double quotes\"\"\"");
        assertThat(CsvExportUtil.escapeCell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(CsvExportUtil.escapeCell("line1\r\nline2")).isEqualTo("\"line1\r\nline2\"");
        assertThat(CsvExportUtil.escapeCell(null)).isEmpty();
    }

    @Test
    @DisplayName("Should neutralize dangerous formula operators (=, +, -, @, |, %) with single quote prefix (CWE-1236)")
    void shouldNeutralizeFormulaInjectionOperators() {
        assertThat(CsvExportUtil.escapeCell("=cmd|' /C calc'!A0")).isEqualTo("\"'=cmd|' /C calc'!A0\"");
        assertThat(CsvExportUtil.escapeCell("@SUM(1,2)")).isEqualTo("\"'@SUM(1,2)\"");
        assertThat(CsvExportUtil.escapeCell("+12345")).isEqualTo("\"'+12345\"");
        assertThat(CsvExportUtil.escapeCell("-500")).isEqualTo("\"'-500\"");
        assertThat(CsvExportUtil.escapeCell("|something")).isEqualTo("\"'|something\"");
        assertThat(CsvExportUtil.escapeCell("%0A123")).isEqualTo("\"'%0A123\"");
    }

    @Test
    @DisplayName("Should neutralize formula operators preceded by whitespace or control characters")
    void shouldNeutralizeFormulaOperatorsWithLeadingWhitespaceOrControlChars() {
        assertThat(CsvExportUtil.escapeCell("   =1+1")).isEqualTo("\"'   =1+1\"");
        assertThat(CsvExportUtil.escapeCell("\t@SUM(A1:A10)")).isEqualTo("\"'\t@SUM(A1:A10)\"");
        assertThat(CsvExportUtil.escapeCell("\r\n+4412345")).isEqualTo("\"'\r\n+4412345\"");
        assertThat(CsvExportUtil.escapeCell("\t-999")).isEqualTo("\"'\t-999\"");
    }
}
