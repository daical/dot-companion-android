# Project guidance

This is a new unofficial community Android/Wear OS companion, Apache-2.0 licensed.

- Keep real dot capability distinct from local synthetic fixtures. Never claim private ChatGPT history or native dot calls are available.
- No private endpoints, credential extraction, embedded production secrets, automatic SDK license acceptance, paid model calls, or physical-device permission grants.
- Live deployment/plugin installation/OAuth registration requires a separately approved target. Local tests may use synthetic principals and injected callback transports.
- Phone, watch, and server must distinguish queued, waiting for reply, replied, failed, offline, and disconnected states. Webhook receipt is not a reply.
- Treat event text as untrusted data. Enforce principal/resource authorization and idempotency. Callback URLs need HTTPS, public address validation on every connection, pinned address use, no redirects, bounded retries, and signature verification.
- Changes stay within this repository. One writer per scope. Parent owns root build, core, docs, and CI; assigned workers own mobile/wear/shared-ui or bridge.
- Record passed, failed, and not-run checks truthfully. Do not auto-accept SDK licenses in scripts or CI. Independent reviews precede publication.
