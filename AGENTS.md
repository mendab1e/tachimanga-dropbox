# Project context

## Goal and user preferences

- Build a Tachimanga extension that browses a private Dropbox manga library and reads chapters directly in the app.
- The user's manga are **CBZ/ZIP files**. Image-folder chapters are outside the current implementation.
- The user wants to avoid individual developer registrations and manually distributing keys. The extension bundles the user-supplied maintainer public app key from the Git-ignored local file `extension/dropbox-app-key.txt`. Keep this file local and do not commit it. Users leave the optional custom-app field blank and only authorize Dropbox access.
- Preserve direct archive streaming and private account access. Do not silently replace this with local imports, public shared links, or a manga server.

## Current deliverable

- Kotlin APK extension targeting the Tachiyomi extension API **1.4**, intended for Tachimanga on iOS.
- Application/package: `eu.kanade.tachiyomi.extension.all.dropbox`.
- Source class: `eu.kanade.tachiyomi.extension.all.dropbox.Dropbox`.
- Current version: `1.4.11`, version code `11`.
- Installable artifact: `dist/tachiyomi-all.dropbox-v1.4.11.apk`.
- Installation, connection steps, limitations, and device acceptance checks: `README.md`.
- Local validation record: `dist/VALIDATION.md`. The entire `dist/` directory is Git-ignored; do not commit artifacts or files under it.
- This is an extension loaded by Tachimanga, not a standalone Android or iOS reader.

## Code map

Main Kotlin files are under `extension/src/main/kotlin/eu/kanade/tachiyomi/extension/all/dropbox/`:

- `Dropbox.kt`: host source interface, source preferences, browsing/search/latest, manga/chapter/page conversion, synthetic image interceptor, archive-index cache.
- `DropboxApi.kt`: Dropbox read-only HTTP API, pagination, OAuth PKCE and refresh tokens, metadata and revision-pinned byte-range downloads.
- `RemoteZip.kt`: central-directory parsing and individual stored/deflated page decoding, bounded reads, size and CRC checks.
- `Library.kt`: canonical library-path comparisons, cover lookup, and grouping archive files into series and chapters.
- `LibraryCache.kt`: bounded archive-index and catalog caches; shared in-flight archive loads, one-minute catalog freshness, and invalidation/request generations.
- `LibraryRepository.kt`: shared browse/cover metadata access, with API fallback for uncached or expired series.
- `NaturalOrder.kt`: numeric filename ordering, image/archive recognition, MIME types.

Other important files:

- `extension/src/test/kotlin/.../dropbox/`: JVM tests using JUnit and MockWebServer.
- `extension/src/main/AndroidManifest.xml`: extension discovery metadata and permissions.
- `extension/src/main/res/mipmap-*/ic_launcher.png`: ordinary PNG icons for APK-loader compatibility.
- `host-api/`: compile-time extension API and Injekt sources; provenance and licenses are retained.
- `scripts/package.py`: versioned APK/checksum packaging and inspection of DEX class definitions to reject bundled host/dependency classes.
- `.github/workflows/build.yml`: build, test, and artifact-upload workflow; it does not publish a release or extension repository.

## Library behavior

Default Dropbox path is `/Manga`; `/` selects the account/app-folder root.

```text
/Manga/
  Series Name/
    cover.jpg
    Chapter 001.cbz
    Chapter 002.zip
    Volume 2/
      Chapter 003.cbz
  Standalone Book.cbz
```

- Each direct subfolder containing archives becomes one manga. Nested archives become its chapters.
- Archives directly in the library root become standalone manga with one chapter.
- Empty series folders, hidden paths, and macOS metadata are ignored.
- Optional series covers: `cover.jpg`, `cover.jpeg`, `cover.png`, `cover.webp`. Otherwise use the first page of the first naturally ordered archive.
- Page paths and chapter filenames are naturally sorted; chapter lists are returned in descending source order.
- Search matches series/archive titles and reuses catalog data for up to one minute. Browse/Latest page 1 reloads; the Refresh Dropbox library setting invalidates catalog metadata immediately without discarding immutable archive indexes. Later pages retain their current snapshot past the freshness interval. Latest uses the newest archive modification timestamp in each series.
- Saved manga/chapter references use Dropbox IDs; page requests pin immutable revisions. Keep these identities stable when changing the implementation.
- The user explicitly chose authorization only via the link in settings. All source/manga/chapter browser URLs are static https://www.dropbox.com and never prepare OAuth state.

## Archive and HTTP constraints

