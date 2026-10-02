# Local bridge contract (v0.1)

This contract describes a local development harness. A production OAuth endpoint and a real dot subscription are not configured. Local fixture replies are synthetic.

App requests carry `Authorization: Bearer <app development token>` and JSON content types. The token is scoped to one `conversationId`. Tokens must not be placed in URLs, source, or logs. The developer chooses the token outside version control.

- `GET /v1/status` -> `{ "mode": "local_development", "liveDotConnected": false }`.
- `POST /v1/messages` -> body `{ "requestId": "UUID", "conversationId": "local-preview", "text": "...", "createdAt": "ISO-8601" }`; new request result `{ "requestId": "UUID", "status": "queued" }`. Same ID and content is idempotent and returns its existing status (`queued|awaiting_reply|replied|failed`); changed content under an existing ID is rejected. A duplicate does not reactivate a stopped/failed event.
- `GET /v1/messages?conversationId=local-preview` -> `{ "messages": [{ "requestId": "UUID", "conversationId": "local-preview", "text": "...", "createdAt": "ISO-8601", "status": "queued|awaiting_reply|replied|failed", "reply": null }] }`. Replied messages include a string `reply`.

The MCP endpoint is `/mcp`. Authenticated MCP principals use a separate development token scoped to the same conversation. Read tool: `fetch_request` with `requestId`. Write tool: `send_reply` with `requestId` and `text`; replies are idempotent. Event: `companion.message.created`; filter: `conversation_id`; payload: `request_id`, `conversation_id`, `created_at` (no model instructions or private context).

Phone/watch transport uses Google Play Services Wearable Data Layer. No bearer tokens or backend URLs travel over the watch link. Requests carry schema version, request ID, text, and created time. The phone acknowledges persistent queueing separately from backend receipt or reply.

Public HTTPS callback delivery follows OpenAI's documented MCP Events protocol (`2026-07-28`). A local injected receiver used in tests cannot establish real ChatGPT interoperability. Do not expose this development server publicly.

Events do not replay (`cursor: null`). A message submitted without an active matching subscription remains locally queued and is not retroactively delivered when a subscription appears. Restore the subscription and send a new explicit request. Phone transport exhaustion can retry with the same request ID; a failed backend event needs subscription/bridge recovery rather than an automatic resubmission.
