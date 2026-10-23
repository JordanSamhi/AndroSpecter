package com.jordansamhi.androspecter.result;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaintAnalysisResultTest {
    @Test
    void constructorCopiesNestedCollections() {
        List<TaintPathStep> path = new ArrayList<>();
        path.add(new TaintPathStep("<C: void m()>", "return"));
        TaintEndpoint endpoint = new TaintEndpoint("source", "<C: void m()>", "return");
        TaintFinding finding = new TaintFinding(endpoint, endpoint, path, true);
        List<TaintFinding> findings = new ArrayList<>(List.of(finding));
        List<String> diagnostics = new ArrayList<>(List.of("example diagnostic"));
        TaintAnalysisResult result = new TaintAnalysisResult(UUID.randomUUID(), AnalysisStatus.SUCCESS,
                findings, diagnostics);
        path.clear();
        findings.clear();
        diagnostics.clear();
        assertEquals(1, result.findings().size());
        assertEquals(1, result.findings().get(0).path().size());
        assertEquals(List.of("example diagnostic"), result.diagnostics());
        assertThrows(UnsupportedOperationException.class, result.diagnostics()::clear);
    }

    @Test
    void emptyFailuresAreNeverFormattedAsSuccessfulEmptyReports() {
        for (AnalysisStatus status : List.of(AnalysisStatus.FAILED, AnalysisStatus.TIMED_OUT, AnalysisStatus.CANCELLED)) {
            TaintAnalysisResult result = new TaintAnalysisResult(UUID.randomUUID(), status, List.of(), List.of("reason"));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> TaintResultFormatter.format(result));
            assertTrue(failure.getMessage().contains(status.name()));
            assertTrue(failure.getMessage().contains("reason"));
        }
        assertTrue(TaintResultFormatter.format(new TaintAnalysisResult(UUID.randomUUID(),
                AnalysisStatus.SUCCESS, List.of(), List.of())).isEmpty());
    }
}
