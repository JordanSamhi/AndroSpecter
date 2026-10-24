package com.jordansamhi.androspecter.inspection;

import com.jordansamhi.androspecter.analysis.AnalysisContext;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * An immutable registration plan for visiting the current Soot scene. Start with {@link #builder()},
 * register only the callbacks needed, and run the plan through an analysis or {@link #run(AnalysisContext)}.
 * No application-class or library filter is imposed by default. Bodies are loaded only for selected concrete
 * methods when a statement or invocation callback is registered.
 * <p>Callbacks receive borrowed, mutable Soot objects, not detached snapshots. Use them only on the context's
 * owning thread while that context remains valid; do not reset Soot from a callback. Collection snapshots
 * allow structural edits during traversal, but removed objects may still be visited and newly added objects
 * may not be visited. Mutating callbacks must preserve valid bodies and manage any cached analysis results.
 * Prefer an explicit transformation for mutations that should invalidate a managed analysis's call graph.
 * <p>Traversal is nested: for each selected class, class callbacks run before its selected methods;
 * for each method, method callbacks precede its units; for each unit, statement callbacks precede
 * invocation callbacks. Registrations at each level retain their order. Class, method, and unit
 * enumeration follow Soot's current collection order, not a separately sorted order.
 * Immutability covers the registration lists, not mutable state captured by predicates or callbacks.
 * A plan may be reused, but callers must ensure its callbacks and predicates are suitable for each
 * scene and thread; registering a callback does not detach any Soot objects it captures.
 * <pre>{@code
 * Inspection plan = Inspection.builder()
 *         .classes(clazz -> clazz.getName().startsWith("com.example."))
 *         .onInvoke((caller, statement, invocation) ->
 *                 System.out.println(invocation.getMethodRef().getSignature()))
 *         .build();
 * plan.run(context); // A valid AnalysisContext on its owning thread is required.
 * }</pre>
 */
public final class Inspection {
    /** Receives an invocation expression together with its containing method and statement. */
    @FunctionalInterface
    public interface InvocationVisitor {
        /**
         * Visits an invocation without requiring the inspector to resolve its target method.
         * All arguments are non-null and borrowed from the current scene and share its lifetime.
         * The callback executes synchronously on the inspection thread. Failures propagate
         * immediately from {@link Inspection#run(AnalysisContext)} without rollback.
         *
         * @param caller method containing the invocation
         * @param statement statement containing {@code invocation}
         * @param invocation invocation expression, which may target an unresolved method
         */
        void accept(SootMethod caller, Stmt statement, InvokeExpr invocation);
    }

    private final List<Predicate<SootClass>> classes;
    private final List<Predicate<SootMethod>> methods;
    private final List<Consumer<SootClass>> classVisitors;
    private final List<Consumer<SootMethod>> methodVisitors;
    private final List<BiConsumer<SootMethod, Unit>> statementVisitors;
    private final List<InvocationVisitor> invocationVisitors;

    private Inspection(Builder builder) {
        classes = List.copyOf(builder.classes);
        methods = List.copyOf(builder.methods);
        classVisitors = List.copyOf(builder.classVisitors);
        methodVisitors = List.copyOf(builder.methodVisitors);
        statementVisitors = List.copyOf(builder.statementVisitors);
        invocationVisitors = List.copyOf(builder.invocationVisitors);
    }

    /**
     * Creates a builder with no callbacks or filtering restrictions.
     *
     * @return a new, independently mutable builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Visits selected classes and methods in the current scene on the context's owning thread.
     * Class predicates run before method enumeration, and method predicates run before body loading.
     * Method predicates are evaluated only when at least one method, statement, or invocation
     * callback is registered. Statement and invocation callbacks can trigger body retrieval;
     * class and method callbacks alone do not, unless their own code loads bodies.
     * A plan with no callbacks only checks the context; it evaluates no predicates and loads no bodies.
     * Context validity and cancellation are checked around each user callback and predicate.
     * Callback, predicate, and Soot body-loading failures propagate immediately without undoing earlier work.
     *
     * @param context valid context for the current Soot scene; this method does not close it
     * @throws NullPointerException if {@code context} is null
     * @throws IllegalStateException if the context is closed, belongs to another thread, or Soot was reset
     * @throws java.util.concurrent.CancellationException if cancellation or thread interruption is observed
     * @throws RuntimeException if a predicate, callback, or Soot body-loading operation fails
     */
    public void run(AnalysisContext context) {
        Objects.requireNonNull(context, "context").checkValid();
        if (classVisitors.isEmpty() && methodVisitors.isEmpty() && statementVisitors.isEmpty() && invocationVisitors.isEmpty()) {
            return;
        }
        for (SootClass clazz : new ArrayList<>(Scene.v().getClasses())) {
            context.checkValid();
            boolean selectedClass = matches(classes, clazz, context);
            if (!selectedClass) continue;
            for (Consumer<SootClass> visitor : classVisitors) {
                context.checkValid();
                visitor.accept(clazz);
                context.checkValid();
            }
            if (methodVisitors.isEmpty() && statementVisitors.isEmpty() && invocationVisitors.isEmpty()) continue;
            for (SootMethod method : new ArrayList<>(clazz.getMethods())) {
                context.checkValid();
                boolean selectedMethod = matches(methods, method, context);
                if (!selectedMethod) continue;
                for (Consumer<SootMethod> visitor : methodVisitors) {
                    context.checkValid();
                    visitor.accept(method);
                    context.checkValid();
                }
                if ((statementVisitors.isEmpty() && invocationVisitors.isEmpty()) || !method.isConcrete()) continue;
                List<Unit> units = new ArrayList<>(method.retrieveActiveBody().getUnits());
                context.checkValid();
                for (Unit unit : units) {
                    context.checkValid();
                    for (BiConsumer<SootMethod, Unit> visitor : statementVisitors) {
                        context.checkValid();
                        visitor.accept(method, unit);
                        context.checkValid();
                    }
                    if (unit instanceof Stmt && ((Stmt) unit).containsInvokeExpr()) {
                        Stmt statement = (Stmt) unit;
                        for (InvocationVisitor visitor : invocationVisitors) {
                            context.checkValid();
                            visitor.accept(method, statement, statement.getInvokeExpr());
                            context.checkValid();
                        }
                    }
                }
            }
        }
        context.checkValid();
    }

    private static <T> boolean matches(List<Predicate<T>> predicates, T value, AnalysisContext context) {
        for (Predicate<T> predicate : predicates) {
            context.checkValid();
            boolean selected = predicate.test(value);
            context.checkValid();
            if (!selected) return false;
        }
        return true;
    }

    /**
     * Mutable callback registration builder created by {@link Inspection#builder()}.
     * It is not thread-safe. Each build copies its current registration lists, so the builder
     * may be reused without changing previously built plans.
     */
    public static final class Builder {
        private final List<Predicate<SootClass>> classes = new ArrayList<>();
        private final List<Predicate<SootMethod>> methods = new ArrayList<>();
        private final List<Consumer<SootClass>> classVisitors = new ArrayList<>();
        private final List<Consumer<SootMethod>> methodVisitors = new ArrayList<>();
        private final List<BiConsumer<SootMethod, Unit>> statementVisitors = new ArrayList<>();
        private final List<InvocationVisitor> invocationVisitors = new ArrayList<>();

        private Builder() {}

        /**
         * Adds a class predicate. Repeated calls are combined with short-circuit AND in registration order.
         * Rejected classes receive no callbacks and none of their method bodies are loaded by this plan.
         *
         * @param predicate predicate over a borrowed class in the current scene
         * @return this builder
         * @throws NullPointerException if {@code predicate} is null
         */
        public Builder classes(Predicate<SootClass> predicate) {
            classes.add(Objects.requireNonNull(predicate, "predicate"));
            return this;
        }

        /**
         * Adds a method predicate. Repeated calls are combined with short-circuit AND in registration order.
         * Rejected methods receive no callbacks and their bodies are not loaded by this plan.
         *
         * @param predicate predicate over a borrowed method in a selected class
         * @return this builder
         * @throws NullPointerException if {@code predicate} is null
         */
        public Builder methods(Predicate<SootMethod> predicate) {
            methods.add(Objects.requireNonNull(predicate, "predicate"));
            return this;
        }

        /**
         * Adds a class predicate excluding classes classified as libraries by {@code filter}.
         * This is ANDed with existing class predicates; repeated calls exclude the union of their libraries.
         *
         * @param filter caller-selected classification policy
         * @return this builder
         * @throws NullPointerException if {@code filter} is null
         */
        public Builder excludeLibraries(LibraryFilter filter) {
            return classes(Objects.requireNonNull(filter, "filter").negate());
        }

        /**
         * Appends a callback for every selected class, before its method callbacks.
         * Registering only class callbacks does not enumerate methods or load bodies.
         *
         * @param visitor callback receiving a borrowed class from the current scene
         * @return this builder
         * @throws NullPointerException if {@code visitor} is null
         */
        public Builder onClass(Consumer<SootClass> visitor) {
            classVisitors.add(Objects.requireNonNull(visitor, "visitor"));
            return this;
        }

        /**
         * Appends a callback for every selected method, including abstract and native methods.
         * The inspector does not load bodies for method callbacks alone. Method predicates
         * do not suppress class callbacks; use a class predicate to reject a whole class.
         *
         * @param visitor callback receiving a borrowed method from the current scene
         * @return this builder
         * @throws NullPointerException if {@code visitor} is null
         */
        public Builder onMethod(Consumer<SootMethod> visitor) {
            methodVisitors.add(Objects.requireNonNull(visitor, "visitor"));
            return this;
        }

        /**
         * Appends a callback for each unit in selected concrete methods, including identities and returns.
         * Enabling this level loads those methods' bodies. Statement callbacks precede invocation callbacks
         * for the same unit; both arguments are borrowed from the current scene.
         *
         * @param visitor callback receiving the containing method and unit
         * @return this builder
         * @throws NullPointerException if {@code visitor} is null
         */
        public Builder onStatement(BiConsumer<SootMethod, Unit> visitor) {
            statementVisitors.add(Objects.requireNonNull(visitor, "visitor"));
            return this;
        }

        /**
         * Appends a callback for invocation expressions in selected concrete methods.
         * This loads their bodies but requires no class, method, or statement callback registration.
         * The inspector does not resolve the invocation's target method.
         *
         * @param visitor callback receiving borrowed invocation context
         * @return this builder
         * @throws NullPointerException if {@code visitor} is null
         */
        public Builder onInvoke(InvocationVisitor visitor) {
            invocationVisitors.add(Objects.requireNonNull(visitor, "visitor"));
            return this;
        }

        /**
         * Copies the current registrations into a reusable plan; later builder edits do not affect it.
         * Callback and predicate instances themselves are shared, not cloned.
         *
         * @return a new immutable registration plan
         */
        public Inspection build() {
            return new Inspection(this);
        }
    }
}
