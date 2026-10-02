# Contributing

Start with the README capability boundaries, architecture, bridge contract, and threat model. Keep patches small and make connection/delivery claims match observable evidence.

- New code stays Apache-2.0 compatible; retain upstream notices and document new dependencies.
- Do not include tokens, keys, runtime state, private conversations, or developer-specific paths.
- Use official documented interfaces. Real-dot integration needs an approved plugin/authentication setup and an actual subscription test.
- Keep synthetic fixtures clearly labeled. Do not replace an unavailable dot connection with a generic model and describe it as the same dot.
- Run the bridge tests and synthetic demo, core/shared tests, native UI tests, Android builds, and lint appropriate to the change. Report missing-device checks separately.
- Tests use injected clocks, principals and callback transports. They must not make paid model calls, grant device permissions, accept SDK licenses, or register accounts.

Submit an issue or pull request with the concrete behavior, validation, and any remaining limitations. See [AGENTS.md](AGENTS.md) for project automation guidance.
