package com.jordansamhi.androspecter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soot.*;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class AndroidAppInspectorTest {
    private SootClass subject;

    @BeforeEach
    void setUp() {
        G.reset();
        subject = new SootClass("example.Subject", Modifier.PUBLIC);
        Scene.v().addClass(subject);
        subject.addMethod(new SootMethod("first", Collections.emptyList(), VoidType.v(),
                Modifier.PUBLIC | Modifier.STATIC));
    }

    @AfterEach
    void tearDown() {
        G.reset();
    }

    @Test
    void methodOnlyInspectionDoesNotLoadBodies() {
        CountingInspector inspector = new CountingInspector();
        inspector.setExaminationCriteria(true, true, false);
        assertDoesNotThrow(inspector::run);
        assertEquals(1, inspector.classes);
        assertEquals(1, inspector.methods);
        assertEquals(0, inspector.statements);
        assertFalse(subject.getMethods().get(0).hasActiveBody());
    }

    @Test
    void methodCallbackCanAddMethodsWithoutInvalidatingTraversal() {
        CountingInspector inspector = new CountingInspector() {
            @Override
            public void examineMethod(SootClass sc, SootMethod sm) {
                super.examineMethod(sc, sm);
                sc.addMethod(new SootMethod("added", Collections.emptyList(), VoidType.v(),
                        Modifier.PUBLIC | Modifier.STATIC));
            }
        };
        inspector.setExaminationCriteria(false, true, false);
        assertDoesNotThrow(inspector::run);
        assertEquals(1, inspector.methods);
        assertEquals(2, subject.getMethods().size());
    }

    private static class CountingInspector extends AndroidAppInspector {
        int classes;
        int methods;
        int statements;

        @Override
        public void examineClass(SootClass sc) {
            classes++;
        }

        @Override
        public void examineMethod(SootClass sc, SootMethod sm) {
            methods++;
        }

        @Override
        public void examineStatement(SootClass sc, SootMethod sm, Unit unit) {
            statements++;
        }
    }
}
