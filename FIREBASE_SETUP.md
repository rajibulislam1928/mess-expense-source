# Firebase setup for group sync

Mess Expense stores its tracker locally first, then syncs the selected group through Firebase Authentication and Cloud Firestore. Google sign-in identifies each person; there is no app password and no Drive/Sheets sync. Only a signed-in member of a group can read or write that group's event log. Group invite codes are random, single-use, and expire after 48 hours; a group owner can revoke unused codes.

## Firebase project setup

1. In Firebase Console, create or select a project and register an Android app with package `com.messexpense.app`.
2. Enable Google sign-in under Authentication. Download the Firebase Android `google-services.json` for that exact package after registering the Android app.
3. Create a Cloud Firestore database. In Firebase Console → Firestore Database → Rules, paste the complete contents of `firebase/firestore.rules.example` and click **Publish**. These rules define the group membership boundary, restrict invite operations, and deny other access by default. Do not replace them with rules that allow all authenticated users.
4. Add the debug signing certificate SHA-1 for the APK you intend to test to the Firebase Android app's fingerprint settings, then ensure Google OAuth has the corresponding client. A separately signed release needs its own certificate fingerprint.
5. Place `google-services.json` at `app/google-services.json` in the private source tree before building. The Google Services plugin is only applied when this file exists. Do not commit the configuration file to this public source repository, and never put service-account JSON, OAuth client secrets, keystores, signing passwords, or Google passwords in the Android client.

## In-app setup and use

1. Install the app on each phone and sign in with each person's own Google account.
2. On the first phone, create a group. The group owner can make an invite code. It is a one-time code with 48-hour expiry; share it only with intended members. The owner can revoke an unused code from the app.
3. On another phone, sign in and enter that code under Join group. The app consumes the code while granting that signed-in account membership. The code itself is not a permanent access credential.
4. Select the group and use **Sync now** to pull/apply the group's saved data; edits then sync while connected. The app reports errors and keeps local data if offline or Firebase is unavailable. When a joined group has no shared tracker yet but this phone has saved entries, the app asks before uploading those entries.
5. The XLSX import/export controls continue to save through the Android file picker on the device. Importing a workbook does not automatically make it shared; sync it to the selected group after reviewing the loaded data.

## Verification required

A build without `google-services.json` verifies compilation only; it does not connect to Firebase. After project setup, test Google sign-in, create/join/revoke flows, member and nonmember Firestore rules, group-isolated data, one-phone read/write, offline status, and live updates on two real Android phones. Do not describe sync as operational until those tests pass.
