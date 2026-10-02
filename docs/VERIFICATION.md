# Verification record

Build success, synthetic bridge success, real-dot interoperability and device UI checks are separate claims. Release notes and GitHub Actions identify the exact tested commit; this record will be completed before tagging the milestone.

The initial local environment has Node.js but no installed Android SDK, Gradle, JDK or emulator. No Android SDK licenses have been accepted by project automation.

| Check | Observed result |
| --- | --- |
| Bridge `npm test`, Node.js 26.0.0 | 87 passed, 0 failed/skipped; independently rerun |
| Bridge `npm run demo` | Synthetic request/event/read/reply, restart, refresh and unsubscribe passed; 0 external callbacks and model calls |
| Wrapper integrity | Gradle 8.11.1 wrapper JAR matches its official SHA-256; distribution SHA-256 pinned |
| Source pattern check | Passed; checks do not replace independent review |
| Local Gradle/native build | Blocked: no JDK or Android SDK installed |
| Hosted Android build/unit/UI/lint | Pending first reviewed source publication |
| Native rendered screenshots | Pending hosted UI tests |
| Paired phone/watch, physical devices, TalkBack, battery and process death on devices | Not run |
| Production OAuth, public endpoint, plugin install or real dot subscription | Not implemented/validated |

The tests exercise synthetic principals, injected callback receivers and clocks. They do not establish a live dot connection or real DNS/TLS interoperability. The local CLI's outbound callback transport remains disabled.
