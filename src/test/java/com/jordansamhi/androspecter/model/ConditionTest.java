package com.jordansamhi.androspecter.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soot.*;
import soot.jimple.*;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ConditionTest {
    private SootClass owner;

    @BeforeEach
    void setUp() {
        G.reset();
        owner = new SootClass("example.Conditions", Modifier.PUBLIC);
        Scene.v().addClass(owner);
    }

    @AfterEach
    void tearDown() {
        G.reset();
    }

    @Test
    void rejectsNullAndNonConcreteMethodsWithClearErrors() {
        IfStmt statement = Jimple.v().newIfStmt(Jimple.v().newEqExpr(IntConstant.v(0), IntConstant.v(1)),
                Jimple.v().newReturnVoidStmt());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new Condition(statement, null))
                .getMessage().contains("concrete method"));
        SootMethod abstractMethod = new SootMethod("abstractMethod", Collections.emptyList(), VoidType.v(),
                Modifier.PUBLIC | Modifier.ABSTRACT);
        owner.addMethod(abstractMethod);
        assertThrows(IllegalArgumentException.class, () -> new Condition(statement, abstractMethod));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new Condition(null, abstractMethod))
                .getMessage().contains("must not be null"));
    }

    @Test
    void rejectsUnavailableBodyAndForeignCondition() {
        SootMethod method = new SootMethod("run", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method);
        IfStmt foreign = Jimple.v().newIfStmt(Jimple.v().newEqExpr(IntConstant.v(0), IntConstant.v(1)),
                Jimple.v().newReturnVoidStmt());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new Condition(foreign, method))
                .getMessage().contains("retrieve the method body"));
        JimpleBody body = Jimple.v().newBody(method);
        method.setActiveBody(body);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        body.validate();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new Condition(foreign, method))
                .getMessage().contains("belong to the method body"));
    }

    @Test
    void identifiesExclusiveTrueAndFalseBranchesAndExcludesTheMerge() {
        SootMethod method = new SootMethod("run", Collections.singletonList(IntType.v()), VoidType.v(),
                Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method);
        JimpleBody body = Jimple.v().newBody(method);
        method.setActiveBody(body);
        Local flag = Jimple.v().newLocal("flag", IntType.v());
        body.getLocals().add(flag);
        body.getUnits().add(Jimple.v().newIdentityStmt(flag, Jimple.v().newParameterRef(IntType.v(), 0)));
        Unit merge = Jimple.v().newReturnVoidStmt();
        Unit trueWork = Jimple.v().newNopStmt();
        Unit falseWork = Jimple.v().newNopStmt();
        Unit skipTrueBranch = Jimple.v().newGotoStmt(merge);
        IfStmt statement = Jimple.v().newIfStmt(Jimple.v().newEqExpr(flag, IntConstant.v(0)), trueWork);
        body.getUnits().addAll(Arrays.asList(statement, falseWork, skipTrueBranch, trueWork, merge));
        body.validate();

        Condition condition = new Condition(statement, method);
        assertTrue(condition.hasTwoBranches());
        assertSame(trueWork, condition.getEntryPointTrueBranch());
        assertSame(falseWork, condition.getEntryPointFalseBranch());
        assertEquals(Collections.singleton(trueWork), condition.getTrueBranch());
        assertTrue(condition.getFalseBranch().containsAll(Arrays.asList(falseWork, skipTrueBranch)));
        assertEquals(2, condition.getFalseBranch().size());
        assertFalse(condition.getTrueBranch().contains(merge));
        assertFalse(condition.getFalseBranch().contains(merge));
    }
}
