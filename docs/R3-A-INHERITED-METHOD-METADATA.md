# R3-A — Inherited Method Metadata

Branch: `refactor/r3-inherited-method-metadata`
Base: `refactor/r2-classload-observer`

## Delivered scope

- Extend bounded class discovery with an `inheritedMethods` metadata array.
- Include public methods inherited from superclasses and interfaces.
- Keep methods declared directly on the target class in the existing `methods` array.
- Reuse the existing per-request member-record budget; inherited signatures consume the same budget.
- Mark `metadataTruncated` when inherited metadata exceeds the remaining budget.
- Never invoke a discovered method, initialize a class, or read a field value.

## Deliberate limits

- This increment reports inherited public methods exposed by `Class.methods`; it does not claim to enumerate non-public inherited members.
- Constructor and field metadata remain in their existing bounded discovery path.
- Hook application remains limited to the existing declared-method rules. Discovery output is not an executable rule and does not automatically install hooks.
- No parameter/return-value transformation, field mutation, or generated code execution is introduced.

## Tests

`InheritedMethodMetadataTest` covers inherited superclass methods, exclusion of methods declared by the target class, deterministic record budgeting, truncation, and zero-budget behavior.

## Acceptance

- Android Debug build and JVM unit tests must pass in CI.
- Inspect the serialized discovery response to confirm `inheritedMethods` is present and response/member budgets remain bounded.
- Real LSPosed acceptance remains separate: validate the exact target app class loader and runtime reflection behavior on device.

## Next increments

1. Add non-public inherited metadata with explicit visibility rules and the same budgets.
2. Introduce stable metadata IDs and schema-versioned candidate rule proposals.
3. Add constructor-hook support only after lifecycle, overload identity, rollback, and device tests are specified.
4. Add staged candidate validation and last-known-good rule retention before any Agent-driven application.