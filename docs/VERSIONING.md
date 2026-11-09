# Versioning

The current version is **v0.6.6**. Android and the companion use `0.6.6`; Android's `versionCode` is `17`. This update shows overlapping events side by side in narrow timeline columns. Earlier published binaries remain unchanged.

Use plain `MAJOR.MINOR.PATCH` numbers, without testing suffixes or an extra build counter. The `v` prefix belongs to the Git tag and release title. Major zero means initial development under [Semantic Versioning](https://semver.org/). Physical-device and integration testing are still in progress.

Increase Android's integer `versionCode` for every distributed update. Use a new version and tag when publishing changed binaries. Published artifacts should otherwise remain immutable so testers can identify exactly what they installed. A newer APK signed with the same key can update the previous test build while preserving its app data; an APK signed with a different key cannot update it in place.

## GitHub release listing

Publish each version as a normal GitHub release marked Latest, titled `Sunlith v<version>`. Upload and verify the APK, companion ZIP, and checksums before publishing. Keep existing release archives intact so earlier downloads remain available. Do not rewrite Git history.

The README stays focused on the app and setup. Downloads live in GitHub Releases; setup guides use the permanent latest-release link. No per-version README download links or link-update workflow are needed. Release descriptions follow [the release-note format](RELEASE-NOTES.md), with concrete grouped changes and brief asset explanations. Keep build results in TESTING.md. Omit routine installation, data-preservation and pairing-compatibility boilerplate.

## Package a tested build

The distributed APK uses the nondebuggable, R8-minified `distribution` variant.
It retains the existing signing certificate so it can update previous installs.
This variant preserves the exact APIs used by instrumentation while allowing R8
method optimization and obfuscation; it is not the unconstrained `release` variant.
Its explicit API rules live in `app/proguard-distribution-test-api.pro` and must
track new instrumentation calls. The normal debug workflow remains available.

Build and test the distribution pair before packaging:

```powershell
.\gradlew.bat --no-daemon -PtestBuildType=distribution :app:assembleDistribution :app:assembleDistributionAndroidTest
```

Install the exact `app/build/outputs/apk/distribution/app-distribution.apk` and
`app/build/outputs/apk/androidTest/distribution/app-distribution-androidTest.apk`
on the isolated emulator, then run instrumentation with ADB. After Android and
companion checks pass, run `python tools/package-release.py` with Python 3.
The default is the distribution output; `--apk <verified-apk>` can name a frozen
copy, whose bytes must match that output. `--build-type debug` is an explicit
development alternative, not the public release default.

The script checks Android/companion versions, variant metadata, source freshness
and APK equality, copies the APK to `dist/Sunlith-v<version>.apk`, builds the
companion ZIP, and writes `dist/SHA256SUMS.txt`. The ZIP contains START-HERE.md,
companion source and tests, package lockfile, setup documentation, license and
notices. Credentials, dependencies and private screenshots are excluded. Archive
source equality and local Markdown links are verified.

Before uploading, verify the APK signing certificate matches the previous published APK, install it as an update, and check the packaged files match the tested build. Publish after CI passes for the tagged commit. If GitHub refuses to start CI because of an account restriction, run the available build and test commands locally and record the restriction and any unverified platforms in the test record and release notes. Download each uploaded asset and compare its SHA-256 with the checksum file. On Windows use `Get-FileHash <file> -Algorithm SHA256`; on macOS use `shasum -a 256 <file>`; on Linux use `sha256sum <file>`.

## Name

Sunlith was selected after checking GitHub repository search and Google Play app-name search. No exact app-name match was found in those checked sources. This is a limited naming check, not a guarantee that the word has never been used anywhere.
