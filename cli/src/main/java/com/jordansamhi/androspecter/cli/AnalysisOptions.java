package com.jordansamhi.androspecter.cli;

import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.analysis.CallGraphAlgorithm;
import com.jordansamhi.androspecter.execution.CancellationSource;
import com.jordansamhi.androspecter.execution.Deadline;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class AnalysisOptions {
    @Option(names = "--platforms", paramLabel = "DIR", description = "Android SDK platforms directory; required for APK analysis.") Path platforms;
    @Option(names = "--algorithm", defaultValue = "CHA", description = "Graph/taint algorithm: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).") CallGraphAlgorithm algorithm;
    @Option(names = "--classpath", paramLabel = "PATH", description = "Additional dependency JAR/directory; repeat for multiple entries.") List<Path> classpath = new ArrayList<>();
    @Option(names = "--timeout", defaultValue = "0", paramLabel = "SECONDS", description = "Cooperative cancellation deadline; 0 disables it (not a hard runtime bound).") int timeout;
    @Option(names = "--callback-timeout", defaultValue = "0", paramLabel = "SECONDS", description = "Graph/taint callback phase limit; 0 disables it. Expiration can produce a partial graph.") int callbackTimeout;

    void validate(CliSupport command, Path apk) {
        command.requireFile(apk, "APK");
        if (platforms == null || !Files.isDirectory(platforms) || !Files.isReadable(platforms)) {
            throw command.usage("--platforms must name a readable Android SDK platforms directory");
        }
        if (timeout < 0 || callbackTimeout < 0) throw command.usage("Timeouts must be nonnegative seconds");
        for (Path entry : classpath) {
            if (entry.toAbsolutePath().normalize().toString().indexOf(java.io.File.pathSeparatorChar) >= 0) {
                throw command.usage("Classpath entry contains the platform path separator: " + entry);
            }
            if ((!Files.isDirectory(entry) && !Files.isRegularFile(entry)) || !Files.isReadable(entry)) {
                throw command.usage("Classpath entry is not readable: " + entry);
            }
        }
    }

    void rejectForOffline(CliSupport command) {
        for (String name : List.of("--platforms", "--algorithm", "--classpath", "--timeout", "--callback-timeout")) {
            if (command.spec.commandLine().getParseResult().hasMatchedOption(name)) {
                throw command.usage(name + " is only available with --apk");
            }
        }
    }

    List<Path> inputs(Path apk) {
        List<Path> inputs = new ArrayList<>(classpath);
        inputs.add(apk);
        inputs.add(platforms);
        return inputs;
    }

    AndroidAnalysis.Builder builder(Path apk, RunControl control) {
        return AndroidAnalysis.builder(apk, platforms).callGraphAlgorithm(algorithm)
                .additionalClasspath(classpath.toArray(Path[]::new))
                .callbackTimeout(Duration.ofSeconds(callbackTimeout))
                .cancellationToken(control.source.token());
    }

    static final class RunControl implements AutoCloseable {
        final CancellationSource source = new CancellationSource();
        private final Deadline deadline;
        RunControl(int seconds) {
            deadline = seconds > 0 ? Deadline.after(Duration.ofSeconds(seconds), source::cancel) : null;
        }

        /** Disarms the deadline and closes this command's cancellation source. */
        @Override public void close() {
            if (deadline != null) deadline.close();
            source.close();
        }
    }
}
