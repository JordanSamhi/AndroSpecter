# AndroSpecter command-line tool

The CLI runs APK metadata extraction, code inspection, call-graph export and queries,
and taint analysis without writing a Java application. It is a separate application
built against **AndroSpecter 1.2.0-SNAPSHOT**, with **JDK 17 or later** and
**Maven 3.6.3 or later** for building.
The development version must be built locally; it is not a published release download.

## Build and run

From the AndroSpecter repository root:

```sh
java -version
mvn -version
mvn -B install
mvn -B -f cli/pom.xml package
bin/androspecter --help
```

The first build installs this checkout's library in the local Maven repository.
The second builds the separate CLI project and its executable distribution,
`cli/target/androspecter-cli.jar`. Reinstall the library and rebuild the CLI after
changing library code. The CLI owns its logging provider; the library remains usable
inside applications with their own logging configuration.

The launcher uses `JAVA_HOME/bin/java` when `JAVA_HOME` is set, otherwise `java`
from PATH. It locates the built JAR relative to the launcher; `ANDROSPECTER_JAR`
can select another CLI JAR. You can also launch it directly, including on systems
without a POSIX shell:

```sh
java -Xmx4g -jar cli/target/androspecter-cli.jar --help
```

The root library JAR and its dependency bundle are library artifacts, not this
executable. Use `androspecter-cli.jar` with `java -jar`.

Set explicit inputs for the examples below:

```sh
APK=/absolute/path/to/app.apk
PLATFORMS=/absolute/path/to/Android/sdk/platforms
APP_PACKAGE=com.example.app
```

The platforms directory contains installations such as `android-35/android.jar`.
The CLI does not guess the SDK location from environment variables. Metadata and
saved-graph commands need no SDK. APK analysis does not require a device, Redis,
or an AndroZoo account. Maven may download dependencies while building.

## Commands

JSON is the default format for every command. Use `COMMAND --help` for its options.

| Command | Required input | Formats |
| --- | --- | --- |
| `metadata` | `--apk APK` | `json`, `text` |
| `inspect` | `--apk APK --platforms DIR` | `json`, `text` |
| `graph` | `--apk APK --platforms DIR` **or** `--input-json FILE` | `json`, `dot`, `html` |
| `query` | `--input-json FILE --source SIGNATURE --target SIGNATURE` | `json`, `text` |
| `taint` | `--apk APK --platforms DIR --sources-sinks FILE` | `json`, `text` |

### Read the manifest

```sh
bin/androspecter metadata --apk "$APK" --format text
bin/androspecter metadata --apk "$APK" -o manifest.json
```

This reads binary manifest declarations directly without initializing Soot.
Resource-backed and absent values can appear as null; raw attributes retain the
original declaration. Exported/enabled values describe explicit declarations,
not Android's effective defaults or a finding that a component is exposed.
The command does not verify APK signatures or merge split APKs.

### Inspect code without constructing a graph

```sh
bin/androspecter inspect --apk "$APK" --platforms "$PLATFORMS" \
  --package "$APP_PACKAGE" --list-methods -o inspection.json

bin/androspecter inspect --apk "$APK" --platforms "$PLATFORMS" \
  --package "$APP_PACKAGE" --invocations --format text
```

Inspection uses class-only initialization. `--list-methods` includes method details;
`--invocations` additionally loads selected concrete method bodies to inspect static
invocation sites. Invocation inspection is off by default. Neither option constructs
Android callback entry points or a call graph. Counts describe the loaded Soot/Jimple
model, not runtime executions or bytecode instruction counts.

Repeat `--package` to select the union of several packages and their subpackages.
`com.example.app` does not select `com.example.application`. Inspection package scopes
select application code; the manifest package can differ from the code packages of interest. Package filtering
is an explicit scope, not automatic third-party library classification.

### Build, save and view a graph

```sh
bin/androspecter graph --apk "$APK" --platforms "$PLATFORMS" \
  --algorithm CHA -o graph.json

bin/androspecter graph --input-json graph.json --format html -o graph.html
bin/androspecter graph --input-json graph.json --format dot -o graph.dot
```

The JSON output is a versioned `GraphJson` document that can be loaded by the Java
library or reused by the CLI. It preserves edge kinds, parallel edge occurrences,
call-site text and positions. A call-site position is a zero-based Jimple unit index,
not a source line or bytecode offset; `-1` means unavailable. Graph construction can
include framework methods and generated Android entry points. Captured graph nodes
are methods incident to edges, not an inventory of every method declared in the APK.

HTML is a standalone graph report that can be opened locally. DOT is a visualization
export; the CLI imports JSON, not DOT or HTML. Offline graph conversion requires only
the saved graph. It does not reload the APK or initialize Soot.

To restrict the graph, select packages and optionally a starting method:

```sh
bin/androspecter graph --input-json graph.json --package "$APP_PACKAGE" \
  --from '<com.example.app.MainActivity: void onCreate(android.os.Bundle)>' \
  --format dot -o reachable.dot
```

