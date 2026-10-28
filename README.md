# Mess Expense for Android

Source snapshot for the Mess Expense v1.3.6 debug APK (`versionCode` 9): [APK release](https://github.com/rajibulislam1928/mess-expense-apk/releases/tag/debug-2026-10-27-group-sync-1.3.6).

Mess Expense tracks weekly meals and bazaar spending. This version includes local storage, device-file `.xlsx` import/export, and source code for Firebase Authentication and Cloud Firestore group sync with invite codes and a manual **Sync now** control. It does not use an app-specific password or Google Drive/Sheets for sync.

## Firebase and testing status

This public source snapshot does not include `google-services.json`, service-account credentials, signing keys, or passwords. Firebase has not been configured for this release, and live group sync, Firestore rules, installation on a phone, and cross-phone behavior have not been verified. The app should be treated as local-only until a Firebase project is configured and the checks in [FIREBASE_SETUP.md](FIREBASE_SETUP.md) pass. The rules example is at [`firebase/firestore.rules.example`](firebase/firestore.rules.example); review and deploy it only in your own Firebase project.

## Build

- Android Studio with JDK 17 and Android SDK platform 34
- Package: `com.messexpense.app`; minimum Android version: Android 6 (API 23)
- Open this folder in Android Studio and run Gradle sync, or build the debug variant from the IDE.
- For Firebase work, configure your own project and place its matching `google-services.json` at `app/google-services.json` locally. That file is ignored by Git; never commit service-account credentials or signing secrets.

This repository contains the application source, not a signed release APK. Existing dependencies keep their own licenses and terms.

## License

Unless otherwise noted, this project is licensed under the MIT License; see [LICENSE](LICENSE).
