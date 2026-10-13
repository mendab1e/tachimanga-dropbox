# Dropbox source for Tachimanga

A Kotlin extension targeting the Tachiyomi **1.4** API supported by Tachimanga. Browse your private Dropbox library and read CBZ/ZIP pages directly in the reader. No manga server, public shared links, or full-library download is required.

## Install

1. Transfer `dist/tachiyomi-all.dropbox-v1.4.10.apk` to your iPhone or iPad (Files, AirDrop, or iCloud Drive).
2. In Tachimanga, open **Browser/Browse → Extensions → +**, then select the APK. See [Tachimanga's manual-install guide](https://tachimanga.app/help/guides/extensions.html).
3. Open **Sources → Dropbox → source settings** and follow the connection steps below.

The APK is an extension package loaded by Tachimanga; you do not install it as an iOS application.

If an imported update still shows 1.4.6, fully close and reopen Tachimanga, then check Dropbox's source settings. The top row must say **Dropbox extension 1.4.10**, with **Installed source build 1.4.10 (code 10)** beneath it. This label comes from the loaded source code independently of the Extensions list metadata. If it shows 1.4.10 while the list shows 1.4.6, the list metadata is stale; if the row is absent or older, the new source has not loaded. The release APK's manifest and Tachimanga's own offline APK parser both report 1.4.10/code 10. Ensure you select the exact new filename when importing.

## Connect your Dropbox

This APK includes the maintainer's public app key. You do **not** need a developer account or an app key:

1. Set **Library folder** to your manga directory, such as `/Manga`.
2. Leave **Custom Dropbox app (optional)** blank.
3. In source settings, open **Dropbox sign-in**, copy its automatically prepared link, and open it in Safari.
4. Sign in and approve read access. Copy Dropbox's authorization code into **Dropbox authorization code** in source settings, then browse the source to finish connecting.

The pending sign-in link is generated automatically and reused across source/settings reloads, keeping its PKCE verifier stable while you sign in. To reconnect as another account, select **Dropbox library actions → Disconnect Dropbox**, reopen settings, and use the new sign-in link. The source browser always opens the Dropbox website. Tachimanga also displays that URL beneath the browse title, so it must remain static rather than contain a pending authorization link.

Tachimanga 5.1 runtime does not expose an iOS clipboard service to extensions: Android clipboard methods throw `Stub!`, and `Context.startActivity` and plain preference-click handling are no-ops. Therefore, this extension cannot supply a working native Copy button or clickable WebView link inside settings. Those controls require Tachimanga host support. Copying the authorization code back to source settings is still required because no native OAuth callback bridge is available.

To use your own Dropbox application, or a build without a bundled key:

1. Open the [Dropbox App Console](https://www.dropbox.com/developers/apps) and create a **Scoped access** app. Choose **Full Dropbox** to access your existing manga folder. **App folder** also works, but all paths will then be relative to `/Apps/<your app name>`.
2. Under **Permissions**, enable only `files.metadata.read` and `files.content.read`, then submit the change.
3. Copy the **App key** from the app's Settings tab into **Custom Dropbox app (optional)** (or **Dropbox app key** in an unconfigured build). Leave the app secret alone; this extension never needs it.
4. Set **Library folder** to your manga directory, such as `/Manga`. For App folder access, use `/` to browse the app folder itself.
5. Open the automatically prepared **Dropbox sign-in** link from source settings in Safari and approve the two read permissions. Dropbox will display an authorization code; no redirect URL needs to be registered.
6. Copy that code into **Dropbox authorization code** in the source settings. Return to Dropbox's source and browse it to finish connecting.

The extension uses PKCE S256 and requests offline access. After connection it clears the single-use code/verifier and automatically renews short-lived access tokens with its refresh token. Credentials stay in the host app's source preferences; they are never included in page URLs or embedded in the APK. See [Dropbox's OAuth guide](https://docs.dropboxapi.com/dropbox-api/docs/oauth).

Dropbox API requests bypass inherited host HTTP interceptors, event listeners, cookies, authenticators and disk caching, and do not follow redirects. Token responses are limited to 64 KiB; malformed responses produce generic errors without retaining response contents in exception causes. Other JSON API responses are limited to 8 MiB.

Credentials still use the host app's source preferences. Their encryption, backup exclusions and diagnostic handling depend on Tachimanga; these have not been verified on iOS. The extension API has no implemented Keychain/secure-storage bridge, and the APK's backup setting does not configure the host app. Securing credential storage at rest requires host support.

**Dropbox library actions → Disconnect Dropbox** removes saved credentials and the prepared link on this device and invalidates pending authentication responses, so a delayed sign-in or refresh cannot restore them. Reopening source settings can prepare a fresh sign-in link; that does not authorize access. To revoke the authorization at Dropbox, remove the app under your account's **Connected apps**. Do not paste credentials into issues or commit them to this project. If you change Dropbox accounts, remove the previous account's saved library items and downloaded/cached images in Tachimanga before adding the new account's library.


## Library layout

```text
/Manga/
  My Series/
    cover.jpg                    # optional: cover.jpg/jpeg/png/webp
    Chapter 001.cbz
    Chapter 002.zip
    Volume 2/                    # optional nested chapter folders
      Chapter 003.cbz
  Another Series/
    001.cbz
  Standalone Volume.cbz          # shown as a manga with one chapter
```

Each direct subfolder containing archives appears as one manga. Archives anywhere beneath that series are chapters. Archives directly in the library folder appear as standalone manga. Empty folders and hidden paths are ignored.

Within a CBZ/ZIP, image paths are sorted naturally: `1.jpg`, `2.jpg`, `10.jpg`. Nested image folders work, and macOS `__MACOSX` files and hidden files are ignored. Supported extensions are JPG/JPEG, PNG, WebP, GIF, BMP and AVIF; actual decoding depends on your host app/device.

An explicit series cover is used when available. Otherwise, the first page of the first archive becomes the cover. Chapters appear newest/highest filename first. Search matches series/archive titles; Latest sorts series by their newest archive's Dropbox modification timestamp. Refresh Browse or Latest to discover Dropbox changes. Searches reuse a catalog for up to one minute; for an immediate search refresh, tap **Refresh Dropbox library** in source settings and search again. Later pages retain their current catalog snapshot past that interval. Fresh catalog data also supplies cover/chapter metadata, avoiding another series listing for each cover. Folder and file IDs keep saved manga/chapter references stable when files are renamed or moved within the library.

## Streaming and limits

The reader obtains archive metadata, fetches the ZIP central directory with HTTP byte ranges, and fetches/decompresses only the selected page's compressed data. Requests pin the Dropbox revision so an upload cannot mix bytes from different versions. Page URLs also identify the ZIP entry offset, so duplicate filenames remain distinct. Existing name-only page URLs still work for unique filenames; refresh the chapter if a legacy URL has an ambiguous name. Small archives under approximately 65 KiB can fit entirely in the tail/index request. The source WebView opens the Dropbox website. Manga/chapter WebView actions open a pending sign-in link when disconnected and Dropbox Home when connected.

- Standard ZIP/CBZ with **stored** or **deflate** compression is supported, including data descriptors and Unicode filenames.
- Encrypted, split/multi-part, ZIP64, CBR/RAR, PDF and other compression methods are unsupported. Repack them as ordinary ZIP/CBZ files.
- A page may be at most **32 MiB** compressed or decompressed; central directories at most **16 MiB** and 20,000 entries. The selected library is capped at 100,000 items.
- ZIP filenames are limited to 1024 encoded bytes. Page indexes have a conservative 8 MiB accounting budget per archive; oversized indexes are rejected before sorting or building lookup maps. Cached indexes share a 16 MiB accounting budget, with eviction of the least recently used indexes and at most sixteen indexes retained. These budgets estimate index memory rather than the host app's total heap. At most two archive index loads run simultaneously.
- Concurrent requests for the same archive revision share one index load; page entries are indexed by name and offset. Central directories are parsed from the already-fetched ZIP tail when they fit there, otherwise an exact directory range is requested. Page bytes are handed to Tachimanga, which controls reader caching and offline downloads. No archive extraction or page files are written by the extension.
- A missing or incorrect HTTP range response produces an error instead of silently downloading a large archive. Dropbox rate-limit errors report the retry time.
- Personal Dropbox and mounted shared folders in the selected account namespace are supported. Selecting a Dropbox Business team namespace is not implemented.

## Build and test

Requirements: **JDK 17**, **Android SDK platform 34**, and **build-tools 34.0.0**. Install them through Android Studio or the Android command-line tools. Set `ANDROID_HOME` or put `sdk.dir=/absolute/path/to/sdk` in `local.properties`.

```sh
./gradlew :extension:testDebugUnitTest :extension:assembleRelease
python3 scripts/package.py
```

### Bundled public Dropbox app key

Store the maintainer-supplied public app key in the local file `extension/dropbox-app-key.txt` as a single line. This file is ignored by Git. Ordinary local builds read it automatically and bundle the key for the authorization-code exchange and automatic token renewal. On a fresh checkout, create this file yourself or supply one of the build overrides below.


Override it for a build with:

```sh
./gradlew -PdropboxAppKey=YOUR_PUBLIC_APP_KEY :extension:assembleRelease
python3 scripts/package.py
```

Key selection is **Gradle property `dropboxAppKey` → nonblank environment variable `DROPBOX_APP_KEY` → ignored local file `extension/dropbox-app-key.txt`**. An explicitly empty property, or no key supplied through any of these sources, produces a personal/manual-key build. GitHub Actions reads the repository variable **DROPBOX_APP_KEY**; set it under **Settings → Secrets and variables → Actions → Variables** to bundle the maintainer key in CI builds. The local key file is not available in a fresh CI checkout, so leaving the variable unset produces a manual-key build.

The key is a public OAuth client identifier stored as `BuildConfig.DROPBOX_APP_KEY` and is visible in the APK by design. **Never put an App secret, access token, or refresh token in the key file or build setting.** PKCE handles authorization without an app secret.

The maintainer's Dropbox app must enable `files.metadata.read` and `files.content.read`, and permit the intended users to connect. Changing the bundled app registration requires users to reconnect. Keep the same registration for ordinary updates and use a persistent signing key for distribution.

The build uses the checked-in Gradle 8.2.1 wrapper. Output is `extension/build/outputs/apk/release/extension-release.apk`; the packaging script verifies its package/version against the configured release, copies the versioned APK and SHA-256 checksum into `dist/`, and checks that compile-time host classes have not leaked into the APK.

For this workspace, JDK/SDK/Gradle have also been downloaded under the ignored `.tooling/` directory. Use the following if no system JDK is installed:

```sh
JAVA_HOME="$PWD/.tooling/jdk" GRADLE_USER_HOME="$PWD/.tooling/gradle-home" \
  .tooling/gradle-8.2.1/bin/gradle --no-daemon \
  :extension:testDebugUnitTest :extension:assembleRelease
python3 scripts/package.py
```

The default release artifact is signed with a personal/debug key, suitable for manual installation. Keep the existing key for subsequent personal builds (`.tooling/debug.keystore` in this workspace). For distribution, use a persistent private signing key and set `EXTENSION_KEYSTORE`, `EXTENSION_STORE_PASSWORD`, `EXTENSION_KEY_ALIAS` and `EXTENSION_KEY_PASSWORD`. Increase `versionCode`/`versionName` in `extension/build.gradle.kts` when releasing an update. Neither credentials nor signing keys are checked in.

The `host-api` module contains pinned original extension API 1.4.2 and Injekt sources with upstream licenses. These are **compile-only**: the host provides the implementations. This avoids unavailable JitPack artifacts. All remaining dependencies resolve through Google Maven and Maven Central. See [host-api/ORIGIN.md](host-api/ORIGIN.md).

## Validation

Automated JVM tests cover the Dropbox browse → series → archive → page flow using MockWebServer, pagination, PKCE exchange, token expiry/401 renewal, HTTP ranges and revisions, Unicode headers, natural ordering, stored/deflated archives, CRC corruption, encrypted/ZIP64 rejection and page-size limits. Security regressions cover oversized filenames and indexes, cache eviction by memory, simultaneous load limits, malformed/oversized token responses, host observer isolation and refusal of token redirects. Regression tests also cover disconnect during pending sign-in/refresh, app-key changes, nonblocking cache invalidation during slow loads, overlapping catalog refresh ordering, grouping at the 100,000-item limit, duplicate ZIP filenames, legacy page references, and canonical cover/relative-path comparisons after casing changes. Performance regression tests also cover shared archive loads and invalidation, catalog freshness/forced refresh, cover metadata reuse and revision updates, ZIP-tail request counts, and indexed lookups across 20,000 entries. No real Dropbox credentials are needed for these tests.

An offline integration check against the Java runtime bundled with the installed Tachimanga 5.1 reproduces the old settings crash and verifies APK version parsing, the executable build label, settings construction, preference JSON serialization, library edits, automatic stable PKCE links across source reloads, stable source metadata across connection states, manga/chapter browser actions, refresh, and disconnect after the fix. It uses in-memory test preferences and makes no Dropbox requests or changes to the installed app's data. Run it after compiling with:

```sh
python3 scripts/test_tachimanga_runtime.py
```

The script defaults to the runtime inside `/Applications/Tachimanga.app`, accepts `--runtime` and `--java-home`, and does not redistribute Tachimanga's code. The test-only harness uses Java reflection/Unsafe to provide memory preferences and a network client without invoking Android configuration constructors.

The extension still requires a device acceptance check in **Tachimanga on iOS**, including native settings rendering, Safari/code copy, real Dropbox authorization and reader images. Offline runtime checks do not prove that end-to-end flow. No real Dropbox account or iOS reader session has been used during development.

Device acceptance:

1. Import the APK and confirm Dropbox appears as a source.
2. Connect Dropbox with the steps above; check both a series folder and standalone CBZ.
3. Read a deflated multi-page CBZ and confirm page order and images; test a stored ZIP too.
4. Close/reopen Tachimanga and check that the connection persists. Verify reading after the access-token expiry period.
5. Upload a chapter, refresh its series, and confirm it appears. Verify that repeated searches reuse listings and that **Dropbox library actions → Refresh Dropbox library** makes updated library metadata available after browsing/searching again. Test a changed cover with an uncached image request. Disconnect and confirm new uncached requests require sign-in.
