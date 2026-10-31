# Mess Expense 1.3.7 candidate — versionCode 13

This is a public, debug-signed pre-production preview. It does not replace the restored installed APK and is not a production Play Store release.

## Background sync and notifications

- Checks the selected group's Firestore event log with WorkManager on connected networks, at Android's periodic-work minimum interval (15 minutes; actual execution can be later).
- Applies non-conflicting remote events into a durable SharedPreferences snapshot while the WebView is closed; restores that state into the WebView when reopened. Local unsynced changes are preserved when their fields do not overlap. On overlap, it does not overwrite local state; existing foreground sync remains responsible for conflict resolution.
- Posts one replaceable notification for newly observed remote-device changes; filters out this device's events and tracks event IDs/high-water marks. Android 13+ notification permission is requested once; if declined, enable it in Android Settings.
- Does not upload local edits from the closed-app worker.

## Checks and limits

- `assembleDebug` succeeded with Firebase client configuration present only in the private build environment; `google-services.json` is ignored and excluded from the public source archive.
- Firestore nested-array codec/patch-decode regression passed; JavaScript syntax check and APK signature verification passed. Android unit-test task reports `NO-SOURCE`. No emulator or physical-device, live Firebase, background-merge/conflict, or notification-delivery test was run.
- Debug APK only; no production signing or Play Store readiness. The APK was not installed, so the restored installed version remains untouched.
