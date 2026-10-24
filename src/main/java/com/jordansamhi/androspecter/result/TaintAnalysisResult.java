package com.jordansamhi.androspecter.result;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable taint result independent of Soot's global state. Available findings from
 * incomplete analyses are retained; only {@link AnalysisStatus#SUCCESS} with no findings
 * represents a reported successful analysis with no findings. A successful status does not
 * certify soundness or the absence of runtime information leaks. All component values are
 * immutable, safe to read across threads, and remain usable after the analysis closes or
 * Soot is reset. Lists retain their supplied order and duplicates; this record itself does
 * not sort, deduplicate, or recompute the completion state.
 *
 * @param analysisId non-null identifier linking this result to its analysis or caller-supplied run
 * @param status non-null completion state; check this before interpreting {@code findings}
 * @param findings non-null list of detached findings, with no null elements; copied to an
 *                 immutable list, which may be empty for any status
 * @param diagnostics non-null list of diagnostic strings, with no null elements; copied to an
 *                    immutable list, containing backend exceptions or termination information
 *                    when reported, rather than all backend log messages
 */
public record TaintAnalysisResult(UUID analysisId, AnalysisStatus status,
                                  List<TaintFinding> findings, List<String> diagnostics) {
    /**
     * Creates a result with defensive copies of both lists. The caller supplies the status;
     * it is not inferred from findings or diagnostics. For example, this constructor permits
     * a successful status with nonempty diagnostics. Inputs must remain stable during copying.
     *
     * @param analysisId non-null analysis identifier
     * @param status non-null completion state
     * @param findings non-null detached findings; null elements are not allowed
     * @param diagnostics non-null diagnostic strings; null elements are not allowed
     * @throws NullPointerException if any argument or list element is null
     */
    public TaintAnalysisResult {
        Objects.requireNonNull(analysisId, "analysisId");
        Objects.requireNonNull(status, "status");
        findings = List.copyOf(findings);
        diagnostics = List.copyOf(diagnostics);
    }

    /**
     * Reports whether the result has a successful completion state, independently of finding
     * count or diagnostics. This does not mean the result is free of reported flows.
     *
     * @return {@code true} exactly when {@link #status()} is {@link AnalysisStatus#SUCCESS}
     */
    public boolean isSuccess() {
        return status == AnalysisStatus.SUCCESS;
    }
}
