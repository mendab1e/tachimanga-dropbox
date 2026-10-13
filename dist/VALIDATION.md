# Validation — Dropbox extension 1.4.10

- Release APK compiled successfully with JDK 17, Gradle 8.2.1 and Android SDK 34.
- 60 JVM tests passed; 0 failures, 0 errors, 0 skipped. Includes a mock Dropbox browse-to-page integration.
- Regression coverage: disconnect during pending OAuth exchange/refresh, app-key replacement, replacement PKCE verifier preservation, cache invalidation while catalog/archive loaders are blocked, the sixteen-index bound, duplicate ZIP entry names, legacy page URLs, and cover/relative-path handling after casing changes.
- Additional regressions reject a slower earlier catalog refresh after a newer refresh completes, preserve account-root grouping, and group a 100,000-item fixture within a 10-second test timeout (previous 1.4.5 observation: 0.200 seconds on this workspace's JVM).
- Fresh catalog metadata supplies series covers, the naturally first archive, and standalone files without metadata/subtree RPCs. A mock explicit-cover flow uses only the initial library listing and the revision-pinned cover download; forced refresh replaces the cover revision.
- Search catalogs expire after one minute; pagination can retain the same snapshot past expiry. Explicit Refresh Dropbox library clears catalog metadata and rejects pending older listings while retaining immutable archive indexes.
- Concurrent misses for one archive revision share one load. Tests verify unrelated keys proceed, failed loads can retry, settings invalidation wakes waiting requests before older I/O finishes, and obsolete loads cannot replace a new index.
- ZIP directories inside the fetched tail use one index range request; directories extending outside the tail still request the exact directory range. Indexed lookups for all 20,000 entries passed (previous 1.4.5 fixture plus parsing/lookups observation: 0.068 seconds on the local JVM). Timings do not measure the iOS runtime.
- Security regressions reject filenames over 1024 encoded bytes and indexes exceeding an 8 MiB accounting budget; cache eviction respects the shared 16 MiB budget and LRU order, and invalidation resets accounting. Different archives are limited to two simultaneous index loads.
- Malformed, structurally invalid, oversized and overflowing-expiry token responses produce generic exceptions without token-bearing causes, and do not mutate credentials. Mock requests bypass inherited application/network interceptors, event listeners and cookies; token redirects are refused.
- The original long-filename memory-exhaustion fixture now fails with a controlled IOException in a 128 MiB JVM. The fixture does not execute the iOS reader.
- Token JSON responses are bounded to 64 KiB; other JSON API responses are bounded to 8 MiB. A new offline connection must return its own refresh token.
- Android release vital lint and debug lint passed. Debug lint has 8 advisory findings.
- APK v1/v2 signatures verified with Android apksigner. This is a personal/debug-signed build.
- APK class definitions inspected with dexdump: no host API, Injekt, Kotlin, OkHttp, RxJava, or serialization implementations bundled.
- Archive reader requires valid partial-content responses and verifies decompressed size/CRC.
- Version code increased to 10; packaged artifact is `tachiyomi-all.dropbox-v1.4.10.apk`. Signing certificate matches the preceding personal build; the packaged SHA-256 checksum was verified.

- Public maintainer app key remains bundled and was confirmed in the APK. Existing tests for bundled-key PKCE exchange/refresh, custom-key fallback, and preservation of credentials for equivalent effective keys passed.

- Settings compatibility: the old code reproduced `NoSuchMethodError: EditTextPreference.setPersistent(boolean)` against the installed Tachimanga 5.1 runtime. The corrected release passes offline settings construction and the host JSON serialization using that runtime. Library edits, automatically prepared sign-in links, saved PKCE link across source reloads, refresh and disconnect were exercised with memory-only preferences and no network requests. Plain click listeners are no-ops in this host. Sign-in uses the automatically prepared settings link in Safari. Source metadata uses a static Dropbox website URL.

- Browse subtitle regression: the user screenshot confirmed that the OAuth URL returned by getHomeUrl() was also displayed below the browse title. Runtime integration now calls that method through the parent HttpSource type and checks the static https://www.dropbox.com URL before sign-in, while authorization is pending, after connecting, after reload, and after disconnect. Reading source metadata leaves credentials untouched and never prepares or rotates PKCE state. Settings keep their stable sign-in link, and separate manga/chapter WebView actions retain pending authorization / connected Dropbox Home URLs. Native rendering of this fix still requires device verification.

- Version diagnostics: the installed Tachimanga 5.1 runtime's actual APK parser recognizes version 1.4.10/code 10. Settings serialize six preferences, including the disabled executable build label. Packaging rejects a stale APK whose manifest differs from Gradle configuration. The user reports importing updates through Extensions → + while seeing 1.4.6; the device-side cause remains unconfirmed until the new settings label is checked.

Credential storage remains in host source preferences. At-rest encryption, backup exclusions and host diagnostic handling on iOS are unverified and require host support; the extension has no implemented secure-storage bridge.

Not tested: live Dropbox OAuth/account access or native Tachimanga iOS settings rendering and reader images. Host compatibility classes and settings serialization were checked offline, but APK import, Safari/code copy, archive reading and real images still require the device checks in README.md.
