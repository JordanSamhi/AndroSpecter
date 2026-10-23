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

    /**
     * Opens an analysis synchronously using CHA, empty source/sink settings, and no
     * phase timeouts. Opening resets Soot and constructs Android entry points and the
     * call graph. Close the returned session on this same thread, for example:
     * <pre>{@code
     * try (AndroidAnalysis analysis = AndroidAnalysis.open(apk, platforms)) {
     *     CallGraphSnapshot graph = analysis.callGraph();
     * }
     * }</pre>
     *
     * @param apk readable APK file
     * @param platforms Android SDK platforms directory containing platform versions
     * @return the initialized session owned by the calling thread
     * @throws NullPointerException if either path is null
     * @throws IllegalArgumentException if the APK is not readable or platforms is not a directory
     * @throws IllegalStateException if another managed session is open
     * @throws CancellationException if the calling thread is interrupted
     * @throws RuntimeException if backend initialization fails; acquired ownership is released
     */
    public static AndroidAnalysis open(Path apk, Path platforms) {
        return builder(apk, platforms).open();
    }

    /**
     * Creates a caller-owned builder without opening files or modifying Soot. Paths are
     * converted to absolute normalized paths now; filesystem validation occurs on opening.
     *
     * @param apk APK file to analyze
     * @param platforms Android SDK platforms directory
     * @return an independent builder with the defaults described by {@link #open(Path, Path)}
     * @throws NullPointerException if either path is null
     */
    public static Builder builder(Path apk, Path platforms) {
        return new Builder(apk, platforms);
    }

    /**
     * Returns this session's stable identity, including after closure or cancellation.
     *
     * @return the identifier attached to this session's detached results
     */
    public UUID id() {
        return context.id();
    }

    /**
     * Reads the package name extracted by FlowDroid from the APK manifest.
     *
     * @return the application's manifest package name
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     * @throws CancellationException if cancellation or thread interruption has been requested
     */
    public String packageName() {
        context.checkValid();
        return flowdroid.getPackageName();
    }

    /**
     * Advanced access for operations that borrow live Soot objects. Closing this
     * view invalidates live access; close AndroidAnalysis itself to release ownership.
     * Do not retain or use borrowed Soot objects after closure or a Soot reset.
     *
     * @return this session's thread-confined context
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     * @throws CancellationException if cancellation or thread interruption has been requested
     */
    public AnalysisContext context() {
        context.checkValid();
        return context;
    }

    /**
     * Returns the mutable metrics accumulator belonging only to this session. Values are
     * caller-managed; obtaining it does not calculate metrics. Its snapshot method produces
     * immutable detached values that remain usable after the session closes.
     *
     * @return this session's independent accumulator
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     * @throws CancellationException if cancellation or thread interruption has been requested
     */
    public ResultsAccumulator metrics() {
        context.checkValid();
        return metrics;
    }

    /**
     * Returns the immutable source/sink settings selected when the session was opened.
     * An explicit configuration passed to a taint run does not replace these defaults.
     * The settings remain available after closure or cancellation.
     *
     * @return the session's configured default source/sink signatures
     */
    public SourceSinkConfiguration sourcesAndSinks() {
        return sourcesAndSinks;
    }

    /**
     * Returns a detached, indexed snapshot of the current call graph. Repeated calls reuse
     * the snapshot until a transformation or taint run invalidates it. After invalidation,
     * this method rebuilds the graph before capturing it. The snapshot contains methods
     * incident to graph edges, not every method declared in the APK.
     *
     * @return an immutable snapshot that remains usable after this session closes
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     * @throws CancellationException if cancellation or thread interruption is observed
     * @throws RuntimeException if call-graph construction or capture fails
     */
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

    /**
     * Runs an inspection synchronously on the owning thread, checking the context before
     * and after it returns. Inspections should read Soot state; use {@link #apply(Transformation)}
     * for mutations so that the cached call graph is invalidated. Callback exceptions propagate.
     *
     * @param inspection operation borrowing this session's live context
     * @throws NullPointerException if {@code inspection} is null
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     * @throws CancellationException if cancellation or thread interruption is observed
     */
    public void inspect(Inspection inspection) {
        context.checkValid();
        Objects.requireNonNull(inspection, "inspection").run(context);
        context.checkValid();
    }

    /**
     * Applies a transformation synchronously on the owning thread and invalidates the cached
     * call graph before it runs. The next {@link #callGraph()} call rebuilds that graph, even
     * if the transformation failed after making partial changes. Exceptions propagate and
     * changes are not rolled back. Compose ordered transformations with TransformationPipeline.
     *
     * @param transformation operation allowed to modify this session's Soot state
     * @throws NullPointerException if {@code transformation} is null
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     * @throws CancellationException if cancellation or thread interruption is observed
     */
    public void apply(Transformation transformation) {
        context.checkValid();
        Objects.requireNonNull(transformation, "transformation");
        // Invalidate even if a transformation changes bodies and then fails.
        graph = null;
        graphDirty = true;
        transformation.apply(context);
        context.checkValid();
    }

    /**
     * Runs taint analysis using {@link #sourcesAndSinks()}. The default builder configuration
     * is empty; explicitly configure sources and sinks to describe the flows of interest.
     * See {@link #analyzeTaint(SourceSinkConfiguration)} for status and cancellation behavior.
     *
     * @return detached findings, completion status, and diagnostics
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     */
    public TaintAnalysisResult analyzeTaint() {
        return analyzeTaint(sourcesAndSinks);
    }

    /**
     * Runs FlowDroid synchronously with the supplied signatures without changing the session's
     * configured defaults. The result distinguishes completion, timeout, cancellation, and
     * backend failure, and may contain partial findings. Cancellation is cooperative and
     * uses FlowDroid's best-effort abort hook; keep the session open until this method returns.
     * Requested cancellation or interruption produces a CANCELLED result, preserving available
     * findings; this wrapper does not clear the thread's interrupt flag. Lifecycle misuse
     * still throws. Starting a run
     * invalidates the cached call graph for a subsequent {@link #callGraph()} call.
     *
     * @param configuration immutable sources and sinks to use for this run
     * @return immutable findings, status, and diagnostics that outlive the session
     * @throws NullPointerException if {@code configuration} is null
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     */
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

    /**
     * Releases this managed session on its creating thread. This unregisters cancellation,
     * drops backend references, and resets Soot only if this session still owns the current
     * generation. A scene created by an external reset is left intact. Cleanup and lease
     * release are attempted even if the cancellation registration's close method fails.
     * Repeated calls on the owner thread are harmless; detached results remain usable.
     *
     * @throws IllegalStateException if called from another thread
     * @throws RuntimeException if a caller-supplied cancellation registration fails to close
     */
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

    /**
     * Mutable, caller-owned session configuration. Configure and open on one thread;
     * changing a builder does not update an already opened session. Defaults are CHA,
     * empty source/sink settings, no explicit cancellation, and disabled phase timeouts.
     */
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

        /**
         * Selects the strategy used to build the call graph.
         *
         * @param algorithm supported call-graph algorithm
         * @return this builder
         * @throws NullPointerException if {@code algorithm} is null
         */
        public Builder callGraphAlgorithm(CallGraphAlgorithm algorithm) {
            this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
            return this;
        }

        /**
         * Sets the immutable defaults used by the session's no-argument taint analysis.
         * Bundled signatures are included only when explicitly requested in the configuration.
         *
         * @param configuration source and sink signatures for future sessions
         * @return this builder
         * @throws NullPointerException if {@code configuration} is null
         */
        public Builder sourcesAndSinks(SourceSinkConfiguration configuration) {
            this.sourcesAndSinks = Objects.requireNonNull(configuration, "configuration");
            return this;
        }

        /**
         * Supplies cooperative cancellation and registers FlowDroid's best-effort data-flow
         * abort hook while the session is open. A caller may signal its source from another
         * thread. Cancellation does not forcibly stop callback or call-graph construction.
         *
         * @param token caller-owned token, whose callbacks may run on the cancelling thread
         * @return this builder
         * @throws NullPointerException if {@code token} is null
         */
        public Builder cancellationToken(CancellationToken token) {
            this.cancellation = Objects.requireNonNull(token, "token");
            return this;
        }

        /**
         * Sets FlowDroid's callback-analysis limit. Positive durations round up to whole
         * seconds; zero disables the limit. Expiration can yield a partial callback/call graph
         * and is not a deadline for the entire session-opening operation.
         *
         * @param timeout nonnegative duration no greater than Integer.MAX_VALUE seconds
         * @return this builder
         * @throws NullPointerException if {@code timeout} is null
         * @throws IllegalArgumentException if the duration is negative or exceeds the supported range
         */
        public Builder callbackTimeout(Duration timeout) {
            this.callbackTimeout = validDuration(timeout);
            return this;
        }

        /**
         * Sets FlowDroid's data-flow phase limit. Positive durations round up to whole
         * seconds; zero disables the limit. This is not a deadline for the entire taint run.
         *
         * @param timeout nonnegative duration no greater than Integer.MAX_VALUE seconds
         * @return this builder
         * @throws NullPointerException if {@code timeout} is null
         * @throws IllegalArgumentException if the duration is negative or exceeds the supported range
         */
        public Builder taintTimeout(Duration timeout) {
            this.taintTimeout = validDuration(timeout);
            return this;
        }

        /**
         * Sets FlowDroid's path-reconstruction phase limit. Positive durations round up to
         * whole seconds; zero disables the limit. Flows without reconstructed paths may
         * still be present in the returned taint findings.
         *
         * @param timeout nonnegative duration no greater than Integer.MAX_VALUE seconds
         * @return this builder
         * @throws NullPointerException if {@code timeout} is null
         * @throws IllegalArgumentException if the duration is negative or exceeds the supported range
         */
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

        /**
         * Validates paths, acquires exclusive managed-session ownership, resets Soot, and
         * constructs the entry points and call graph synchronously. Close the returned
         * session on this thread before opening another. If opening fails, acquired
         * ownership is released and initialization cleanup is attempted.
         *
         * @return the initialized session using this builder's current settings
         * @throws IllegalArgumentException if the APK is unreadable or platforms is not a directory
         * @throws IllegalStateException if another managed session is open
         * @throws CancellationException if the token is cancelled or this thread is interrupted
         * @throws RuntimeException if backend initialization or cancellation registration fails
         */
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
