package com.jordansamhi.androspecter.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soot.G;
import soot.Kind;
import soot.Modifier;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.VoidType;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CallGraphSnapshotTest {
    @BeforeEach
    @AfterEach
    void resetSoot() {
        G.reset();
    }

    private SootMethod method(SootClass owner, String name) {
        SootMethod method = new SootMethod(name, List.of(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method);
        return method;
    }

    @Test
    void preservesParallelEdgesAndIndexesDetachedCallsAfterReset() {
        SootClass owner = new SootClass("example.Calls");
        Scene.v().addClass(owner);
        SootMethod caller = method(owner, "caller");
        SootMethod callee = method(owner, "callee");
        SootMethod isolated = method(owner, "isolated");
        JimpleBody body = Jimple.v().newBody(caller);
        caller.setActiveBody(body);
        Unit firstSite = Jimple.v().newNopStmt();
        Unit secondSite = Jimple.v().newNopStmt();
        body.getUnits().addAll(List.of(firstSite, secondSite, Jimple.v().newReturnVoidStmt()));
        CallGraph graph = new CallGraph();
        graph.addEdge(new Edge(caller, firstSite, callee, Kind.STATIC));
        graph.addEdge(new Edge(caller, secondSite, callee, Kind.STATIC));
        graph.addEdge(new Edge(caller, firstSite, callee, Kind.CLINIT));
        graph.addEdge(new Edge(callee, null, caller, Kind.GENERIC_FAKE));
        UUID id = UUID.randomUUID();
        CallGraphSnapshot snapshot = CallGraphSnapshot.capture(id, graph);
        String callerName = caller.getSignature();
        String calleeName = callee.getSignature();
        String isolatedName = isolated.getSignature();

        graph.addEdge(new Edge(callee, null, isolated, Kind.STATIC));
        body.getUnits().clear();
        G.reset();
        assertEquals(id, snapshot.analysisId());
        assertEquals(4, snapshot.edges().size());
        assertEquals(Set.of(callerName, calleeName), snapshot.methods());
        assertFalse(snapshot.containsMethod(isolatedName));
        assertEquals(Set.of(callerName), snapshot.callersOf(calleeName));
        assertEquals(Set.of(calleeName), snapshot.calleesOf(callerName));
        assertTrue(snapshot.containsEdge(callerName, calleeName));
        assertFalse(snapshot.containsEdge(calleeName, isolatedName));
        assertEquals(3, snapshot.outgoingEdges(callerName).size());
        assertEquals(3, snapshot.incomingEdges(calleeName).size());
        assertEquals(Set.of(0, 1), snapshot.outgoingEdges(callerName).stream()
                .map(CallGraphEdge::callSiteIndex).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("STATIC", "CLINIT"), snapshot.outgoingEdges(callerName).stream()
                .map(CallGraphEdge::kind).collect(java.util.stream.Collectors.toSet()));
        assertTrue(snapshot.outgoingEdges(callerName).stream().allMatch(edge -> "nop".equals(edge.callSite())));
        assertNull(snapshot.outgoingEdges(calleeName).get(0).callSite());
        assertEquals(-1, snapshot.outgoingEdges(calleeName).get(0).callSiteIndex());
        assertThrows(UnsupportedOperationException.class, snapshot.edges()::clear);
        assertThrows(UnsupportedOperationException.class, snapshot.methods()::clear);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.callersOf(calleeName).clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.outgoingEdges(callerName).clear());
    }

    @Test
    void doesNotLoadBodiesAndSupportsEmptyGraphs() {
        SootClass owner = new SootClass("example.Unloaded");
        Scene.v().addClass(owner);
        SootMethod source = method(owner, "source");
        SootMethod target = method(owner, "target");
        CallGraph graph = new CallGraph();
        graph.addEdge(new Edge(source, Jimple.v().newNopStmt(), target, Kind.STATIC));
        CallGraphSnapshot snapshot = CallGraphSnapshot.capture(UUID.randomUUID(), graph);
        assertEquals(-1, snapshot.edges().get(0).callSiteIndex());
        assertFalse(source.hasActiveBody());
        CallGraphSnapshot empty = CallGraphSnapshot.capture(UUID.randomUUID(), new CallGraph());
        assertTrue(empty.edges().isEmpty());
        assertTrue(empty.methods().isEmpty());
        assertTrue(empty.callersOf("missing").isEmpty());
        assertTrue(empty.calleesOf("missing").isEmpty());
    }
}
