# R1 — Bounded Discovery and Runtime Lifecycle

Branch: `refactor/r1-bounded-discovery`

## Delivered in this branch

### Bounded metadata discovery

`DexDiscoveryEngine` performs read-only metadata inspection in the target process.
It does not invoke app methods or read field values. It reports method signatures,
constructor signatures, and field name/type/modifier metadata.

Default per-request budgets:

| Budget | Default | Hard range |
|---|---:|---:|
| DEX elements visited | 16 | 1–32 |
| Class names scanned | 2,500 | 1–10,000 |
| Member records | 500 | 1–1,000 |
| Wall-clock budget | 1,500 ms | 100–5,000 ms |
| Serialized response | 64 KiB | 4–128 KiB |

The response reports scan counts, DEX counts, timeout, truncation, and pagination
metadata. Exact-class lookup returns an explicit `class_not_found` error. Reflection
metadata calls themselves are not interruptible by this engine; the deadline is checked
between DEX entries and metadata records, so the wall-clock limit is best-effort around
individual Android reflection calls.

### Discovery task lifecycle

`DiscoveryTask` defines queued/running/succeeded/failed/expired/cancelled states,
revisioned leases, bounded attempts, stale-worker rejection, and retryable failures.
The Provider persists the task snapshot and request payload. A target process claims a
request before doing work; if the process dies, another poll can reclaim it after the
lease expires. A stale worker cannot overwrite a newer task revision.

### Hook lifecycle accounting

`HookRuntime.applyWithReport` distinguishes added, replaced, retained, removed, and
failed hooks. Unchanged rules are retained without reinstalling callbacks. A failed
replacement keeps the previously installed callback active. The existing Pair-returning
method remains for source compatibility.

### DSH separation and CI

The DSH Agent plugin remains outside the Android app module. Android build and Node
plugin validation use separate workflows. Android CI now runs JVM unit tests and uses
per-branch concurrency cancellation to avoid wasting runner capacity on superseded
commits.

## Validation status

- Earlier bounded-discovery compile passed on the branch before Provider queue integration.
- Earlier JVM budget tests passed.
- The Provider queue, MainModule polling, Hook lifecycle report and task retry changes
  require the current CI run to finish before they can be marked build-verified.
- The DSH plugin syntax/package workflow passed after the package files were ported.
- No real-device LSPosed test has been performed in this delivery.

## Explicitly not complete

1. Strong certificate/signature pinning for the DSH host Provider caller. Current host
   authorization checks that the Binder UID maps to `app.dsh.mobile`; this is not a
   cryptographic pairing guarantee.
2. Source-level confirmation that the separate DSH host app implements all six bridge
   routes and maps them correctly to Provider calls.
3. Real device validation of Binder identity, MIUI/Android package visibility, DEX
   reflection access, runtime hook replacement/unhook semantics, and process-restart
   recovery.
4. APK size measurement for both debug and minified release artifacts.

No branch merge, main update, release publication, or device deployment is implied by
this document.
