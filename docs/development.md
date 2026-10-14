# Development

This is a Kotlin APK extension targeting the Tachiyomi extension API 1.4, loaded by Mihon or Tachimanga. Keep the package, source identity, preference keys and signing key stable for compatible updates. Read the version from `extension/build.gradle.kts`.

## Build

Requirements: JDK 17, Android SDK platform 34 and build-tools 34.0.0. Set `ANDROID_HOME` or put `sdk.dir=/absolute/path/to/sdk` in `local.properties`. Use the checked-in Gradle 8.2.1 wrapper.

```sh
./gradlew :extension:testDebugUnitTest :extension:assembleRelease :extension:lintDebug
python3 scripts/package.py
```

The release output is `extension/build/outputs/apk/release/extension-release.apk`. Packaging verifies package/version, rejects bundled host/dependency implementations, and writes the versioned APK and SHA-256 checksum under ignored `dist/`.

This workspace also has ignored tools under `.tooling/`. If no system JDK is installed:

```sh
JAVA_HOME="$PWD/.tooling/jdk" GRADLE_USER_HOME="$PWD/.tooling/gradle-home" \
  .tooling/gradle-8.2.1/bin/gradle --no-daemon \
  :extension:testDebugUnitTest :extension:assembleRelease :extension:lintDebug
python3 scripts/package.py
```

See [testing](testing.md) for the optional installed-runtime check and device acceptance steps. Documentation-only changes do not require rebuilding.

## Bundled public app key

Store the maintainer-supplied public Dropbox app key as a single line in ignored `extension/dropbox-app-key.txt`. Fresh checkouts need that local file or a build override:

```sh
./gradlew -PdropboxAppKey=YOUR_PUBLIC_APP_KEY :extension:assembleRelease
python3 scripts/package.py
```

Precedence is Gradle property `dropboxAppKey` → nonblank environment variable `DROPBOX_APP_KEY` → local key file. An explicitly empty property, or no key from any source, produces a build requiring users to enter a custom key.

GitHub Actions uses the repository variable `DROPBOX_APP_KEY`; configure it under **Settings → Secrets and variables → Actions → Variables**. The local key file is absent from fresh CI checkouts.

The key is a public client identifier, visible in the APK as `BuildConfig.DROPBOX_APP_KEY`. Never put an app secret, access token or refresh token in the key file or build settings. Ordinary users authorize the maintainer's app; they do not register their own. Changing the effective app registration requires reconnecting, so retain it across ordinary updates.

## Dropbox app registration

For the maintainer, a custom app-key override, or a build without a bundled key:

1. Create a **Scoped access** app in the [Dropbox App Console](https://www.dropbox.com/developers/apps). Choose **Full Dropbox** for an existing manga folder. **App folder** also works, with paths relative to `/Apps/<your app name>`; use `/` as the source library path for its root.
2. Enable only `files.metadata.read` and `files.content.read`, then submit the permissions change. Ensure the intended users are eligible to authorize the app.
3. For automatic Android sign-in, add this exact value under **Settings → OAuth 2 → Redirect URIs** in the same app:

   ```text
   dropbox-manga://oauth/callback
   ```

4. For a custom override, enter the public **App key** in **Custom Dropbox app (optional)**, or **Dropbox app key** in an unconfigured build. The app secret is never needed.
5. Follow the [connection steps](../README.md#connect-your-dropbox). Manual authorization displays a code and requires no registered redirect URI.

Dropbox-side permissions, redirect registration and user eligibility need live verification before being reported as configured correctly. See [Dropbox's OAuth documentation](https://docs.dropboxapi.com/dropbox-api/docs/oauth).

## Signing and distribution

The default release build uses a personal/debug key for manual installation. Preserve the existing key (`.tooling/debug.keystore` in this workspace) for compatible updates. Distribution signing uses `EXTENSION_KEYSTORE`, `EXTENSION_STORE_PASSWORD`, `EXTENSION_KEY_ALIAS` and `EXTENSION_KEY_PASSWORD`.

Increase `versionCode`/`versionName` in `extension/build.gradle.kts` for distributed releases. Verify APK signatures and the packaged checksum, and record validation in ignored `dist/VALIDATION.md`. Keep credentials, keystores, local settings, tooling and build outputs out of Git. The CI workflow builds and uploads artifacts; it does not publish a release or extension repository.

## Implementation constraints

- `DropboxAuth` owns credentials and manual/Android PKCE state; `DropboxPreferences` builds settings; `DropboxApi` handles file requests. Shared private transport and response readers live in `DropboxHttp`. Preserve separate manual and callback verifiers, pre/post-exchange state validation, and settings-only authorization.
- Manual authorization omits `redirect_uri`; Android authorization and exchange use the same callback URI. Links survive settings reloads; successful exchange clears both flows. The Java-only callback activity relays a code via Binder; it has no host-library dependencies and never receives verifiers or tokens.
- Tachimanga 5.1's compatibility layer lacks usable plain preference clicks, `Context.startActivity` and clipboard support; clipboard methods throw `Stub!`. Do not call `setPersistent()`. Use typed defaults, stable keys and preference-change listeners that return false after settings are applied atomically. Keep native Android controls behind the platform/installed-activity gate.
- Tachimanga shares the source browser URL with its browse subtitle. Keep source/manga/chapter browser URLs static; do not put OAuth links in metadata. Native iOS callback, document-picker and File Provider integration require host support.
- API requests remove inherited interceptors, event listeners, cookies, authenticators and disk caches, and refuse redirects. Token JSON is bounded to 64 KiB; other JSON to 8 MiB. Parser errors must not retain token-bearing contents or causes. Credentials remain in host preferences; encryption and backup policy depend on the host, not the extension manifest.
- ZIP reads pin revisions, require exact partial responses and validate decompressed size/CRC. Entry offsets distinguish duplicate names; legacy name-only references remain usable for unique names. Reuse directory bytes from the ZIP tail when available; never extract archive paths to disk.
- ZIP filenames are limited to 1024 encoded bytes. Indexes have a conservative 8 MiB accounting budget; the LRU cache has a shared 16 MiB budget and at most sixteen indexes. Concurrent misses for the same revision share a load, with at most two simultaneous index loads. Catalogs have one-minute freshness; explicit refresh retains immutable archive indexes, and pagination retains its existing snapshot.
- Host API, Injekt, Kotlin stdlib, OkHttp, RxJava, serialization and AndroidX preferences are compile-only. Do not bundle replacements or casually raise ABI requirements. Keep `kotlin.stdlib.default.dependency=false` and the wrapper checksum. Vendored API/Injekt provenance, licenses and compiler adjustments are documented in [host-api/ORIGIN.md](../host-api/ORIGIN.md).
