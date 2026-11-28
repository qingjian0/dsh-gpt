# Sign in with ChatGPT (SIWC) integration

## Scope

SIWC is an independent, first-party ChatGPT plan authorization channel. It does not replace the existing ChatGPT Android DPoP relay or the legacy ChatGPT Web relay. It does not use Codex OAuth, Codex CLI credentials, or Codex Responses.

The SIWC plan-usage flow is restricted to the public OpenAI Responses API:

- Model catalog: `GET https://api.openai.com/v1/models`
- Inference: `POST https://api.openai.com/v1/responses`
- Every inference request sets `store=false` and `stream=true`.
- A stream is successful only after `response.completed`.
- ChatGPT `backend-api` endpoints are not used by this channel.

Official protocol references:

- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference

## Authorization and account isolation

1. Persist one opaque host ID for this app installation. The current implementation uses a UUIDv4 URI.
2. Bind an ephemeral loopback listener to `127.0.0.1` before launching the system browser.
3. Keep the exact `http://127.0.0.1:<port>/auth/callback` URI for both authorization and token exchange.
4. Generate fresh PKCE verifier/challenge, state, and OIDC nonce for every attempt.
5. Use `dynamic_agent_client` only for first-time registration. Persist and reuse the issued `oaiapp_...` client ID for that account registration.
6. Verify the ID-token signature against the discovered JWKS and validate issuer, audience, nonce, expiry, and subject before saving the profile.
7. Keep each issued client ID, verified subject, granted scopes, and token set isolated in its own profile.

The loopback listener accepts only local connections and bounds request-line/header sizes. OAuth authorization URLs and bearer credentials must never be logged.

## Credential protection and lifecycle

- Access, refresh, and ID tokens are stored together in an AES-GCM encrypted profile document.
- The AES key is generated and held by Android Keystore.
- There is no plaintext fallback if Keystore is unavailable.
- Refresh is serialized per client/subject pair and commits the replacement access token and rotating refresh token together.
- Sign-out attempts provider-side refresh-token revocation, retries transient failures, then clears local tokens regardless of remote confirmation.
- Sign-out retains the account registration mapping and stable host ID for later reauthorization.

## Current implementation boundary

The account vault, browser login, model catalog request, explicit Responses API smoke test, refresh, and sign-out lifecycle are implemented in the SIWC development branch.

This remains a separate account/API test path. It is not yet wired as a selectable upstream mode in the local OpenAI-compatible gateway. The Android DPoP and Web gateway paths remain unchanged until a separately tested Responses-compatible gateway adapter is implemented.