- Fetch archive metadata and the ZIP tail/central directory, then fetch only the selected page's compressed bytes using HTTP ranges.
- Small archives can fit entirely in the approximately 65 KiB ZIP-tail request. Do not describe this as guaranteeing that no whole archive is ever downloaded.
- Require the expected `206` and exact `Content-Range`; reject full-file fallback or mismatched content lengths.
- Supported compression methods: stored and deflate, including data descriptors and Unicode names.
- Unsupported: encrypted, split/multi-part, ZIP64, RAR/CBR, PDF, and other compression methods.
- Limits: 32 MiB per compressed/decompressed page, 16 MiB central directory, 20,000 ZIP entries, 100,000 library items.
- ZIP filenames are limited to 1024 encoded bytes; reject indexes exceeding a conservative 8 MiB accounting budget. Cache indexes under a shared 16 MiB accounting budget with LRU eviction, retain at most sixteen, and allow at most two simultaneous index loads. Concurrent misses for the same revision share one load. Cached indexes avoid repeated metadata requests and index name/offset page lookups; central-directory bytes already in the fetched ZIP tail are reused.
- Validate decompressed size and CRC. Do not extract archive paths onto disk.
- Supported image filename extensions include JPG/JPEG, PNG, WebP, GIF, BMP, AVIF; actual image decoding depends on the host/device.
- Dropbox Business team-namespace selection is not implemented.

## Authentication and iOS integration decisions

- Current sign-in is OAuth authorization-code flow with **PKCE S256**, no app secret, and `token_access_type=offline`.
- Settings start with a disabled executable build/version label (`extension_build`) to distinguish loaded source code from stale Extensions metadata, then include an optional custom app-key override, library path, library-actions ListPreference (refresh/disconnect), automatically prepared sign-in-link field, and authorization-code field. The sign-in flow uses the automatically prepared settings link in Safari, then the user pastes Dropbox's code into settings. Blank app-key overrides use `BuildConfig.DROPBOX_APP_KEY`; changing the effective OAuth client clears credentials, while equivalent keys preserve the connection.
- A user reported HTTP 500 on opening settings. The installed Tachimanga 5.1 runtime reproduced `NoSuchMethodError: EditTextPreference.setPersistent(boolean)` with the old code. Its `Preference.setOnPreferenceClickListener` is a no-op. The settings bridge cannot open a clickable WebView link or copy to clipboard; the current sign-in flow requires copying the settings link into Safari. Never call `setPersistent()` or depend on plain Preference click actions; provide non-null typed defaults and stable keys, and use supported preference-change listeners. Returning false prevents host re-persistence after the API applies settings atomically.
- `authorization_url` stores the prepared public PKCE challenge link, never the verifier in the URL. It is removed on disconnect, app changes, and successful code exchange. `signInLink()` reuses the pending link/verifier across reloads and does not start authorization for an already linked account.
- The user requested simpler generation and a Copy button. Tachimanga 5.1's ClipboardManager methods throw Stub!, its CustomContext has no clipboard service, and startActivity is a no-op. Do not add fake controls. Versions 1.4.8/1.4.9 returned the OAuth URL from getHomeUrl(), but a device screenshot showed it beneath the browse title, even while manga were displayed. The runtime Source DTO exposes one baseUrl for both the source WebView and subtitle; there is no separate display/browser URL. Version 1.4.10 returns static baseUrl from getHomeUrl() without touching authentication. Do not reintroduce OAuth parameters into source metadata. The older 1.4 compile-only API does not declare this hook, so it is declared without override and checked through the real parent's virtual dispatch. Version 1.4.11 removes the remaining manga/chapter OAuth browser behavior at the user's request. All browser hooks return static baseUrl; browserUrl() was removed. Authentication errors direct users to the settings link. Keep authorization exclusively in settings.
- Authorization has no redirect URI: Dropbox displays a code that the user pastes into source settings. Browsing completes the exchange and clears the code/verifier.
- Request only `files.metadata.read` and `files.content.read`. Refresh access tokens automatically and retry authentication failures at most once.
- Use the private API client that removes inherited interceptors, event listeners, cookies, authenticators and disk caches; refuse redirects. Bound token JSON responses to 64 KiB and other JSON responses to 8 MiB. Never retain token parser errors as exception causes.
- Credentials live in the host app's source preferences. At-rest encryption and backup exclusions depend on the host; no secure-storage bridge is implemented. The extension manifest does not configure host backup behavior. Never put tokens in image URLs, errors, logs, checked-in files, or APK assets.
- Local disconnect clears credentials; Dropbox-side revocation is a separate Connected apps action.
- A distributed extension uses **one maintainer-owned Dropbox app registration**, so individual users only sign in. The user supplied the public key in the Git-ignored local file `extension/dropbox-app-key.txt`; its Dropbox-side permissions/user eligibility have not been verified. Build precedence: Gradle property `dropboxAppKey`, nonblank environment variable `DROPBOX_APP_KEY`, ignored local key file. GitHub Actions reads `vars.DROPBOX_APP_KEY`; configure that variable for CI builds with the maintainer key because the local file is absent from fresh checkouts. Without a key from any source, the build requires users to enter a custom key. If the local key file is already tracked in another checkout, remove it from the Git index while preserving the local file. Do not embed app secrets or tokens, or substitute an unrelated app key.
- Dropbox's native iOS SDK/app sign-in still requires a registered app key and native callback/Info.plist support. The current APK extension API has no implemented iOS document-picker or native SDK bridge.
- Dropbox in iOS Files requires no personal developer registration. Copying chapters from Files into `On My iPhone/Tachimanga/local/<Series>/` supports Tachimanga Local source, but creates local copies rather than this extension's API streaming flow.
- Direct native File Provider/document-picker integration would require host-side Tachimanga support; do not claim it can be added merely by changing this Kotlin extension.
- References checked during development:
  - https://tachimanga.app/help/guides/extensions_guide.html
  - https://tachimanga.app/help/guides/local-source.html
  - https://docs.dropboxapi.com/dropbox-api/docs/oauth
  - https://docs.dropboxapi.com/dropbox-api/docs/technical-reference/request-response-formats
  - https://github.com/dropbox/SwiftyDropbox
  - https://help.dropbox.com/integrations/ios-files-app
