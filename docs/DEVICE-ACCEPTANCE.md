# DSH-GPT R1 Device Acceptance Checklist

This checklist is intentionally manual. A GitHub Actions build cannot verify LSPosed
injection, Binder identity, hidden DEX reflection behavior, or OEM process lifecycle.

## Test environment

Record exact values before testing:

- Android build/version and security patch
- Device model and ABI
- KernelSU/Magisk root implementation and version
- LSPosed version and mode
- ChatGPT package/version
- dsh-gpt module version/commit
- DSH host version/commit
- Whether DSH and ChatGPT run under separate UIDs

## A. Module loading and scope

1. Install the Debug APK from the verified R1 artifact.
2. Enable the module in LSPosed and scope only `com.openai.chatgpt`.
3. Force-stop and relaunch ChatGPT.
4. Confirm log: `module loaded; process=com.openai.chatgpt; api=102`.
5. Confirm the Application Context bootstrap runs once and Provider calls originate
   from the target package UID.
6. Confirm a normal ChatGPT launch is not delayed by DEX discovery; discovery must run
   only after an explicit DSH request.

## B. Provider identity and configuration

1. From the DSH host app, call `GET /hook/status`.
2. Confirm Provider reachability and distinguish it from `targetObserved`.
3. Submit an empty valid rules snapshot, then a harmless metadata-only rule.
4. Confirm the target process applies the new revision within the polling interval.
5. Disable and re-enable Hook callbacks; verify disable unhooks active callbacks and
   re-enable restores the last valid snapshot.
6. Confirm an unrelated UID cannot publish observations or manage rules/tasks.
7. Record the DSH host signing certificate and add explicit signature pinning before
   treating package-name-only Binder identity as a production security boundary.

## C. Bounded discovery

1. Request a narrow prefix such as `com.openai.auth` with a small page size.
2. Record elapsed time, classesScanned, dexScanned, recordCount, response bytes,
   scanTruncated, timedOut and nextCursor.
3. Verify methods, constructors and field name/type/modifier metadata are returned.
4. Verify discovery does not invoke methods or read field values.
5. Request the next page with the returned cursor; confirm no duplicate page records.
6. Test an exact class that exists and one that does not; expect explicit
   `class_not_found` for the latter.
7. Trigger cancellation while queued and while running; verify terminal CANCELLED
   state and rejection of stale worker results.
8. Kill the target process during a discovery task. After the 30-second lease expires,
   relaunch ChatGPT and confirm the same request is reclaimed with an incremented
   attempt/revision, not permanently skipped.
9. Force a transient Provider/index error and verify retries stop at the configured
   maximum attempt count.

## D. Hook lifecycle accounting

Using only a harmless method that is safe to observe:

1. Apply a rule and confirm `added` increments.
2. Apply the identical snapshot and confirm `retained` increments without rehooking.
3. Change only sampling configuration and confirm `replaced` increments.
4. Remove/disable the rule and confirm `removed` increments and callback no longer runs.
5. Make one rule invalid while another is valid; confirm the valid rule still applies
   and a failed replacement preserves the last-known-good callback.
6. Confirm event records contain only rule ID, class/method signature, elapsed time,
   and exception type. No arguments, return values, HTTP headers, cookies, request or
   response bodies, credentials, or challenge/proof material may appear.

## E. Performance, size and rollback

1. Measure cold start with no discovery request; compare against the same build without
   discovery enabled.
2. Measure peak memory and CPU during repeated bounded discovery requests.
3. Measure Debug and minified Release APK sizes separately; current verified number is
   Debug only (988,410 bytes).
4. Repeat on the supported Android/LSPosed version and at least one secondary Android
   version if available.
5. Rollback: disable the module in LSPosed, force-stop ChatGPT, then uninstall the
   Debug APK if necessary. Do not leave experimental Hook rules enabled.

## Acceptance rule

Do not merge either repository's integration branch until all sections A–E are recorded
with actual device output and no unresolved P0 security or lifecycle defect remains.
