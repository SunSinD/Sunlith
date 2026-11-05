# Versioning

The current version is **v0.2.0**. The Android display version and MCP companion version are `0.2.0`; Android's `versionCode` is `6`. This minor update adds direct computer access to phone calendars.

Use plain `MAJOR.MINOR.PATCH` numbers, without testing suffixes or an extra build counter. The `v` prefix belongs to the Git tag and release title. Major zero means initial development under [Semantic Versioning](https://semver.org/). Physical-device and integration testing are still in progress.

Increase Android's integer `versionCode` for every distributed update. Use a new version and tag when publishing changed binaries. Published artifacts should otherwise remain immutable so testers can identify exactly what they installed. A newer APK signed with the same key can update the previous test build while preserving its app data; an APK signed with a different key cannot update it in place.

## GitHub release listing

Publish each version as a normal GitHub release marked Latest, titled `Sunlith v<version>`. Upload and verify the APK, companion ZIP, and checksums before publishing. Keep existing release archives intact so earlier downloads remain available. Do not rewrite Git history.

The Update download links workflow updates the README's three links to the most recently published release by publication time, including releases marked as prereleases. It can also be run manually from Actions. Keep the standalone labels `Download the APK`, `Release notes`, and `Download the computer companion` intact so the workflow can find them. Check the workflow after publishing. Setup guides link to the latest release page.

## Package a tested build

From the repository root, after the Android and companion checks pass, run `python tools/package-release.py` with Python 3. It reads the version from the companion package and checks it matches Android. It copies the built `app-debug.apk` to `dist/Sunlith-v<version>.apk`, builds the companion ZIP, and writes `dist/SHA256SUMS.txt`. The ZIP contains START-HERE.md, companion source and tests, package lockfile, setup documentation, license and notices. Credentials, dependencies and private screenshots are excluded. The script verifies archive source equality and local Markdown links.

Before uploading, verify the APK signing certificate matches the previous published APK, install it as an update, and check the packaged files match the tested build. Publish only after CI passes for the tagged commit. Download each uploaded asset and compare its SHA-256 with the checksum file. On Windows use `Get-FileHash <file> -Algorithm SHA256`; on macOS use `shasum -a 256 <file>`; on Linux use `sha256sum <file>`.

## Name

Sunlith was selected after checking GitHub repository search and Google Play app-name search. No exact app-name match was found in those checked sources. This is a limited naming check, not a guarantee that the word has never been used anywhere.
