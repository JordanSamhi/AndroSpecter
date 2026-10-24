package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.inspection.LibraryFilter;
import soot.SootClass;
import soot.SootMethod;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Immutable, per-logger scope. Defaults select Soot application classes, accept all methods, and classify
 * no classes as libraries. Class and method predicates must both accept a method for it to be eligible.
 * A logger operation's {@code includeLibraries=false} additionally excludes its configured library policy;
 * {@code true} never widens the class or method scope. Predicates receive borrowed Soot objects during use.
 * The options retain predicate instances, so any captured mutable state remains the caller's responsibility.
 * Predicates run on the logger's owning thread and may be evaluated repeatedly for the same class or method;
 * use side-effect-free predicates and do not retain borrowed Soot arguments after the analysis ends.
 * The options object itself can be reused across scenes if its predicates do not capture scene-bound objects.
 */
public final class InstrumentationOptions {
    private final Predicate<SootClass> classes;
    private final Predicate<SootMethod> methods;
    private final LibraryFilter libraries;

    private InstrumentationOptions(Builder builder) {
        classes = builder.classes;
        methods = builder.methods;
        libraries = builder.libraries;
    }

    /**
     * Creates a builder with application-class scope, all methods, and no library classification.
     *
     * @return a new mutable builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Returns the class-scope predicate retained by these options.
     *
     * @return non-null configured class predicate; it is not a copy or a snapshot of selected classes
     */
    public Predicate<SootClass> classFilter() { return classes; }

    /**
     * Returns the method predicate applied within accepted classes.
     *
     * @return non-null configured method predicate; it is not a copy or a snapshot of selected methods
     */
    public Predicate<SootMethod> methodFilter() { return methods; }

    /**
     * Returns the policy used by operations that exclude libraries.
     *
     * @return non-null configured library classifier; it is not a copy
     */
    public LibraryFilter libraryFilter() { return libraries; }

    /** Mutable options builder whose setters replace, rather than combine, previous policies. */
    public static final class Builder {
        private Predicate<SootClass> classes = SootClass::isApplicationClass;
        private Predicate<SootMethod> methods = method -> true;
        private LibraryFilter libraries = LibraryFilter.none();

        private Builder() {}

        /**
         * Replaces the class scope, including the default application-class restriction.
         * Repeated calls replace the previous predicate; compose predicates explicitly when AND or OR is needed.
         *
         * @param predicate predicate selecting classes eligible for instrumentation
         * @return this builder
         * @throws NullPointerException if {@code predicate} is null
         */
        public Builder classes(Predicate<SootClass> predicate) {
            classes = Objects.requireNonNull(predicate, "predicate");
            return this;
        }

        /**
         * Replaces the method-scope predicate, which initially accepts all methods.
         * Repeated calls replace the previous predicate; class scope remains an additional restriction.
         *
         * @param predicate predicate selecting methods within accepted classes
         * @return this builder
         * @throws NullPointerException if {@code predicate} is null
         */
        public Builder methods(Predicate<SootMethod> predicate) {
            methods = Objects.requireNonNull(predicate, "predicate");
            return this;
        }

        /**
         * Replaces the classifier used when a logger operation specifies {@code includeLibraries=false}.
         * This does not change the class or method scope and has no filtering effect on operations that include libraries.
         *
         * @param filter classification policy; use {@link LibraryFilter#none()} to classify no libraries
         * @return this builder
         * @throws NullPointerException if {@code filter} is null
         */
        public Builder libraryFilter(LibraryFilter filter) {
            libraries = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Captures the current predicate references; later builder setters do not affect the result.
         *
         * @return new options retaining the configured predicates
         */
        public InstrumentationOptions build() { return new InstrumentationOptions(this); }
    }
}
