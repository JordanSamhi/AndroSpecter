package com.jordansamhi.androspecter.graph;

import soot.SootMethod;
import soot.Unit;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Immutable indexed snapshot of all edges in a call graph, including framework and
 * generated entry points. Nodes are methods incident to edges; isolated methods are not
 * part of Soot's edge-based CallGraph. Snapshot data remains usable after G.reset().
 * Parallel edges are retained as separate list entries, even when their detached values match.
 * Queries use indexes built during capture and return immutable collections. Method
 * arguments are full Jimple signatures, for example {@code <example.App: void run()>}.
 */
public final class CallGraphSnapshot {
    private final UUID analysisId;
    private final Set<String> methods;
    private final List<CallGraphEdge> edges;
    private final Map<String, Set<String>> callers;
    private final Map<String, Set<String>> callees;
    private final Map<String, List<CallGraphEdge>> incoming;
    private final Map<String, List<CallGraphEdge>> outgoing;

    private CallGraphSnapshot(UUID analysisId, List<CallGraphEdge> captured) {
        this.analysisId = Objects.requireNonNull(analysisId, "analysisId");
        captured.sort(Comparator.comparing(CallGraphEdge::source).thenComparing(CallGraphEdge::target)
                .thenComparingInt(CallGraphEdge::callSiteIndex)
                .thenComparing(CallGraphEdge::callSite, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(CallGraphEdge::kind));
        this.edges = List.copyOf(captured);
        Set<String> nodes = new TreeSet<>();
        Map<String, Set<String>> callers = new LinkedHashMap<>();
        Map<String, Set<String>> callees = new LinkedHashMap<>();
        Map<String, List<CallGraphEdge>> incoming = new LinkedHashMap<>();
        Map<String, List<CallGraphEdge>> outgoing = new LinkedHashMap<>();
        for (CallGraphEdge edge : edges) {
            nodes.add(edge.source());
            nodes.add(edge.target());
            callers.computeIfAbsent(edge.target(), key -> new LinkedHashSet<>()).add(edge.source());
            callees.computeIfAbsent(edge.source(), key -> new LinkedHashSet<>()).add(edge.target());
            incoming.computeIfAbsent(edge.target(), key -> new ArrayList<>()).add(edge);
            outgoing.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(edge);
        }
        this.methods = Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
        callers.replaceAll((key, values) -> Collections.unmodifiableSet(values));
        callees.replaceAll((key, values) -> Collections.unmodifiableSet(values));
        incoming.replaceAll((key, values) -> List.copyOf(values));
        outgoing.replaceAll((key, values) -> List.copyOf(values));
        this.callers = Collections.unmodifiableMap(callers);
        this.callees = Collections.unmodifiableMap(callees);
        this.incoming = Collections.unmodifiableMap(incoming);
        this.outgoing = Collections.unmodifiableMap(outgoing);
    }

    /**
     * Copies and indexes all edges while the caller owns a stable Soot analysis.
     * The caller must prevent graph/body mutation and Soot resets until capture returns;
     * this method does not acquire an analysis lock. Source positions are indexed from
     * active bodies only: capture does not load bodies or retain any Soot object.
     *
     * @param analysisId non-null identifier to associate with the captured graph
     * @param graph non-null live graph whose edges all have source and target methods
     * @return immutable, detached snapshot, independent of subsequent graph changes or resets
     * @throws NullPointerException if either argument or an edge's source or target method is null
     */
    public static CallGraphSnapshot capture(UUID analysisId, CallGraph graph) {
        Objects.requireNonNull(analysisId, "analysisId");
        Objects.requireNonNull(graph, "graph");
        List<CallGraphEdge> edges = new ArrayList<>();
        Map<SootMethod, Map<Unit, Integer>> positions = new IdentityHashMap<>();
        Map<SootMethod, String> signatures = new IdentityHashMap<>();
        Map<Unit, String> callSiteTexts = new IdentityHashMap<>();
        for (Edge edge : graph) {
            SootMethod source = Objects.requireNonNull(edge.src(), "Call graph edge has no source method");
            SootMethod target = Objects.requireNonNull(edge.tgt(), "Call graph edge has no target method");
            Unit callSite = edge.srcUnit();
            int index = -1;
            if (callSite != null) {
                Map<Unit, Integer> methodPositions = positions.computeIfAbsent(source, method -> {
                    Map<Unit, Integer> result = new IdentityHashMap<>();
                    if (method.hasActiveBody()) {
                        int ordinal = 0;
                        for (Unit unit : method.getActiveBody().getUnits()) {
                            result.put(unit, ordinal++);
                        }
                    }
                    return result;
                });
                index = methodPositions.getOrDefault(callSite, -1);
            }
            edges.add(new CallGraphEdge(signatures.computeIfAbsent(source, SootMethod::getSignature),
                    signatures.computeIfAbsent(target, SootMethod::getSignature),
                    callSite == null ? null : callSiteTexts.computeIfAbsent(callSite, Unit::toString),
                    edge.kind().name(), index));
        }
        return new CallGraphSnapshot(analysisId, edges);
    }

    /**
     * Returns the identifier supplied at capture, for associating graph and analysis results.
     *
     * @return non-null analysis identifier
     */
    public UUID analysisId() {
        return analysisId;
    }

    /**
     * Returns the unique source and target methods represented by the captured edges.
     * This includes framework and generated methods but excludes declared methods with no edge.
     *
     * @return immutable set of full method signatures in lexicographic order; empty for an empty graph
     */
    public Set<String> methods() {
        return methods;
    }

    /**
     * Returns every captured edge, retaining parallel entries even if their detached values match.
     * Edges are ordered by source, target, source-unit position, statement text, then kind.
     *
     * @return immutable ordered list of detached edges, possibly empty
     */
    public List<CallGraphEdge> edges() {
        return edges;
    }

    /**
     * Looks up distinct methods with an edge targeting the given method.
     * Use {@link #incomingEdges(String)} to inspect individual call sites and edge kinds.
     *
     * @param method non-null full target method signature; an unknown signature is allowed
     * @return immutable set of caller signatures, empty if no incoming edge exists
     * @throws NullPointerException if {@code method} is null
     */
    public Set<String> callersOf(String method) {
        return callers.getOrDefault(Objects.requireNonNull(method, "method"), Set.of());
    }

    /**
     * Looks up distinct methods targeted by an edge from the given method.
     * Use {@link #outgoingEdges(String)} to inspect individual call sites and edge kinds.
     *
     * @param method non-null full source method signature; an unknown signature is allowed
     * @return immutable set of callee signatures, empty if no outgoing edge exists
     * @throws NullPointerException if {@code method} is null
     */
    public Set<String> calleesOf(String method) {
        return callees.getOrDefault(Objects.requireNonNull(method, "method"), Set.of());
    }

    /**
     * Looks up all edges targeting the given method, including parallel entries.
     *
     * @param method non-null full target method signature; an unknown signature is allowed
     * @return immutable incoming edges in snapshot order, empty if none exist
     * @throws NullPointerException if {@code method} is null
     */
    public List<CallGraphEdge> incomingEdges(String method) {
        return incoming.getOrDefault(Objects.requireNonNull(method, "method"), List.of());
    }

    /**
     * Looks up all edges originating in the given method, including parallel entries.
     *
     * @param method non-null full source method signature; an unknown signature is allowed
     * @return immutable outgoing edges in snapshot order, empty if none exist
     * @throws NullPointerException if {@code method} is null
     */
    public List<CallGraphEdge> outgoingEdges(String method) {
        return outgoing.getOrDefault(Objects.requireNonNull(method, "method"), List.of());
    }

    /**
     * Tests whether the method occurs as a source or target of any captured edge.
     * This is not a test for whether a method is declared in the APK or loaded in Soot.
     *
     * @param method non-null full method signature
     * @return whether the signature is an incident node of this snapshot
     * @throws NullPointerException if {@code method} is null
     */
    public boolean containsMethod(String method) {
        return methods.contains(Objects.requireNonNull(method, "method"));
    }

    /**
     * Tests for at least one directed edge between two signatures, regardless of call site or kind.
     *
     * @param source non-null full source method signature
     * @param target non-null full target method signature
     * @return {@code true} if at least one captured edge connects {@code source} to {@code target}
     * @throws NullPointerException if either signature is null
     */
    public boolean containsEdge(String source, String target) {
        return calleesOf(source).contains(Objects.requireNonNull(target, "target"));
    }
}
