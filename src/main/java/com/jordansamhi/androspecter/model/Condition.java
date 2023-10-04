package com.jordansamhi.androspecter.model;

import soot.Body;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.CaughtExceptionRef;
import soot.jimple.IdentityStmt;
import soot.jimple.IfStmt;
import soot.jimple.Stmt;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.SimpleDominatorsFinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Condition class encapsulates information and operations related to a conditional statement (IfStmt) within a method.
 * It provides functionalities to analyze and process the branches, statements dominated by the condition,
 * and the control flow graph surrounding the condition.
 * <p>
 * The class holds references to the SootMethod and Body containing the condition, the InfoflowCFG representing the control
 * flow graph, and the BriefUnitGraph for analyzing the control flow within the method.
 * <p>
 * It also maintains collections to keep track of the statements dominated by the condition,
 * and the units comprising the first, second, and both branches of the condition.
 * <p>
 * The class provides methods to initialize and process the condition, generate the control flow graph,
 * identify the branches and the statements dominated by the condition, and access or modify the associated
 * SootMethod, Body, InfoflowCFG, BriefUnitGraph, and the collections of units and statements related to the condition.
 */
public class Condition {

    /**
     * The SootMethod object representing the method containing the condition.
     */
    private SootMethod method;

    /**
     * The Body object representing the method body.
     */
    private Body body;

    /**
     * The BriefUnitGraph object representing the control flow graph of the method.
     */
    private BriefUnitGraph graph;

    /**
     * The IfStmt object representing the condition.
     */
    private IfStmt condition;

    /**
     * The list of statements that are dominated by the condition.
     */
    private List<Stmt> stmtsDominatedByCondition;

    /**
     * The set of units representing the first branch of the condition.
     */
    private Set<Unit> trueBranch;

    /**
     * The set of units representing the second branch of the condition.
     */
    private Set<Unit> falseBranch;
    /**
     * The Unit object representing the successor of the true branch of the condition.
     */
    private Unit entryPointTrueBranch;

    /**
     * The Unit object representing the successor of the false branch of the condition.
     */
    private Unit entryPointFalseBranch;

    /**
     * A boolean field indicating whether the condition has two branches.
     */
    private boolean hasTwoBranches;


    /**
     * Private default constructor initializes the collections used to store statements and branches related to the condition.
     * This constructor is called within the public constructor to initialize the state of the Condition object.
     */
    private Condition() {
        this.stmtsDominatedByCondition = new ArrayList<>();
        this.trueBranch = new HashSet<>();
        this.falseBranch = new HashSet<>();
    }

    /**
     * Public constructor creates a Condition object and initializes it with the given IfStmt and SootMethod objects.
     *
     * @param i      The IfStmt object representing the condition.
     * @param method The SootMethod object containing the condition.
     */
    public Condition(IfStmt i, SootMethod method) {
        this();
        this.condition = i;
        this.method = method;
        generateGraph();
        generateGuardedStmts();
        generateBranches();
    }

    /**
     * Checks if the given Unit represents a caught exception.
     *
     * @param u The Unit to check.
     * @return true if the Unit represents a caught exception, false otherwise.
     */
    private boolean isCaughtException(Unit u) {
        if (u instanceof IdentityStmt) {
            IdentityStmt is = (IdentityStmt) u;
            Value rightOp = is.getRightOp();
            return rightOp instanceof CaughtExceptionRef;
        }
        return false;
    }

