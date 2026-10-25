package com.jordansamhi.androspecter.files;

import soot.jimple.infoflow.android.data.AndroidMethod;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Immutable source/sink definitions containing only full Jimple method signatures.
 * No mutable FlowDroid or Soot objects are retained. Signatures are syntax-checked and
 * normalized without loading the declaring classes; membership does not prove that a
 * method exists in the analyzed APK or framework. The same signature may be both a source
 * and a sink. For example:
 * <pre>{@code
 * var definitions = SourceSinkConfiguration.builder()
 *     .source("<example.Input: java.lang.String read()>")
 *     .sink("<example.Output: void send(java.lang.String)>")
 *     .build();
 * }</pre>
 *
 * @param sources non-null source signatures with no null elements; copied, normalized, and
 *                exposed as an immutable set in lexicographic order
 * @param sinks non-null sink signatures with no null elements; copied, normalized, and
 *              exposed as an immutable set in lexicographic order
 */
public record SourceSinkConfiguration(Set<String> sources, Set<String> sinks) {
    /**
     * Creates an independent configuration from the supplied signature sets.
     * Later changes to the input sets cannot affect this configuration.
     *
     * @param sources non-null full Jimple source signatures
     * @param sinks non-null full Jimple sink signatures
     * @throws NullPointerException if either set or a signature is null
     * @throws IllegalArgumentException if a signature has malformed method or parameter syntax
     */
    public SourceSinkConfiguration {
        sources = normalized(sources);
        sinks = normalized(sinks);
    }

    private static Set<String> normalized(Set<String> signatures) {
        TreeSet<String> normalized = new TreeSet<>();
        for (String signature : Objects.requireNonNull(signatures, "signatures")) {
            normalized.add(SourcesSinksManager.parseSignature(signature, "configuration").getSignature());
        }
        return Collections.unmodifiableSet(normalized);
    }

    /**
     * Starts an independent mutable builder with no source or sink definitions.
     *
     * @return new builder; bundled definitions are not added implicitly
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a configuration with no source or sink definitions.
     *
     * @return immutable configuration whose source and sink sets are both empty
     */
    public static SourceSinkConfiguration empty() {
        return new SourceSinkConfiguration(Set.of(), Set.of());
    }

    /**
     * Loads an independent snapshot of the source/sink definitions bundled with this library.
     * Changes to the legacy {@link SourcesSinksManager#v()} singleton do not affect this result.
     *
     * @return immutable bundled definitions
     * @throws IllegalStateException if the bundled classpath resource is missing
     * @throws java.io.UncheckedIOException if the bundled resource cannot be read
     * @throws IllegalArgumentException if a bundled record is malformed
     */
    public static SourceSinkConfiguration bundledDefaults() {
        return fromManager(new SourcesSinksManager());
    }

    /**
     * Loads exactly the definitions in a UTF-8 file, without adding bundled entries.
     * Each nonblank, noncomment line must have the form
     * {@code SOURCE|<example.Input: java.lang.String read()>} or the corresponding
     * {@code SINK|...} form. Leading/trailing whitespace is ignored, and lines whose
     * first nonwhitespace character is {@code #} are comments. To merge definitions,
     * use {@link Builder#include(SourceSinkConfiguration)} explicitly.
     *
     * @param path non-null path to the definition file
     * @return immutable configuration containing only the file's definitions
     * @throws NullPointerException if {@code path} is null
     * @throws IllegalArgumentException if a record is malformed; the message identifies its line
     * @throws java.io.UncheckedIOException if the file or required classpath resource cannot be read
     * @throws IllegalStateException if the manager's required bundled classpath resource is missing
     * @throws UnsupportedOperationException if {@code path} cannot be converted to a {@link java.io.File}
     */
    public static SourceSinkConfiguration fromFile(Path path) {
        SourcesSinksManager manager = new SourcesSinksManager(false);
        manager.loadSourcesSinksFromFile(Objects.requireNonNull(path, "path").toFile());
        return fromManager(manager);
    }

    private static SourceSinkConfiguration fromManager(SourcesSinksManager manager) {
        return new SourceSinkConfiguration(
                manager.getSources().stream().map(AndroidMethod::getSignature).collect(Collectors.toSet()),
                manager.getSinks().stream().map(AndroidMethod::getSignature).collect(Collectors.toSet()));
    }

    /**
     * Converts source signatures to fresh FlowDroid method descriptors without resolving Soot methods.
     * Both the returned set and its descriptors are mutable and independent of this configuration
     * and of previous conversions. Pass this set as the sources argument to FlowDroid.
     *
     * @return new mutable source descriptor set, empty when no sources are configured
     */
    public Set<AndroidMethod> toSourceMethods() {
        return toMethods(sources);
    }

    /**
     * Converts sink signatures to fresh FlowDroid method descriptors without resolving Soot methods.
     * Both the returned set and its descriptors are mutable and independent of this configuration
     * and of previous conversions. Pass this set as the sinks argument to FlowDroid.
     *
     * @return new mutable sink descriptor set, empty when no sinks are configured
     */
    public Set<AndroidMethod> toSinkMethods() {
        return toMethods(sinks);
    }

    private static Set<AndroidMethod> toMethods(Set<String> signatures) {
        return signatures.stream().map(s -> SourcesSinksManager.parseSignature(s, "configuration"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Mutable, reusable configuration builder. Its public no-argument constructor creates
     * an empty builder without bundled definitions. Signatures are validated by {@link #build()},
     * and each built configuration is independent of subsequent builder changes.
     * Builder instances are not thread-safe.
     */
    public static final class Builder {
        private final Set<String> sources = new LinkedHashSet<>();
        private final Set<String> sinks = new LinkedHashSet<>();

        /**
         * Creates an independent, empty builder, equivalent to {@link SourceSinkConfiguration#builder()}.
         * No bundled definitions are loaded or added. Add signatures with {@link #source(String)}
         * and {@link #sink(String)}, or merge a configuration with {@link #include(SourceSinkConfiguration)},
         * before calling {@link #build()}. Building immediately produces empty source and sink sets.
         */
        public Builder() {
        }

        /**
         * Adds a source signature; repeated signatures are deduplicated when building.
         * Validation is deferred until {@link #build()}.
         *
         * @param signature full Jimple signature, required to be non-null and valid when built
         * @return this builder for chaining
         */
        public Builder source(String signature) {
            sources.add(signature);
            return this;
        }

        /**
         * Adds a sink signature; repeated signatures are deduplicated when building.
         * Validation is deferred until {@link #build()}.
         *
         * @param signature full Jimple signature, required to be non-null and valid when built
         * @return this builder for chaining
         */
        public Builder sink(String signature) {
            sinks.add(signature);
            return this;
        }

        /**
         * Adds all source and sink signatures from another configuration, retaining current entries.
         *
         * @param configuration non-null immutable configuration to merge
         * @return this builder for chaining
         * @throws NullPointerException if {@code configuration} is null
         */
        public Builder include(SourceSinkConfiguration configuration) {
            sources.addAll(configuration.sources());
            sinks.addAll(configuration.sinks());
            return this;
        }

        /**
         * Validates the accumulated signatures and copies them into an immutable configuration.
         * The builder remains usable after this call.
         *
         * @return new immutable configuration independent of later builder mutations
         * @throws NullPointerException if any added signature is null
         * @throws IllegalArgumentException if an added signature is malformed
         */
        public SourceSinkConfiguration build() {
            return new SourceSinkConfiguration(sources, sinks);
        }
    }
}
