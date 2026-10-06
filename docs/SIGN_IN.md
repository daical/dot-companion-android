# ChatGPT account sign-in

This local implementation provides account identity on the phone. It has not completed a real device sign-in, connected the selected existing dot, or started a native dot call. It makes no inference requests and requests no ChatGPT plan-usage permission.

## Phone experience

The home screen shows **Continue with ChatGPT** before the local synthetic preview. The button starts authorization in the system browser. The callback page asks the user to return to Dot Companion while the app verifies identity. Cancellation, denial, expiry, invalid responses, unavailable storage, and sign-out have separate visible states. An unsuccessful candidate cannot replace the active account.

The pending browser attempt and localhost listener live in the phone process. If Android ends that process during authorization, the user must return and start a fresh attempt. Whether the process remains available during the browser round trip still needs testing on the actual phone.

Saved accounts remain separated by their issued client ID and verified identity. Choosing a saved account restores only its own unexpired credentials. Expired accounts require reauthorization. Adding an account runs a new registration without overwriting the existing account before verification succeeds. This identity-only slice does not silently request refresh or model-use scopes.

The **Open ChatGPT** action launches the installed official phone app. The user chooses their dot and taps its phone button if their app supports it. The companion does not report a connected call, select an undocumented dot URL, or claim that mobile web supports dot calls. The watch explains how to sign in and call through the phone; credentials never cross the Wear transport.

## Protocol and storage

- The JVM `identity` module implements the [documented open-source sign-in flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in). The initial registration uses `dynamic_agent_client`, the actual app name, and a stable host UUID. Returning authorization uses the issued client ID.
- A callback listener binds only `127.0.0.1` on an available port before opening authorization. The path is exactly `/auth/callback`. Fresh state, nonce, and PKCE bind each attempt; duplicate, wrong-state, wrong-client, stale, and replayed callbacks are rejected. The listener and its input sizes are bounded, and cancel closes the pending connection.
- The requested scopes are `openid profile email`. Granted scope information comes from the token response. For an identity-only response with no scope field, the stored permission set is empty; the signed and nonce-bound ID token can still establish identity. The callback's scope text cannot enable ChatGPT plan usage or dot access.
- Discovery, token exchange, JWKS, and revocation use the fixed OpenAI HTTPS issuer with redirects disabled. Nimbus JOSE + JWT verifies asymmetric signatures, issuer, audience, authorized party, nonce, time claims, and the expected returning subject. An ID-token-only response is supported.
- The phone saves account records atomically with Android Keystore AES-GCM encryption. Backups are disabled. Secret-bearing objects redact their string representation. A corrupt or unavailable key/file activates no account and is preserved for recovery.
- Sign-out attempts remote revocation when a revocable token exists, then clears the selected local credentials while keeping its registration for future authorization. Failed remote revocation is disclosed. A failed local storage write disables credential use in that process and reports that removal from storage is unconfirmed.
- Tokens, callback contents, and provider errors are never sent to the watch, the synthetic bridge, logs, or model APIs.

## Watch restart recovery

The watch's phone-link preference now survives process restart. Only the watch opts into that persistence policy; the phone still disconnects its development bridge because its development token is memory-only. Request IDs, pinned peers, acknowledgment ownership, transport attempts, and retry deadlines remain in the durable queue. Remembering the mode does not establish current phone reachability or a live dot connection. Corrupt history still fails closed.

## Validation and remaining gates

The local native suite passed 138 tests: core 10, identity 49, shared UI/storage 24, phone 34 and watch 21. Both APKs assembled and lint passed with no errors. The identity tests use synthetic signed sessions and injected provider transports, plus real localhost HTTP callback handling. Native regression tests cover account storage/UI boundaries, watch restart/retry behavior, and the phone's credential-clearing behavior. Real Android Keystore behavior and the authenticated browser round trip still require physical-device validation. See the [verification record](VERIFICATION.md) for the evidence and limits.

Before calling this slice delivered:

1. Use an authorized Android SDK environment and compile both APKs, run identity/core/native tests and lint, and verify dependency notices and the paired signer. The current local build passed these checks. Scripts must not answer SDK license prompts automatically.
2. Verify the intended Android phone, ChatGPT account and proposed client registration. Complete browser authentication and consent personally on that device.
3. Confirm callback completion, verified account label, cancellation/denial, account switching, process restart, expiry, and sign-out on the actual phone. Confirm private account storage and absence of credential-bearing Wear messages.
4. Verify the official ChatGPT phone app is installed, opens from the handoff, and offers a manual call to the intended dot. Record app handoff separately from a call actually connecting.
5. Test paired phone/watch message recovery with a matched signing key. A new debug key cannot silently replace an APK with another signer; preserve data and resolve that separately before installation.
6. Separately approve and validate a hosted/plugin target and selected-dot subscription before claiming real dot messaging. Identity sign-in alone does not authorize this integration. Durable background reply processing remains open.

Official references: [registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [identity verification](https://developers.openai.com/siwc/website), [dot voice channels](https://learn.chatgpt.com/docs/dots/channels#chatgpt-and-voice), and [MCP Events](https://developers.openai.com/plugins/build/mcp-events).

For an existing preview with a different certificate, build **both** debug apps with `-PcompanionSideBySide=true`. The separate `dev.dotcompanion.app.dev` pair keeps the original install and its history intact. Its account registrations, credentials and queue start in separate app storage. Confirm the installed package and signer on both devices before testing.
