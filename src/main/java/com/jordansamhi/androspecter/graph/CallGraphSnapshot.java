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
     * Captures a stable graph while the caller owns the current Soot analysis.
     * Does not load method bodies or retain any Soot object in the returned snapshot.
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

    public UUID analysisId() {
        return analysisId;
    }

    public Set<String> methods() {
        return methods;
    }

    public List<CallGraphEdge> edges() {
        return edges;
    }

    public Set<String> callersOf(String method) {
        return callers.getOrDefault(Objects.requireNonNull(method, "method"), Set.of());
    }

    public Set<String> calleesOf(String method) {
        return callees.getOrDefault(Objects.requireNonNull(method, "method"), Set.of());
    }

    public List<CallGraphEdge> incomingEdges(String method) {
        return incoming.getOrDefault(Objects.requireNonNull(method, "method"), List.of());
    }

    public List<CallGraphEdge> outgoingEdges(String method) {
        return outgoing.getOrDefault(Objects.requireNonNull(method, "method"), List.of());
    }

    public boolean containsMethod(String method) {
        return methods.contains(Objects.requireNonNull(method, "method"));
    }

    public boolean containsEdge(String source, String target) {
        return calleesOf(source).contains(Objects.requireNonNull(target, "target"));
    }
}
