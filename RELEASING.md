# Publishing AndroSpecter

The `release` profile uses Sonatype's Central Publisher Portal through
`central-publishing-maven-plugin:0.12.0`. It replaces the retired OSSRH URLs and
Nexus staging plugin. Ordinary `mvn install` builds remain local and do not activate
publishing. The current `1.2.0-SNAPSHOT` version remains a development version.

## Local checks

Use JDK 17 or later and Maven 3. Maven 4 is not supported by this publishing plugin.
Before preparing a release, run the tests, generate the strict public Javadoc, and
build the independently maintained examples:

```sh
mvn clean install
mvn -f examples/pom.xml clean package dependency:copy-dependencies
mvn -Prelease -Dgpg.skip=true verify
```

The last command checks release packaging, including source and Javadoc JARs,
without signing or uploading. It does not validate credentials or prove that
Central will accept a signed release. Inspect the generated API documentation and
exercise the examples against representative APKs before choosing a release version.

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

Choose the intended non-snapshot version, update the root POM and the library
dependency in `examples/pom.xml`, and update version references in the README,
examples, and wiki. Commit these changes under the maintainer's identity. Keep the
examples' library dependency aligned with the artifact being tested.

With the release version in place, create the signed bundle locally:

```sh
mvn -Prelease -Dcentral.skipUpload=true clean deploy
```

This command runs the build and installs artifacts in the local Maven repository,
then stages the release bundle at `target/central-publishing/central-bundle.zip`
without uploading it. Inspect its POM, library JAR, sources, Javadoc, signatures,
and checksums. Do not pass `-Dgpg.skip=true` when preparing a publishable bundle.

When ready to send the release to Central:

```sh
mvn -Prelease clean deploy
```

The profile uploads the release and waits for Central's validation. Automatic
publication is disabled: complete publication from the Portal after reviewing the
validated deployment. Confirm that the released Maven coordinates resolve before
creating the corresponding Git tag/release and moving the checkout to its next
development version.

## Development snapshots

The same profile routes a `-SNAPSHOT` version to Central's snapshot service.
Enable snapshots for the namespace in the Portal before deploying them. Snapshot
deployment makes the development artifact available directly; it does not use the
release validation and manual publication flow above. `-Dcentral.skipUpload=true`
also suppresses snapshot uploads. Keep consumer documentation explicit about
whether a development build must be installed locally or is actually published.

## References

- [OSSRH retirement and migration](https://central.sonatype.org/pages/ossrh-eol/)
- [Central publishing Maven plugin](https://central.sonatype.org/publish/publish-portal-maven/)
- [Central snapshot publishing](https://central.sonatype.org/publish/publish-portal-snapshots/)
