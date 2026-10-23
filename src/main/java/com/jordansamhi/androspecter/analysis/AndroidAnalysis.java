package com.jordansamhi.androspecter.analysis;

import com.jordansamhi.androspecter.FlowdroidUtils;
import com.jordansamhi.androspecter.ResultsAccumulator;
import com.jordansamhi.androspecter.execution.CancellationToken;
import com.jordansamhi.androspecter.files.SourceSinkConfiguration;
import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import com.jordansamhi.androspecter.inspection.Inspection;
import com.jordansamhi.androspecter.instrumentation.Transformation;
import com.jordansamhi.androspecter.result.AnalysisStatus;
import com.jordansamhi.androspecter.result.TaintAnalysisResult;
import soot.G;
import soot.Scene;
import soot.jimple.infoflow.android.SetupApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One Android analysis with an owned Soot lifecycle. Opening constructs the
 * Android entry points and call graph. Use try-with-resources on the creating
 * thread. Only one managed analysis can be open in this JVM at a time; external
 * Soot users must also coordinate access. Detached results outlive close/reset.
 */
public final class AndroidAnalysis implements AutoCloseable {
    private static final AtomicReference<UUID> ACTIVE = new AtomicReference<>();
    private final AnalysisContext context;
    private FlowdroidUtils flowdroid;
    private SetupApplication application;
    private final SourceSinkConfiguration sourcesAndSinks;
    private final CancellationToken cancellation;
    private CancellationToken.Registration registration;
    private final ResultsAccumulator metrics = new ResultsAccumulator();
    private CallGraphSnapshot graph;
    private boolean graphDirty;
    private boolean closed;

    private AndroidAnalysis(AnalysisContext context, FlowdroidUtils flowdroid, SetupApplication application,
                            SourceSinkConfiguration sourcesAndSinks, CancellationToken cancellation,
                            CancellationToken.Registration registration) {
        this.context = context;
        this.flowdroid = flowdroid;
        this.application = application;
        this.sourcesAndSinks = sourcesAndSinks;
        this.cancellation = cancellation;
        this.registration = registration;
    }

    public static AndroidAnalysis open(Path apk, Path platforms) {
        return builder(apk, platforms).open();
    }

    public static Builder builder(Path apk, Path platforms) {
        return new Builder(apk, platforms);
    }

    public UUID id() {
        return context.id();
    }

    public String packageName() {
        context.checkValid();
        return flowdroid.getPackageName();
    }

    /**
     * Advanced access for operations that borrow live Soot objects. Closing this
     * view invalidates live access; close AndroidAnalysis itself to release ownership.
     */
    public AnalysisContext context() {
        context.checkValid();
        return context;
    }

    /** Independent metrics; snapshot() produces immutable detached values. */
    public ResultsAccumulator metrics() {
        context.checkValid();
        return metrics;
    }

    public SourceSinkConfiguration sourcesAndSinks() {
        return sourcesAndSinks;
    }

    /** Captures and indexes once, until an analysis or transformation invalidates the cache. */
    public CallGraphSnapshot callGraph() {
        context.checkValid();
        if (graphDirty) {
            application.constructCallgraph();
            context.checkValid();
            graphDirty = false;
        }
        if (graph == null) {
            graph = CallGraphSnapshot.capture(id(), Scene.v().getCallGraph());
        }
        context.checkValid();
        return graph;
    }

    public void inspect(Inspection inspection) {
        context.checkValid();
        Objects.requireNonNull(inspection, "inspection").run(context);
        context.checkValid();
    }

    /** Ordered transformations can be composed using TransformationPipeline. No rollback is implied. */
    public void apply(Transformation transformation) {
        context.checkValid();
        Objects.requireNonNull(transformation, "transformation");
        // Invalidate even if a transformation changes bodies and then fails.
        graph = null;
        graphDirty = true;
        transformation.apply(context);
        context.checkValid();
    }

    public TaintAnalysisResult analyzeTaint() {
        return analyzeTaint(sourcesAndSinks);
    }

    /** Returns completion status and partial findings instead of turning failures into empty success. */
    public TaintAnalysisResult analyzeTaint(SourceSinkConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        try {
            context.checkValid();
        } catch (CancellationException ex) {
            return new TaintAnalysisResult(id(), AnalysisStatus.CANCELLED, List.of(), List.of(ex.toString()));
        }
        graph = null;
        graphDirty = true;
        TaintAnalysisResult result = flowdroid.runTaintAnalysisResult(
                configuration.toSourceMethods(), configuration.toSinkMethods(), id());
        // A reset is a lifecycle error even if the backend returned an analysis result.
        if (!context.isValid()) {
            context.checkValid();
        }
        if (cancellation.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            List<String> diagnostics = new ArrayList<>(result.diagnostics());
            diagnostics.add("Cancellation was requested by the calling application");
            return new TaintAnalysisResult(id(), AnalysisStatus.CANCELLED, result.findings(), diagnostics);
        }
        return result;
    }

