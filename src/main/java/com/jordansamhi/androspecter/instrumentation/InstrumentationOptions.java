package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.inspection.LibraryFilter;
import soot.SootClass;
import soot.SootMethod;

import java.util.Objects;
import java.util.function.Predicate;

/** Immutable, per-logger scope. Defaults select application classes and enable no library classification. */
public final class InstrumentationOptions {
    private final Predicate<SootClass> classes;
    private final Predicate<SootMethod> methods;
    private final LibraryFilter libraries;

    private InstrumentationOptions(Builder builder) {
        classes = builder.classes;
        methods = builder.methods;
        libraries = builder.libraries;
    }

    public static Builder builder() { return new Builder(); }
    public Predicate<SootClass> classFilter() { return classes; }
    public Predicate<SootMethod> methodFilter() { return methods; }
    public LibraryFilter libraryFilter() { return libraries; }

    public static final class Builder {
        private Predicate<SootClass> classes = SootClass::isApplicationClass;
        private Predicate<SootMethod> methods = method -> true;
        private LibraryFilter libraries = LibraryFilter.none();

        private Builder() {}

        /** Replaces the default application-class scope with the supplied predicate. */
        public Builder classes(Predicate<SootClass> predicate) {
            classes = Objects.requireNonNull(predicate, "predicate");
            return this;
        }

        public Builder methods(Predicate<SootMethod> predicate) {
            methods = Objects.requireNonNull(predicate, "predicate");
            return this;
        }

        public Builder libraryFilter(LibraryFilter filter) {
            libraries = Objects.requireNonNull(filter, "filter");
            return this;
        }

        public InstrumentationOptions build() { return new InstrumentationOptions(this); }
    }
}
