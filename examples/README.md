# Runnable Java examples

These four small programs use the instance API from **AndroSpecter 1.2.0-SNAPSHOT**.
They run locally on Java 17 with an APK and an Android SDK `platforms` directory.
They do not require a device, Redis, an AndroZoo account, or network access during analysis.
Maven may download dependencies when building.

| Program | Demonstrates |
| --- | --- |
| [InspectApk](src/main/java/com/jordansamhi/androspecter/examples/InspectApk.java) | Package metadata, scoped invocation metrics, detached graph queries after close |
| [AnalyzeTaint](src/main/java/com/jordansamhi/androspecter/examples/AnalyzeTaint.java) | File-based source/sink settings, result status, findings, JSON export after close |
| [InstrumentInMemory](src/main/java/com/jordansamhi/androspecter/examples/InstrumentInMemory.java) | Scoped Logger, transformation pipeline, graph rebuilding |
| [CancelAnalysis](src/main/java/com/jordansamhi/androspecter/examples/CancelAnalysis.java) | Caller-owned cancellation source and deadline, cleanup on the owning thread |

## Build

Run these commands from the **AndroSpecter repository root** using JDK 17:

```sh
java -version
mvn -version
mvn -B install
mvn -B -f examples/pom.xml package dependency:copy-dependencies
```

`install` places this checkout's library and POM in the local Maven repository. The examples
are a separate Maven project, not a root-build module. They depend on that local snapshot;
the commands do not assume it is published remotely. Reinstall the library and rebuild
the examples after changing its API. For a clean example rebuild, add `clean` before `package`.

The example POM declares:

| Dependency or plugin | Version | Purpose |
| --- | --- | --- |
| `com.jordansamhi:androspecter` | `1.2.0-SNAPSHOT` | The locally installed library, including its transitive Soot/FlowDroid dependencies |
| `com.google.code.gson:gson` | `2.10.1` | Explicit direct dependency used by AnalyzeTaint for JSON |
| `org.slf4j:slf4j-simple` | `2.0.17` | Runtime logging provider chosen by this example application |
| `maven-compiler-plugin` | `3.7.0` | Compile with Java release 17 |
| `maven-dependency-plugin` | `3.6.1` | Copy runtime dependencies into `examples/target/dependency` |

Programs run from `target/classes` plus the copied dependencies. The generated example JAR
is an ordinary JAR without an executable manifest or embedded dependencies; use the classpath
commands below. On Windows, replace the classpath separator `:` with `;`.

Set the input paths and the package containing the application code you want to inspect:

```sh
APK=/absolute/path/to/app.apk
PLATFORMS=/absolute/path/to/Android/sdk/platforms
APP_PREFIX=com.example.app
```

`PLATFORMS` is the directory containing version folders such as `android-35`, not one
`android.jar` file. On macOS, the default SDK location is often
`$HOME/Library/Android/sdk/platforms`. Install the Android platform versions needed by the APK.

`APP_PREFIX` selects a package and its subpackages. Each scoped example checks both
`isApplicationClass()` and a class-name prefix ending in `.`: `com.example.app` does not
select `com.example.application`. This is an explicit analysis scope, not a library detector.
The manifest package and code packages can differ; choose the actual code package.

## Inspect an APK

```sh
java -Xmx4g -cp 'examples/target/classes:examples/target/dependency/*' \
  com.jordansamhi.androspecter.examples.InspectApk \
  "$APK" "$PLATFORMS" "$APP_PREFIX"
```

To query a specific method, supply its full Jimple signature as a quoted fourth argument:

```sh
java -Xmx4g -cp 'examples/target/classes:examples/target/dependency/*' \
  com.jordansamhi.androspecter.examples.InspectApk \
  "$APK" "$PLATFORMS" "$APP_PREFIX" \
  '<com.example.app.MainActivity: void onCreate(android.os.Bundle)>'
```

Replace the example signature with one from your APK. Without it, the program chooses the
first graph method under the prefix, or reports that none matches. Output includes the
manifest package, selected class/method/invocation counts, total graph size, and the queried
method's callers and callees. These queries run **after the session closes**, using detached
metrics and graph snapshots.

Inspection filters classes before loading bodies. Invocation counts are static call sites
in selected concrete methods, not runtime execution counts. The full call graph can include
framework and generated entry-point methods; it is not limited by the inspection prefix.
Its nodes are methods incident to edges, so a declared method without edges can be absent.

