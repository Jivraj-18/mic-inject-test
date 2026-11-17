# mic-inject-test

Minimal no-root probe: can a shell-registered (Shizuku/app_process) AudioPolicy
loopback mix feed audio into another app's microphone capture on this device?

Builds `MicInject.java` to `classes.dex` in GitHub Actions and attaches it to a
release. Run the dex on the phone as the shell user via `rish`; a 440 Hz tone is
injected into the MIC capture preset for 90 seconds. Record with any voice
recorder during that window — if the recording contains the tone, injection
works.

## Build
Push these files to a repo. Actions runs automatically and creates the
**"Latest build"** release with `classes.dex`.

## Run (phone)
1. Download `classes.dex` from the release into your Download folder.
2. In Termux: `sh rish` (confirm `id` shows `uid=2000(shell)`).
3. `sh /sdcard/Download/run.sh`
4. While it prints "INJECTING...", record ~10s in a voice recorder, then play back.

Tone present → mic injection works, no root. Room only → the HAL ignores the
injection mix (the `[MicInject]` lines show how far it got).
