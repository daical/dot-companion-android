# Interaction design

An original coral/violet companion gives the local preview a recognizable presence without copying the Apple Watch demo or ChatGPT branding. Movement is restrained; the character never stands in for a connection indicator.

## Phone

The connection label is always visible. A local preview offers synthetic text replies, local bridge mode reports that dot support is unverified, and disconnected/offline states explain that requests remain on the device. Message cards show their individual state and reply provenance; a mode switch never changes the meaning of an older response.

The text composer has a labeled input and a send action. Configuration is an explicit developer action; the token is memory-only and not shared with the watch. Delete history removes the local queue through a distinct control. Voice is labeled unavailable and has no hidden permission or recording action.

## Watch

Use a round-screen list with concise labels, readable contrast and controls at least 48 dp high. Keep the companion small enough that status and the primary text action remain reachable. Long text scrolls; narrow screens and increased font sizes require native screenshot and paired-device verification.

The watch records its outbound request before transmission. The phone returns a durable-queue receipt separately from a reply. Disconnection keeps the same request ID for retry. A watch-local synthetic fixture is visibly labeled and is not sent to the backend later as if it were new.

## States to verify

| User-visible condition | Meaning |
| --- | --- |
| Local preview / synthetic reply | Entirely local fixture |
| Queued | Saved locally; no remote delivery claimed |
| Sending | A transport attempt is running |
| Waiting for reply | Bridge accepted the request; no answer yet |
| Replied / local bridge | Reply tool supplied content to the harness; real dot still unverified |
| Transport failed / retry | Local transport attempts exhausted; retry keeps the same request ID |
| Bridge delivery failed | Recover the subscription, then explicitly send a new request |
| Offline / disconnected | Keep pending requests and avoid a false delivery claim |

Robolectric/Compose semantics checks and native-rendered screenshots provide the first UI evidence. They do not replace TalkBack, font scaling, real-device animation, battery, or paired Data Layer checks.
