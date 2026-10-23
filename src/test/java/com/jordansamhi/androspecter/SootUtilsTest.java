package com.jordansamhi.androspecter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soot.G;
import soot.Kind;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.VoidType;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SootUtilsTest {
    private SootUtils utils;

    @BeforeEach
    void setUp() {
        G.reset();
        utils = new SootUtils();
    }

    @AfterEach
    void tearDown() {
        G.reset();
    }

    private SootClass addClass(String name) {
        SootClass sc = new SootClass(name);
        Scene.v().addClass(sc);
        return sc;
    }

    private SootMethod addMethod(SootClass sc, String name) {
        SootMethod method = new SootMethod(name, Collections.emptyList(), VoidType.v());
        sc.addMethod(method);
        return method;
    }

    @Test
    void sceneQueriesReflectAddedRemovedAndResetClasses() {
        SootClass first = addClass("example.First");
        SootMethod initial = addMethod(first, "initial");
        assertTrue(utils.getClasses().contains(first));
        assertTrue(utils.getAllMethods().contains(initial));
        assertTrue(utils.getAllMethodsExceptLibraries().contains(initial));

        SootMethod added = addMethod(first, "added");
        SootClass second = addClass("example.Second");
        assertTrue(utils.getClasses().contains(second));
        assertTrue(utils.getAllMethods().contains(added));
        assertTrue(utils.getAllMethodsExceptLibraries().contains(added));

        first.removeMethod(initial);
        Scene.v().removeClass(second);
        assertFalse(utils.getClasses().contains(second));
        assertFalse(utils.getAllMethods().contains(initial));

        G.reset();
        SootClass nextApp = addClass("example.NextApp");
        assertEquals(Collections.singleton(nextApp), utils.getClasses());
        assertEquals(Collections.singleton(nextApp), utils.getNonLibraryClasses());
        assertTrue(utils.getAllMethods().isEmpty());
        assertTrue(utils.getAllMethodsExceptLibraries().isEmpty());
    }

    @Test
    void graphQueriesReflectMutationAndDifferentGraphs() {
        SootClass sc = addClass("example.Calls");
        SootMethod source = addMethod(sc, "source");
        SootMethod firstTarget = addMethod(sc, "firstTarget");
        SootMethod secondTarget = addMethod(sc, "secondTarget");
        CallGraph graph = new CallGraph();
        Edge firstEdge = new Edge(source, null, firstTarget, Kind.STATIC);
        graph.addEdge(firstEdge);
        assertEquals(2, utils.getCountOfNodes(graph));
        assertTrue(utils.isCalledInCallGraph(firstTarget, graph));
        assertTrue(utils.getMethodsInCallGraphExceptLibraries(graph).contains(firstTarget));

        graph.removeEdge(firstEdge);
        graph.addEdge(new Edge(source, null, secondTarget, Kind.STATIC));
        assertFalse(utils.isInCallGraph(firstTarget, graph));
        assertFalse(utils.isCalledInCallGraph(firstTarget, graph));
        assertTrue(utils.isInCallGraph(secondTarget, graph));
        assertTrue(utils.getMethodsInCallGraphExceptLibraries(graph).contains(secondTarget));
        assertFalse(utils.getMethodsInCallGraphExceptLibraries(graph).contains(firstTarget));
        assertEquals(0, utils.getCountOfNodes(new CallGraph()));
    }

    @Test
    void sceneQueriesIncludeLibrariesButExcludeDummyMain() {
        SootClass app = addClass("example.App");
        SootClass library = addClass("pro.cryptoevil.Library");
        SootClass dummy = addClass("dummyMainClass");
        assertTrue(utils.getAllClasses().contains(app));
        assertTrue(utils.getAllClasses().contains(library));
        assertFalse(utils.getAllClasses().contains(dummy));
        assertFalse(utils.getNonLibraryClasses().contains(library));

        utils.getClasses().clear();
        assertTrue(utils.getClasses().contains(app));
    }
}
