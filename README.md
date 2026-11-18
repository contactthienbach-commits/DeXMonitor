# DeX Monitor 0.8.0

Floating performance strip for Samsung DeX (Galaxy S20 FE, Android 13). Source only.

## Changes in 0.8.0 (vs 0.7.0)
- Fixed a malformed `<service>` tag in AndroidManifest.xml that stopped the build.
- Overlay is now created on the external (DeX) display when one is active, using a display/window context; falls back to the phone screen.
- Rounded card look, fixed width (640dp max), remembers dragged position.
- CPU % falls back to an estimate from core clock speed (shown with ~) when /proc/stat is blocked.
- Fixed literal "\n" in the app-usage list, chart text now scales with density.
- Android 13 notification permission is requested; "reset position" button added.

## Build (no Android Studio needed)
1. Create a GitHub repo and upload everything in this folder (including `.github/workflows/build.yml`).
2. Open the Actions tab, run "Build APK" (it also runs on every push).
3. Download the artifact `DeXMonitor-debug-apk`, unzip, install `app-debug.apk` on the phone.

## Use
1. Open DeX Monitor, grant "Appear on top", tap "Bật / áp dụng".
2. Connect the phone to the hub (DeX). The strip should appear on the DeX screen above the taskbar.
3. Tap to expand (auto-collapses after 10 s), drag to move. Use button 5 if it ends up off-screen.

## Limits
- Not compiled or tested here. Expect to fix small build errors on first run.
- No per-process CPU/RAM list (Android blocks that for normal apps); usage list is foreground time over 24 h.
- GPU / SoC temperature depend on kernel access and may show N/A.
