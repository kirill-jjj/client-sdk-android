---
'livekit-android': patch
---

Add stereo=1 to the subscriber answer's Opus fmtp line for media sections where the server offer advertised sprop-stereo=1. Without this, a stereo track published by another participant is decoded as mono on Android.
