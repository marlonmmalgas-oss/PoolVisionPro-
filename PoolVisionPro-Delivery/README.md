# Pool Vision Pro

Android screen-capture aiming overlay. The app captures frames with Android MediaProjection, estimates the green table area, segments compact non-felt coloured/white regions as ball candidates, estimates the cue ball from whiteness, and draws a cue-to-object guide, object travel ray and four geometric rail-reflection candidates.

## Build the installable APK
Upload the contents of this project root to the root of your GitHub repository (including `.github/workflows/android.yml`). GitHub Actions builds `app-debug.apk`; download `PoolVisionPro-debug-apk` from the successful workflow run's Artifacts section.

## Use
Install the APK, grant Display over other apps, tap Start Live Table Scan and approve screen capture. Switch to 8 Ball Pool. Drag the PV toolbar; PV toggles drawing, +/- adjusts guide length, and × closes the overlay.

## Accuracy and limitations
This implementation is an on-device colour-segmentation heuristic, not a trained pool-specific detector. It can confuse UI graphics/reflections with balls and can miss balls due to table colour, lighting or screen scaling. Cue identification and nearest-target selection are estimates. Rail lines are geometric reflection candidates; spin, speed, precise collision transfer, pocket-mouth geometry, multi-ball collision chains and cue-ball resting position are not modelled. Use the APK to test device compatibility and detection quality; do not assume every suggested path is physically valid.
