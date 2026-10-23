<p align="center">
<img width="1200px" src="https://github.com/JordanSamhi/AndroSpecter/blob/main/data/androspecter_logo.png">
</p> 

# AndroSpecter Library

AndroSpecter wraps Soot and FlowDroid to make Android call-graph construction,
taint analysis, code inspection, and instrumentation easier to use from Java.
The calling application owns execution, logging, and result presentation.

## Getting Started

### Importing with Maven

You can use **AndroSpecter** in your Maven project by adding the following dependency to your `pom.xml` file:

```xml
<dependency>
    <groupId>com.jordansamhi</groupId>
    <artifactId>androspecter</artifactId>
    <version>1.2.0-SNAPSHOT</version>
</dependency>
```

`1.2.0-SNAPSHOT` is the current development version, not a published Maven Central
release. Run `mvn clean install` in this checkout before consuming it locally.
Published versions are listed [here](https://mvnrepository.com/artifact/com.jordansamhi/androspecter).

### Open an analysis and query its graph

```java
import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.analysis.CallGraphAlgorithm;
import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import java.nio.file.Path;

CallGraphSnapshot graph;
try (AndroidAnalysis analysis = AndroidAnalysis.builder(
        Path.of("app.apk"), Path.of("/path/to/android-sdk/platforms"))
        .callGraphAlgorithm(CallGraphAlgorithm.CHA)
        .open()) {
    graph = analysis.callGraph();
    System.out.println(analysis.packageName());
}

// Detached strings and immutable collections remain valid after close/G.reset().
String method = "<example.MainActivity: void onCreate(android.os.Bundle)>";
System.out.println(graph.calleesOf(method));
System.out.println(graph.callersOf(method));
System.out.println(graph.containsMethod(method));
```

`open()` constructs Android entry points and a call graph. Supported algorithms
are `CHA`, `RTA`, `VTA`, and `SPARK`. A snapshot indexes callers, callees, incoming
and outgoing edges, and membership queries once. Edges retain their call-site
text, position when available, and kind; parallel edges are preserved. Nodes are
methods incident to edges, including framework and generated entry points, not
all declared methods. `analysisId()` links each snapshot to its originating session.
Snapshots of a large graph consume additional memory.

One managed session may be open in a JVM at a time. Use and close it on the thread
that opened it. Opening another session fails rather than replacing the first.
Closing resets its owned Soot state; external `G.reset()` calls invalidate its
context. External Soot users must also coordinate global access. Use separate
processes for parallel analyses. Live `SootClass`, `SootMethod`, and `Unit` values
borrowed during callbacks must not be reused after a reset or close.
Closing a borrowed `analysis.context()` only invalidates live access; always
close `AndroidAnalysis` itself to release ownership and backend resources.

### Inspect only what you need

```java
import com.jordansamhi.androspecter.inspection.Inspection;
import com.jordansamhi.androspecter.inspection.LibraryFilter;
import soot.SootClass;

analysis.inspect(Inspection.builder()
    .classes(SootClass::isApplicationClass)
    .excludeLibraries(LibraryFilter.packages("androidx", "com.vendor.sdk"))
    .methods(method -> method.getName().equals("onCreate"))
    .onInvoke((method, statement, invocation) ->
        analysis.metrics().incrementMetric("invocations"))
    .build());

var metrics = analysis.metrics().snapshot();
```

Callbacks are optional: `onClass`, `onMethod`, `onStatement`, and `onInvoke`.
Predicates run before bodies are loaded. Class/method-only inspections do not
load bodies; an inspection with no callbacks does no traversal. Inspection
callbacks should observe code; use transformations for mutations.
`LibraryFilter` is a predicate and supports normal `and`, `or`, and `negate`
composition. `knownLibraries()` explicitly opts into the bundled legacy list.
Metrics and source/sink settings belong to each session; no singleton reset is
needed. Metric snapshots are detached and immutable.

### Get structured taint findings

```java
import com.jordansamhi.androspecter.files.SourceSinkConfiguration;
import com.jordansamhi.androspecter.result.TaintAnalysisResult;

var definitions = SourceSinkConfiguration.builder()
    .source("<example.Source: java.lang.String read()>")
    .sink("<example.Sink: void send(java.lang.String)>")
    .build();
TaintAnalysisResult result = analysis.analyzeTaint(definitions);
for (var finding : result.findings()) {
    System.out.println(finding.source().invokedMethodSignature());
    System.out.println(finding.sink().invokedMethodSignature());
}
System.out.println(result.status());
System.out.println(result.diagnostics());
```

`TaintAnalysisResult` contains an analysis ID, `SUCCESS`, `FAILED`, `TIMED_OUT`,
or `CANCELLED` status, findings, and diagnostics. Findings contain detached source
and sink values, paths, and `pathAvailable`. Endpoints distinguish the enclosing
method signature from the invoked source/sink API and retain the access-path
description. An unavailable enclosing method or non-invocation endpoint has a
null signature; a missing reconstructed path does not discard a detected flow.
Partial findings remain available after incomplete analyses. **Check the status
before interpreting an empty finding list.**
Diagnostics contain backend exceptions and termination information. General
Soot/FlowDroid parser and typing warnings remain in the caller's logs; `SUCCESS`
does not certify analysis soundness or the absence of such warnings.

These immutable records contain no live Soot objects or exceptions, so clients
can retain them after reset and serialize them with their chosen JSON library.
`TaintResultFormatter.format(result)` provides legacy text rendering and rejects
incomplete results. `FlowdroidUtils.runTaintAnalysisResult(...)` also exposes the
structured API for existing integrations that manage Soot themselves.

Source/sink configurations can be built explicitly, loaded with
`SourceSinkConfiguration.fromFile(path)`, or initialized with
`bundledDefaults()`. File loading uses exactly the supplied file. Combine
configurations with `builder().include(configuration)` when wanted. A session's
default configuration is empty; pass definitions to `analyzeTaint(definitions)`
or configure `builder(...).sourcesAndSinks(definitions)` explicitly.

### Control cancellation, deadlines, and logging

```java
import com.jordansamhi.androspecter.execution.CancellationSource;
import com.jordansamhi.androspecter.execution.Deadline;
import java.time.Duration;

try (var cancellation = new CancellationSource();
     var deadline = Deadline.after(Duration.ofMinutes(2), cancellation::cancel);
     var analysis = AndroidAnalysis.builder(apk, platforms)
         .cancellationToken(cancellation.token())
         .callbackTimeout(Duration.ofSeconds(30))
         .taintTimeout(Duration.ofMinutes(1))
         .pathTimeout(Duration.ofSeconds(20))
         .open()) {
    var result = analysis.analyzeTaint(definitions);
}
```

The caller can also invoke `cancellation.cancel()` from another thread. Deadlines
invoke a caller-provided callback on a daemon thread. Cancellation is cooperative:
live operations check the token, and FlowDroid receives a best-effort data-flow
abort request. Callback/call-graph phases may not stop immediately. A deadline
does not forcibly stop Java code; retain the session until the running operation
returns. FlowDroid phase limits are rounded up to seconds; zero disables a limit.
Callback timeouts may yield a partial graph. Initialization/inspection cancellation
throws `CancellationException`; taint execution reports `CANCELLED` with any
available findings.

AndroSpecter logs through `slf4j-api` and supplies no logging backend or global
logging configuration, following the [SLF4J library model](https://www.slf4j.org/manual.html#libraries).
Choose a compatible SLF4J 2.x provider in the calling application, for example:

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.17</version>
    <scope>runtime</scope>
</dependency>
```

`new CommandLineOptions("my-tool").parse(args)` returns an immutable parse result
with `helpRequested()` and `helpText()`; invalid arguments throw
`CommandLineOptions.ValidationException`. Printing help and selecting process
exit codes belong to the executable, not the library.

### Compose instrumentation explicitly

```java
import com.jordansamhi.androspecter.instrumentation.InstrumentationOptions;
import com.jordansamhi.androspecter.instrumentation.Logger;
import com.jordansamhi.androspecter.instrumentation.TransformationPipeline;

var logger = new Logger(analysis.context(), InstrumentationOptions.builder()
    .classes(clazz -> clazz.isApplicationClass()
        && clazz.getName().startsWith("example."))
    .libraryFilter(LibraryFilter.packages("com.vendor.sdk"))
    .build());
logger.logAllMethods("MY_ANALYSIS", false);

analysis.apply(TransformationPipeline.builder()
    .add(logger)
    .add(context -> { /* another caller-defined transformation */ })
    .build());
```

New logger instances own their options and registered transformations; they do
not register global Soot packs. Their default scope is application classes, with
no implicit library classification. Pipeline stages execute in registration
order and are not transactional. Reapplying a pipeline may insert probes again.
After `apply`, `callGraph()` rebuilds before capturing a new snapshot; previously
returned snapshots remain unchanged. Instrumentation changes the in-memory model;
APK export/signing remains a separate caller-controlled step.

### Migration from 1.1.8

- `CommandLineOptions.parseArgs` and `TimeOut` no longer call `System.exit`.
  Handle parse errors/help in your executable. The deprecated `TimeOut(int)`
  signals its cancellation token and logs expiration; callers must observe the
  token or supply an explicit callback. `TimeOut.cancel()` disarms the timer.
- `Writer` and metric printing now use SLF4J. Add/configure a provider in your
  application if you want logs; the bundled global `log4j.xml` has been removed.
- Legacy singleton accessors remain for compatibility but are deprecated where
  instance APIs replace them. Use `new ResultsAccumulator()`,
  `new SourcesSinksManager()`, or an `AndroidAnalysis` session for isolated state.
- Existing `runTaintAnalysis` text output and logger `v()` workflows remain
  available. Prefer structured results and context-bound logger instances.
- Legacy `AndroidAppInspector` callbacks now default to no-ops. Its `run()` still
  works; `runIn(context)` accepts a caller-owned context. New code can use
  `Inspection` without subclassing.

## Detailed Documentation

This README documents the new session APIs. The
[AndroSpecter wiki](https://github.com/JordanSamhi/AndroSpecter/wiki) contains
additional examples for the older utility APIs.

## Building and testing

Use JDK 17 or later and Maven. FlowDroid 2.15.1 requires Java 17;
the library is compiled for that same minimum version.

The analysis dependencies are pinned to Soot **4.7.1** and FlowDroid **2.15.1**
(`soot-infoflow`, `soot-infoflow-android`, and `soot-infoflow-summaries`).
FlowDroid 2.15.1 itself depends on Soot 4.7.1. Their versions are centralized in
the `soot.version` and `flowdroid.version` Maven properties.

Upgrading from the previous dependency set (Soot 4.6.0 / FlowDroid 2.14.1)
requires upgrading Java 11 installations to Java 17 or later.

```sh
mvn test
mvn clean verify
```

The tests use synthetic Soot bodies, generated DEX files, temporary files, and fake service connections.
They cover detached results, session ownership/reset guards, cancellation,
source/sink isolation, graph indexes, and composed inspection/instrumentation.
They do not require Redis, an AndroZoo API key, an Android SDK, or a device.
Analyzing real APKs still requires the Android platform JARs.

Development validation on 2026-10-23 used Java 17:

- `mvn clean verify`: 163 tests passed, with no failures or skipped tests.
- A small APK with a known source-to-sink flow returned a successful structured
  finding with a reconstructed path, serialized to JSON after closing the session.
- The same APK accepted instance-based logging instrumentation; rebuilding its
  graph added calls to the generated helper while preserving the original snapshot.
- Word Snake (`justinasmegames.wordsnake`) retained the earlier counts: 6215
  application classes, 30 methods and 437 Jimple statements in the manifest
  package, and 925269 graph edges. Two upstream `Failed Typing` diagnostics and
  unsupported `FLAG_WEAK` resource warnings remain; completion does not establish
  that those diagnostics are harmless or that the graph is complete.

## Analysis and instrumentation behavior

- Run Soot analyses sequentially within a JVM. Initialization resets Soot's global
  state; helper queries reflect the current scene and call graph. Class and method
  totals can include loaded framework classes. Legacy utility library exclusion
  uses the bundled package-prefix list, independently of Soot's application-class
  flag. New inspection and instrumentation APIs accept caller-defined filters.
- Multidex APKs are processed by default in Soot 4.7.1. The removed
  `process_multiple_dex` and FlowDroid `mergeDexFiles` options are no longer needed.
- `FlowdroidUtils.runTaintAnalysis` throws `IllegalStateException` when analysis
  fails, returns no result, or terminates early. Only a successful analysis with no
  detected flows returns an empty set. Detected flows remain in the report when
  their detailed paths are unavailable.
- Instrumentation logs record coverage: each distinct message is emitted at most
  once per app process, regardless of its tag. Statement probes record that a
  statement was reached; identity, return, and monitor statements are excluded.
  Call logs identify the statically referenced target. Component messages now
  include the callback, for example `ACTIVITY=com.example.Main|void onStart()`.
- `AndroidAppsProcessor` takes a timeout in **minutes**. It records success only
  after `processResults()` returns, cancels timed-out tasks, and shuts down their
  executors. If a task ignores interruption for five seconds, the worker stops
  polling; it cannot forcibly terminate Java code. The caller owns the Redis
  connection and can close it with try-with-resources.
- The legacy `TimeOut` utility takes **seconds**, signals cancellation, and invokes
  its callback when it expires. It never terminates the JVM. Call `cancel()` to disarm it.
- Metric vectors order columns alphabetically by metric name. Use the same metric
  names for comparable rows. Legacy singleton users must call
  `ResultsAccumulator.v().reset()` before a new app; session metrics are isolated.
  `CommandLineOptions.reset()` clears CLI configuration and parsed values;
  `SourcesSinksManager.reset()` restores the bundled source/sink definitions.
- Source/sink files use UTF-8 records such as
  `SOURCE|<android.telephony.TelephonyManager: java.lang.String getLine1Number()>`.
  Types are uppercase `SOURCE` or `SINK`. Loading into a `SourcesSinksManager` is
  additive; `SourceSinkConfiguration.fromFile` loads only that file. Malformed
  files are rejected with a location before any of their entries are applied.

## Built With

* [Maven](https://maven.apache.org/) - Dependency Management

## License

This project is licensed under the GNU LESSER GENERAL PUBLIC LICENSE 2.1 - see the [LICENSE](LICENSE) file for details

## Contact & Contributions

If you have any questions, issues, feedback, or want to contribute, please don't hesitate to [contact us](mailto:jordan.samhi@uni.lu).

Your insights and contributions are always welcome! 🌟
