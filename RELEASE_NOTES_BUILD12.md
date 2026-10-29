# Build 12 — Mess Expense 1.3.6 (versionCode 12)

This public source snapshot corresponds to the debug-signed APK published in the separate APK repository.

## Fixes

- Preserve Firestore-compatible encoding/decoding for nested arrays.
- Rename the Firestore-reserved field that caused sync writes to be rejected.
- Declare `keptEmptyGroupId` so empty-group handling does not fail at runtime.

## Verification

The build, codec regression test, JavaScript syntax checks, and APK signature verification passed for Build 12. The APK is debug-signed and intended for testing, not production distribution. Google sign-in on a physical phone and synchronization between two phones remain untested.

The configured Firebase project's latest published member-gated Firestore rules were verified to match the tested candidate; a deny-all catch-all is retained. Public source excludes private Firebase configuration and signing credentials.

APK release: https://github.com/rajibulislam1928/mess-expense-apk/releases/tag/firebase-test-2026-10-29-v1.3.6-build12
