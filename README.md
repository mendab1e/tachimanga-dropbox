# Dropbox for Mihon & Tachimanga

Browse your private Dropbox manga library and read CBZ/ZIP chapters directly in **Mihon on Android** or **Tachimanga on iOS**. No manga server, public shared links, or full-library download is required.

## Install

Use the packaged `dist/tachiyomi-all.dropbox-v1.4.12.apk` for either reader. The extension label is **Dropbox (Mihon + Tachimanga)**; the source inside the reader is **Dropbox**. This is a reader extension, not a standalone app.

### Mihon (Android)

1. Transfer the APK to your Android device and open it to install. Allow installation from that app if prompted.
2. In Mihon, open **Browse → Extensions** and trust the extension if prompted.
3. Open **Browse → Sources → Dropbox**, then source settings.

See [Mihon's extension installation help](https://mihon.app/docs/faq/browse/extensions).

### Tachimanga (iPhone/iPad)

1. Transfer the APK to your iPhone or iPad.
2. In Tachimanga, open **Browser/Browse → Extensions → +**, then select the APK.
3. Open **Sources → Dropbox → source settings**.

See [Tachimanga's manual-install guide](https://tachimanga.app/help/guides/extensions.html).

## Connect your Dropbox

The maintainer's APK includes a public app key: you do **not** need a developer account. Leave **Custom Dropbox app (optional)** blank and set **Library folder** to your manga directory, such as `/Manga`. Use `/` for the account/app-folder root.

### Android

1. Tap **Sign in to Dropbox** in source settings.
2. Sign in and approve read access in your browser.
3. Wait for the return to the reader and **Dropbox connected**, then browse the source.

If the button is missing, use the manual flow below. If the reader closes during authorization or the attempt takes more than ten minutes, start sign-in again.

### iOS or manual fallback

1. Open **Dropbox sign-in** (or **Dropbox sign-in (manual fallback)** on Android), copy its prepared link, and open it in your browser—Safari on iOS.
2. Approve access, then paste Dropbox's code into **Dropbox authorization code** in source settings.
3. Browse the source to finish connecting.

Authorization starts in source settings. The source's browser actions open the Dropbox website.

### Disconnect or change accounts

Select **Dropbox library actions → Disconnect Dropbox**, reopen settings, and sign in again. This removes the local connection; to revoke Dropbox access, remove the app under your account's **Connected apps**. When changing accounts, also remove the previous account's saved manga and cached/downloaded images from the reader.

Credentials stay in the reader's source preferences. Their encryption and backup handling depend on the reader. Do not share authorization codes or tokens in bug reports.

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

- Each direct subfolder containing archives becomes a manga; nested archives become its chapters. Root-level archives become standalone manga. Empty folders, hidden paths and macOS metadata are ignored.
- Optional covers: `cover.jpg`, `cover.jpeg`, `cover.png`, or `cover.webp`. Otherwise, the first page of the first archive becomes the cover.
- Pages and chapters use natural filename ordering (`1`, `2`, `10`); chapters are listed in descending order. Latest uses archive modification times.
- Refresh Browse or Latest to discover changes. Search can reuse listings for one minute; use **Dropbox library actions → Refresh Dropbox library** for an immediate refresh.

## Supported files and limits

- Standard CBZ/ZIP archives using stored or deflate compression. Encrypted, split/multipart, ZIP64, CBR/RAR and PDF files are unsupported.
- Images: JPG/JPEG, PNG, WebP, GIF, BMP and AVIF; decoding depends on the reader/device.
- Maximum page size: **32 MiB** compressed or decompressed. Archives may have at most 20,000 entries and a 16 MiB central directory; the selected library may contain at most 100,000 items.
- Personal Dropbox and mounted shared folders are supported. Dropbox Business team-namespace selection is not implemented.

The extension reads archive indexes and individual pages using HTTP ranges. Small archives can fit entirely within the approximately 65 KiB index-tail request. The reader controls page caching and offline downloads.

## Troubleshooting

- **No Android sign-in button:** install the APK through Android's package installer, or use manual sign-in.
- **Authorization expired or reader closed:** start a fresh sign-in attempt in source settings.
- **Manga missing:** check the library path and CBZ/ZIP layout, then refresh the Dropbox library.
- **Unsupported archive or range error:** repack unsupported files as ordinary ZIP/CBZ. A rejected range response produces an error instead of silently downloading a large archive.
- **A build asks for an app key:** see [custom Dropbox app setup](docs/development.md#dropbox-app-registration).

## Development and testing

See the [development guide](docs/development.md) for builds, app registration, signing and implementation notes. See [testing and device acceptance](docs/testing.md) and the [spec coverage map](docs/spec-coverage.md) for verification.

Automated checks cover API/archive behavior and offline Tachimanga 5.1 settings compatibility. Live Dropbox OAuth, native Android browser/Binder handoff and native iOS reading have not been independently verified.
