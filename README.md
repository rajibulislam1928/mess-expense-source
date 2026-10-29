# Mess Expense for Android

Mess Expense tracks weekly meals and bazaar spending, supports `.xlsx` import/export, and includes Firebase Authentication and Cloud Firestore group-sync controls with invite codes and manual sync.

## Current build

- App version: `1.3.6` (`versionCode` 12)
- Source snapshot: [GitHub source release v1.3.6-build12](https://github.com/rajibulislam1928/mess-expense-source/releases/tag/v1.3.6-build12)
- APK: [Build 12 GitHub prerelease](https://github.com/rajibulislam1928/mess-expense-apk/releases/tag/firebase-test-2026-10-29-v1.3.6-build12)
- Package: `com.messexpense.app`; minimum Android version: Android 6 (API 23)

Build 12 fixes Firestore data encoding for nested arrays, the reserved Firestore field name, and a missing `keptEmptyGroupId` declaration. The codec regression test, build checks, JavaScript syntax checks, and APK signature verification passed for the published build. The APK is debug-signed and is for testing, not a production Play Store release.

## Firebase and test status

Firebase Authentication and Firestore group-sync code and example rules are included. A member-gated rules version with a deny-all fallback has been published for the configured project and verified against the tested rules candidate. However, Google sign-in on a physical phone and synchronization between two phones have not been tested; do not treat device sign-in or cross-device sync as verified. Local tracker data remains available if sync fails.

The public source repository intentionally excludes `google-services.json`, service-account credentials, signing keys, and passwords. To build or test against your own Firebase project, configure it locally as described in [FIREBASE_SETUP.md](FIREBASE_SETUP.md), and keep private configuration out of Git.

## Build

- Android Studio with JDK 17 and Android SDK platform 34
- Open this folder in Android Studio and run Gradle sync, or build the debug variant from the IDE/Gradle.
- For Firebase work, place your project-matching `google-services.json` at `app/google-services.json` locally; Git ignores this file.

This repository contains source code, not a production-signed APK. Release APKs are in the separate [APK repository](https://github.com/rajibulislam1928/mess-expense-apk).

## License

Unless otherwise noted, this project is licensed under the MIT License; see [LICENSE](LICENSE).
