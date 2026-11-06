# Versioning

The current version is **v0.4.0**. The Android display version and MCP companion version are `0.4.0`; Android's `versionCode` is `8`. This minor update adds configurable calendar layouts, continuous agendas and Material You colors.

Use plain `MAJOR.MINOR.PATCH` numbers, without testing suffixes or an extra build counter. The `v` prefix belongs to the Git tag and release title. Major zero means initial development under [Semantic Versioning](https://semver.org/). Physical-device and integration testing are still in progress.

Increase Android's integer `versionCode` for every distributed update. Use a new version and tag when publishing changed binaries. Published artifacts should otherwise remain immutable so testers can identify exactly what they installed. A newer APK signed with the same key can update the previous test build while preserving its app data; an APK signed with a different key cannot update it in place.

## GitHub release listing

Publish each version as a normal GitHub release marked Latest, titled `Sunlith v<version>`. Upload and verify the APK, companion ZIP, and checksums before publishing. Keep existing release archives intact so earlier downloads remain available. Do not rewrite Git history.

The README stays focused on the app and setup. Downloads live in GitHub Releases; setup guides use the permanent latest-release link. No per-version README download links or link-update workflow are needed. Release descriptions follow [the release-note format](RELEASE-NOTES.md), with concrete grouped changes and results from that build. Omit routine installation, data-preservation and pairing-compatibility boilerplate.

## Package a tested build

From the repository root, after the Android and companion checks pass, run `python tools/package-release.py` with Python 3. It reads the version from the companion package and checks it matches Android. It copies the built `app-debug.apk` to `dist/Sunlith-v<version>.apk`, builds the companion ZIP, and writes `dist/SHA256SUMS.txt`. The ZIP contains START-HERE.md, companion source and tests, package lockfile, setup documentation, license and notices. Credentials, dependencies and private screenshots are excluded. The script verifies archive source equality and local Markdown links.

Before uploading, verify the APK signing certificate matches the previous published APK, install it as an update, and check the packaged files match the tested build. Publish only after CI passes for the tagged commit. Download each uploaded asset and compare its SHA-256 with the checksum file. On Windows use `Get-FileHash <file> -Algorithm SHA256`; on macOS use `shasum -a 256 <file>`; on Linux use `sha256sum <file>`.

## Name

Sunlith was selected after checking GitHub repository search and Google Play app-name search. No exact app-name match was found in those checked sources. This is a limited naming check, not a guarantee that the word has never been used anywhere.
