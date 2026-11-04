# Third-party software

Sunlith's original source is Apache-2.0. DigiCal, Fossify, Etar, Dux Calendar and the other apps in the [research notes](RESEARCH-CALENDAR.md) informed feature research. Their code, logos, screenshots and design assets are not included.

The Android APK bundles notices in `assets/third-party-notices.txt`.

| Component | Use | License |
| --- | --- | --- |
| AndroidX / Jetpack Compose / Material | Native interface, lifecycle, widgets, notifications | Apache-2.0 |
| Kotlin / kotlinx.coroutines | Language runtime and asynchronous work | Apache-2.0 |
| [biweekly 0.6.8](https://github.com/mangstadt/biweekly) | iCalendar parsing and writing | BSD-2-Clause |
| [vinnie 2.0.2](https://github.com/mangstadt/vinnie) | Folded calendar content lines | MIT; bundled codec notices retained |
| [Jackson Core 2.22.4](https://github.com/FasterXML/jackson-core) | biweekly's JSON-format dependency | Apache-2.0; bundled parser notices retained |

The Node MCP companion uses packages installed separately through npm:

| Component | License |
| --- | --- |
| [MCP TypeScript SDK 1.32.2](https://github.com/modelcontextprotocol/typescript-sdk) | MIT |
| [Google Auth Library 11.3.3](https://github.com/googleapis/google-auth-library-nodejs) | Apache-2.0 |
| [Zod 4.6.7](https://github.com/colinhacks/zod) | MIT |

Transitive packages retain their own licenses in their installed distributions. The lockfile pins the companion's dependency tree. Run `npm ls --all` to inspect it and Gradle's `:app:dependencies` task for the Android tree.

The dependency declarations and cached artifact license metadata were checked on November 4, 2026. The bundled notices include vinnie's complete `LICENSES` text and Jackson Core 2.22.4's license, notice, and bundled number-parser notices. Test-only JUnit, Hamcrest and Android test libraries are not included in the main application APK. Recheck the resolved dependency tree and update the bundled notices when versions change.

Screenshots and any demo recording show the running Sunlith application with synthetic data. No third-party calendar screenshots, logos, personal schedules, or proprietary design assets are redistributed in the repository.
