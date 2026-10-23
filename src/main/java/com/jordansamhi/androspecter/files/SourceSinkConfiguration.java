package com.jordansamhi.androspecter.files;

import soot.jimple.infoflow.android.data.AndroidMethod;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Immutable method-signature configuration; contains no mutable FlowDroid or Soot objects. */
public record SourceSinkConfiguration(Set<String> sources, Set<String> sinks) {
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

    public static Builder builder() {
        return new Builder();
    }

    public static SourceSinkConfiguration empty() {
        return new SourceSinkConfiguration(Set.of(), Set.of());
    }

    public static SourceSinkConfiguration bundledDefaults() {
        return fromManager(new SourcesSinksManager());
    }

    /** Loads exactly this UTF-8 file, without implicitly adding bundled entries. */
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

    /** Returns fresh FlowDroid objects, so caller mutation cannot alter this configuration. */
    public Set<AndroidMethod> toSourceMethods() {
        return toMethods(sources);
    }

    public Set<AndroidMethod> toSinkMethods() {
        return toMethods(sinks);
    }

    private static Set<AndroidMethod> toMethods(Set<String> signatures) {
        return signatures.stream().map(s -> SourcesSinksManager.parseSignature(s, "configuration"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static final class Builder {
        private final Set<String> sources = new LinkedHashSet<>();
        private final Set<String> sinks = new LinkedHashSet<>();

        public Builder source(String signature) {
            sources.add(signature);
            return this;
        }

        public Builder sink(String signature) {
            sinks.add(signature);
            return this;
        }

        public Builder include(SourceSinkConfiguration configuration) {
            sources.addAll(configuration.sources());
            sinks.addAll(configuration.sinks());
            return this;
        }

        public SourceSinkConfiguration build() {
            return new SourceSinkConfiguration(sources, sinks);
        }
    }
}
