# Contributing to AndroSpecter

## Public API documentation

Every new or changed public method, constructor, and interface callback, and every
protected extension point available to subclasses, must
have Javadoc that explains how a library developer should use it. Keep the
documentation alongside the declaration and update it whenever behavior changes.

- Explain what the operation does and any required setup.
- Document each parameter with `@param`, non-void results with `@return`, and
  expected failure conditions with `@throws`.
- Declare public and protected no-argument constructors explicitly and document
  them. Class-level descriptions do not document compiler-generated constructors.
- State relevant nullability, mutation, ownership, thread, cancellation, and
  Soot lifecycle constraints. Say whether returned values survive a reset.
- Document record components with class-level `@param` tags so their generated
  accessors have meaningful API documentation.
- Include a short usage example when setup or composition is not obvious.

Before committing API changes, generate and validate the public documentation
with JDK 17 or later, including the JDK used for publication. Check `mvn -version`:
Maven may use a different Java runtime from the shell's `java` command, and newer
Javadoc tools can report additional documentation warnings.

```sh
mvn org.apache.maven.plugins:maven-javadoc-plugin:3.4.0:javadoc \
    -Dshow=protected -Ddoclint=all -Dmaven.javadoc.failOnWarnings=true
```

The generated documentation is in `target/site/apidocs/index.html`. `protected`
includes both public API and subclass extension points. The release profile uses
the same visibility and fails on Javadoc warnings. Resolve missing documentation
and validation warnings rather than suppressing them.

Doclint checks structure, links, and required tags, but cannot establish that a
description matches the implementation. Review defaults, failure behavior, returned
collection ownership, and Soot lifecycle constraints against the code as well.
