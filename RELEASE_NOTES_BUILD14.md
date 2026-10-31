# Mess Expense 1.3.8 public preview — versionCode 14

This is a debug-signed preview, not a production-ready or Play Store release.

## Changes

- Adds an in-app updater that checks public GitHub releases, offers Install or Cancel, verifies the downloaded APK package/version, opens Android's normal confirmation installer, and removes the cached APK after the app confirms the newer version is installed. Android still requires the user to approve installation.
- Adds a collapsible background diagnostics panel with last attempt/success, WorkManager state, notification permission/channel state, and latest worker error.
- Moves Firebase sync and group-management controls into the drawer; only the selected group name remains in the main header.
- Fixes permission-prompt state and respects the Shared tracker updates notification channel state.

## Verification and limits

- The candidate APK build succeeded and signature verification passed. Its signing certificate matches the public v1.3.7 preview.
- No device, updater, notification-delivery, or live Firebase tests were performed. WorkManager scheduling and runtime sync/notification behavior remain unverified.
- The public source repository excludes `google-services.json`, service-account credentials, passwords, and signing keys.
- The APK is debug-signed and not production-ready.
