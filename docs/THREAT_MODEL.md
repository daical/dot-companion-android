# Threat model

Scope: a local development milestone, plus requirements for a future deployed bridge. The current server is loopback only; it is not a production security boundary or an OAuth provider.

| Asset / attacker action | Required control | Verification |
| --- | --- | --- |
| Other local processes access queued text or reply tools | Separate app/MCP principals, per-conversation authorization, constant-time token checks | Unauthorized, cross-principal and revoked-principal tests |
| Browser page attacks loopback server | Strict Host and Origin validation; no permissive CORS; bearer authorization | HTTP boundary tests |
| Callback URL reaches a private service | HTTPS/public address policy; DNS validation for every connection; pinned address/TLS hostname; no redirects | URL/IP/DNS failure tests with injected transport |
| Forged or replayed webhook | Standard Webhooks HMAC over exact bytes and fresh signing time, stable event ID; single-use verification challenge | Signing/challenge tests; real receiver validation remains pending |
| Duplicate text, duplicate reply, or reordered status | Stable request IDs, conflict rejection, idempotent tools; keep reply state terminal | Queue and bridge tests |
| Subscription survives expiry or access revocation | Persistent finite expiry and access recheck at delivery; idempotent unsubscribe | Fake-clock and restart/revocation tests |
| Watch message pretends to be a token or protocol upgrade | Same app/certificate Data Layer boundary, strict schema/version/size validation, no auth data in envelope | Core envelope tests; paired-device check pending |
| Preview is mistaken for the existing dot | Persistent reply provenance, visible synthetic mode labels, liveDotConnected always false | Native semantics tests and manual review |
| Prompt injection via text | Event payload contains IDs/data, not instructions; fetch tool returns untrusted text; no automatic privileged actions | Payload contract review |
| Sensitive text persists | Private app storage, backup disabled, bounded queue, delete-history control; server files mode 0600 | Persistence tests/review; OS/device validation pending |
| CI or release leaks secrets | New project only, ignored runtime data and keys, read-only workflow permissions, pinned action commits | Source allowlist/secret scan before publication |

Future production work must add OAuth 2.1/PKCE, exact audience/scope validation, protected-resource metadata, encrypted secret storage, rate limits, durable outbox capacity limits, retention/deletion policy, key rotation, operational monitoring, and reviewed hosting. Do not make the loopback harness public as a shortcut.

No model API key, private ChatGPT endpoint, browser cookie, scraped chat, microphone recording, or notification permission belongs in this milestone.
