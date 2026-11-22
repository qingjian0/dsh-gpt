# R2 — Optional ClassLoader Metadata Watcher

Branch: `refactor/r2-classload-observer`
Base: `refactor/r1-bounded-discovery`

## Purpose

Port the useful observation behavior of the legacy dynamic class watcher without
reintroducing an unbounded scanner or arbitrary code execution.

## Contract

- Config field: `observeClassLoads`, default `false`.
- Hooks only `ClassLoader.loadClass(String, boolean)`.
- Records only successful class loads whose names start with `com.openai.`.
- Deduplicates class names and caps one enabled window at 256 unique names.
- EventBuffer remains bounded (100 records by default) and debounces Provider publish.
- Event shape contains a fixed rule ID, class name, fixed method signature and elapsed
  time. It does not retain Class objects, ClassLoaders, method arguments, return values,
  field values, network data or credentials.
- Disabling the runtime unhooks the watcher and clears its deduplication window.

## Safety/performance choices

The watcher is opt-in and disabled by default. It does not call `Class.forName`,
initialize classes, enumerate methods, or inspect fields. The explicit DEX discovery
request remains the only path that inspects class member metadata.

## Validation

JVM tests cover prefix filtering, deduplication, event budget and reset. Android CI must
compile the libxposed hook and run the complete JVM test suite. Real LSPosed validation
is still required to measure global ClassLoader hook overhead and verify that the
protected two-argument overload is hookable on the target Android build.
