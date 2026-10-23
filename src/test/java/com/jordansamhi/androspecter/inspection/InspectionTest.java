package com.jordansamhi.androspecter.inspection;

import com.jordansamhi.androspecter.analysis.AnalysisContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soot.*;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.Stmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InspectionTest {
    @BeforeEach void reset() { G.reset(); }
    @AfterEach void cleanup() { G.reset(); }

    @Test
    void emptyInspectionDoesNotEvaluatePredicatesOrLoadBodies() {
        SootMethod selected = method(clazz("example.Selected"), "noBody");
        try (AnalysisContext context = AnalysisContext.capture()) {
            Inspection.builder().classes(clazz -> {
                fail("No callbacks means no class traversal");
                return true;
            }).methods(method -> {
                fail("No callbacks means no method traversal");
                return true;
            }).build().run(context);
        }
        assertFalse(selected.hasActiveBody());
    }

    @Test
    void invocationOnlyInspectionFiltersBeforeLoadingBodies() {
        SootClass object = clazz("java.lang.Object");
        SootClass selected = clazz("example.Selected");
        SootClass rejected = clazz("example.Rejected");
        selected.setSuperclass(object);
        rejected.setSuperclass(object);
        SootMethod rejectedClassMethod = method(rejected, "neverLoad");
        SootMethod rejectedMethod = method(selected, "neverLoad");
        SootMethod caller = method(selected, "visit");
        SootMethod callee = new SootMethod("target", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC | Modifier.NATIVE);
        rejected.addMethod(callee);
        JimpleBody body = Jimple.v().newBody(caller);
        caller.setActiveBody(body);
        Stmt invocation = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(callee.makeRef()));
        body.getUnits().add(invocation);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        body.validate();
        List<String> calls = new ArrayList<>();
        try (AnalysisContext context = AnalysisContext.capture()) {
            Inspection.builder().classes(clazz -> clazz == selected).methods(method -> method == caller)
                    .onInvoke((method, statement, expression) -> {
                        assertSame(caller, method);
                        assertSame(invocation, statement);
                        calls.add(expression.getMethodRef().getSignature());
                    }).build().run(context);
        }
        assertEquals(Collections.singletonList(callee.getSignature()), calls);
        assertFalse(rejectedClassMethod.hasActiveBody());
        assertFalse(rejectedMethod.hasActiveBody());
    }

    @Test
    void metadataOnlyCallbacksNeverRetrieveBodiesAndKeepRegistrationOrder() {
        SootClass selected = clazz("example.Selected");
        SootMethod method = method(selected, "noBody");
        List<String> events = new ArrayList<>();
        Inspection.Builder builder = Inspection.builder().onClass(c -> events.add("class"))
                .onMethod(m -> events.add("first"))
                .onMethod(m -> events.add("second"));
        Inspection inspection = builder.build();
        builder.onMethod(m -> events.add("registered later"));
        try (AnalysisContext context = AnalysisContext.capture()) {
            inspection.run(context);
        }
        assertEquals(List.of("class", "first", "second"), events);
        assertFalse(method.hasActiveBody());
    }

    @Test
    void customLibraryPoliciesComposeAndExcludeWithoutLoadingBodies() {
        SootClass library = clazz("third.party.Client");
        SootClass customLibrary = clazz("example.Generated");
        SootClass application = clazz("third.partyother.Application");
        method(library, "neverLoad");
        method(customLibrary, "neverLoad");
        SootMethod appMethod = method(application, "visit");
        JimpleBody body = Jimple.v().newBody(appMethod);
        appMethod.setActiveBody(body);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        LibraryFilter libraries = LibraryFilter.packages("third.party")
                .or(c -> c.getName().equals("example.Generated"));
        List<String> visited = new ArrayList<>();
        try (AnalysisContext context = AnalysisContext.capture()) {
            Inspection.builder().excludeLibraries(libraries)
                    .onStatement((m, unit) -> visited.add(m.getSignature())).build().run(context);
        }
        assertEquals(Collections.singletonList(appMethod.getSignature()), visited);
        assertFalse(libraries.isLibrary(application));
        assertTrue(libraries.and(c -> c == library).isLibrary(library));
        assertFalse(libraries.and(c -> c == library).isLibrary(customLibrary));
        assertTrue(libraries.negate().isLibrary(application));
    }

    @Test
    void resetInsideCallbackStopsBeforeAnotherCallbackSeesStaleObjects() {
        SootClass selected = clazz("example.Selected");
        method(selected, "neverLoad");
        AtomicInteger later = new AtomicInteger();
        try (AnalysisContext context = AnalysisContext.capture()) {
            Inspection inspection = Inspection.builder().onClass(c -> G.reset())
                    .onClass(c -> later.incrementAndGet()).onMethod(m -> later.incrementAndGet()).build();
            assertThrows(IllegalStateException.class, () -> inspection.run(context));
        }
        assertEquals(0, later.get());
    }

    @Test
    void resetInsidePredicateStopsBeforeBodyRetrieval() {
        SootMethod selected = method(clazz("example.Selected"), "neverLoad");
        AtomicInteger visits = new AtomicInteger();
        try (AnalysisContext context = AnalysisContext.capture()) {
            Inspection inspection = Inspection.builder().methods(method -> {
                G.reset();
                return true;
            }).onStatement((method, unit) -> visits.incrementAndGet()).build();
            assertThrows(IllegalStateException.class, () -> inspection.run(context));
        }
        assertEquals(0, visits.get());
        assertFalse(selected.hasActiveBody());
    }

    private static SootClass clazz(String name) {
        SootClass clazz = new SootClass(name, Modifier.PUBLIC);
        Scene.v().addClass(clazz);
        return clazz;
    }

    private static SootMethod method(SootClass owner, String name) {
        SootMethod method = new SootMethod(name, Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method);
        return method;
    }
}
