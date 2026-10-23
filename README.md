<p align="center">
<img width="1200px" src="https://github.com/JordanSamhi/AndroSpecter/blob/main/data/androspecter_logo.png">
</p> 

# AndroSpecter Library

Welcome to **AndroSpecter**, your one-stop solution to facilitate the static analysis of Java programs and Android apps! 🚀

This library offers various features such as APK file downloading, FlowDroid analyses initialization, bytecode instrumentation, library management, Redis interaction, method signature conversion, and more. Whether you're a developer, researcher, or tech enthusiast, AndroSpecter can greatly simplify and expedite your work.

## Getting Started

### Importing with Maven

You can use **AndroSpecter** in your Maven project by adding the following dependency to your `pom.xml` file:

```xml
<dependency>
    <groupId>com.jordansamhi</groupId>
    <artifactId>androspecter</artifactId>
    <version>1.X.Y</version>
</dependency>
```

:warning: Find the latest version of the AndroSpecter library [here](https://mvnrepository.com/artifact/com.jordansamhi/androspecter).

## Detailed Documentation

For a comprehensive guide, examples, and detailed documentation of all features and classes, please visit the [official AndroSpecter wiki](https://github.com/JordanSamhi/AndroSpecter/wiki).

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
They do not require Redis, an AndroZoo API key, an Android SDK, or a device.
Analyzing real APKs still requires the Android platform JARs.

## Analysis and instrumentation behavior

- Run Soot analyses sequentially within a JVM. Initialization resets Soot's global
  state; helper queries reflect the current scene and call graph. Class and method
  totals can include loaded framework classes. Library exclusion uses the bundled
  package-prefix list, independently of Soot's application-class flag.
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
- The separate `TimeOut` utility takes **seconds** and terminates the JVM when it
  expires. Call `cancel()` to disarm it.
- Metric vectors order columns alphabetically by metric name. Use the same metric
  names for comparable rows, and call `ResultsAccumulator.v().reset()` before a
  new app. `CommandLineOptions.reset()` clears CLI configuration and parsed values;
  `SourcesSinksManager.reset()` restores the bundled source/sink definitions.
- Source/sink files use UTF-8 records such as
  `SOURCE|<android.telephony.TelephonyManager: java.lang.String getLine1Number()>`.
  Types are uppercase `SOURCE` or `SINK`. Loading is additive, and malformed files
  are rejected with a location before any of their entries are applied.

## Built With

* [Maven](https://maven.apache.org/) - Dependency Management

## License

This project is licensed under the GNU LESSER GENERAL PUBLIC LICENSE 2.1 - see the [LICENSE](LICENSE) file for details

## Contact & Contributions

If you have any questions, issues, feedback, or want to contribute, please don't hesitate to [contact us](mailto:jordan.samhi@uni.lu).

Your insights and contributions are always welcome! 🌟
