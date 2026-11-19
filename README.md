# Pool Vision Pro — consolidated vision rebuild

This is a complete Android project replacement package for the current repository. It includes the Android app, MediaProjection capture service, floating overlay, OpenCV vision engine, Gradle configuration and GitHub Actions APK workflow.

## What changed

- Table detection now targets the blue/cyan felt hue measured from the supplied gameplay recording (OpenCV hue approximately 96; accepted range 78–115).
- Table candidates must satisfy central-position, area, width/height and near-2:1 geometry checks. The playable region is inset from the felt/cushion mask.
- Ball circles use a radius derived from table width rather than a fixed screen radius.
- Cue-ball selection evaluates saturation and brightness across the ball disc, rejecting many striped balls whose centre alone is white. It also uses previous-frame position continuity.
- Overlay distinguishes the cue-ball candidate and displays cue/contact, object-to-nearest-pocket, cue-stop and one-cushion reflected candidate guides.
- Capture analysis is throttled to approximately 11–12 frames per second and processes the latest available frame.
- GitHub Actions builds and uploads `app-debug.apk` as the `PoolVisionPro-APK` artifact.

## Build

The workflow is at `.github/workflows/android.yml`. It runs on pushes to `main` and manual workflow dispatch. It builds `assembleDebug` and uploads `app/build/outputs/apk/debug/app-debug.apk`.

## Accuracy boundary — read before treating this as finished

The attached 68-second recording was used to tune the table hue and geometry. A local OpenCV experiment on 13 frames sampled every five seconds consistently found the same table mask rectangle at the 900-pixel analysis width; Hough detection returned 9–21 circle candidates depending on ball overlap. This is a detector smoke test, not ground-truth accuracy validation.

This package does **not** contain a trained neural ball detector. Hough circles can miss touching/overlapping balls or create false candidates. Cue-ball selection is a visual heuristic with temporal continuity. The app does not yet infer the actual cue-stick aim vector, spin, shot power, collision restitution, velocity, or physically validated multi-cushion paths. The displayed bank rays are geometric candidates, not guaranteed makeable shots. It therefore must not be represented as a fully validated production pool solver.

The project has not been built in this environment because an Android SDK/Gradle installation is not available here. The included GitHub Actions workflow is the APK build route; a successful Actions run is required before an installable APK exists.