    /**
     * Generates branches from the condition statement, processes them to identify
     * the true and false branches, and retains common units.
     * The method initializes and fills branchOne and branchTwo Sets,
     * and determines the entry points for both branches.
     */
    private void generateBranches() {
        final List<Unit> successors = new ArrayList<>();
        for (final Unit u : this.graph.getSuccsOf(condition)) {
            if (!isCaughtException(u)) {
                successors.add(u);
            }
        }
        if (successors.size() > 1) {
            Stmt succ1 = (Stmt) successors.get(0);
            Stmt succ2 = (Stmt) successors.get(1);
            Set<Unit> branchOne = new HashSet<>();
            Set<Unit> branchTwo = new HashSet<>();
            processBranches(succ1, branchOne);
            processBranches(succ2, branchTwo);
            retainCommonUnits(branchOne, branchTwo);

            determineBranches(succ1, succ2, branchOne, branchTwo);
            assignBranches(branchOne, branchTwo);
        }
    }

    /**
     * Assigns the true and false branches based on the provided branch sets and the pre-determined entry points.
     * - If both branches are present, it assigns the true and false branches based on the location of the true entry point.
     * - If only one branch is present, it determines whether this branch represents the true or false branch based on the
     * entry points, and assigns the branches accordingly.
     *
     * @param branchOne A set representing the first branch stemming from the condition.
     * @param branchTwo A set representing the second branch stemming from the condition.
     */
    private void assignBranches(Set<Unit> branchOne, Set<Unit> branchTwo) {
        if (this.hasTwoBranches) {
            this.trueBranch = branchOne.contains(this.entryPointTrueBranch) ? branchOne : branchTwo;
            this.falseBranch = (this.trueBranch == branchOne) ? branchTwo : branchOne;
        } else {
            Set<Unit> singleBranch = branchOne.isEmpty() ? branchTwo : branchOne;
            if (this.entryPointTrueBranch != null) {
                this.trueBranch = singleBranch.contains(this.entryPointTrueBranch) ? singleBranch : null;
                this.falseBranch = (this.trueBranch == null) ? singleBranch : null;
            } else if (this.entryPointFalseBranch != null) {
                this.falseBranch = singleBranch.contains(this.entryPointFalseBranch) ? singleBranch : null;
                this.trueBranch = (this.falseBranch == null) ? singleBranch : null;
            }
        }
    }


    /**
     * Evaluates the branching logic based on the provided statements and sets
     * the {@code entryPointTrueBranch} and {@code entryPointFalseBranch} fields accordingly.
     * It also updates the {@code hasTwoBranches} field to reflect whether both branches are present.
     *
     * @param succ1     The first successor statement associated with the condition.
     * @param succ2     The second successor statement associated with the condition.
     * @param branchOne A set of Units representing the first branch.
     * @param branchTwo A set of Units representing the second branch.
     */
    private void determineBranches(Stmt succ1, Stmt succ2, Set<Unit> branchOne, Set<Unit> branchTwo) {
        Stmt tgt = this.condition.getTarget();
        if (branchOne.isEmpty() || branchTwo.isEmpty()) {
            this.hasTwoBranches = false;
            assignSingleBranch(succ1, succ2, tgt, branchOne, branchTwo);
        } else {
            this.hasTwoBranches = true;
            assignTrueAndFalseBranches(succ1, succ2, tgt);
        }
    }

    /**
     * Assigns a single branch entry point based on the specified successor statements,
     * target statement, and the emptiness of the branchOne set.
     *
     * @param succ1     The first successor statement of the condition.
     * @param succ2     The second successor statement of the condition.
     * @param tgt       The target statement of the condition.
     * @param branchOne The set representing the first branch.
     * @param branchTwo The set representing the second branch.
     */
    private void assignSingleBranch(Stmt succ1, Stmt succ2, Stmt tgt, Set<Unit> branchOne, Set<Unit> branchTwo) {
        Stmt succ = null;
        if (branchOne.isEmpty()) {
            succ = branchTwo.contains(succ1) ? succ1 : succ2;
        } else {
            succ = branchOne.contains(succ1) ? succ1 : succ2;
        }
        if (succ.equals(tgt)) {
            this.entryPointTrueBranch = succ;
            this.entryPointFalseBranch = null;
        } else {
            this.entryPointFalseBranch = succ;
            this.entryPointTrueBranch = null;
        }
    }


