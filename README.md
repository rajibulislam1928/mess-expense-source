# Mess Expense for Android

Mess Expense tracks weekly meals and bazaar spending, supports `.xlsx` import/export, and includes Firebase Authentication and Cloud Firestore group-sync controls with invite codes and manual sync.

## Current build

- Current public preview: [Mess Expense 1.3.7 (versionCode 13)](https://github.com/rajibulislam1928/mess-expense-apk/releases/tag/firebase-preview-2026-10-31-v1.3.7-build13), with this source tagged [`v1.3.7`](https://github.com/rajibulislam1928/mess-expense-source/tree/v1.3.7).
- Previous public build: [v1.3.6 source](https://github.com/rajibulislam1928/mess-expense-source/releases/tag/v1.3.6-build12) and [Build 12 APK](https://github.com/rajibulislam1928/mess-expense-apk/releases/tag/firebase-test-2026-10-29-v1.3.6-build12).
- Package: `com.messexpense.app`; minimum Android version: Android 6 (API 23)

Build 12 fixed Firestore data encoding for nested arrays, the reserved Firestore field name, and a missing `keptEmptyGroupId` declaration. The 1.3.7 preview adds background event polling, safe non-conflicting updates to durable local state, and change notifications. Build, codec/patch-decode regression, JavaScript syntax, and APK signature checks passed. No emulator or physical-device, live Firebase, background-merge/conflict, or notification-delivery test was run. This is debug-signed pre-production software, not a Play Store release; it does not replace the restored installed APK.

## Firebase and test status

Firebase Authentication and Firestore group-sync code and example rules are included. A member-gated rules version with a deny-all fallback has been published for the configured project and verified against the tested rules candidate. However, Google sign-in on a physical phone and synchronization between two phones have not been tested; do not treat device sign-in or cross-device sync as verified. Local tracker data remains available if sync fails.

The public source repository intentionally excludes `google-services.json`, service-account credentials, signing keys, and passwords. To build or test against your own Firebase project, configure it locally as described in [FIREBASE_SETUP.md](FIREBASE_SETUP.md), and keep private configuration out of Git.

## Build from source

### Requirements

- JDK 17 (Android Gradle Plugin 8.5.2 requires JDK 17).
- Android SDK Platform 34 and Android SDK Build-Tools 34.0.0.
- Internet access for the first wrapper run and dependency resolution.

The checked-in Gradle Wrapper pins Gradle 8.7 and verifies the official distribution with its SHA-256 checksum. Use the wrapper rather than a machine-installed Gradle. Android Gradle Plugin 8.5.2 is pinned in `build.gradle`.

### Debug APK

From a shell with `git` and the Android SDK installed:

```sh
git clone https://github.com/rajibulislam1928/mess-expense-source.git
cd mess-expense-source
./gradlew --version
./gradlew clean assembleDebug
```

On Windows, use `gradlew.bat --version` and `gradlew.bat clean assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Android Studio can also open the repository root and use the checked-in wrapper.

For a headless Linux SDK installation, set `ANDROID_SDK_ROOT` to the SDK directory and install the required packages with `sdkmanager "platforms;android-34" "build-tools;34.0.0"`. Android Studio's SDK Manager can install the same packages.

### Codec regression check

The host-side regression test needs only JDK 17 and can run without an Android SDK:

```sh
mkdir -p /tmp/mess-expense-codec-test
javac -d /tmp/mess-expense-codec-test \
  app/src/main/java/com/messexpense/app/FirestoreArrayCodec.java \
  tools/FirestoreArrayCodecTest.java
java -ea -cp /tmp/mess-expense-codec-test com.messexpense.app.FirestoreArrayCodecTest
```

It should print `PASS: nested Firestore event arrays encode without nested lists and round-trip exactly`.

### Firebase configuration and signing

The app compiles without Firebase project configuration, but Firebase sign-in and live sync require your own project setup. Follow [FIREBASE_SETUP.md](FIREBASE_SETUP.md), then put that project's `google-services.json` at `app/google-services.json` locally; this file is ignored by Git. Never commit Firebase service credentials, OAuth secrets, keystores, or signing passwords.

The debug APK uses the standard debug signing key and is for testing, not Play Store distribution. A production release must be signed with your own private release key; signing credentials are not included in this repository. The published APKs are in the separate [APK repository](https://github.com/rajibulislam1928/mess-expense-apk).

## License

Unless otherwise noted, this project is licensed under the MIT License; see [LICENSE](LICENSE).


## Background sync and change notifications (1.3.7 public preview)

After a group has completed a foreground sync, Android WorkManager checks its Firestore event log on a connected network (no more often than every 15 minutes; Android may defer runs). It replays remote events into a durable local snapshot while the WebView is closed, preserving local unsynced edits when their fields do not overlap. If an edit conflicts with a remote change, the worker leaves the local snapshot untouched; the existing foreground sync resolves the conflict when the app is opened. The WebView restores the background snapshot on next launch. The worker does not upload local edits while the app is closed.

One replaceable notification summarizes newly observed changes from other devices; own-device events are filtered and event IDs/high-water marks suppress duplicate alerts. Android 13+ requests notification permission once. If declined or disabled, enable notifications in Android Settings. Background execution, live Firebase access, conflict behavior, and notification delivery still need emulator or physical-device testing; this candidate was compiled and static/host checks passed but that does not verify live behavior. A Firebase client config used for a private build is not included in source files.