    @Override
    public void close() {
        context.checkOwner();
        if (closed) {
            return;
        }
        closed = true;
        try {
            registration.close();
        } finally {
            try {
                // Do not reset somebody else's scene if external code already called G.reset().
                if (context.ownsCurrentGeneration()) {
                    G.reset();
                }
            } finally {
                // A retained closed handle must not retain the backend's scene/results.
                flowdroid = null;
                application = null;
                registration = null;
                graph = null;
                context.close();
                ACTIVE.compareAndSet(context.id(), null);
            }
        }
    }

    public static final class Builder {
        private final Path apk;
        private final Path platforms;
        private CallGraphAlgorithm algorithm = CallGraphAlgorithm.CHA;
        private SourceSinkConfiguration sourcesAndSinks = SourceSinkConfiguration.empty();
        private CancellationToken cancellation = CancellationToken.none();
        private Duration callbackTimeout = Duration.ZERO;
        private Duration taintTimeout = Duration.ZERO;
        private Duration pathTimeout = Duration.ZERO;

        private Builder(Path apk, Path platforms) {
            this.apk = Objects.requireNonNull(apk, "apk").toAbsolutePath().normalize();
            this.platforms = Objects.requireNonNull(platforms, "platforms").toAbsolutePath().normalize();
        }

        public Builder callGraphAlgorithm(CallGraphAlgorithm algorithm) {
            this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
            return this;
        }

        public Builder sourcesAndSinks(SourceSinkConfiguration configuration) {
            this.sourcesAndSinks = Objects.requireNonNull(configuration, "configuration");
            return this;
        }

        /** Cooperative cancellation, plus FlowDroid's best-effort data-flow abort hook. */
        public Builder cancellationToken(CancellationToken token) {
            this.cancellation = Objects.requireNonNull(token, "token");
            return this;
        }

        /** FlowDroid phase limits, rounded up to seconds; zero disables the respective limit. */
        public Builder callbackTimeout(Duration timeout) {
            this.callbackTimeout = validDuration(timeout);
            return this;
        }

        public Builder taintTimeout(Duration timeout) {
            this.taintTimeout = validDuration(timeout);
            return this;
        }

        public Builder pathTimeout(Duration timeout) {
            this.pathTimeout = validDuration(timeout);
            return this;
        }

        private static Duration validDuration(Duration value) {
            Objects.requireNonNull(value, "timeout");
            if (value.isNegative()) {
                throw new IllegalArgumentException("Timeout must not be negative");
            }
            if (value.compareTo(Duration.ofSeconds(Integer.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException("Timeout exceeds FlowDroid's supported range");
            }
            return value;
        }

        private static int seconds(Duration duration) {
            return Math.toIntExact(duration.getSeconds() + (duration.getNano() == 0 ? 0 : 1));
        }

        public AndroidAnalysis open() {
            return openWith(new FlowdroidUtils(apk.toString()));
        }

        // Test seam: normal clients always use the real FlowDroid wrapper above.
        AndroidAnalysis openWith(FlowdroidUtils flowdroid) {
            cancellation.throwIfCancellationRequested();
            if (!Files.isRegularFile(apk) || !Files.isReadable(apk)) {
                throw new IllegalArgumentException("APK is not readable: " + apk);
            }
            if (!Files.isDirectory(platforms)) {
                throw new IllegalArgumentException("Android platforms directory is missing: " + platforms);
            }
            UUID id = UUID.randomUUID();
            if (!ACTIVE.compareAndSet(null, id)) {
                throw new IllegalStateException("Another AndroidAnalysis is open; close it before opening a new analysis");
            }
            CancellationToken.Registration registration = null;
            boolean initializationStarted = false;
            try {
                registration = Objects.requireNonNull(cancellation.onCancellation(flowdroid::abortAnalysis),
                        "cancellation registration");
                cancellation.throwIfCancellationRequested();
                initializationStarted = true;
                SetupApplication application = flowdroid.initializeFlowdroid(platforms.toString(), null,
                        algorithm.name(), configuration -> {
                            // The wrapper resets its cancellation latch during initialization.
                            cancellation.throwIfCancellationRequested();
                            configuration.getCallbackConfig().setCallbackAnalysisTimeout(seconds(callbackTimeout));
                            configuration.setDataFlowTimeout(seconds(taintTimeout));
                            configuration.getPathConfiguration().setPathReconstructionTimeout(seconds(pathTimeout));
                        });
                cancellation.throwIfCancellationRequested();
                return new AndroidAnalysis(AnalysisContext.capture(id, cancellation), flowdroid,
                        application, sourcesAndSinks, cancellation, registration);
            } catch (RuntimeException | Error ex) {
                if (registration != null) {
                    try {
                        registration.close();
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (cleanupFailure != ex) {
                            ex.addSuppressed(cleanupFailure);
                        }
                    }
                }
                try {
                    if (initializationStarted) {
                        G.reset();
                    }
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != ex) {
                        ex.addSuppressed(cleanupFailure);
                    }
                } finally {
                    ACTIVE.compareAndSet(id, null);
                }
                throw ex;
            }
        }
    }
}