    /**
     * Assigns true and false branch entry points based on the successor statements and the target statement.
     *
     * @param succ1 The first successor statement of the condition.
     * @param succ2 The second successor statement of the condition.
     * @param tgt   The target statement of the condition.
     */
    private void assignTrueAndFalseBranches(Stmt succ1, Stmt succ2, Stmt tgt) {
        if (tgt.equals(succ1)) {
            this.entryPointTrueBranch = succ1;
            this.entryPointFalseBranch = succ2;
        } else {
            this.entryPointTrueBranch = succ2;
            this.entryPointFalseBranch = succ1;
        }
    }


    /**
     * Recursively processes the branches of the control flow graph starting from the given unit.
     * It adds the unit to the specified branch if it's dominated by the condition and not already present in the branch.
     * This method is called recursively for each successor of the given unit.
     *
     * @param succ   The starting unit for processing the branch.
     * @param branch The set of units representing the current branch.
     */
    private void processBranches(final Unit succ, final Set<Unit> branch) {
        if (!branch.contains(succ) && this.stmtsDominatedByCondition.contains(succ)) {
            branch.add(succ);
            for (final Unit successor : this.graph.getSuccsOf(succ)) {
                processBranches(successor, branch);
            }
        }
    }

    /**
     * Retains the common units between the two branches, and updates the branchOne, branchTwo, and bothBranches sets.
     * It identifies the intersection of units between branchOne and branchTwo,
     * removes the common units from each.
     */
    private void retainCommonUnits(Set<Unit> branchOne, Set<Unit> branchTwo) {
        final Set<Unit> intersection = new HashSet<>(branchOne);
        intersection.retainAll(branchTwo);
        branchOne.removeAll(intersection);
        branchTwo.removeAll(intersection);
    }

    /**
     * Generates a list of statements that are dominated by the condition.
     * It iterates through the units of the method body, checking if each unit is dominated by the condition
     * and is not the condition itself, adding such units to the list of statements dominated by the condition.
     */
    private void generateGuardedStmts() {
        final SimpleDominatorsFinder<Unit> pdf = new SimpleDominatorsFinder<>(this.graph);
        if (body != null) {
            for (final Unit u : body.getUnits()) {
                if (pdf.isDominatedBy(u, condition) && !u.equals(condition)) {
                    this.stmtsDominatedByCondition.add((Stmt) u);
                }
            }
        }
    }

    /**
     * Generates the graph representation of the method's control flow,
     * retrieving the method and body associated with the condition,
     * and creating a BriefUnitGraph object to represent the control flow graph.
     */
    private void generateGraph() {
        body = (method != null && method.isConcrete()) ? method.retrieveActiveBody() : null;
        if (body != null) {
            this.graph = new BriefUnitGraph(body);
        }
    }

    /**
     * Gets the value of the hasTwoBranches field.
     *
     * @return true if the condition has two branches, false otherwise.
     */
    public boolean hasTwoBranches() {
        return hasTwoBranches;
    }

    /**
     * Retrieves the entry point of the true branch of the condition.
     *
     * @return A Unit representing the entry point of the true branch.
     */
    public Unit getEntryPointTrueBranch() {
        return entryPointTrueBranch;
    }

    /**
     * Retrieves the entry point of the false branch of the condition.
     *
     * @return A Unit representing the entry point of the false branch.
     */
    public Unit getEntryPointFalseBranch() {
        return entryPointFalseBranch;
    }

    /**
     * Retrieves the set of units representing the true branch of the condition.
     *
     * @return A set of units comprising the true branch.
     */
    public Set<Unit> getTrueBranch() {
        return trueBranch;
    }

    /**
     * Retrieves the set of units representing the false branch of the condition.
     *
     * @return A set of units comprising the false branch.
     */
    public Set<Unit> getFalseBranch() {
        return falseBranch;
    }
}