- Recheck current official documentation before changing integration assumptions.

## Build and dependency rules

- Toolchain: JDK 17, Gradle wrapper 8.2.1, Android Gradle Plugin 8.2.2, Kotlin 1.7.22, Android platform/build-tools 34.
- Android min SDK is 21; compile and target SDK are 34; JVM target is 1.8.
- JitPack artifacts for the old API/Injekt were unavailable, so their original pinned sources are vendored in `host-api`.
- `host-api/ORIGIN.md` records upstream commits and the omission of the unused legacy `LocalScoped` class for compiler compatibility. Preserve provenance/licenses and document any further adjustments.
- The host API, Injekt, Kotlin stdlib, OkHttp, RxJava, serialization, and AndroidX preferences are **compile-only**. Tachimanga supplies their runtime implementations. Do not package replacement copies or casually upgrade their ABI requirements.
- Keep `kotlin.stdlib.default.dependency=false` and the wrapper distribution checksum.
- Google Maven and Maven Central supply remaining dependencies.

Standard verification:

```sh
./gradlew :extension:testDebugUnitTest :extension:assembleRelease :extension:lintDebug
python3 scripts/package.py
```

This workspace also has ignored project-local build tools, SDK, Gradle caches, and a personal signing key under `.tooling/`; `local.properties` points to that SDK. If no system JDK is installed:

```sh
JAVA_HOME="$PWD/.tooling/jdk" GRADLE_USER_HOME="$PWD/.tooling/gradle-home" \
  .tooling/gradle-8.2.1/bin/gradle --no-daemon \
  :extension:testDebugUnitTest :extension:assembleRelease :extension:lintDebug
python3 scripts/package.py
```

- Original APK output: `extension/build/outputs/apk/release/extension-release.apk`.
- The packaging script needs Android `dexdump` from build-tools 34.0.0, located through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `local.properties`.
- Default release signing uses the personal/debug key. Keep the existing key for compatible personal updates; never commit it.
- Distribution signing uses `EXTENSION_KEYSTORE`, `EXTENSION_STORE_PASSWORD`, `EXTENSION_KEY_ALIAS`, `EXTENSION_KEY_PASSWORD`.
- Packaging validates the built APK package/version against Gradle configuration before copying, preventing stale builds from being labeled as newer releases.
- Increment version code/name for new distributed releases, regenerate `dist/`, and update validation notes accurately.
- Build outputs, SDKs, credentials, keystores, and local settings remain ignored. Do not publish artifacts or access a live account without the user's authorization.

## Validation baseline and remaining work

At the initial delivery:

- 21 JVM tests passed with no failures/errors.
- Release vital lint and debug lint passed; debug lint reported eight advisory findings.
- APK v1/v2 signatures verified with `apksigner`.
- DEX class definitions were inspected to confirm host/dependency implementations were absent.
- Tests cover mock Dropbox browse-to-page flow, cursors, PKCE exchange, token renewal, ranges/revisions, Unicode headers, natural ordering, ZIP decoding and error/limit handling.

Current 1.4.11 verification: 60 JVM tests passed, release/debug lint passed, APK signatures verified, and the maintainer public key was confirmed in the APK. The maintainer key file is now ignored by Git; local builds still read it, while fresh CI checkouts need `vars.DROPBOX_APP_KEY` to bundle the key. The user supplied a device screenshot with manga visible and an unwanted OAuth URL subtitle. Live OAuth/reading acceptance checks remain outstanding; the screenshot does not establish complete reader validation.

Runtime integration: `python3 scripts/test_tachimanga_runtime.py` tests release class files against the Java/Android compatibility classes from an installed Tachimanga runtime (default `/Applications/Tachimanga.app/Wrapper/Tachimanga.app/MCHome.bundle/res.zip`). It checks the built APK package/version with the host's actual APK parser, uses test-only memory preferences, checks the executable build label and settings JSON serialization, and exercises library edits, sign-in-link preparation/reload, refresh and disconnect without network requests. Do not redistribute the host runtime or use real app preferences in this test. The harness uses reflection/Unsafe solely to bypass Android configuration constructors.

**Not yet verified:** live Dropbox OAuth/API access or native Tachimanga iOS rendering/reading. Settings behavior has been verified offline against the installed Tachimanga 5.1 compatibility classes; those checks do not prove the UI, Safari/code-copy flow, or real reader images.

Preserve that distinction in reports. Follow `README.md`'s device acceptance checks when a device/live account becomes available. For code changes, run relevant tests and the release/package checks; do not rerun builds solely for documentation edits.
