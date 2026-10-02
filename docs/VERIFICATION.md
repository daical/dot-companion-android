# Verification record

The implementation at [`b92e0354582da643c5e888db02c0bd0b537ee62f`](https://github.com/daical/dot-companion-android/commit/b92e0354582da643c5e888db02c0bd0b537ee62f) passed [the complete hosted workflow](https://github.com/daical/dot-companion-android/actions/runs/36963111664) on October 2, 2026. This evidence snapshot and the [screenshots](SCREENSHOTS.md) come from that run. Release notes identify the additional successful workflow for the exact tagged commit and its paired APKs.

The foundation is an unofficial community preview. Build success, synthetic bridge success, device transport and a real dot connection are separate claims.

| Check | Observed result |
| --- | --- |
| Bridge `npm test`, hosted Node.js 22 | 87 passed, 0 failed/cancelled/skipped; also independently passed locally on Node.js 26 |
| Notice-generator Python regressions | 11 passed, including byte offsets, complete text, multiple runtime archives and inherited POM licenses |
| Bridge `npm run demo` | 11 synthetic request/event/read/reply, restart, refresh and unsubscribe checks passed; 0 external callbacks and model calls |
| Native JVM/Android tests | 70 passed, 0 failed/errors/skipped: core 10, shared 22, phone 20, watch 18 |
| Phone/watch `assembleDebug` | Both APKs built with JDK 17, platform 35 and Build Tools 35.0.0 |
| Shared/phone/watch `lintDebug` | All passed; 0 errors and 24 warnings: shared 2, phone 9, watch 13 |
| Native rendered UI | Six actual View/Canvas PNGs exported under Robolectric native graphics and visually inspected; phone 393 × 851 dp and round watch 192 × 192 dp at mdpi |
| Round-watch clock regression | Initial render contains clock pixels; after scrolling to the receipt, the clock adds zero pixels in the top-center clock band |
| APK signatures and paired identity | Official SDK `apksigner` and `aapt2` passed; both apps use `dev.dotcompanion.app` and the same build's debug signing certificate |
| Runtime graph and bundled notices | Phone 91 entries/76 runtime archives; watch 94 entries/79. APK asset hashes match coverage reports; full project notices and every dependency attribution/upstream notice reference are present |
| Google upstream notice preservation | Exact resolved AAR hashes checked independently; all 67 byte-range notice fragments retained verbatim, with only identical full text deduplicated into 14 texts |
| Wrapper and source review | Gradle 8.11.1 wrapper JAR checksum verified, distribution checksum pinned; source pattern check and independent foundation/security reviews passed |
| Local native toolchain | Not run: the initial workstation has no JDK, Android SDK, Gradle or emulator. Hosted builds use preinstalled SDK packages; no automatic license acceptance or SDK installation |
| Paired phone/watch emulators or physical devices | Not run |
| TalkBack, large fonts, battery and process death on devices | Not run; local durability/failure behavior has JVM/Android regression coverage |
| Production OAuth, public endpoint, plugin installation or real dot subscription | Not implemented or validated |
| Real callback DNS/TLS or native dot voice | Not validated; local CLI outbound callbacks and microphone/notification permissions remain disabled |

Lint warnings are retained in CI reports. They concern dependency updates, target API 35, stable versioned capability names, Wear capability resources consumed by Play services, and intent-filter formatting. They are not a completed device or store-compatibility review. No lint errors are suppressed to pass this milestone.

The hosted artifact includes test XML/HTML, lint reports, resolved dependency reports, notice coverage, screenshots, signing output and an APK/notice hash manifest. Private absolute-path input catalogs and signing keystores are excluded. Developer APKs share a key within one build; keys can differ between builds, so the APK bytes are not claimed to be reproducible. Release checksums refer to the actual distributed pair.

UI tests use explicit local request, receipt and license fixtures. Bridge tests use synthetic principals, injected callback receivers and clocks. These checks do not establish a live dot connection, paired hardware delivery, accessibility on a watch, or public DNS/TLS interoperability. The next gates are listed in the [roadmap](ROADMAP.md).
