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
 * Immutable inspection plan. Only registered levels are visited; bodies are loaded only for selected concrete
 * methods when a statement or invocation callback is registered. Traversal snapshots tolerate collection edits.
 * Callbacks run class, method, statement, then invocation; registrations at each level retain their order.
 */
public final class Inspection {
    @FunctionalInterface
    public interface InvocationVisitor {
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

    public static Builder builder() {
        return new Builder();
    }

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

    public static final class Builder {
        private final List<Predicate<SootClass>> classes = new ArrayList<>();
        private final List<Predicate<SootMethod>> methods = new ArrayList<>();
        private final List<Consumer<SootClass>> classVisitors = new ArrayList<>();
        private final List<Consumer<SootMethod>> methodVisitors = new ArrayList<>();
        private final List<BiConsumer<SootMethod, Unit>> statementVisitors = new ArrayList<>();
        private final List<InvocationVisitor> invocationVisitors = new ArrayList<>();

        private Builder() {}

        /** Adds a class predicate; repeated calls are combined with AND. */
        public Builder classes(Predicate<SootClass> predicate) {
            classes.add(Objects.requireNonNull(predicate, "predicate"));
            return this;
        }

        /** Adds a method predicate; repeated calls are combined with AND. */
        public Builder methods(Predicate<SootMethod> predicate) {
            methods.add(Objects.requireNonNull(predicate, "predicate"));
            return this;
        }

        public Builder excludeLibraries(LibraryFilter filter) {
            return classes(Objects.requireNonNull(filter, "filter").negate());
        }

        public Builder onClass(Consumer<SootClass> visitor) {
            classVisitors.add(Objects.requireNonNull(visitor, "visitor"));
            return this;
        }

        public Builder onMethod(Consumer<SootMethod> visitor) {
            methodVisitors.add(Objects.requireNonNull(visitor, "visitor"));
            return this;
        }

        public Builder onStatement(BiConsumer<SootMethod, Unit> visitor) {
            statementVisitors.add(Objects.requireNonNull(visitor, "visitor"));
            return this;
        }

        public Builder onInvoke(InvocationVisitor visitor) {
            invocationVisitors.add(Objects.requireNonNull(visitor, "visitor"));
            return this;
        }

        public Inspection build() {
            return new Inspection(this);
        }
    }
}
