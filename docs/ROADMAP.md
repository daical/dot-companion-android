# Staged roadmap

1. **Local foundation (this milestone):** native phone and round-watch screens, original animation, persistent queue and clear states, shared validation/retry tests, local authenticated synthetic text bridge, reviewed source and reproducible CI debug APKs.
2. **Device validation:** after Android SDK setup/license approval, exercise phone and watch emulators together. Check round 192/227 dp screens, large fonts, TalkBack, process death, disconnect/reconnect, duplicate request recovery, and screenshots. Physical-device installation requires a separately chosen device and explicit permission.
3. **Real dot text:** approve an endpoint/auth target; implement and review production OAuth and storage; install a private development plugin; subscribe the actual dot; prove request → event → fetch → reply → phone/watch and unsubscribe/revocation. Only then label that specific session connected.
4. **Push-to-talk:** establish a supported audio path first. Design explicit press/release/cancel, visible recording state, and audio deletion; request microphone permission only in the approved test context. Do not present text events as a voice call.
5. **Public beta:** reproducible signed builds, dependency notices, device matrix, privacy/retention documentation, external security review, and a release checklist. Play Store publication, paid hosting, notifications, and production OAuth grants are separate choices.

Acceptance of stage 1 does not imply stages 2–5 are complete. No paid standalone model mode is implemented.
