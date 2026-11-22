# R3-B — Stable Runtime Candidates

Branch: `refactor/r3-runtime-candidate-proposals`
Base: `refactor/r3-inherited-method-metadata`

## Scope

- Define stable method identity from schema version, target package, runtime class, declaring class, method name, and JVM descriptor.
- Use JVM descriptors so overloaded methods and inherited declarations remain distinct.
- Emit versioned, JSON-serializable candidate proposals with bounded evidence references.
- Restrict proposal targets to the `com.openai.*` namespace and the initial action to metadata-only timing observation.
- Enforce a staged lifecycle: proposed → approved → validated → applied. Rejection is available before application.
- Require a non-empty approval actor, validation reference, and application reference at each transition.

## Safety boundary

A candidate proposal is data, not executable code. This increment does not resolve methods, install hooks, execute application code, inspect arguments or return values, read fields, or alter network traffic. Creating a proposal does not imply approval. Runtime application remains a separate, explicitly authorized operation.

## Identity

The candidate ID is a truncated SHA-256 digest over a canonical, schema-versioned identity tuple. JVM descriptors encode primitive, reference, and array parameter/return types. A method overload therefore receives a different ID, and inherited methods retain their declaring-class identity.

## Tests

- Stable identity for repeated discovery of the same overload.
- Distinct IDs and descriptors for overloaded methods.
- Package-scope and evidence validation.
- Approval and validation gates before application.
- Rejection terminality.

## Acceptance

Run Android Debug build, JVM unit tests, and LSPosed metadata verification in CI. Device acceptance remains separate and must confirm target class-loader identity and real hook lifecycle behavior.
