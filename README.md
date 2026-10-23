<p align="center">
  <img width="1000" src="data/androspecter_logo_v2.png" alt="AndroSpecter logo">
</p>

# AndroSpecter

**Android static analysis from Java, with simpler Soot and FlowDroid APIs.**

AndroSpecter helps you build call graphs, inspect classes and invocations, run
source-to-sink taint analyses, and compose instrumentation. It is a **library**:
your application controls execution, cancellation, logging, and output.

[Getting started](https://github.com/JordanSamhi/AndroSpecter/wiki/Getting-started) ·
[Wiki](https://github.com/JordanSamhi/AndroSpecter/wiki) ·
[Runnable examples](examples/README.md) ·
[Contributing](CONTRIBUTING.md)

## What you can do

| Task | Main APIs | Guide |
| --- | --- | --- |
| Open an APK analysis and manage its lifetime | `AndroidAnalysis`, `AnalysisContext` | [Analysis sessions](https://github.com/JordanSamhi/AndroSpecter/wiki/Analysis-sessions) |
| Query callers, callees, and call sites after analysis closes | `CallGraphSnapshot` | [Graphs and inspection](https://github.com/JordanSamhi/AndroSpecter/wiki/Call-graphs-and-inspection) |
| Visit only the classes, methods, statements, or invocations you need | `Inspection`, `LibraryFilter` | [Graphs and inspection](https://github.com/JordanSamhi/AndroSpecter/wiki/Call-graphs-and-inspection) |
| Obtain structured findings with sources, sinks, paths, and status | `SourceSinkConfiguration`, `TaintAnalysisResult` | [Taint analysis](https://github.com/JordanSamhi/AndroSpecter/wiki/Taint-analysis) |
| Apply scoped logging probes and ordered transformations | `Logger`, `TransformationPipeline` | [Instrumentation](https://github.com/JordanSamhi/AndroSpecter/wiki/Instrumentation) |
| Choose deadlines and logging behavior in the calling application | `CancellationSource`, `Deadline`, SLF4J | [Execution and logging](https://github.com/JordanSamhi/AndroSpecter/wiki/Cancellation-and-logging) |

## Requirements and installation

| Component | Version used by this checkout |
| --- | --- |
| Java | 17 or later |
| AndroSpecter | `1.2.0-SNAPSHOT` — local development build |
| Soot | `4.7.1` |
| FlowDroid | `2.15.1` |

You need Maven to build the library and an Android SDK `platforms` directory
containing platform JARs to analyze APKs. A connected Android device is not needed.

**`1.2.0-SNAPSHOT` has not been published to Maven Central.** Install this checkout
locally before using the examples or depending on this version:

```sh
git clone https://github.com/JordanSamhi/AndroSpecter.git
cd AndroSpecter
mvn clean install
```

Check `java -version` and `mvn -version` if Maven uses a different JDK from your
shell. In your application's `pom.xml`, add:

```xml
<dependency>
    <groupId>com.jordansamhi</groupId>
    <artifactId>androspecter</artifactId>
    <version>1.2.0-SNAPSHOT</version>
</dependency>
```

The library supplies the SLF4J API, without a logging provider or global logging
configuration. If your application has no provider yet, it can select one:

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.17</version>
    <scope>runtime</scope>
</dependency>
```

Use one compatible SLF4J 2.x provider. See [Getting started](https://github.com/JordanSamhi/AndroSpecter/wiki/Getting-started)
for a complete Maven setup and the distinction between local and published versions.

## Your first analysis

This complete class opens an APK, builds its Android entry points and CHA call
graph, then queries the detached graph after the analysis has closed:

```java
import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import java.nio.file.Path;

public final class QuickStart {
    /**
     * Builds and inspects an APK's call graph.
     * @param args APK path and Android SDK platforms directory
     * @throws IllegalArgumentException if two paths are not supplied
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: QuickStart <app.apk> <sdk/platforms>");
        }

        CallGraphSnapshot graph;
        try (AndroidAnalysis analysis = AndroidAnalysis.open(
                Path.of(args[0]), Path.of(args[1]))) {
            System.out.println("Package: " + analysis.packageName());
            graph = analysis.callGraph();
        }

        System.out.println("Call graph edges: " + graph.edges().size());
        graph.methods().stream().limit(5).forEach(method -> {
            System.out.println(method);
            System.out.println("  Callees: " + graph.calleesOf(method));
        });
    }
}
```

Use `AndroidAnalysis.builder(apk, platforms)` to select `CHA`, `RTA`, `VTA`, or
`SPARK`, set source/sink definitions, or configure cancellation and phase limits.
The [session guide](https://github.com/JordanSamhi/AndroSpecter/wiki/Analysis-sessions)
explains these choices and shows how to collect instance-owned metrics.

## Run the included examples

After installing the library, build the independent example project from the
repository root:

```sh
mvn -f examples/pom.xml package dependency:copy-dependencies

APK=/absolute/path/to/app.apk
PLATFORMS=/absolute/path/to/android-sdk/platforms
APP_PREFIX=com.example.app

java -Xmx4g -cp 'examples/target/classes:examples/target/dependency/*' \
    com.jordansamhi.androspecter.examples.InspectApk \
    "$APK" "$PLATFORMS" "$APP_PREFIX"
```

Replace the paths and package prefix with your own. The prefix controls inspection
scope; it is not proof that a class is first-party code. On Windows, use `;`
instead of `:` between classpath entries.

| Example | Demonstrates |
| --- | --- |
| `InspectApk` | Invocation callbacks, per-analysis metrics, and indexed graph queries |
| `AnalyzeTaint` | File-based source/sink definitions, completion status, and JSON export after close |
| `InstrumentInMemory` | Scoped logging probes, transformation composition, and graph rebuilding |
| `CancelAnalysis` | A caller-owned cancellation token and cooperative deadline |

The [example README](examples/README.md) contains every command and expected
behavior. These programs are executable consumers of the library. AndroSpecter's
own JAR has no command-line entry point.

## Lifecycle and result semantics

- **One managed session per JVM.** Open, use, and close it on the same thread.
  Soot has global state; use separate processes for parallel analyses. External
  Soot users must coordinate access too.
- **Live objects have a lifetime.** Callback arguments such as `SootMethod` and
  `Unit` must not be reused after close or `G.reset()`. Graph, taint, and metric
  snapshots contain detached data and survive those operations.
- **Graphs describe static possibilities.** They include framework and generated
  entry points. Snapshot nodes are methods incident to an edge, not every method
  declared in the APK. Large indexed snapshots require additional memory.
- **Check taint status.** `SUCCESS`, `FAILED`, `TIMED_OUT`, and `CANCELLED` distinguish
  completion from partial results. An empty finding list alone is not evidence
  that no flow exists. Source/sink settings are empty unless explicitly supplied.
- **Cancellation is cooperative.** Deadlines request cancellation; they do not
  forcibly terminate Java code. Some backend phases may finish before noticing
  a request. Callback timeouts can leave a partial graph.
- **Instrumentation changes the in-memory model.** APK export and signing are
  separate caller-controlled steps. Pipelines execute in order without rollback.

See [Migration and troubleshooting](https://github.com/JordanSamhi/AndroSpecter/wiki/Migration-and-troubleshooting)
for legacy singleton APIs, changed timeout/CLI behavior, backend diagnostics,
and common setup errors.

## Build, test, and browse the API

```sh
mvn clean verify
mvn org.apache.maven.plugins:maven-javadoc-plugin:3.4.0:javadoc \
    -Dshow=public -Ddoclint=all -Dmaven.javadoc.failOnWarnings=true
```

Open `target/site/apidocs/index.html` for the public API reference. Tests use
synthetic Soot bodies, generated DEX files, and local fixtures; they do not require
an Android SDK, Redis service, or AndroZoo key. The examples require an APK and
Android platform JARs.

Every new or changed public API must have usage-oriented Javadoc. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the documentation requirements.

## License and contact

AndroSpecter is licensed under [GNU LGPL 2.1](LICENSE).
For questions or contributions, open a [GitHub issue](https://github.com/JordanSamhi/AndroSpecter/issues)
or contact [Jordan Samhi](mailto:jordan.samhi@uni.lu).
