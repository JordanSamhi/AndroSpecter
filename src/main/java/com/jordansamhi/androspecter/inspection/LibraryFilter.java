package com.jordansamhi.androspecter.inspection;

import com.jordansamhi.androspecter.files.LibrariesManager;
import soot.SootClass;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Classifies library classes using a caller-selected policy, independently of Soot's application-class flag.
 * Instances compose as predicates; {@code true} means the class is a library. Classes passed to a filter are
 * borrowed Soot objects and must only be used within their owning analysis's thread and lifetime.
 * Implementations should avoid scene mutation; exceptions propagate to the inspection or transformation.
 * Composition retains the original filter instances and their captured state. It does not
 * make mutable predicates thread-safe or freeze a changing classification policy. Null class
 * arguments are outside this interface's contract; implementations need not handle them uniformly.
 */
@FunctionalInterface
public interface LibraryFilter extends Predicate<SootClass> {
    /**
     * Determines whether a class belongs to the libraries described by this policy.
     *
     * @param clazz borrowed class to classify; callers supply a non-null class
     * @return {@code true} if this policy classifies the class as a library
     */
    boolean isLibrary(SootClass clazz);

    /**
     * Adapts {@link #isLibrary(SootClass)} to the standard predicate interface.
     *
     * @param clazz non-null borrowed class to classify
     * @return the result of {@link #isLibrary(SootClass)}
     */
    @Override
    default boolean test(SootClass clazz) {
        return isLibrary(clazz);
    }

    /**
     * Creates a policy requiring both this filter and {@code other} to match.
     * The second predicate is evaluated only when this filter matches.
     *
     * @param other additional classification predicate
     * @return a composed filter retaining both predicates
     * @throws NullPointerException if {@code other} is null
     */
    @Override
    default LibraryFilter and(Predicate<? super SootClass> other) {
        Objects.requireNonNull(other, "other");
        return clazz -> isLibrary(clazz) && other.test(clazz);
    }

    /**
     * Creates a policy matching either this filter or {@code other}.
     * The second predicate is evaluated only when this filter does not match.
     *
     * @param other additional classification predicate
     * @return a composed filter retaining both predicates
     * @throws NullPointerException if {@code other} is null
     */
    @Override
    default LibraryFilter or(Predicate<? super SootClass> other) {
        Objects.requireNonNull(other, "other");
        return clazz -> isLibrary(clazz) || other.test(clazz);
    }

    /**
     * Creates a policy that reverses this filter's classification.
     *
     * @return a filter matching precisely the classes this filter rejects
     */
    @Override
    default LibraryFilter negate() {
        return clazz -> !isLibrary(clazz);
    }

    /**
     * Creates a policy that classifies no classes as libraries.
     *
     * @return an always-false filter
     */
    static LibraryFilter none() {
        return clazz -> false;
    }

    /**
     * Wraps a predicate as a library classification policy without copying its captured state.
     *
     * @param predicate predicate whose {@code true} results identify libraries
     * @return a filter delegating to {@code predicate}
     * @throws NullPointerException if {@code predicate} is null
     */
    static LibraryFilter from(Predicate<? super SootClass> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return predicate::test;
    }

    /**
     * Matches any listed package or its subpackages using package boundaries.
     * For example, {@code com.example} matches {@code com.example.util} but not {@code com.exampleother}.
     * The array is copied; an empty array matches no classes. Names are matched exactly
     * as supplied: whitespace is not trimmed and Java package-name syntax is not validated
     * beyond the checks described below. The class's package is read when the filter is evaluated.
     *
     * @param packageNames nonblank package names without a trailing dot
     * @return a filter matching the union of the listed packages and subpackages
     * @throws NullPointerException if {@code packageNames} is null
     * @throws IllegalArgumentException if any element is null, blank, or ends with a dot
     */
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

    /**
     * Adapts the legacy {@link LibrariesManager} classification, including its current configured package list.
     * The manager is consulted when the filter is evaluated; its list is not snapshotted.
     * Thus later changes to the legacy manager can change the filter's answer.
     * New inspections and instance loggers do not enable this policy implicitly.
     *
     * @return a filter delegating to the legacy library manager
     */
    static LibraryFilter knownLibraries() {
        return clazz -> LibrariesManager.v().isLibrary(clazz);
    }
}
