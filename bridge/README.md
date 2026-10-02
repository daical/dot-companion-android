# Local bridge harness

This is an unofficial community development harness. It is not affiliated with or endorsed by OpenAI. It implements a testable subset of the MCP Events wire shapes, using synthetic development principals. It is **not a production MCP endpoint, OAuth 2.1 implementation, or a verified live dot integration**. Existing ChatGPT conversations, native dot calls, and microphone/audio transport are unavailable here.

The command-line server always binds `127.0.0.1`, defaults to port `8787`, and disables outbound callbacks. There is no public-bind flag, tunnel, deployment script, OAuth registration, or model call. Do not expose this server publicly. A local HTTP request or a successful synthetic callback does not prove that a dot is connected.

## Run the synthetic milestone

Node.js 22 or newer is required. No runtime or development packages are installed.

```sh
cd bridge
npm test
npm run demo
```

`demo` and `e2e` run the same synthetic scenario. It starts a temporary loopback listener, generates distinct app/MCP credentials in memory, verifies and deduplicates signed callbacks in an injected receiver, fetches a request, stores an explicitly synthetic reply, restarts private state, refreshes, and unsubscribes. It removes its temporary files and closes the listener before exit. It prints the check results; credential values and message bodies are not printed. No real subscription, external callback, or model call occurs. Local listeners require a sandbox permission that some execution environments restrict.

## Run the development queue

Set two distinct random development values without putting them in a command-line argument, URL, source file, or log. These shell assignments capture generated values without printing them:

```sh
export DOT_BRIDGE_APP_TOKEN="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("base64url"))')"
export DOT_BRIDGE_MCP_TOKEN="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("base64url"))')"
npm start
```

Tokens require 32–256 base64url characters. Generation with 32 random bytes is recommended. There is no password-based or account-based sign-in. Keep the same environment values across restarts to retain the development credentials. `DOT_BRIDGE_CONVERSATION_ID` defaults to `local-preview`; `DOT_BRIDGE_PORT` defaults to `8787`. The native preview currently uses `local-preview`.

For the Android emulator only, the phone can use `http://10.0.2.2:8787` to reach host loopback. The accepted Host names are `127.0.0.1`, `localhost`, and `10.0.2.2`, with the actual listener port. Requests with any Origin, forwarding headers, or duplicate security headers are rejected. The app token belongs only in the phone's developer configuration; it never travels over the Wear Data Layer. Physical-device installation and permissions are outside this milestone.

Private state is stored in `bridge/.runtime/state.json`. Its directory is mode `0700` and atomic snapshots are mode `0600`. The file contains local message text, replies, callback signing keys, subscriptions, and pending delivery records, but does not contain bearer token values. This is single-process development storage, with bounded capacity and no automatic deletion. Keep it private; both `.runtime/` and environment files are ignored by Git. Corrupt, linked, or permissively readable state fails startup and is preserved for manual recovery.

## App contract and state

The REST routes and separate MCP development token follow [the bridge contract](../docs/BRIDGE_CONTRACT.md). `/v1/status` always reports `mode: local_development` and `liveDotConnected: false`. `/mcp` supports JSON-RPC POST for `server/discover`, `events/list`, `events/subscribe`, `events/unsubscribe`, `tools/list`, and `tools/call`. Discovery advertises `2026-07-28`; this subset has not undergone MCP/ChatGPT interoperability validation.

| Status | What happened |
| --- | --- |
| `queued` | The bridge durably accepted a local request. There may be no active subscription. |
| `awaiting_reply` | A matching callback acknowledged receipt. The reply tool has not supplied a reply. |
| `replied` | The authenticated, conversation-scoped `send_reply` tool stored text. Its source is a local bridge, and dot provenance remains unverified. |
| `failed` | Every matching delivery stopped or exhausted its bounded attempts before receipt. |

An exact duplicate message ID and body returns the record's current status; it does not create a second event or reactivate a failed delivery. A changed body under an existing ID is rejected. Replies are likewise idempotent: identical repeated text succeeds and conflicting text is rejected. Fetch/reply tools cannot access another principal's conversation. Replies do not create message events, avoiding a feedback loop.

Events use `cursor: null` and provide no protocol replay. A message accepted before any active subscription remains locally queued, and creating a subscription later does not send its old event. A new request needs an explicit new UUID. Failed local delivery needs subscription/bridge recovery; reposting its existing ID is not recovery. This boundary also applies across interruptions. Persisted pending delivery attempts can resume while a valid subscription still exists, but that is separate from protocol replay.

## Callback harness boundaries

The event catalog, deterministic identity, callback challenge, expiration/refresh, original-identity unsubscribe, and thin request-ID payload follow the [official MCP Events documentation](https://developers.openai.com/plugins/build/mcp-events). Signing uses the [Standard Webhooks specification](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md) with Node's built-in HMAC-SHA256 over the exact serialized bytes. There is no copied SDK or third-party runtime dependency.

Subscriptions receive a finite lifetime, defaulting to one hour and capped at one hour; a requested lifetime under one second receives a one-second minimum. Requests for an unlimited lifetime still receive a finite expiration. Successful verification is cached for up to one minute per development principal/callback/key. Signing-key changes are reverified and dual-signed during a one-minute rotation window. Every delivery rechecks scope and expiry. Revocation, unsubscribe, and a `410` response stop future delivery. An already in-flight request cannot be recalled.

The separately exported `SafeHttpsCallbackTransport` is for isolated review and injected tests; the CLI does not select it. It requires HTTPS on port 443, resolves DNS on every attempt, rejects any answer set with a local/private/reserved address, pins the validated socket address, preserves the original hostname for TLS verification, and rejects redirects. It conservatively rejects **all IPv6**, including public IPv6 and mixed dual-stack DNS answers. Response/time/payload bounds are enforced. Real DNS/TLS and real callbacks have not been tested. The synthetic receiver opens no external socket.

Events are capped at 256 KiB. Transient connection errors, timeouts, `408`, `429`, and `5xx` use at most five total attempts with exponential delays; event ID and body remain stable while signing time and signature are refreshed. Each outbox record is rechecked immediately before sending, so resubscribing cannot revive a stopped record from a previously selected batch. `410`, `413`, redirects, security failures, and other permanent HTTP failures do not retry. The implementation does not support event batching, replay cursors, gap/terminated notifications, streaming, or a remote OAuth session.

The synthetic receiver binds subscription IDs to successful signed callback challenges and their signing keys before accepting events. Standard Webhooks does not sign `X-MCP-Subscription-Id`; that header cannot supply a new deduplication namespace. Within its fixed callback, the receiver deduplicates using the signed event ID. These fixture checks do not establish real ChatGPT receiver behavior.

## Before any live integration

An approved target would need a production authenticated HTTPS MCP endpoint, OAuth 2.1/PKCE and audience/scope validation, a real plugin installation and authorized event subscription, managed secret storage, durable transactional delivery, tenant isolation, updated address policy, operational limits, and independent interoperability/security review. A real event must reach the selected dot, and that dot must successfully call the scoped fetch/reply tools, before any live messaging claim. None of those approvals or validations is performed by this harness.
