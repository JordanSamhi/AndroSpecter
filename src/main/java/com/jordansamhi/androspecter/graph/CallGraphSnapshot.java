package com.jordansamhi.androspecter.graph;

import soot.SootMethod;
import soot.Unit;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Immutable indexed snapshot of all edges in a call graph, including framework and
 * generated entry points. Capture from Soot includes methods incident to edges; factories
 * can also retain explicitly supplied isolated methods. Snapshot data remains usable after G.reset().
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

    private CallGraphSnapshot(UUID analysisId, Set<String> nodes, List<CallGraphEdge> captured) {
        this.analysisId = Objects.requireNonNull(analysisId, "analysisId");
        captured.sort(Comparator.comparing(CallGraphEdge::source).thenComparing(CallGraphEdge::target)
                .thenComparingInt(CallGraphEdge::callSiteIndex)
                .thenComparing(CallGraphEdge::callSite, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(CallGraphEdge::kind));
        this.edges = List.copyOf(captured);
        Map<String, Set<String>> callers = new LinkedHashMap<>();
        Map<String, Set<String>> callees = new LinkedHashMap<>();
        Map<String, List<CallGraphEdge>> incoming = new LinkedHashMap<>();
        Map<String, List<CallGraphEdge>> outgoing = new LinkedHashMap<>();
        for (CallGraphEdge edge : edges) {
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
     * Creates a snapshot from detached edges without accessing Soot. Nodes are the union
     * of edge endpoints. Every edge occurrence is retained, including equal parallel edges.
     * Input collections may be changed after this method returns.
     *
     * @param analysisId non-null identity of the originating analysis
     * @param edges non-null collection of non-null detached edges
     * @return immutable snapshot with sorted nodes and edges
     * @throws NullPointerException if an argument or an edge is null
     */
    public static CallGraphSnapshot of(UUID analysisId, Collection<CallGraphEdge> edges) {
        Objects.requireNonNull(edges, "edges");
        List<CallGraphEdge> captured = new ArrayList<>(edges);
        Set<String> nodes = new TreeSet<>();
        for (CallGraphEdge edge : captured) {
            Objects.requireNonNull(edge, "edge");
            nodes.add(edge.source());
            nodes.add(edge.target());
        }
        return new CallGraphSnapshot(analysisId, nodes, captured);
    }

    /**
     * Creates a snapshot with an explicit node set, including isolated methods. Repeated
     * method signatures are deduplicated, while parallel edge occurrences are preserved.
     * Signatures and edge kinds are opaque identifiers here; this factory does not parse
     * signatures, resolve methods, or access Soot.
     *
     * @param analysisId non-null identity of the originating analysis
     * @param methods non-null collection of non-null method signatures
     * @param edges non-null collection of non-null edges whose endpoints both occur in methods
     * @return immutable snapshot independent of subsequent input collection changes
     * @throws NullPointerException if an argument, method signature, or edge is null
     * @throws IllegalArgumentException if an edge endpoint is absent from methods
     */
    public static CallGraphSnapshot of(UUID analysisId, Collection<String> methods,
                                       Collection<CallGraphEdge> edges) {
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(edges, "edges");
        Set<String> nodes = new TreeSet<>();
        for (String method : methods) {
            nodes.add(Objects.requireNonNull(method, "method"));
        }
        List<CallGraphEdge> captured = new ArrayList<>(edges);
        for (CallGraphEdge edge : captured) {
            Objects.requireNonNull(edge, "edge");
            if (!nodes.contains(edge.source()) || !nodes.contains(edge.target())) {
                throw new IllegalArgumentException("Edge endpoints must be present in methods: " + edge);
            }
        }
        return new CallGraphSnapshot(analysisId, nodes, captured);
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
        return of(analysisId, edges);
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
     * Returns all method nodes, including isolated nodes supplied to a factory or retained
     * by a subgraph. A snapshot captured directly from Soot includes only edge endpoints;
     * it does not enumerate all declarations in the APK.
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
     * Tests whether the method belongs to this snapshot, including an explicit isolated node.
     * This is not a test for whether a method is declared in the APK or loaded in Soot.
     *
     * @param method non-null full method signature
     * @return whether the signature is a node of this snapshot
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

    /**
     * Computes the forward reachability closure using the snapshot's indexes. The known
     * starting method is included, even if isolated. Cycles terminate and parallel edges
     * do not duplicate methods. This is call-graph reachability, not proof of executable
     * control flow or taint propagation; no hidden depth or size limit is imposed.
     *
     * @param source non-null starting method signature
     * @return immutable lexicographically ordered reachable nodes, empty for an unknown source
     * @throws NullPointerException if source is null
     */
    public Set<String> reachableFrom(String source) {
        return reachable(source, callees);
    }

    /**
     * Computes the reverse reachability closure: methods that can reach the target through
     * zero or more graph edges. A known target is included, even if isolated. The traversal
     * terminates on cycles and does not impose a hidden depth or size limit.
     *
     * @param target non-null destination method signature
     * @return immutable lexicographically ordered ancestor nodes, empty for an unknown target
     * @throws NullPointerException if target is null
     */
    public Set<String> reachableTo(String target) {
        return reachable(target, callers);
    }

    private Set<String> reachable(String start, Map<String, Set<String>> adjacency) {
        Objects.requireNonNull(start, "method");
        if (!methods.contains(start)) {
            return Set.of();
        }
        Set<String> visited = new TreeSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        visited.add(start);
        pending.add(start);
        while (!pending.isEmpty()) {
            for (String next : adjacency.getOrDefault(pending.remove(), Set.of())) {
                if (visited.add(next)) {
                    pending.add(next);
                }
            }
        }
        return Collections.unmodifiableSet(visited);
    }

    /**
     * Tests reachability through zero or more directed graph edges. A known node reaches
     * itself; an unknown signature does not, even when both arguments are equal.
     *
     * @param source non-null starting method signature
     * @param target non-null destination method signature
     * @return true if a graph path exists; this does not establish execution feasibility
     * @throws NullPointerException if either argument is null
     */
    public boolean canReach(String source, String target) {
        return !shortestPath(source, target).isEmpty();
    }

    /**
     * Finds a shortest directed path by number of edges using breadth-first search. The
     * returned list includes both endpoints, so its edge count is {@code size() - 1}.
     * Equal-length alternatives are resolved deterministically by visiting neighboring
     * method signatures in lexicographic order. Edge kinds and parallel call sites do not
     * affect path length; use a filtered subgraph to restrict eligible edges first.
     *
     * @param source non-null starting method signature
     * @param target non-null destination method signature
     * @return immutable method sequence; one node for a known source equal to target, or
     *         an empty list when either endpoint is unknown or no path exists
     * @throws NullPointerException if either argument is null
     */
    public List<String> shortestPath(String source, String target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (!methods.contains(source) || !methods.contains(target)) {
            return List.of();
        }
        Map<String, String> previous = new HashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        previous.put(source, null);
        pending.add(source);
        while (!pending.isEmpty()) {
            String current = pending.remove();
            if (current.equals(target)) {
                List<String> path = new ArrayList<>();
                for (String step = target; step != null; step = previous.get(step)) {
                    path.add(step);
                }
                Collections.reverse(path);
                return List.copyOf(path);
            }
            for (String next : callees.getOrDefault(current, Set.of())) {
                if (!previous.containsKey(next)) {
                    previous.put(next, current);
                    pending.add(next);
                }
            }
        }
        return List.of();
    }

    /**
     * Creates an induced subgraph containing selected methods and every edge between them.
     * Selected methods remain present even when isolated. The originating analysis identity
     * is retained and this snapshot is unchanged.
     *
     * @param methods predicate evaluated once for each node, in lexicographic order
     * @return immutable filtered graph, including equal parallel edges
     * @throws NullPointerException if methods is null
     * @throws RuntimeException if the predicate fails
     */
    public CallGraphSnapshot subgraph(Predicate<String> methods) {
        return subgraph(methods, edge -> true);
    }

    /**
     * Creates a subgraph with selected methods and eligible edges. The edge predicate is
     * evaluated only when both endpoints were selected, once per edge occurrence in snapshot
     * order. Selected nodes are retained even if all incident edges are rejected. Predicate
     * failures propagate without changing this snapshot; the originating analysis ID is kept.
     *
     * @param methods non-null node predicate, evaluated once per node in lexicographic order
     * @param edges non-null predicate restricting edges between selected nodes
     * @return immutable filtered graph preserving parallel-edge multiplicity
     * @throws NullPointerException if either predicate is null
     * @throws RuntimeException if a predicate fails
     */
    public CallGraphSnapshot subgraph(Predicate<String> methods, Predicate<CallGraphEdge> edges) {
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(edges, "edges");
        Set<String> selected = new TreeSet<>();
        for (String method : this.methods) {
            if (methods.test(method)) {
                selected.add(method);
            }
        }
        List<CallGraphEdge> retained = new ArrayList<>();
        for (CallGraphEdge edge : this.edges) {
            if (selected.contains(edge.source()) && selected.contains(edge.target()) && edges.test(edge)) {
                retained.add(edge);
            }
        }
        return of(analysisId, selected, retained);
    }
}
