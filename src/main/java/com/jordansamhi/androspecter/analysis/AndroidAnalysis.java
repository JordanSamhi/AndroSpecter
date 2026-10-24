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
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.SetupApplication;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One Android analysis with an owned Soot lifecycle. Opening normally constructs the
 * Android entry points and call graph; class-only mode defers that work until needed.
 * Open, inspect, transform, analyze, and close on the same thread, using try-with-resources.
 * Only one managed analysis can be open in this JVM at a time; external Soot users must
 * coordinate access as well. Opening replaces existing Soot state, and closing normally
 * resets the owned scene. Sessions do not restore a previous scene.
 * <p>
 * Cancellation requests may originate on another thread, but the owner must wait for an
 * operation to return before closing the session. Graph/taint snapshots contain detached
 * values that survive close or reset. Borrowed Soot classes, methods, bodies, and units do
 * not. {@link #id()} and {@link #sourcesAndSinks()} remain available without live-context
 * checks; other accessors require an open, uncancelled session on its owner thread.
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
                            CancellationToken.Registration registration, InitializationMode initializationMode) {
        this.context = context;
        this.flowdroid = flowdroid;
        this.application = application;
        this.sourcesAndSinks = sourcesAndSinks;
        this.cancellation = cancellation;
        this.registration = registration;
        this.graphDirty = initializationMode == InitializationMode.CLASSES_ONLY;
    }

    /**
     * Opens an eager analysis synchronously using CHA, empty source/sink settings,
     * {@link CallbackOptions#defaults()}, precise path reconstruction, disabled code
     * elimination, and no phase timeouts or additional classpath. Thread interruption is
     * still observed. Opening resets Soot and constructs Android entry points and the
     * call graph. Close the returned session on this same thread. In this method-scope
     * example, {@code apk} and {@code platforms} are existing {@link Path} values:
     * <pre>{@code
     * try (AndroidAnalysis analysis = AndroidAnalysis.open(apk, platforms)) {
     *     CallGraphSnapshot graph = analysis.callGraph();
     * }
     * }</pre>
     *
     * @param apk non-null readable APK file on the default filesystem
     * @param platforms non-null Android SDK platforms directory containing installed platform versions;
     *                  a single {@code android.jar} is not accepted by the managed API
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
     * The mutable builder is not thread-safe and may be reused for sequential sessions.
     *
     * @param apk non-null APK file to analyze; the backend requires a default-filesystem path
     * @param platforms non-null Android SDK platforms directory, not an individual platform JAR
     * @return an independent builder with the defaults described by {@link #open(Path, Path)}
     * @throws NullPointerException if either path is null
     */
    public static Builder builder(Path apk, Path platforms) {
        return new Builder(apk, platforms);
    }

    /**
     * Returns this session's stable identity, including after closure, cancellation, or a Soot reset.
     * It may be read from any thread. Multiple taint runs and graph snapshots in this session
     * share this identifier; it is not a distinct identifier for each operation.
     *
     * @return the identifier attached to this session's detached results
     */
    public UUID id() {
        return context.id();
    }

    /**
     * Reads the manifest package lazily through the low-level helper. The first result,
     * including a failed read, is cached for this session. Read failures are logged and
     * return null instead of throwing a parsing exception; later calls do not retry.
     *
     * @return the manifest package name, or null if unavailable or manifest processing failed
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     * @throws CancellationException if cancellation or thread interruption has been requested
     */
    public String packageName() {
        context.checkValid();
        return flowdroid.getPackageName();
    }

    /**
     * Returns the same context on each call for operations borrowing live Soot objects.
     * Closing this view makes subsequent checked operations on the session invalid, but
     * does not release session ownership or reset Soot; close AndroidAnalysis itself too.
     * Do not retain or use borrowed Soot objects after closure or a Soot reset. Mutate the
     * scene through {@link #apply(Transformation)} so graph snapshots are invalidated.
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
     * caller-managed; obtaining it does not calculate metrics or set an application name.
     * Repeated calls return the same accumulator. A previously obtained accumulator contains
     * no Soot objects, remains usable after session closure, and is not cleared by closing.
     * Its snapshot method copies the current values into an immutable detached result.
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
     * The settings remain available on any thread after closure, cancellation, or Soot reset.
     *
     * @return the session's configured default source/sink signatures
     */
    public SourceSinkConfiguration sourcesAndSinks() {
        return sourcesAndSinks;
    }

    /**
     * Returns a detached, indexed snapshot of the current call graph. Repeated calls reuse
     * the snapshot until a transformation or taint run invalidates it. In class-only mode,
     * the first call constructs Android entry points and the graph in the existing scene.
     * After invalidation, this method rebuilds the graph before capturing it. The snapshot
     * contains methods incident to graph edges, including framework and generated methods,
     * not every method declared in the APK. Inspection predicates do not restrict this graph.
     * <p>
     * Direct scene/body mutations outside {@link #apply(Transformation)} are not detected and
     * can leave the cached snapshot stale. Cancellation is checked before and after backend
     * construction/capture; it is not a hard deadline for either operation. A callback phase
     * timeout can leave a partial graph even when this method returns without an exception.
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
     * for mutations so that the cached call graph is invalidated. This method does not
     * promote a class-only session to graph construction. The inspection may load method
     * bodies according to its registered callbacks. Callback exceptions propagate.
     *
     * @param inspection operation borrowing this session's live context
     * @throws NullPointerException if {@code inspection} is null
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     * @throws CancellationException if cancellation or thread interruption is observed
     * @throws RuntimeException if a predicate, visitor, or body-loading operation fails
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
     * changes are not rolled back. This method does not itself construct a graph; transformations
     * must not reset Soot, close the session, or retain borrowed values beyond its lifetime.
     * Compose ordered stages with
     * {@link com.jordansamhi.androspecter.instrumentation.TransformationPipeline}.
     *
     * @param transformation operation allowed to modify this session's Soot state
     * @throws NullPointerException if {@code transformation} is null
     * @throws IllegalStateException if the session is closed, Soot was reset, or the caller is not its owner
     * @throws CancellationException if cancellation or thread interruption is observed
     * @throws RuntimeException if the transformation fails; earlier changes remain in the scene
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
     * still throws. In class-only mode, FlowDroid constructs Android entry points and a graph
     * as part of the run without resetting the loaded scene. Starting a run
     * invalidates the cached call graph for a subsequent {@link #callGraph()} call. Each result
     * carries this session's {@link #id()}, including when different definitions are used in
     * successive runs. The analysis and any backend code transformations affect the live model.
     * <p>
     * An already-cancelled session returns an empty CANCELLED result without invoking FlowDroid.
     * A returned backend result can contribute partial findings; an exception before it is
     * returned yields no findings. Missing reconstructed paths do not remove findings.
     * Diagnostics include reported backend failures and termination information, not every log
     * warning. Callback-analysis limits can yield an incomplete model without a taint timeout
     * status. JVM errors are not converted to result statuses.
     *
     * @param configuration non-null immutable sources and sinks to use for this run; either set may be empty
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
     * Repeated calls on the owner thread are harmless, including after cancellation or a
     * previous close failure. Detached results and a previously obtained metrics accumulator
     * remain usable. This method does not cancel/close the caller's cancellation source, wait
     * for an in-flight cancellation callback, or run pending analysis work to completion.
     *
     * @throws IllegalStateException if called from another thread
     * @throws RuntimeException if cancellation unregistration or Soot cleanup fails
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
     * eager initialization, precise path reconstruction, default callback discovery,
     * empty source/sink settings, no additional classpath or explicit cancellation,
     * disabled code elimination, and disabled phase timeouts. Thread interruption is
     * observed even with no explicit token. Create builders with {@link AndroidAnalysis#builder(Path, Path)}.
     * <p>
     * Setters replace their respective setting. The advanced configuration callback runs
     * on every {@link #open()} after all typed settings, regardless of setter call order,
     * and may override ordinary backend settings. Builders may open multiple sequential
     * sessions, but retain the same cancellation token and callback objects until replaced.
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
        private InitializationMode initializationMode = InitializationMode.EAGER;
        private PathReconstructionMode pathReconstruction = PathReconstructionMode.PRECISE;
        private CallbackOptions callbacks = CallbackOptions.defaults();
        private List<Path> additionalClasspath = List.of();
        private Consumer<InfoflowAndroidConfiguration> flowDroidCustomizer = configuration -> { };

        private Builder(Path apk, Path platforms) {
            this.apk = Objects.requireNonNull(apk, "apk").toAbsolutePath().normalize();
            this.platforms = Objects.requireNonNull(platforms, "platforms").toAbsolutePath().normalize();
        }

        /**
         * Selects the strategy used when a graph is constructed, either during eager opening
         * or a later graph/taint operation. The default is {@link CallGraphAlgorithm#CHA}.
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
         * Selects eager graph construction or class-only loading. Class-only sessions support inspection
         * immediately; their first graph request or taint run builds the Android model in the same scene.
         * They remain subject to the same single-session and owning-thread requirements.
         *
         * @param mode non-null initialization strategy; EAGER is the default
         * @return this builder
         * @throws NullPointerException if {@code mode} is null
         */
        public Builder initializationMode(InitializationMode mode) {
            this.initializationMode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Selects reconstruction of detailed taint paths. NONE retains detected findings without requesting
         * paths, FAST selects FlowDroid's faster reconstruction, and PRECISE is the default.
         * A result's pathAvailable flag remains authoritative for each finding.
         *
         * @param mode non-null path reconstruction strategy
         * @return this builder
         * @throws NullPointerException if {@code mode} is null
         */
        public Builder pathReconstruction(PathReconstructionMode mode) {
            this.pathReconstruction = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Replaces callback-discovery settings without changing the separately configured callback timeout.
         * The default is {@link CallbackOptions#defaults()}. Disabling callbacks or limiting discovery
         * can omit Android behavior from graph and taint results. The FAST analyzer does not apply
         * the default analyzer's callback-count/depth limits or timeout in pinned FlowDroid 2.15.1.
         *
         * @param options non-null immutable callback settings
         * @return this builder
         * @throws NullPointerException if {@code options} is null
         */
        public Builder callbacks(CallbackOptions options) {
            this.callbacks = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Replaces extra library/class-directory entries added after the selected Android platform JAR.
         * Entries are copied, normalized to absolute paths, and deduplicated in encounter order.
         * Readability is checked when opening; wildcards and paths containing the platform classpath
         * separator are unsupported. Extra entries provide dependencies, not additional APK inputs.
         *
         * @param entries non-null array of non-null JAR/file or class-directory paths; empty clears additions
         * @return this builder
         * @throws NullPointerException if the array or an entry is null
         * @throws IllegalArgumentException if an entry contains the platform classpath separator
         */
        public Builder additionalClasspath(Path... entries) {
            Objects.requireNonNull(entries, "entries");
            LinkedHashSet<Path> paths = new LinkedHashSet<>();
            for (Path entry : entries) {
                Path normalized = Objects.requireNonNull(entry, "classpath entry").toAbsolutePath().normalize();
                if (normalized.toString().indexOf(File.pathSeparatorChar) >= 0) {
                    throw new IllegalArgumentException("Classpath entry contains the path separator: " + normalized);
                }
                paths.add(normalized);
            }
            additionalClasspath = List.copyOf(paths);
            return this;
        }

        /**
         * Replaces the advanced callback applied after typed settings and before backend construction/loading.
         * It receives a configuration copy; supported instance values are copied back, including independent
         * category keys, so retaining that object does not expose the live backend configuration.
         * The callback may override ordinary options, but must not change APK/platform/source-sink file inputs,
         * Soot integration mode, disable taint execution, reset Soot, or modify FlowDroid's static settings.
         * Violations are rejected by {@link #open()}, not when this setter is called. Additional
         * classpath entries are validated after customization. The callback is invoked once per
         * opening attempt that reaches configuration, and its exceptions propagate from open.
         * <p>
         * The callback must only configure its argument: arbitrary external side effects cannot be rolled
         * back or sandboxed. Copying is audited against the pinned FlowDroid version. Changes to global static
         * options are unsupported and restored if detected; the callback runs synchronously on the owner thread.
         *
         * @param customizer non-null callback; subsequent calls replace the previous callback
         * @return this builder
         * @throws NullPointerException if {@code customizer} is null
         */
        public Builder configureFlowDroid(Consumer<InfoflowAndroidConfiguration> customizer) {
            this.flowDroidCustomizer = Objects.requireNonNull(customizer, "customizer");
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
         * Closing the session unregisters its callback without closing or cancelling the source.
         * A cancelled token remains cancelled; supply a fresh source for a later independent run.
         * Custom tokens must return a non-null registration and support registration/cancellation
         * across the threads used by the application. Thread interruption is also observed.
         *
         * @param token non-null caller-owned token, whose callbacks may run on the cancelling thread
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
         * and is not a deadline for the entire session-opening operation. The default is zero.
         * This limit applies to the default callback analyzer in FlowDroid 2.15.1; FAST callback
         * discovery does not install that timeout watcher. Disabled callback discovery performs no
         * callback-analysis work, and class-only opening defers it until a graph or taint run.
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
         * seconds; zero disables the limit and is the default. This is not a deadline for the
         * entire taint run and does not limit callback discovery or path reconstruction.
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
         * whole seconds; zero disables the limit and is the default. Flows without reconstructed
         * paths may still be present in the returned taint findings. This is not a whole-run
         * deadline and does not request paths when {@link PathReconstructionMode#NONE} is selected.
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

        private void customizeConfiguration(InfoflowAndroidConfiguration configuration) {
            cancellation.throwIfCancellationRequested();
            configuration.getCallbackConfig().setCallbackAnalysisTimeout(seconds(callbackTimeout));
            configuration.setDataFlowTimeout(seconds(taintTimeout));
            configuration.getPathConfiguration().setPathReconstructionTimeout(seconds(pathTimeout));
            configuration.getPathConfiguration().setPathReconstructionMode(switch (pathReconstruction) {
                case NONE -> InfoflowConfiguration.PathReconstructionMode.NoPaths;
                case FAST -> InfoflowConfiguration.PathReconstructionMode.Fast;
                case PRECISE -> InfoflowConfiguration.PathReconstructionMode.Precise;
            });
            configuration.getCallbackConfig().setEnableCallbacks(callbacks.enabled());
            configuration.getCallbackConfig().setCallbackAnalyzer(callbacks.analyzer() == CallbackOptions.Analyzer.DEFAULT
                    ? InfoflowAndroidConfiguration.CallbackAnalyzer.Default
                    : InfoflowAndroidConfiguration.CallbackAnalyzer.Fast);
            configuration.getCallbackConfig().setMaxCallbacksPerComponent(callbacks.maxCallbacksPerComponent());
            configuration.getCallbackConfig().setMaxAnalysisCallbackDepth(callbacks.maxAnalysisDepth());
            configuration.getAnalysisFileConfig().setAdditionalClasspath(additionalClasspath.stream()
                    .map(Path::toString).collect(Collectors.joining(File.pathSeparator)));

            InfoflowAndroidConfiguration edited = FlowDroidConfigurationCopy.copy(configuration);
            G generation = G.v();
            boolean oneResultPerAccessPath = InfoflowConfiguration.getOneResultPerAccessPath();
            String baseDirectory = InfoflowConfiguration.getBaseDirectory();
            boolean activityEntryMethods = InfoflowAndroidConfiguration.getCreateActivityEntryMethods();
            try {
                flowDroidCustomizer.accept(edited);
                cancellation.throwIfCancellationRequested();
                if (G.v() != generation) {
                    throw new IllegalStateException("FlowDroid customizer must not reset Soot");
                }
                if (oneResultPerAccessPath != InfoflowConfiguration.getOneResultPerAccessPath()
                        || !Objects.equals(baseDirectory, InfoflowConfiguration.getBaseDirectory())
                        || activityEntryMethods != InfoflowAndroidConfiguration.getCreateActivityEntryMethods()) {
                    throw new IllegalArgumentException("FlowDroid customizer must not change global static settings");
                }
                if (!samePath(edited.getAnalysisFileConfig().getTargetAPKFile(), apk)
                        || !samePath(edited.getAnalysisFileConfig().getAndroidPlatformDir(), platforms)
                        || !Objects.equals(edited.getAnalysisFileConfig().getSourceSinkFile(),
                                configuration.getAnalysisFileConfig().getSourceSinkFile())) {
                    throw new IllegalArgumentException("FlowDroid customizer must not change managed input files");
                }
                if (edited.getSootIntegrationMode() != configuration.getSootIntegrationMode()
                        || !edited.isTaintAnalysisEnabled()) {
                    throw new IllegalArgumentException("FlowDroid customizer must preserve Soot ownership and taint execution");
                }
                validateClasspath(edited.getAnalysisFileConfig().getAdditionalClasspath());
                FlowDroidConfigurationCopy.copyInto(edited, configuration);
            } finally {
                InfoflowConfiguration.setOneResultPerAccessPath(oneResultPerAccessPath);
                InfoflowConfiguration.setBaseDirectory(baseDirectory);
                InfoflowAndroidConfiguration.setCreateActivityEntryMethods(activityEntryMethods);
            }
        }

        private static boolean samePath(File actual, Path expected) {
            return actual != null && actual.toPath().toAbsolutePath().normalize().equals(expected);
        }

        private static void validateClasspath(String classpath) {
            if (classpath == null || classpath.isEmpty()) {
                return;
            }
            for (String entry : classpath.split(Pattern.quote(File.pathSeparator), -1)) {
                if (entry.isEmpty()) {
                    throw new IllegalArgumentException("Additional classpath must not contain empty entries");
                }
                Path path = Path.of(entry);
                if ((!Files.isRegularFile(path) && !Files.isDirectory(path)) || !Files.isReadable(path)) {
                    throw new IllegalArgumentException("Additional classpath entry is not readable: " + path);
                }
            }
        }

        /**
         * Validates paths, acquires exclusive managed-session ownership, resets Soot, and
         * loads classes and, in eager mode, constructs entry points and the call graph synchronously.
         * Close the returned session on this thread before opening another. The advanced customizer
         * runs after typed settings, once configuration is reached. No analysis inputs are downloaded.
         * <p>
         * Cancellation or path validation before initialization leaves existing Soot state intact.
         * Once initialization starts, failure triggers a Soot reset and releases acquired session
         * ownership; the previous scene is not restored. Cleanup failures are attached as suppressed
         * exceptions to the opening failure. A builder remains reusable, but a cancelled token must
         * be replaced before another opening can succeed.
         *
         * @return the initialized session using this builder's current settings
         * @throws IllegalArgumentException if paths/classpath are invalid or customization changes protected settings
         * @throws IllegalStateException if another managed session is open or the customizer resets Soot
         * @throws CancellationException if the token is cancelled or this thread is interrupted
         * @throws RuntimeException if the customizer, backend initialization, or cancellation registration fails
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
            validateClasspath(additionalClasspath.stream().map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator)));
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
                        algorithm.name(), this::customizeConfiguration, initializationMode);
                cancellation.throwIfCancellationRequested();
                return new AndroidAnalysis(AnalysisContext.capture(id, cancellation), flowdroid,
                        application, sourcesAndSinks, cancellation, registration, initializationMode);
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
