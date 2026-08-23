---
'livekit-android': patch
---

Negotiate stereo Opus on the subscriber answer: add `stereo=1` to the fmtp line for media sections where the server offer advertised `sprop-stereo=1`. Without this, a stereo track published by another participant is decoded as mono on Android.

Also adds a `stereo` option to `LocalAudioTrackOptions` (surfaced as the `TF_STEREO` track feature) and `AudioTrackPublishOptions` (sent as `stereo` in the AddTrackRequest), mirroring client-sdk-js `forceStereo`.
