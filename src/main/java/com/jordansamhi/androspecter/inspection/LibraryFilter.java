package com.jordansamhi.androspecter.inspection;

import com.jordansamhi.androspecter.files.LibrariesManager;
import soot.SootClass;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;

/** Classifies library classes. Classification is a caller-supplied policy, not a statement of ownership. */
@FunctionalInterface
public interface LibraryFilter extends Predicate<SootClass> {
    boolean isLibrary(SootClass clazz);

    @Override
    default boolean test(SootClass clazz) {
        return isLibrary(clazz);
    }

    @Override
    default LibraryFilter and(Predicate<? super SootClass> other) {
        Objects.requireNonNull(other, "other");
        return clazz -> isLibrary(clazz) && other.test(clazz);
    }

    @Override
    default LibraryFilter or(Predicate<? super SootClass> other) {
        Objects.requireNonNull(other, "other");
        return clazz -> isLibrary(clazz) || other.test(clazz);
    }

    @Override
    default LibraryFilter negate() {
        return clazz -> !isLibrary(clazz);
    }

    static LibraryFilter none() {
        return clazz -> false;
    }

    static LibraryFilter from(Predicate<? super SootClass> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return predicate::test;
    }

    /** Matches exact package names and their subpackages, not adjacent names such as com.exampleother. */
    static LibraryFilter packages(String... packageNames) {
        String[] prefixes = Objects.requireNonNull(packageNames, "packageNames").clone();
        Arrays.stream(prefixes).forEach(prefix -> {
            if (prefix == null || prefix.isBlank() || prefix.endsWith(".")) {
                throw new IllegalArgumentException("Package names must be nonblank and must not end with a dot");
            }
        });
        return clazz -> Arrays.stream(prefixes).anyMatch(prefix -> clazz.getPackageName().equals(prefix)
                || clazz.getPackageName().startsWith(prefix + "."));
    }

    /** Explicit adapter for the bundled, legacy package list. New inspections do not enable this implicitly. */
    static LibraryFilter knownLibraries() {
        return clazz -> LibrariesManager.v().isLibrary(clazz);
    }
}
