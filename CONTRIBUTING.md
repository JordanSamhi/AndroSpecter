# Contributing to AndroSpecter

## Public API documentation

Every new or changed public method, constructor, and interface callback must
have Javadoc that explains how a library developer should use it. Keep the
documentation alongside the declaration and update it whenever behavior changes.

- Explain what the operation does and any required setup.
- Document each parameter with `@param`, non-void results with `@return`, and
  expected failure conditions with `@throws`.
- State relevant nullability, mutation, ownership, thread, cancellation, and
  Soot lifecycle constraints. Say whether returned values survive a reset.
- Document record components with class-level `@param` tags so their generated
  accessors have meaningful API documentation.
- Include a short usage example when setup or composition is not obvious.

Before committing API changes, generate and validate the public documentation
with JDK 17 or later:

```sh
mvn org.apache.maven.plugins:maven-javadoc-plugin:3.4.0:javadoc \
    -Dshow=public -Ddoclint=all -Dmaven.javadoc.failOnWarnings=true
```

The generated documentation is in `target/site/apidocs/index.html`. Resolve
missing documentation and validation warnings rather than suppressing them.