## Run taint analysis and write JSON

Copy the definition template and replace its placeholder signatures with actual source and
sink methods before interpreting any findings:

```sh
cp examples/sources-sinks.example.txt /tmp/androspecter-sources-sinks.txt
# Edit /tmp/androspecter-sources-sinks.txt for your APK and analysis policy.

java -Xmx4g -cp 'examples/target/classes:examples/target/dependency/*' \
  com.jordansamhi.androspecter.examples.AnalyzeTaint \
  "$APK" "$PLATFORMS" /tmp/androspecter-sources-sinks.txt /tmp/androspecter-taint.json
```

The UTF-8 format is `SOURCE|<full method signature>` or `SINK|<full method signature>`,
one record per line. Blank lines and lines beginning with `#` are accepted. The loader
uses exactly this file; it does not silently add bundled sources or sinks. A placeholder
file can produce zero findings because its methods are not present in the APK.

The program prints the result's status and findings, then writes the detached result using
Gson **after closing the analysis**. JSON contains `analysisId`, `status`, `findings`, and
`diagnostics`. Findings include source/sink metadata, `path`, and `pathAvailable`; an
unavailable reconstructed path does not erase the corresponding finding. Null metadata
is retained in the JSON. The output file is replaced if it exists, and its parent directory
must already exist.

Check `status` before interpreting the findings: failed, timed-out, or cancelled runs may
have empty or partial results. `SUCCESS` with zero findings means that the configured run
completed without reporting a flow; it does not prove the APK secure or the analysis sound.
Initialization or definition-file errors throw before a taint result exists, so this example
does not write a result file for them. A normally exiting example process can still report
a non-success analysis status; callers decide their own exit-code policy.

This example runs whole-application taint analysis with no phase limits. Its source/sink
file controls the queried flows, not an inspection package scope. For a bounded workflow,
configure the builder's callback, taint, and path timeouts and a cancellation token.

## Instrument the in-memory scene

```sh
java -Xmx4g -cp 'examples/target/classes:examples/target/dependency/*' \
  com.jordansamhi.androspecter.examples.InstrumentInMemory \
  "$APK" "$PLATFORMS" "$APP_PREFIX"
```

The program selects concrete methods in application classes under the explicit package
prefix, registers method-coverage probes on an instance-owned Logger, and applies it
through a TransformationPipeline. It compares detached graph sizes before and after:
`analysis.apply(...)` invalidates the cached graph, and the next `callGraph()` rebuilds it.

No APK is exported, signed, installed, or executed. A successful run demonstrates mutation
and graph rebuilding, not the runtime behavior of an instrumented app. Graph counts need
not change when the selected methods are unreachable or the prefix matches nothing.
The logger's generated helper deduplicates coverage messages at runtime; these probes
are not event counters. Reapplying the same transformation can insert duplicate probes,
and a failed transformation is not rolled back.

## Request cancellation with a deadline

```sh
java -Xmx4g -cp 'examples/target/classes:examples/target/dependency/*' \
  com.jordansamhi.androspecter.examples.CancelAnalysis \
  "$APK" "$PLATFORMS" "$APP_PREFIX" 30
```

The final argument is a positive whole number of seconds (at most `2147483647`). The
deadline starts **before opening the APK** and requests cancellation on a daemon thread.
The owning thread performs initialization and scoped invocation inspection, then closes
the session. The same duration sets FlowDroid's callback-phase limit; that limit and the
deadline are different mechanisms.

Cancellation is cooperative. Callback or call-graph construction may take longer to stop,
and the deadline is not a hard wall-clock bound. The example prints whether cancellation
was observed and whether the deadline expired. A quick operation can finish first. Closing
the timer only disarms its callback; closing the CancellationSource requests cancellation.
For a hard runtime bound or concurrent Soot analyses, use separate processes managed by
the calling application.

## Execution and resource limits

Each program opens one managed session in one JVM and closes it on the creating thread.
Do not run overlapping managed sessions in the same JVM, reset Soot externally while a
session is active, or retain borrowed Soot objects after closing. Detached results can
outlive the session. The examples print their own results to standard output; backend
logging goes through the example application's SLF4J provider.

`-Xmx4g` is a starting heap budget, not a guarantee that every APK will fit. APK size,
callbacks, libraries, and the chosen analysis affect runtime and memory. These examples
do not cover native code, reflection completeness, Android runtime verification, APK
packaging, alignment, or signing.
