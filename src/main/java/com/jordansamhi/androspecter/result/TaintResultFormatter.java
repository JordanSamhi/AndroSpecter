package com.jordansamhi.androspecter.result;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Text presentation for detached findings, also used by the legacy string-result API. */
public final class TaintResultFormatter {
    private TaintResultFormatter() {
    }

    /**
     * Formats a successful analysis using the legacy report layout. Identical textual
     * reports are deduplicated in encounter order. To display a finding from an incomplete
     * result, call {@link #formatFinding(TaintFinding)} explicitly instead.
     *
     * @param result non-null detached result with a successful completion state
     * @return immutable set of report strings, empty for a successful result with no findings
     * @throws NullPointerException if {@code result} is null
     * @throws IllegalStateException when the analysis failed, timed out, or was cancelled
     */
    public static Set<String> format(TaintAnalysisResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.isSuccess()) {
            throw new IllegalStateException("Taint analysis " + result.analysisId() + " ended with "
                    + result.status() + ": " + String.join("; ", result.diagnostics()));
        }
        Set<String> reports = new LinkedHashSet<>();
        for (TaintFinding finding : result.findings()) {
            reports.add(formatFinding(finding));
        }
        return Collections.unmodifiableSet(reports);
    }

    /**
     * Formats one finding without checking the containing analysis's completion state.
     * Missing paths and unknown enclosing methods are shown explicitly. The returned
     * text does not include the analysis status or diagnostics; present those separately
     * when rendering a finding from an incomplete analysis.
     *
     * @param finding non-null detached finding, including a partial finding if desired
     * @return non-null legacy report text with source, optional path, and sink
     * @throws NullPointerException if {@code finding} is null
     */
    public static String formatFinding(TaintFinding finding) {
        Objects.requireNonNull(finding, "finding");
        StringBuilder report = new StringBuilder("Found leak: \n");
        report.append("  - From ").append(finding.source().description()).append('\n');
        if (!finding.pathAvailable()) {
            report.append("    - Detailed path unavailable.\n");
        } else {
            report.append("    - Detailed path:\n");
            for (TaintPathStep step : finding.path()) {
                report.append("       ").append(step.statement()).append(" => in method: ")
                        .append(step.methodSignature() == null ? "<unknown>" : step.methodSignature()).append('\n');
            }
        }
        report.append("  - To ").append(finding.sink().description()).append('\n');
        return report.toString();
    }
}
