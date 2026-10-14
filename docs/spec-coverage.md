# Authentication and settings refactoring coverage

The refactoring preserves the source API, saved preference keys, separate manual/Android PKCE flows, private transport, and archive streaming. Characterization specs were added and passed against the original implementation before extracting authentication and settings construction.

| Behavior | Executable coverage |
| --- | --- |
| Manual PKCE uses the effective app key, omits a redirect URI, clears the single-use code and retains offline access | `DropboxApiTest` |
| Equivalent effective app keys preserve credentials and pending callback state; replacement keys disconnect | `DropboxApiTest`, `AndroidOAuthTest`, offline Android settings specs |
| Manual links/verifiers survive source recreation and remain independent of Android attempts | `DropboxApiTest`, `AndroidOAuthTest`, `SettingsSmoke` |
| Callback authorization and exchange use the same redirect; callback lifetime remains ten minutes | `AndroidOAuthTest` |
| Wrong state, expired/duplicate callbacks, invalid routes/codes, replay and missing offline tokens are rejected | `AndroidOAuthTest` |
| A callback that expires during exchange cannot save tokens | `AndroidOAuthTest.callbackExpiringDuringExchangeCannotSaveTokens` |
| Concurrent duplicate callbacks across source instances cannot cancel the claimed exchange or issue a second request | `AndroidOAuthTest.duplicateCallbackAcrossSourcesDoesNotCancelTheClaimedExchange` |
| Disconnect, app changes, manual entry or a newer attempt invalidate stale exchanges without erasing replacement state | `AndroidOAuthTest`, `DropboxApiTest` |
| Token renewal retains an existing refresh token when Dropbox omits a replacement; authentication failures retry at most once | `DropboxApiTest` |
| Token/parser failures hide response contents; token redirects and inherited HTTP observers are refused | `SecurityTest`, `AndroidOAuthTest` |
| Settings have stable keys and typed defaults; the build label is disabled; manual links and browser hooks remain compatible | `SettingsSmoke` with the real installed Tachimanga compatibility classes |
| App-key edits and disconnect refresh the Android sign-in button; invalid paths/read-only edits are rejected; code input is trimmed; actions return false to prevent host re-persistence | `SettingsSmoke` with the test-only Android availability double |
| Browse-to-page access, revision-pinned ranges, ZIP validation, catalog and archive-cache behavior | Existing JVM suite |

Run JVM specs, release compilation and lint with the toolchain in the [development guide](development.md#build):

```sh
./gradlew :extension:testDebugUnitTest :extension:assembleRelease :extension:lintDebug
python3 scripts/package.py
python3 scripts/test_tachimanga_runtime.py
```

The runtime script requires a locally installed Tachimanga runtime and uses memory-only preferences. Its Android settings pass replaces only `AndroidSignIn` with a test double in an isolated JVM; the real compatibility pass uses production classes. The double is outside the extension source set and is never packaged.

Native Android activity lifecycle, browser/Binder handoff, live Dropbox OAuth/API access and native iOS rendering require [device acceptance](testing.md#android-device-acceptance). These offline specs do not establish that coverage.
