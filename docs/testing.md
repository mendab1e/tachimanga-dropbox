# Testing and device acceptance

## Automated checks

Run JVM specs, release compilation, lint and packaging using the [development guide](development.md#build). JUnit/MockWebServer tests cover browse-to-page access, pagination, OAuth state and token renewal, private HTTP behavior, exact revision-pinned ranges, ZIP decoding/validation, natural ordering and cache behavior. They require no real Dropbox credentials. See the [spec coverage map](spec-coverage.md) for authentication and settings scenarios.

## Offline Tachimanga compatibility

After compiling the release classes and APK, run:

```sh
python3 scripts/test_tachimanga_runtime.py
```

The script defaults to the compatibility runtime inside `/Applications/Tachimanga.app/Wrapper/Tachimanga.app/MCHome.bundle/res.zip` and accepts `--runtime` and `--java-home`. It uses memory-only preferences and makes no Dropbox requests or changes to installed app data. Do not redistribute the runtime.

The production-class pass checks the host APK parser, executable build label, settings serialization, stable sign-in links, clean browser hooks, library edits, refresh and disconnect. Test-only reflection/Unsafe bypasses Android configuration constructors.

A separate JVM doubles Android sign-in availability to exercise the Android settings branch: equivalent/replacement app keys, connection-button state, library-path validation, the read-only link, trimmed code entry, refresh and disconnect. The real compatibility pass always loads production `AndroidSignIn`; the double is never packaged.

These checks do not establish native preference-click behavior, successful callback UI updates, activity lifecycle, browser/Binder handoff, live Dropbox OAuth/API access or native iOS rendering. Those still require device acceptance.

## Android device acceptance

Use the [Mihon installation and connection instructions](../README.md#mihon-android).

1. Install the APK through Android's package installer, open Mihon's Dropbox settings, and confirm **Sign in to Dropbox** appears.
2. Approve browser authorization and confirm return to the reader with **Dropbox connected** without copying a code. Browse a series and a standalone CBZ and read pages.
3. Disconnect, reopen settings, and test cancel/deny followed by a successful retry. Verify the manual fallback still displays a code and connects when pasted.
4. Change the effective app key and confirm sign-in is immediately available; equivalent keys should preserve the connection. Restore the intended app key afterward.
5. Close/reopen the reader after connecting and check credential persistence and reading after access-token expiry. If the reader is killed during authorization, restart sign-in or use manual fallback.

## Tachimanga iOS device acceptance

Use the [Tachimanga installation instructions](../README.md#tachimanga-iphoneipad) and [manual connection flow](../README.md#ios-or-manual-fallback).

1. Import the APK and confirm Dropbox appears as a source and settings render correctly.
2. Connect using the prepared link in Safari and pasted code; browse a series folder and a standalone CBZ.
3. Read a deflated multi-page CBZ and confirm page order and images; test a stored ZIP too.
4. Close/reopen Tachimanga and check persistence and reading after access-token expiry.
5. Add a chapter in Dropbox, refresh the source and confirm it appears. Check that repeated searches reuse listings and **Refresh Dropbox library** makes updated metadata available after browsing/searching again. Test a changed cover using an uncached image request.
6. Disconnect and confirm new uncached requests require sign-in.

Record device/reader versions and individual results in the validation record. Distinguish automated checks, user-reported operation and independently verified device results; cached images alone do not prove live access.
