# Security and credential handling

## Non-negotiable repository rules

Never commit live credentials or extracted application state. This includes:

- ChatGPT/OpenAI access, refresh, ID, session, and device-bound tokens.
- DPoP private keys, key aliases, installation identifiers, device identifiers, FCM tokens, and authentication session IDs.
- Raw Auth0/ChatGPT shared-preference exports, kit archives, profile dumps, or unredacted request captures.
- Personal account claims, email addresses, organization identifiers, or other account-linked metadata unless explicitly synthetic and reviewed.

Use synthetic fixtures with obviously fake values for tests and documentation. Do not use a real account export as a fixture, even if the token appears expired.

## Local kit handling

The `data/raw-kit/` directory is for temporary local analysis only. Its raw JSON/XML/archive/APK/text exports are ignored by Git. The `relay/kit/` directory must contain only reviewed, redacted examples. Before committing, inspect the staged diff and run a secret scanner.

## Request diagnostics

Gateway request capture is disabled by default. When explicitly enabled, it may contain conversation text even after credential redaction. Keep capture local, disable it after diagnosis, and clear records when no longer needed. Never attach raw captures to public issues or commits.

## Incident response

If a credential or account export is committed:

1. Treat every included credential as compromised immediately.
2. Revoke the affected sessions/tokens and rotate device-bound credentials through the owning service.
3. Remove the files from the current tree.
4. Rewrite affected Git history and coordinate force-pushes / clone cleanup; a deletion commit alone does not remove old blobs.
5. Search forks, artifacts, caches, release assets, workflow logs, and other copies.
6. Verify the credential is invalid and record the remediation without reproducing the secret.

A repository cleanup is not a substitute for credential revocation or history rewriting.
