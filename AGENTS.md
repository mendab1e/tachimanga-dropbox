# Project context

## Product and stable identity

- Build a Kotlin APK extension for Mihon on Android and Tachimanga on iOS, targeting the Tachiyomi extension API **1.4**. It is loaded by the reader, not a standalone app.
- Browse a private Dropbox manga library and stream CBZ/ZIP chapters directly. Image-folder chapters are outside scope. Preserve private API access and range-based archive reading; do not substitute local imports, public shared links, or a manga server.
- Package: `eu.kanade.tachiyomi.extension.all.dropbox`; source class: `eu.kanade.tachiyomi.extension.all.dropbox.Dropbox`.
- Branding: Dropbox for Mihon & Tachimanga; APK label: `Tachiyomi: Dropbox (Mihon + Tachimanga)`. Keep source name `Dropbox`, language, versionId, package, signing key, and saved reference formats stable for compatible updates.
- Read version information from `extension/build.gradle.kts`. User instructions live in `README.md`, maintainer setup in `docs/development.md`, and device acceptance checks in `docs/testing.md`; coverage is mapped in `docs/spec-coverage.md`. Local validation notes belong in ignored `dist/VALIDATION.md`, not here.

## Code map

Kotlin sources are under `extension/src/main/kotlin/eu/kanade/tachiyomi/extension/all/dropbox/`:

- `Dropbox.kt`: host source interface, browsing/search/latest, model conversion, synthetic image interceptor, caches, and settings delegation.
- `DropboxPreferences.kt`: host-compatible settings and library invalidation/refresh callbacks.
- `DropboxApi.kt`: read-only file API, pagination, metadata, revision-pinned downloads, and authentication facade.
- `DropboxAuth.kt`: credentials, manual/callback PKCE, token renewal, and session concurrency checks.
- `DropboxHttp.kt`: isolated transport, bounded response reads, and JSON parsing.
- `Settings.kt`: preference storage interface and stable persisted keys.
- `AndroidSignIn.kt`: native Android settings launcher. Java `AndroidOAuthActivity.java` and `OAuthContract.java` under `extension/src/main/java/` implement the callback relay and parser without host-library dependencies.
- `RemoteZip.kt`: ZIP indexing, stored/deflated page decoding, bounds, size and CRC validation.
- `Library.kt`, `LibraryRepository.kt`, `LibraryCache.kt`: grouping, metadata reuse, bounded caches, freshness and request generations.
- `NaturalOrder.kt`: numeric filename ordering, file recognition and MIME types.

Other relevant paths: `extension/src/test/kotlin/` (JUnit/MockWebServer), `extension/src/main/AndroidManifest.xml` (discovery/callback), `host-api/` (compile-time API/Injekt), `scripts/package.py` (APK/checksum and DEX verification), and `.github/workflows/build.yml` (build/test/artifact upload, not publication). Keep launcher icons as ordinary PNGs for APK-loader compatibility.

## Library and archive behavior

- Default library path is `/Manga`; `/` selects the account/app-folder root. Each direct subfolder containing archives is a manga, with nested archives as chapters. Root-level archives are standalone manga with one chapter. Ignore empty series, hidden paths and macOS metadata.
- Optional series covers are `cover.jpg`, `cover.jpeg`, `cover.png`, or `cover.webp`; otherwise use the first page of the first naturally ordered archive. Sort page paths and chapter filenames naturally; return chapters in descending source order. Latest uses the newest archive modification time per series.
- Search matches series/archive titles and reuses catalog data for one minute. Browse/Latest page 1 reloads; explicit library refresh invalidates catalog metadata but retains immutable archive indexes. Later pages retain their snapshot past the freshness interval.
- Saved manga/chapter references use Dropbox IDs; page requests pin immutable revisions. Preserve these identities and legacy page-reference compatibility.
- Fetch archive metadata and ZIP tail/central directory, then only the selected page's compressed bytes. Small archives can fit entirely within the approximately 65 KiB tail request; do not promise that a whole archive is never downloaded.
- Require expected `206`, exact `Content-Range`, and matching body lengths. Reject full-file fallback. Reuse central-directory bytes already present in the tail. Validate decompressed size and CRC; never extract archive paths to disk.
- Support stored/deflate ZIPs, data descriptors and Unicode names. Reject encrypted, split/multipart, ZIP64, RAR/CBR, PDF and other compression methods. Recognize JPG/JPEG, PNG, WebP, GIF, BMP and AVIF; decoding depends on the host.
- Limits: 32 MiB per compressed/decompressed page, 16 MiB central directory, 20,000 ZIP entries, 1024 encoded bytes per filename, and 100,000 library items. Reject indexes exceeding the conservative 8 MiB accounting budget.
- Archive-index cache: shared 16 MiB accounting budget, LRU eviction, at most sixteen indexes and two simultaneous loads. Concurrent misses for the same revision share a load; retain indexed name/offset lookup.
- Dropbox Business team-namespace selection is not implemented.

## Authentication and host compatibility

