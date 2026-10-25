# Publishing AndroSpecter

The `release` profile uses Sonatype's Central Publisher Portal through
`central-publishing-maven-plugin:0.12.0`. It replaces the retired OSSRH URLs and
Nexus staging plugin. Ordinary `mvn install` builds remain local and do not activate
publishing. Version `2.0.0` is prepared for release; it has not yet been published
by this checkout. The maintainer's usual command is:

```sh
mvn clean deploy -P release
```

With Central credentials and a signing key configured, this command builds,
signs, uploads, and automatically publishes the release after validation. It
waits for publication to complete; no manual Publish click is required.

## Local checks

Use JDK 17 or later and Maven 3.6.3 or later in the Maven 3 series.
Maven 4 is not supported by this publishing plugin.
Before preparing a release, run the tests, generate strict public/protected API Javadoc, and
build the independently maintained examples:

```sh
mvn clean install
mvn -f examples/pom.xml clean package dependency:copy-dependencies
mvn -f cli/pom.xml clean verify
mvn -P release -Dgpg.skip=true verify
```

The last command checks release packaging, including source and Javadoc JARs,
without signing or uploading. It does not validate credentials or prove that
Central will accept a signed release. Inspect the generated API documentation and
exercise the examples against representative APKs before publishing the release.

To check the full deploy lifecycle locally without credentials or a signing key:

```sh
mvn clean deploy -P release -Dcentral.skipUpload=true -Dgpg.skip=true
```

This creates an **unsigned validation bundle**, installs the build locally, and
skips uploading. It exercises packaging and bundle generation, but its missing
signatures make it unsuitable for publication. A signed bundle still needs the
maintainer's key and Central validation.

## Maintainer setup

Use a Central Portal account with access to the `com.jordansamhi` namespace.
Generate a Portal user token and configure a server named `central` in your own
Maven `settings.xml`. The token is different from an old OSSRH credential.
For example, credentials can come from your environment:

```xml
<settings>
    <servers>
        <server>
            <id>central</id>
            <username>${env.CENTRAL_USERNAME}</username>
            <password>${env.CENTRAL_PASSWORD}</password>
        </server>
    </servers>
</settings>
```

Keep credentials outside this repository. Configure the signing key and GPG agent
for the existing Maven GPG plugin; select a key with `-Dgpg.keyname=KEY_FINGERPRINT`
when needed. Central must be able to verify the corresponding public key.

## Prepare a release

The root, examples, and CLI POMs are aligned on `2.0.0`. For a future release,
choose the intended non-snapshot version, update the root POM, both the project
version and library dependency in `examples/pom.xml`, and the CLI version and `androspecter.version`
in `cli/pom.xml`. Update version references in the README, CLI guide, examples,
and wiki. Commit these changes under the maintainer's identity. Keep the consumers'
library dependencies aligned with the artifact being tested.

With the release version in place, create the signed bundle locally:

```sh
mvn clean deploy -P release -Dcentral.skipUpload=true
```

This command runs the build and installs artifacts in the local Maven repository,
then stages the release bundle at `target/central-publishing/central-bundle.zip`
without uploading it. Inspect its POM, library JAR, sources, Javadoc, signatures,
and checksums. Do not pass `-Dgpg.skip=true` when preparing a publishable bundle.

When ready to send the release to Central:

```sh
mvn clean deploy -P release
```

The profile sets `autoPublish=true` and `waitUntil=published`: Central publishes
the release automatically after successful validation, and Maven waits for that
publication. The plugin's default wait limit is 1800 seconds; increase it with
`-DwaitMaxTime=SECONDS` when necessary. If Maven times out or loses the connection
after uploading, inspect the existing deployment in the Portal before retrying:
the server may still complete publication. Confirm that
`com.jordansamhi:androspecter:2.0.0` resolves from Maven Central before
creating the corresponding Git tag/release and moving the checkout to its next
development version. Mark the README and wiki publication status accordingly.

## Standalone CLI distribution

After installing the selected library version locally, build the separate CLI with
`mvn -f cli/pom.xml clean verify`. Smoke-test `java -jar cli/target/androspecter-cli.jar
--help`, metadata extraction, real APK analysis, and an exported offline HTML graph.
The distribution is `cli/target/androspecter-cli.jar`, including its runtime
dependencies and logging provider. Do not distribute the unshaded
`original-androspecter-cli.jar` as the executable.

Attach the executable JAR and its SHA-256 checksum to the corresponding GitHub
release when publishing that release. The CLI POM does not inherit the library's
Central profile; the library's Maven release does not upload the standalone CLI.
The launcher can use a downloaded artifact through `ANDROSPECTER_JAR=/path/to/file.jar`.

## Development snapshots

The same profile routes a `-SNAPSHOT` version to Central's snapshot service.
Enable snapshots for the namespace in the Portal before deploying them. Snapshot
deployment makes the development artifact available directly; it does not use the
release validation and publication flow above. `-Dcentral.skipUpload=true`
also suppresses snapshot uploads. Keep consumer documentation explicit about
whether a development build must be installed locally or is actually published.

## References

- [OSSRH retirement and migration](https://central.sonatype.org/pages/ossrh-eol/)
- [Central publishing Maven plugin](https://central.sonatype.org/publish/publish-portal-maven/)
- [Central snapshot publishing](https://central.sonatype.org/publish/publish-portal-snapshots/)