Replace the quoted signature with one present in your graph. Package filtering first
creates an induced subgraph: both endpoints must be selected for an edge to remain.
`--from` then retains the starting method's reachable closure within that filtered
graph. Paths through excluded packages are therefore absent. Repeated package options
select several scopes; they do not change how the original APK graph was constructed.
A `--from` method missing from the selected graph is a usage error (exit 2).

### Query a saved graph

```sh
bin/androspecter query --input-json graph.json \
  --source '<com.example.app.MainActivity: void onCreate(android.os.Bundle)>' \
  --target '<com.example.app.Network: void send(java.lang.String)>'
```

Output reports `sourceKnown`, `targetKnown`, `canReach`, `shortestPath`,
and the source method's direct callers and callees.
Unknown endpoints and unreachable pairs have no path; the known flags distinguish
the two cases. A known method reaches itself with a one-node path. Shortest paths
minimize edge count and resolve ties deterministically. These are static graph
queries, not proofs of feasible runtime execution or taint propagation.

### Analyze taint flows

Create a UTF-8 definitions file, replacing these signatures with methods relevant to
your APK and analysis policy:

```text
# One definition per line; blank lines and whole-line comments are accepted.
SOURCE|<com.example.app.Input: java.lang.String read()>
SINK|<com.example.app.Output: void send(java.lang.String)>
```

```sh
bin/androspecter taint --apk "$APK" --platforms "$PLATFORMS" \
  --sources-sinks sources-sinks.txt --paths precise -o taint.json
```

The file must contain at least one source and one sink. It supplies exactly the
definitions used; bundled definitions are not added.
This is AndroSpecter's `SOURCE|...` / `SINK|...` format, not FlowDroid's `_SOURCE_`
annotation format. Quoting a signature does not make it a matching source or sink if
that API is absent from the APK's analyzed model.

`--paths none|fast|precise` controls path reconstruction; the default is `precise`.
With `none`, findings can still be reported with `pathAvailable=false` and an empty
path. JSON preserves status, diagnostics, endpoints, and available paths. A failed,
timed-out, or cancelled run may contain partial findings. Initialization or input
validation can fail before there is a taint result to write.

`SUCCESS` with no findings means the configured run completed without reporting a
flow; it does not establish that the APK is secure. `--fail-on-findings` returns exit
code 4 only for a completed taint run containing findings. It does not replace the
incomplete-analysis status when a run fails, times out, or is cancelled.

## Analysis options and limits

APK `graph`, `inspect`, and `taint` commands accept:

| Option | Meaning |
| --- | --- |
| `--classpath PATH` | Additional readable JAR or directory; repeat for multiple entries |
| `--timeout SECONDS` | Cooperative cancellation while opening and analyzing; `0` disables it |

APK `graph` and `taint` additionally accept:

| Option | Meaning |
| --- | --- |
| `--algorithm CHA|RTA|VTA|SPARK` | Call-graph algorithm; default `CHA` |
| `--callback-timeout SECONDS` | FlowDroid callback phase limit; `0` disables it |

Taint also accepts `--taint-timeout SECONDS` and `--path-timeout SECONDS` for the
data-flow and path-reconstruction phases. Phase limits default to disabled. Explicit
`--algorithm` and `--callback-timeout` are rejected by `inspect`, which executes neither
phase. Offline graph commands reject Android analysis options; metadata accepts none.

`--timeout` starts before the analysis session opens and is disarmed after it closes;
it does not time input validation, offline graph processing, or output writing. It is
not a hard process deadline: expiration requests cancellation, and callback/graph
construction may continue until the backend returns. Phase limits
also do not bound the total command duration. Callback timeout can leave a partial
callback graph without a taint `TIMED_OUT` result. Path timeout may leave findings
whose paths are unavailable. Runtime warnings on stderr remain relevant even when
an analysis completes successfully.

## Output files, streams and exit codes

By default results go to stdout. Progress, warnings and errors go to stderr, so JSON
can be redirected without mixing in progress messages:

```sh
bin/androspecter metadata --apk "$APK" > manifest.json 2> metadata.log
```

`-o PATH` writes a result file. Existing destinations require `--overwrite`.
An output must not alias an input through its path, a symbolic link or a hard link;
`--overwrite` does not override that protection. Prefer `-o` when input protection
matters: shell redirection is performed by the shell before the CLI can validate it.

| Exit code | Meaning |
| --- | --- |
| `0` | Command completed successfully, including a query with no path |
| `1` | Analysis/backend or I/O failure, including a taint result with status `FAILED` |
| `2` | Invalid command, arguments, or input configuration |
| `3` | Cancellation or timeout, including taint `CANCELLED` or `TIMED_OUT`; available findings are still reported |
| `4` | Completed taint analysis found flows and `--fail-on-findings` was selected |

Read the result status and diagnostics as well as the exit code. Machine-readable
results and stderr logs serve different purposes; an empty result is not a substitute
for checking whether analysis completed.

For embedding the library, see the [Java examples](../examples/README.md) and
[analysis-session guide](https://github.com/JordanSamhi/AndroSpecter/wiki/Analysis-sessions).
