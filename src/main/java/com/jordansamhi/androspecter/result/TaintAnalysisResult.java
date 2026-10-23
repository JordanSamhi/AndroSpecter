package com.jordansamhi.androspecter.result;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable result independent of Soot's global state. Findings from incomplete analyses
 * are retained; only SUCCESS with no findings represents a completed empty analysis.
 */
public record TaintAnalysisResult(UUID analysisId, AnalysisStatus status,
                                  List<TaintFinding> findings, List<String> diagnostics) {
    public TaintAnalysisResult {
        Objects.requireNonNull(analysisId, "analysisId");
        Objects.requireNonNull(status, "status");
        findings = List.copyOf(findings);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean isSuccess() {
        return status == AnalysisStatus.SUCCESS;
    }
}