- Authorization is exclusively in source settings. All source/manga/chapter browser hooks return static `https://www.dropbox.com` and must never prepare OAuth state. Tachimanga uses the same source URL for its WebView and browse subtitle. The older compile-only API lacks `getHomeUrl()`, so that hook is declared without `override` and tested through the real parent's virtual dispatch.
- Use OAuth authorization-code flow with PKCE S256, no app secret, `token_access_type=offline`, and only `files.metadata.read` / `files.content.read`. Refresh tokens automatically; retry authentication failures at most once.
- Manual/iOS flow omits `redirect_uri`: settings prepare a stable link, the user opens it in Safari, pastes Dropbox's code, and browsing completes exchange. Store only the public challenge link in `authorization_url`; keep the verifier out of URLs. Reuse pending link/verifier across reloads and do not prepare a new link for an already connected account.
- Blank app-key overrides use `BuildConfig.DROPBOX_APP_KEY`. Changing the effective client clears credentials and pending authorization; equivalent keys preserve the connection. Disconnect, app changes and successful exchange clear the prepared link. Local disconnect is separate from Dropbox-side Connected apps revocation.
- Android sign-in is available only on real Android with the installed callback activity. Callback URI: `dropbox-manga://oauth/callback`. Register it in the same Dropbox app used by the effective public key; send the identical URI in authorization and token exchange.
- The Java-only activity relays the code through an explicit ResultReceiver/Binder capability to the reader process. Verifiers and tokens never enter the activity. Keep manual PKCE state separate. Callback state expires after ten minutes, rejects duplicates/mismatches/expiry, and checks persisted state before saving tokens so recreated sources cannot restore obsolete credentials after disconnect, app changes, manual entry or newer attempts. Process death requires restarting sign-in or manual fallback. Do not add a launcher activity.
- Tachimanga 5.1/build 520 compatibility constraints: never call `setPersistent()`; plain Preference click listeners and `CustomContext.startActivity` are no-ops; clipboard methods throw `Stub!` and no clipboard service is exposed. Use stable keys, non-null typed defaults and supported preference-change listeners. Return false after atomic settings updates to prevent host re-persistence. Native Android click listeners must remain behind the platform/activity gate; do not add nonfunctional copy/open controls on iOS.
- Keep the disabled executable build label (`extension_build`) to distinguish loaded source code from stale extension metadata. Refresh the Android connection button after app-key edits, disconnect and successful sign-in.
- No supported extension OAuth callback, native SDK, document-picker or File Provider bridge is implemented on iOS. Such integration requires host support. Scope compatibility conclusions to the tested runtime and recheck official Dropbox/Tachimanga documentation before changing integration assumptions.
- Use the private client that removes inherited interceptors, event listeners, cookies, authenticators and disk caches; refuse redirects. Bound token JSON to 64 KiB and other JSON to 8 MiB. Never retain token parser errors as exception causes.
- Credentials live in host source preferences; encryption and backup exclusions depend on the host. The extension manifest does not configure host backups. Never put tokens in URLs, errors, logs, checked-in files or APK assets.

## Public app key and signing

- Use one maintainer-owned Dropbox app registration so users only authorize access. The supplied public key is in Git-ignored `extension/dropbox-app-key.txt`; keep it local. Never embed an app secret/token or substitute an unrelated key.
- Key precedence: Gradle property `dropboxAppKey`, nonblank environment `DROPBOX_APP_KEY`, then the local key file. An explicitly empty property produces a custom-key build. CI uses `vars.DROPBOX_APP_KEY` because fresh checkouts lack the local file. If the key file is tracked in another checkout, remove it from the index while preserving the local file.
- Dropbox-side permissions, redirect registration and user eligibility require live verification before claiming they are configured correctly.
- Preserve the existing personal signing key under ignored `.tooling/` for compatible updates. Distribution signing uses `EXTENSION_KEYSTORE`, `EXTENSION_STORE_PASSWORD`, `EXTENSION_KEY_ALIAS` and `EXTENSION_KEY_PASSWORD`.
- Keep `dist/`, build outputs, SDKs, caches, credentials, keystores and local settings ignored. Do not publish artifacts or access a live account without user authorization.

## Build and verification

- Pinned toolchain: JDK 17, Gradle wrapper 8.2.1, Android Gradle Plugin 8.2.2, Kotlin 1.7.22, platform/build-tools 34. Min SDK 21; compile/target SDK 34; JVM target 1.8.
- Host API, Injekt, Kotlin stdlib, OkHttp, RxJava, serialization and AndroidX preferences are compile-only. Do not package replacements or casually raise runtime ABI requirements. Preserve `kotlin.stdlib.default.dependency=false` and the wrapper checksum.
- Vendored API/Injekt provenance and compiler adjustments are recorded in `host-api/ORIGIN.md`. Preserve licenses and document further adjustments.

For code changes, run relevant specs and release/package checks:

```sh
./gradlew :extension:testDebugUnitTest :extension:assembleRelease :extension:lintDebug
python3 scripts/package.py
python3 scripts/test_tachimanga_runtime.py
```

If no system JDK is installed, use the ignored project-local toolchain:

```sh
JAVA_HOME="$PWD/.tooling/jdk" GRADLE_USER_HOME="$PWD/.tooling/gradle-home" \
  .tooling/gradle-8.2.1/bin/gradle --no-daemon \
  :extension:testDebugUnitTest :extension:assembleRelease :extension:lintDebug
```

- Release output: `extension/build/outputs/apk/release/extension-release.apk`. Packaging locates build-tools 34.0.0 through `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties`, verifies package/version against Gradle, and rejects bundled host/dependency class definitions. Verify APK signatures and keep validation notes accurate. Increment version code/name for distributed releases.
- The runtime script requires installed Tachimanga compatibility classes (default `/Applications/Tachimanga.app/Wrapper/Tachimanga.app/MCHome.bundle/res.zip`; override with `--runtime` / `--java-home`). It uses memory-only preferences and no network. Do not redistribute the runtime or use real app preferences.
- Its separate Android settings pass uses a test-only availability double, never packaged. Offline checks do not prove native clicks, browser/Binder handoff, live OAuth or reader rendering. Use `docs/testing.md` device acceptance checks for those and distinguish automated, user-reported and independently verified results.
- Do not rerun builds solely for documentation edits.
