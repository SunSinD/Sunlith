# Testing Sunlith

The provider tests run against Android's real calendar provider. They create isolated local calendars and delete only calendars they own. They refuse to run on a physical phone.

## 0.6.7 defaults, zoom and navigation verification, 2026-11-09

The tested distribution APK is version 0.6.7/code 18, nondebuggable and
R8-minified, signed with the existing certificate. Production and test files
were frozen together as candidate r15 throughout the checks below. Only this
verification document changed after runtime verification; the packaged APK
is the exact tested file.

| Artifact | SHA-256 |
| --- | --- |
| Distribution APK | `36b41b22f603b88203aedb9acd397894a347b092defc6cc9ca7f6a6f65d7ad3d` |
| Test APK | `6680357132249c1e03438a282de03414ff7db0222b0a623990f45c9b72cbae2b` |
| Signing certificate | `059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039` |
| Frozen source fingerprint | `6be15b2f82aeb41070e037acf8d1da18d52e0ec1b15b8d12c564312b54fef0fe` |
| Frozen source ZIP | `ff406f5d7cc3784984cf40f72aef4aafff3235f16eab1daa01e589be862475c4` |

| Check | API 36 / Android 16 | API 30 / Android 11 |
| --- | --- | --- |
| Full instrumentation, 25 unique classes each | 288 passed, 0 failed, 7 skipped; 2183.7s | 286 passed, 0 failed, 6 skipped; 1813.4s |
| Focused navigation and positioning regressions | 7 passed, 0 failed | 7 passed, 0 failed |
| Upgrade from published 0.6.6, stock and custom layouts | Both passed | Both passed |

These suite totals combine complete classes from disclosed continuation runs,
not one uninterrupted run. Partial interrupted classes were excluded and
restarted. Raw unsuccessful attempts were preserved. Continuations corrected
emulator and fixture state: an orphan injected touch, missing emulator Wi-Fi,
SystemUI interruption and stale SELinux labels on restored fixture files.
Demo/widget seeding also triggered cleanup guards; only the identified fixture
calendar, events and temporary preferences were removed before continuing.
No physical phone or personal calendar was used.

Local distribution checks passed 84 JVM tests with zero failures or skips.
Lint reported zero errors, 142 warnings and 9 hints. The desktop companion
passed 44 tests with one external opt-in skip; its production dependency audit
found zero vulnerabilities. Distribution app/test builds and diff whitespace
checks passed. Source reviews found no remaining concrete production blocker.
All 193 frozen files matched both the live tree and source ZIP before the
verification-document update.

Fresh visual review covered 91 PNG/XML pairs across seven profiles: 344dp
English at font 1.15, 280dp English at normal font, 344dp French at normal and
200% font, forced RTL French, 690dp inner-screen width and 829dp landscape.
Every XML parsed and every image was reviewed. Five complete date headers fit
at 280dp with normal text; large text uses wider scrolling columns. Grouped
empty-hour boundaries were explicitly brought on screen and were readable.
French all-day labels, centered Day event labels, padding, mirrored arrows,
short-event titles and both time endpoints were checked. Narrow timeline
columns can still shorten titles; very short bars can be blank, with full
content available in List, the short-event chooser and Details.

Real Appearance controls saved all twelve zoom choices independently, six
for Day and six for Week. Day 125% and Week 75% remained selected after
reopening settings. Default Day List, the prominent VPN-not-detected status
and shorter connection details were also reviewed. Stock preset Day timelines
migrated to List once during both API upgrades; customized Day timeline and
Continuous Week choices stayed selected. Provider state was unchanged by
upgrade. API 30's stock before-upgrade PNG is preserved, but its XML dump
failed; that missing XML is not counted as evidence.

Six switching recordings were decoded without temporal sampling: all 561
recorded frames were reviewed. Date taps in Day List, Day timeline, Week List
and Month grid showed no unrelated/intervening-date events. Finger paging in
Day and Week List showed the expected adjacent pages and reversed correctly.
Day timeline can suppress its body for one or two recorded frames while
positioning. Month/Week agendas can retain the preceding content for one
recorded frame after the highlight changes, then snap directly. Some vertical
header/card settling remains during Day List swipes. Variable-rate recordings
cannot prove that every displayed frame was captured.

Performance was measured on API 36 using SwiftShader software rendering,
344dp width, font 1.15 and animation scales 1. Eight isolated layout hosts
passed, each using two warmup swipes then six measured alternating swipes.
Their landing-frame p95 ranged from 44.1 to 66.3ms and maxima from 53.4 to
83.0ms. Real Continuous Week swipes, without screenshots or hierarchy dumps
inside the measured interval, produced:

| Group empty hours | Rendered frames | p50 | p95 | Maximum |
| --- | --- | --- | --- | --- |
| Off | 110 | 46.0ms | 87.9ms | 132.2ms |
| On | 119 | 41.7ms | 60.5ms | 73.7ms |

These measurements contain slow frames and do not establish stutter-free
motion or physical Fold5 performance. A separate passing zoom host recorded
230 unique rendered frames, p50 32.8ms, p95 173.8ms and maximum 798.3ms; that
interval includes setup, taps, screenshots and hierarchy dumps, so it is not
an isolated zoom-gesture benchmark. There is no comparable before/after
hardware measurement proving a speed increase.

Known limits: no physical Fold5 fold/unfold or live Tailscale-network test.
The connection indicator detects an eligible local VPN address in Tailscale's
range; it does not prove the computer is reachable or the whole connection
works. Private guest provider/preferences/display/locale baselines were
restored and both private emulators stopped after verification. The final
byte audit confirmed all 485 original AVD files (94,168,573,854 bytes) were
unchanged.

## 0.6.6 side-by-side overlap verification, 2026-11-09

The tested distribution APK is version 0.6.6/code 17, nondebuggable and
R8-minified, signed with the existing certificate.

| Artifact | SHA-256 |
| --- | --- |
| Distribution APK | `806512b42fb25a32214bd01f7504b70ee0acc0a614c4164c8390ce83e1b770af` |
| Test APK | `1f7e465366d8d38971155dc566ef16d2419e810f4e68978a7d049bfa5094525a` |
| Signing certificate | `059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039` |

| Check | API 36 | API 30 |
| --- | --- | --- |
| Full instrumentation | 285 passed, 0 failed, 7 skipped; 1625.7s | 282 passed, 0 failed, 6 skipped; 1459.2s |
| Upgrade from published 0.6.5 | Installs in place; settings file byte-identical | Not repeated |

Both emulators ran read-only with default animation scales and the device
clock set to 13:00. Earlier attempts are not counted: one reused a script that
turned animations off, which made seven tests that observe an unfinished
animation fail; they passed once animations were back on. One of them,
`enteringDayFocusSupersedesAnUnfinishedWeekTodayRequest`, also fails late in
the evening because its marker one hour before now cannot scroll into the
upper half of the timeline; it passed with the clock at 15:00.

A manual check reproduced the reported case at 1.15 font scale in the 5-day
paged Week: "Diagnostic de circuits" 2:25 to 6 PM overlapping "Course
Registration" 5 to 5:30 PM. Published 0.6.5 showed only a "+2" box. After the
in-place update, 0.6.6 showed both events side by side. Both events also stayed
side by side at 1.3 font scale, in 7-day Week and in Continuous Week.

Local checks passed 84 JVM tests and 44 desktop tests with one external opt-in
skip. Lint reported zero errors. The companion dependency audit found zero
production vulnerabilities. Code reviews by Opus 5.5 and GPT 6.1 Sol traced
paged Week, Continuous Week and Day, including large text and RTL.

Known limits: lanes can narrow to 20dp, so short overlapping events may show
only a few letters; full titles and times stay in Details and accessibility
labels. No physical Fold5 check and no frame-timing claim.

## 0.6.5 continuous Week and grouped hours verification, 2026-11-08

The tested distribution APK is version 0.6.5/code 16, nondebuggable and
R8-minified, signed with the existing certificate.

| Artifact | SHA-256 |
| --- | --- |
| Distribution APK | `2d6a83421212aeaf34f6dd7daeac6b85dd76a4a7e81e1b479781767b2f232fad` |
| Final test APK | `4402b9f512343f85b9b193f60cece125990fecdd16426d55a1bfe4b061b25638` |
| Signing certificate | `059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039` |

| Check | API 36 | API 30 |
| --- | --- | --- |
| Full instrumentation | 285 passed, 0 failed, 7 skipped; 1500.8s | 282 passed, 0 failed, 6 skipped; 1274.9s |
| Upgrade from published 0.6.4 | Event, onboarding and Grid layout kept; grouping stays off | Installs and launches without crashes |

Both emulators ran read-only, so their saved disks were not changed. The first
full runs found five test-only problems, each checked by screen recording or by
hand before changing the test. A Day scroll covered less than a viewport on the
taller hour layout; the overlap chooser helper trusted UiAutomator's end-of-list
signal, although a manual swipe reached and opened the 64th event; a height
check measured an event clipped at the viewport edge; a month helper read the
grid before it was composed; and a connection restart was a one-off timing miss
that passed on reruns. App code was not changed for these. The API 36 run used
the test APK before the month-grid wait was added; that wait only affects the
test helper.

A manual French check in Continuous Week with grouping on showed "Toute la
journée" on two whole lines and compact "Aucun événement" bands for 11 h to
17 h and 21 h to 24 h. Grouping is off by default after a fresh install and
after the upgrade.

Local checks passed 81 JVM tests, distribution app/test builds and 44 desktop
tests with one external opt-in skip. Lint reported zero errors. The companion
dependency audit found zero production vulnerabilities. Earlier display runs
for this version, reviewed by Opus 5.5 and GPT 6.1 Sol, passed at normal,
narrow, wide, landscape, large-text and RTL layouts.

Known limits: no physical Fold5 fold/unfold check and no frame-timing claim
for swipes. Narrow overview titles can still shorten; Details and lists show
full text.

## 0.6.4 calendar readability verification, 2026-11-08

The tested distribution APK is version 0.6.4/code 15, nondebuggable and
R8-minified. The r44 and r46 production APKs are byte-identical. r46 changes
only the readability capture test's scrolling and readiness helper.

| Artifact | SHA-256 |
| --- | --- |
| Distribution APK | `d9bbcca34f3953b1a909fac5cb73ddda69650b25036284fc8f3e53d9bf3eb41e` |
| r46 test APK | `6cc21832f11fc4ee7b2f8f7370081ffaee8ddb1ee4a0d0efa1752ab2f8ff59c2` |
| r44 test APK | `b2fc99dd2ab776eb26bacd33c5f59cfba6ae5e4962f12c794691b1c2f61b4f6f` |
| Signing certificate | `059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039` |

| Check | API 36 | API 30 |
| --- | --- | --- |
| Full instrumentation | r44: 263 passed, 0 failed, 6 skipped; 1395.684s | r46: 261 passed, 0 failed, 5 skipped; 1334.724s |
| Readability display variants | 3 r44 priority + 5 r46 remaining passed | All 8 r46 passed |
| External Android/Node connection | r44: all 8 tools passed | r46: all 8 tools passed |
| Synthetic code 14 to 15 upgrade | Both 3-day and 7-day settings passed | Both 3-day and 7-day settings passed |
| Notification-permission recovery | Both opt-ins passed in r46 | Requires API 33 |

The reused API 36 results ran the older r44 test APK against the identical
production APK; they are not claimed as new r46 executions. Four focused Today
checks and a color preflight also passed on API 36 in r44. API 30's color
preflight passed in r46. Neither full suite used command-line exclusions or
retries. Three API-33-minimum tests account for the discovery difference
(269 versus 266). Full-suite skips are the opt-in readability captures, external
connection fixture, older school-display captures and private live-portal smoke;
API 30 also skips Material You, while API 36 skips both permission opt-ins.
Readability, external tools and API 36 permission recovery were enabled separately.
The older school-display fixture and live-portal smoke were not enabled here.
Upgrade checks prove preference and event preservation; their Month screenshots
do not prove the upgraded three-day or seven-day timeline renders.

English and French were checked at normal width, narrow width, 200% text and
forced RTL. The complete matrix contains 256 checkpoint states, including the
48 reused r44 priority states. The runtime worker reviewed 262 fresh PNGs and
223 valid XML files across display, full-suite, upgrade and platform evidence.
These are file counts, not distinct frames; most full-suite images have no XML.
One original 73-byte invalid XML dump is retained and excluded from valid counts.
Pixel review, rather than semantics alone, checked chronological time ranges,
partial absence versus total class duration, room ordering, mirrored rails and
absence markers, full-absence outlines, large text and month chips.

The earlier r45 capture failure was a test gesture overshooting its Schedule
target. Its original evidence remains recorded; r46 fixes only that helper.
Waiting for day focus to be consumed does not prove its scrolling has finished.
Before API 30 tests, a pre-existing System UI ANR caused a missing XML dump;
a separate recovered-readiness assessment allowed continuation without retrying
a test. A cleanup guard also rejected the nonempty original provider baseline
before mutation. The corrected local harness preserves those original rows.
Raw natural-cleanup differences remain recorded separately from explicit cleanup:
synthetic preferences, demo data and two proven-owned audit files were removed
only after ownership checks. Both original emulator baselines were restored
exactly and both emulators stopped. Source, APK, signing, privacy and historical
artifact audits passed.

Local checks passed 61 JVM tests, distribution app/test builds, desktop syntax
checks and 44 desktop tests with one external opt-in skip. Dependency audit found
zero production vulnerabilities. Lint reported zero errors, 139 warnings and
seven hints. Independent source and evidence reviews found no release blocker.

Known limits: narrow overview labels can ellipsize or hyphenate; scrolling edges
and floating buttons can hide content in a single capture. Some grouped-class
captures prove only partial visibility, and an empty late-hour Monday frame does
not prove its morning event. Search provides the full title, precise absence and
all three room times in every variant. Large-text month grids can open with the
first row's dates above the viewport, and have no visible extra-event count by
design; count semantics remain. Forced EN/FR RTL does not validate Arabic time
formatting. At 200% text, five/seven-day timelines show about one column, and
swipe-off captures can show only Friday's edge beside Saturday. English stored
fixture descriptions do not validate French-generated school notes.
The compact fractional-density month fixture is not physical rotation.
Saved course colors can collide when courses assigned in separate fetched sets
later appear together. The pre-existing Today request-counter reset after process
death remains outside the narrow remounted-page fix. Encrypted school-connection
upgrade, live portal behavior, physical TalkBack, OEM and real-phone checks remain
unverified by this campaign.

## 0.6.3 school setup verification, 2026-11-08

The prepared distribution APK is version 0.6.3/code 14, nondebuggable and
R8-minified, with the existing signing certificate. Its SHA-256 is
`d96b253f7ee874ea3a148f40d585c073152e9f9562c4b852537bd78759e8027f`.
The r22 and r23 production APKs are byte-identical; r23 changes only one test's
disabled-button selector and failure capture.

| Check | API 36 | API 30 |
| --- | --- | --- |
| r22 three school test classes | 65 passed, 1 selector failure, 3 skipped | 65 passed, 1 selector failure, 2 skipped |
| r23 exact affected setup test | 1 passed, 0 failed | 1 passed, 0 failed |
| English/French display variants | 8 passed | 8 passed |
| Isolated code 13 to code 14 upgrade | Passed | Passed |

The r22 failures read the enabled state of the Connect text label instead of its
disabled clickable parent. The corrected test checks that parent and passes all
remaining assertions on both APIs: blank account fields, school selection,
Montmorency's public address and Toronto time zone, the existing calendar ID,
and the connected state. Original failures remain in the archived r22 record;
r23 is a focused closure, not a new full-suite run.

School coverage includes manual Col.NET setup, known and custom reauthentication,
an unavailable saved calendar, notification categories, provider ownership and
recovery, explicit legacy linking, lifecycle cancellation, and personal-event
preservation. The display test was separately enabled for English/French normal,
narrow, 200% text and RTL layouts. The Android 33 notification-permission recovery
test and private live-account smoke were not enabled in this campaign; the former
is SDK-excluded on API 30. No real account was used for these new setup tests.

All 256 school PNG/XML pairs were reviewed. Three PNGs captured the preceding
error frame and are retained but excluded from their named-state coverage; their
XML and the other 253 PNGs show the expected state. No new layout defect or XML
bounds issue was found. Upgrade checks preserved a synthetic event, selected
calendar, theme and layout preferences. Both emulators were restored exactly and
stopped after each campaign; source and frozen-artifact audits passed.

JVM checks passed 39 tests; desktop checks passed 44 with one opt-in skip. Lint
reported zero errors, 135 warnings and seven hints. English/French school resource
keys match. The final source/APK scan found no saved account credentials.

Earlier calendar and school regression evidence is retained separately: r18 full
API 36 had 244 passes, one stale-node automation failure and five skips; API 30
had 243 passes and four skips. r19 closed that failure and a display automation
failure with both affected classes passing on both APIs, plus complete large-text
captures. These are historical runs, not a full-suite claim for the new setup APK.
Instant push and Google-account full-resync ownership behavior remain unverified.

## 0.6.2 final distribution verification, 2026-11-07

The exact packaged app/test pair is nondebuggable and R8-minified, version
0.6.2/code 13. Both APKs retain the published signing certificate. The distribution
build preserves 230 classes and 681 exact instrumentation API members, with method
optimization and obfuscation enabled. Plain release remains unconstrained.

| Check | Passed | Failed | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: |
| Full API 36 | 159 | 0 | 2 | 996.411 |
| Full API 30 | 157 | 0 | 2 | 1035.840 |
| Targeted API 36 | 25 | 0 | 0 | 471.969 |
| Targeted API 30 | 25 | 0 | 0 | 460.449 |
| Settings at 320dp/200% text | 2 | 0 | 0 | 305.624 |
| Settings at 360dp/200% text | 2 | 0 | 0 | 182.743 |
| Large-text Cards and timeline overlaps | 3 | 0 | 0 | 101.326 |
| Notification recovery | 1 | 0 | 0 | 4.503 |
| 24-hour axis capture | 1 | 0 | 0 | 46.360 |
| External Android provider fixture | 1 | 0 | 0 | 4.967 |
| JVM | 26 | 0 | 0 | |

Lint has zero errors, 136 warnings and seven hints. Desktop tests passed 44 cases
with one opt-in skip, syntax checks passed, and the audit reported no vulnerabilities.
All eight external tools passed against this exact APK. Full runner discovery and
terminal counts agree; API-specific exclusions and opt-in skips are recorded in the
summary. All 117 Android source, resource, test and build inputs matched before and
after final runs and matched the packaged pair's installed byte hashes on both APIs.

An actual update from the published 0.6.1 APK, without clearing data, retained an
isolated calendar, event and private sentinel, changed legacy Cards to Timeline,
and preserved independent Month/Week choices. Tests cover subsequent explicit
Events only selection and recreation, existing Timeline/Schedule preferences,
direct Grid opening for empty/ordinary/crowded dates, no overflow count or preview,
both midnight endpoints, separate same-start card taps/durations, distinct instants,
and the earlier focus, paging, icon-reopen, widget and connection regressions.

The screenshot index records 356 fresh final PNGs, including English/French Settings
pages and choices, scroll positions, 320/360dp at 200% text, actual Light/Dark launcher
icons, vector/monochrome/onboarding artwork, direct Grid/Day, simultaneous Cards,
timeline overlaps, RTL and both ends of the hour rail. Connection title and button
clipping found at large French text sizes was fixed before the final freeze by
moving Refresh out of the header and stacking secondary buttons at large font sizes.

Earlier failed gallery sequences, corrected automation selectors and the empty
timeline's misleading UiAutomator scroll return value are retained in the summary.
The latter was replaced by assertions on actual rendered rail movement. The gallery
runs with and without optional captures; its final selections and assertions remain.
An adb transport failure occurred after the API 30 suite had completed successfully;
host recovery, installed hashes and cleanup were verified afterward.

The final Grid frame fixture used 24 all-day events, two warm-up swipes and six
measured alternating swipes. API 36 recorded 239 frames, including 112 landing
frames with P50 17.60ms, P95 18.64ms and max 19.31ms. This single fixture does not
establish steady 60 FPS, performance across every layout, or physical-phone motion.
Older comparison campaigns below belong to their stated APKs.

App SHA-256 (`2,608,766` bytes):

```text
294a4023dd44664bc59727ef0302d205a3cc5e3211486ad9bbeb69a6f6da1442
```

Test SHA-256 (`563,321` bytes):

```text
78ad6fa94c11dae4e0aea697fd29c84d820ae67044ba53fa621b5c48fea7e13d
```

Both certificates: `059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039`.
Evidence: `.toolchain/v0.6.2-final-verification-summary.json`,
`v0.6.2-final-r8-full-emulator-5556.log`, `v0.6.2-final-r8-full-emulator-5554.log`,
`v0.6.2-final-image-index.json` and `v0.6.2-final-r8-review-images/`.
Both emulators are stopped with settings restored; synthetic fixtures, private
exports, credentials and forwarding were removed while existing local data was
preserved. Physical TalkBack, vendor launchers, Taildrop and Doze remain unverified.

## 0.6.1 final distribution verification, 2026-11-07

The packaged APK includes the final all-day Cards correction. Opening an empty
or all-day-only date consumes the focus request while keeping its heading at the
top. The regression covers both dates with animations enabled and disabled; it
fails against the earlier debug product. Timed-event focus remains covered.

| Check | Passed | Failed | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: |
| Full API 36 | 150 | 0 | 2 | 825.478 |
| Full API 30 | 148 | 0 | 2 | 853.375 |
| Targeted API 36 | 7 | 0 | 0 | 116.758 |
| Targeted API 30 | 7 | 0 | 0 | 112.625 |
| Cards at 320dp/200% text | 1 | 0 | 0 | 15.572 |
| External Android provider fixture | 1 | 0 | 0 | 4.477 |
| JVM | 26 | 0 | 0 | |

Desktop tests passed 44 cases with one opt-in skip; all eight external tools
passed against this exact APK. Lint reports zero errors, 131 warnings and seven
hints. Installed app/test hashes matched the frozen distribution pair on both
APIs. All 117 Android source, resource, test and build inputs stayed unchanged
during final runtime verification. Twelve final Cards screenshots were inspected,
including the narrow large-text captures. Test-only synchronization and date-cell
click corrections preceded this freeze; earlier failures and the stopped run are
retained in `.toolchain/cards-top-final-verification-summary.json`.

Final app SHA-256 (`2,624,170` bytes):

```text
83132bc8b09ec5e716c6c362ca759aac5bfc23639dbf2249aa93d4438a222e6d
```

Final test SHA-256 (`552,189` bytes):

```text
86f3cee3c2ab22833db557b8709f0d22ec7e6e76da3ef7ae721d111b54e5c9d5
```

Both retain certificate `059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039`.
The APK remains nondebuggable and R8-minified with the exact test API constraints
described below. The 48 performance runs below measured the immediately preceding
optimized APK, not this final hash. Only the Cards banner-scroll predicate changed
in production afterward; the benchmarked paging paths are unchanged. Physical
phone motion, TalkBack, vendor launchers, Taildrop and Doze remain unverified.
Synthetic fixtures, credentials, exports and forwarding were cleaned up; display,
font, animation and locale settings were restored, and both emulators stopped.

Evidence: `.toolchain/cards-top-final-verification-summary.json`,
`cards-top-ready-final-full-emulator-5556.log`,
`cards-top-ready-final-full-emulator-5554.log`,
`cards-top-ready-final-images-emulator-5556/`,
`cards-top-ready-final-images-emulator-5554/` and
`cards-top-ready-final-narrow-images-emulator-5556/`.

## 0.6.1 preceding optimized distribution verification, 2026-11-07

This preceding candidate superseded the debug candidates below. Its APK is
nondebuggable and R8-minified, version 0.6.1/code 12, signed with the same certificate
as 0.6.0. The distribution variant preserves 229 classes and 672 exact members used
by instrumentation, including three reflected fields and callback overrides.
R8 method optimization and obfuscation remain enabled. These constraints are
explicit in `proguard-distribution-test-api.pro`; this is not an unconstrained
release build. Target and test APKs were built together and tested as an exact pair.

| Check | Passed | Failed | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: |
| Full API 36 | 149 | 0 | 2 | 792.768 |
| Full API 30 | 147 | 0 | 2 | 770.430 |
| Narrow 320/360dp, normal and 200% text | 8 | 0 | 0 | 223.336 |
| Resized Fold emulator, 200% text | 6 | 0 | 0 | 49.095 |
| Motion and boundary checks | 2 | 0 | 0 | 23.447 |
| Notification recovery | 1 | 0 | 0 | 4.860 |
| External Android provider fixture | 1 | 0 | 0 | 4.753 |
| JVM | 26 | 0 | 0 | |

Expected skips retain the opt-in/API reasons listed below. The desktop static suite
passed 44 cases with one opt-in skip; exact-APK desktop integration exercised all
eight tools. Lint reports zero errors, 131 warnings and seven hints. Full declared
test counts match terminal results. A 162-file source fingerprint remained equal
across runtime checks. Only the packaging script subsequently changed; its guards
were checked separately without changing the Android inputs or APK.

The Today regression was reproduced on the old three-day Week timeline: an
unnecessary zero-distance vertical animation swallowed an immediate swipe.
Clamping the target and skipping equal offsets fixed the three-day case, and
immediate swipe/reversal checks pass for both Week timelines on both APIs. The
old seven-day symptom was not separately established. Large-text dropdowns now
stack title and value; inspected French 200% captures retain full selected values.
At 320dp/200% the toolbar date can ellipsize; the full accessible label remains.

Forty-eight alternating debug/distribution runs used equivalent source harnesses
built for their respective target variants, two warm-up swipes and six measured
swipes per run, with no forced compilation profile. The table reports the median
of each build's three trial P95 durations in milliseconds, not pooled percentiles.

| Layout | Debug drag start | Distribution drag start | Debug landing | Distribution landing |
| --- | ---: | ---: | ---: | ---: |
| Grid | 67.8 | 34.2 | 46.0 | 28.0 |
| Agenda | 84.2 | 35.4 | 80.9 | 34.8 |
| Continuous Agenda | 51.7 | 34.0 | 78.5 | 34.4 |
| Week list | 85.0 | 30.9 | 51.4 | 30.2 |
| Day cards | 60.8 | 28.6 | 47.4 | 28.6 |
| Day timeline | 66.0 | 28.7 | 34.6 | 29.0 |
| Seven-day timeline | 82.1 | 29.0 | 51.8 | 30.3 |
| Three-day timeline | 65.7 | 30.4 | 46.0 | 45.6 |

Some median frame durations increased, especially during Continuous Agenda drags.
The three-day timeline's landing tail improved only modestly. Optimized Agenda
tracing still includes about 21.4ms of main-thread measurement work, and P95
durations exceed a 16.7ms frame budget. These are measured improvements, not a
claim of uninterrupted 60 FPS or perfect fluidity. Physical-phone motion remains
unverified. Recordings were inspected with sampled frames, alongside raw frame
timings and traces. Vendor launchers, physical TalkBack, Taildrop and Doze retain
the hardware limits disclosed below.

Evidence: `.toolchain/optimized-verification-summary.json`, `optimized-full-api36.log`,
`optimized-full-api30.log`, `optimized-final-fair-summary.json`,
`optimized-final-traces-summary.json`, `optimized-dense-reversals.mp4`,
`optimized-narrow-320dp-font2.0-v0.6.1/` and `optimized-fold200-v0.6.1/`.
Earlier failed/incomplete R8 callback candidates are retained with their focused
reproduction and exact callback-signature fix. Both emulators are restored and
stopped; synthetic fixtures, credentials, exports and forwarding were cleaned up.

Distribution APK SHA-256 (`2,624,170` bytes):

```text
f7ae9860da84631aef0c03096526f802d6db7e8d9850b17376710d2339e8623d
```

Distribution test APK SHA-256 (`550,345` bytes):

```text
326b968bf98bf001d20d839ebdaf9c233ddcce9261a427a67d5107f5db9659cc
```

Both certificates: `059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039`.

## 0.6.1 debug verification after independent review (superseded), 2026-11-07

This is the latest frozen candidate. It supersedes the initial candidate below.
Both full suites used the exact packaged APK, with one emulator running at a time.
The 160-file source fingerprint was unchanged during these runs; subsequent
documentation updates change the companion archive, not the APK.

| Check | Passed | Failed | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: |
| Full API 36 | 148 | 0 | 2 | 852.872 |
| Full API 30 | 146 | 0 | 2 | 831.439 |
| Narrow 320dp and 360dp | 4 | 0 | 0 | 122.974 |
| Resized emulator, 200% text | 6 | 0 | 0 | 58.844 |
| Motion and buffer boundary | 2 | 0 | 0 | 29.003 |
| Notification recovery | 1 | 0 | 0 | 7.554 |
| External Android provider fixture | 1 | 0 | 0 | 7.488 |
| JVM | 26 | 0 | 0 | |

The complete refinement class passed 19 tests on each API. Full-run skips have the
same opt-in/API reasons listed for the initial candidate. Desktop integration
against the exact final APK passed all eight tools. Lint has zero errors, 129
warnings and seven hints. The desktop static suite remains 44 passes and one
opt-in skip.

Review fixes remove the stripe from the onboarding mark as well as the launcher,
preserve Week/Day customization when changing the Month layout, anchor distant
Schedule dates before focus in all three agenda display modes, and persist an
icon reopen request outside the disposable settings page. Delayed provider-query
tests exercise distant forward/backward dates and recreation. Delayed icon
application tests exercise leaving the page and recreation. Grid sizing now
happens outside each pager page, dates/callbacks are retained by absolute period,
and bounded event data covers more neighboring periods. Agenda pages are prewarmed.

### Measured paging improvements and remaining stalls

Forty-eight alternating runs used the same final test APK against the preserved
pre-review candidate and this APK: three trials per build for eight layouts, two
warm-up swipes and six measured swipes per run. Values below are the medians of
the three runs' P95 frame durations in milliseconds, not pooled percentiles.

| Layout | Drag start before | Drag start after | Landing before | Landing after |
| --- | ---: | ---: | ---: | ---: |
| Grid | 101.7 | 68.6 | 87.1 | 48.7 |
| Agenda | 179.3 | 99.4 | 221.2 | 72.0 |
| Continuous Agenda | 235.1 | 60.7 | 186.3 | 94.8 |
| Week list | 118.4 | 84.3 | 135.6 | 55.7 |
| Day cards | 84.4 | 56.0 | 81.6 | 51.4 |
| Day timeline | 89.4 | 68.8 | 51.8 | 47.9 |
| Seven-day timeline | 84.3 | 84.3 | 80.2 | 64.6 |
| Three-day timeline | 68.2 | 73.4 | 62.0 | 62.4 |

Moving Grid composition out of measurement reduced traced prefetch measure from
approximately 106ms to 5.3ms. After that change, keeping one neighbor improved
reversal/Agenda tails, so one was retained. This supersedes the initial candidate's
zero-neighbor decision. Remaining work is real: Agenda prefetch reached 75.6ms of
main-thread CPU and a measured frame reached 152.2ms. Timeline drag-start spikes
remain; the three-day timeline shows no clear tail improvement. Functional tests
and improved medians do not establish perfect motion. Physical-phone performance
has not been measured, and no hardware-smoothness claim is made.

Final visual evidence includes all three Day openings, eight-layout captures,
dense RTL reversals, French menus, narrow toolbars and 200% text. Full titles remain
available through event details/previews; dense overview and closed-menu truncation
limits remain. Physical Taildrop, vendor launchers, Doze and physical TalkBack are
still unverified. Both emulators were restored and stopped; isolated fixtures,
credentials, exports and ADB forwarding were cleaned up.

Evidence: `.toolchain/post-review-verification-summary.json`,
`post-review-verified-full-api36.log`, `post-review-verified-full-api30.log`,
`post-review-final-fair-summary.json`, `post-review-final-v3-traces-summary.json`,
`post-review-final-dense-reversals.mp4` and `post-review-final-layout-contact.png`.
Earlier failed/incomplete candidates and their fixes are retained in the summary.

APK SHA-256 (`20,544,824` bytes):

```text
d2dfb7960208e3f395610cdfa490e2483d0efad136b361378a1ea6bba14bb28f
```

Test APK SHA-256 (`765,419` bytes):

```text
3997b92163ebafa7433db8c2d5afd9d57b964f5577ff1024790f9fcd660dbc77
```

The signing certificate remains `059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039`.

## 0.6.1 initial candidate verification (superseded), 2026-11-07

The frozen 0.6.1/code 12 APK was installed directly for these runs, with one headless
emulator running at a time. Source fingerprints covering 160 files matched before
and after runtime verification. These results supersede the earlier diagnostic
candidates; interrupted runs and failures remain in the local evidence.

| Check | Passed | Failed | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: |
| Full Android 16 / API 36 | 142 | 0 | 2 | 809.444 |
| Full Android 11 / API 30 | 140 | 0 | 2 | 807.591 |
| Narrow 360dp and 320dp | 4 | 0 | 0 | 119.924 |
| Fold emulator, 200% text | 6 | 0 | 0 | 55.860 |
| Notification permission recovery | 1 | 0 | 0 | 7.363 |
| External Android provider fixture | 1 | 0 | 0 | 6.865 |
| JVM | 26 | 0 | 0 | |
| Desktop companion | 44 | 0 | 1 | |

The API 36 full run skips the opt-in notification recovery and external-client
fixtures; both passed separately. API 30 skips wallpaper colors requiring API 31
and the external-client fixture. The desktop SDK integration separately passed
against the Android provider, exercising all eight tools in 2.251 seconds. Lint
reported zero errors, 127 warnings and seven hints; the dependency audit found zero
vulnerabilities. A skipped opt-in test is not counted as a pass.

New regressions cover retained incoming pager composition, diagonal swipes, queued
arrows, disabled swipes, dense RTL reversals, reduced motion, date-switch defaults
and persistence, distant-date loading, focus during provider refresh, and a visible
top-to-first-event scroll in Cards, Timeline and Compact schedule. Icon confirmation
checks a fresh calendar activity after reopening. Settings tests exercise the real
choice menus, agenda range, separate transfer/About pages and French choices.

All 30 final Day-motion captures, eight dense RTL landings, narrow English/French/
Arabic toolbars and French choice menus were inspected. At 200% text some closed
choice values ellipsize; opening the menu exposes the full reachable options.
Dense week/month overviews retain the bounded-title limits described below.

### Swipe measurements and remaining limit

The same synthetic fixture, with 24 all-day events and six alternating full-month
swipes, measured actual window frames. Three standalone 0.6.0 runs had landing
P95 durations of 334–384ms and maxima of 486–635ms. Three paired runs with the new
pager and zero forced offscreen neighbors had P95 durations of 126–141ms and maxima
of 151–152ms. Keeping one forced neighbor gave P95 durations of 144–160ms and maxima
of 183–186ms, so zero forced neighbors was retained with native lazy prefetch.

The final frozen standalone run recorded 208 frames, including 66 landing frames:
P50 31.4ms, P95 138.9ms and maximum 217.6ms. Frame counts differ between versions,
and median durations did not consistently improve. Traces show real Compose CPU
work, roughly 100ms prefetch measurements, and graphics-command time in a slow
frame. The remaining stalls cannot be dismissed as emulator-only. These checks
establish functional paging and reduced tail stalls, not perfect animation or
physical-phone smoothness. Real-device performance remains unverified.

Evidence is retained locally under `.toolchain/`: `sol-final-verification-summary.json`,
`sol-full-api36-v0.6.1.log`, `sol-full-api30-v0.6.1.log`,
`pager-neighbor-v0.6.1-summary.json`, `sol-frozen-standalone-frames-api36-v0.6.1.log`,
`sol-final-day-motion-v0.6.1/`, `sol-narrow-320dp-v0.6.1/`,
`sol-narrow-360dp-v0.6.1/`, `sol-fold200-v0.6.1/` and `settings-v0.6.1/`.
Temporary fixture credentials, exports, sentinel and ADB forwarding were removed;
test calendars were cleaned, existing calendars preserved, display/font/locale
restored and both emulators stopped. Physical Taildrop, vendor launcher refresh,
Doze and physical TalkBack remain unverified.

Production APK SHA-256 (`20,502,648` bytes):

```text
65cc5ade69215014b401c607a304afeabc596bec3097c4d546d5ce9d49165d54
```

Instrumentation APK SHA-256 (`745,964` bytes):

```text
ee62da599b3d70041c80e034a0d9e87a2316524e266057571f2800506dc2e586
```

Both APKs use certificate SHA-256:

```text
059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039
```

Use an emulator with API 26 or newer. The local test setup includes API 30 and API 36 Pixel 5 emulators. Run both to cover older provider behavior and newer notification/PendingIntent rules. A system image being installed does not establish that tests have passed on it.

## Build and run

With JDK 17 and an Android SDK configured:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest
```

If using the ignored portable toolchain prepared in this checkout, load its process-local environment first:

```powershell
. .\.toolchain\env.ps1
adb devices
```

To run only the provider and import/export tests on an installed test APK:

```powershell
adb -s emulator-5554 shell am instrument -w -e class io.github.sunsind.sunlith.CalendarRepositoryTest io.github.sunsind.sunlith.test/androidx.test.runner.AndroidJUnitRunner
```

The tests cover local calendar creation, event CRUD, reminder persistence, all-day UTC dates, recurrence across a daylight-saving transition, series edits/deletion, ICS round-trip preservation, duplicate imports, and safe handling of unsupported recurrence exceptions.

`CalendarUiTest` drives the actual Compose UI through Android accessibility: create an event, find it through search, edit it, and delete it. It also checks the rendered light/dark appearance and the persisted theme setting. On API 33 and newer, a separate case switches the app locale to French and checks translated calendar/editor/settings controls. These UI tests use and clean up a dedicated emulator-only calendar.

It also checks that the agenda remains reachable in landscape at 200% font size. `CalendarExperienceTest` checks month-grid geometry, centered selected dates, launch views, grouped settings and returning from the calendar selector, first/last and immediately outside month/week boundaries, Month agenda display modes, and all seven Week rows regardless of the Month preference. It also checks that the Day tab returns to today, a fixed calendar stays visible by default, the collapse switch restores it, Today gives visible feedback, and a sixth-row date can be selected in landscape at 200% font size before Today brings its selected cell back into view. Separate cases confirm deliberate horizontal swipes change periods, the off switch prevents navigation, optional arrows move each period, and continuous agenda paging keeps the fixed grid populated. Stable accessibility resource IDs identify the calendar viewport and agenda separately. Font size, rotation, and Android animation settings are restored afterward.

`SettingsPreferencesTest` checks defaults, limits, and persisted values in isolated preferences. `WidgetExperienceTest` uses Android's real widget host to check empty days, the date window, optional calendar names, background opacity, native dividers, multi-day events, and opening a draft from an empty date. `CalendarHandoffUiTest` checks Android event-insert intents and calendar-file sharing, editable drafts, import previews, rejection of unsupported input, and deferral through onboarding/recreation/an existing draft. The content provider used for shared-file fixtures exists only in the test APK.

`AiOnboardingTest` checks the computer-focused introduction, the Agenda/Grid chooser, recreation, the skip route, deferred Computer connection and existing-user migration. The app no longer has an assistant-guide picker. `CalendarCustomizationTest` checks preset views, date selection, timelines, provider event colors, wallpaper colors, event focus across loading and recreation, full-title previews, and localized toolbar bounds.

`CalendarRefinementTest` checks horizontal and curved gestures, cancellation, disabled navigation, queued arrows, Day/Back behavior, and live changes to Android's motion preference. `LauncherIconTest` checks launcher aliases, migration, persistence, interrupted switches, document-picker continuity, and actual adaptive artwork rendering. `ComputerConnectionUiTest`, `DeviceLinkTlsTest` and `DeviceLinkToolsTest` cover direct sharing, cached-file and credential lifetime, scoped calendar access, protocol handling, and pinned TLS. The external desktop fixture runs separately with explicit opt-in.

`ReminderNotificationsTest` additionally checks actual posted notification content, actions, occurrence deduplication, disabled reminders, and delivery through a scheduled Android alarm. A separate test inserts an isolated due provider alert for a `MINUTES_DEFAULT` reminder, sends an explicit calendar-reminder broadcast, and verifies notification delivery without changing the provider alert state. That test verifies broadcast handling, not how a real account chooses its default reminder time. Tests request notification permission on API 33 and newer. For one specific emulator, use `adb -s <serial> shell am instrument ...` or set `ANDROID_SERIAL` before a Gradle connected-test command.

To test notification permission denial and recovery on API 33 or newer, run this separately after the other tests. Revoking a runtime permission can terminate the app process, so it must happen before instrumentation starts:

```powershell
adb -s emulator-5556 shell pm revoke io.github.sunsind.sunlith android.permission.POST_NOTIFICATIONS
adb -s emulator-5556 shell pm clear-permission-flags io.github.sunsind.sunlith android.permission.POST_NOTIFICATIONS user-set user-fixed
adb -s emulator-5556 shell am instrument -w -e class io.github.sunsind.sunlith.NotificationPermissionRecoveryTest -e permissionRecovery true io.github.sunsind.sunlith.test/androidx.test.runner.AndroidJUnitRunner
```

This case checks that denied notifications stay silent, grants permission through the real Android permission dialog, then verifies a posted notification. It is skipped during a normal suite run unless the explicit `permissionRecovery` argument is supplied.

## Screenshots with sample events

```powershell
.\tools\capture-demo.ps1
```

This creates a dedicated **Sunlith demo** local calendar on the emulator, selects it, and adds clearly identified sample events relative to the emulator's current date. Demo events have no reminders, so another installed calendar cannot interrupt captures with fixture alerts. It never seeds events automatically in the production app. Repeated captures replace only that dedicated demo calendar.

Images are saved to `.toolchain/screenshots/month-agenda.png` and `.toolchain/screenshots/widget.png`. The calendar image shows the running app. The widget image shows the real Android RemoteViews widget and collection service in an instrumentation-only AppWidgetHost, rather than a launcher screenshot. The widget test also checks that tapping an event opens its details and editor, and that its add button opens a new event editor. The test temporarily grants widget-binding access to the app and revokes it afterward.

Use `-SkipInstall` if both debug APKs are already installed, or `-OutputDirectory <path>` to choose a different image folder.

## Before relying on reminders

On the intended phone, test notification permission, the selected calendars, an event due in a few minutes, reminder edits, reboot behavior, and any manufacturer battery restrictions. Check whether another calendar app also posts the same reminders. Google account syncing and real-device delivery require separate account/device testing; emulator provider tests do not establish those results.

These are test instructions, not a claim that every check has passed. Record the actual results with the build being tested.

## Verified behavior build

For source commit `44a57c8`, verified against the APK hash below. The `0.1.1` naming update changes version metadata and documentation only; its package verification is recorded separately below.

| Check | Result |
| --- | --- |
| Android JVM unit tests | 20 passed |
| Selected API 30 emulator suite | 43 passed in 303.634 seconds |
| Selected API 36 emulator suite | 44 passed in 333.709 seconds |
| Separate API 36 notification permission recovery | 1 passed in 7.027 seconds |
| Android lint | 0 errors, 70 warnings, 4 hints |
| Desktop MCP tests | 31 passed |
| Desktop MCP syntax check | Passed |
| Desktop MCP dependency audit | 0 vulnerabilities |

Each selected emulator suite completed as a single run without failures. Android JVM tests comprise 13 ICS codec cases, 5 incoming-intent parser cases, and 2 editor-state cases. Notification permission recovery ran separately after revoking permission before instrumentation started.

Tested debug APK SHA-256:

```text
6510163DC36109421906F92DF6254D46D5EF395494EAB74B4574D023EF9B508B
```

The selected emulator suite comprises `CalendarRepositoryTest`, `ReminderNotificationsTest`, `CalendarUiTest`, `WidgetSmokeTest`, `AiOnboardingTest`, `SettingsPreferencesTest`, `CalendarExperienceTest`, `WidgetExperienceTest`, and `CalendarHandoffUiTest`. The French app-locale case requires API 33 and is excluded on API 30. Notification permission recovery runs separately with its explicit argument.

Local captures use running emulators and synthetic events. Widget captures use the actual Android widget in an instrumentation host. Captures are excluded from the repository pending phone testing. Google account sync, migration from existing calendars, physical-device reminder delivery, and manufacturer battery behavior still require testing on the intended phone. Emulator results do not establish universal AI client compatibility or a completed connection to an actual Google account.

## Version 0.1.1 package verification

The plain-version APK uses `versionName` `0.1.1` and `versionCode` `4`. All nine DEX files and the compiled resource table are byte-identical to the full behavior build recorded above. The manifest and signing metadata change with the version.

The Android build, JVM checks and lint passed. After installing over the previous app without uninstalling, the actual create/search/edit/delete UI test passed on API 30 (14.286 seconds) and API 36 (19.010 seconds). The signing certificate matches the previous published APK. Companion syntax checks and all 31 tests passed with server/package version `0.1.1`. The full 88 emulator checks above were not repeated for this metadata-only change.

Version 0.1.1 APK SHA-256:

```text
f97f4babcab9bf5fe7d8d6c766ecc42d8e3ed1c4c202a34c1ae721a4b023da36
```

## Version 0.1.2 artwork verification

This build replaces the launcher foreground, monochrome layer, background, and decorative setup mark with the supplied sun/calendar artwork. It uses version code `5` and the same signing certificate as the previous published APK.

| Check | Result |
| --- | --- |
| Android JVM tests | 20 passed |
| API 30 UI, setup, and real widget tests | 6 passed in 51.362 seconds |
| API 36 UI, setup, and real widget tests | 7 passed in 67.130 seconds |
| Android lint | 0 errors, 70 warnings, 4 hints |
| Companion syntax and tests | Passed, 31 tests |

The UI checks include real create/search/edit/delete, rendered light/dark themes, French on API 36, and landscape with large font sizes. Both emulators installed the new APK over the previous version without uninstalling. Actual launcher captures were inspected on both API levels. A private circle/rounded-square mask comparison and foreground alpha bounds confirm the artwork remains inside the centered 66dp adaptive safe circle. Its visible radius is below 32dp on the 108dp foreground canvas. The themed layer shares that padded alpha geometry. Physical Samsung launcher appearance remains for phone testing.

Current APK SHA-256:

```text
807b7c448f625e962be90777f9f4a794be35b90094b91361e945e235d6dc6c4c
```

The broader behavior suite recorded above belongs to the earlier source commit; it was not repeated for this artwork-only update. Screenshots remain private until phone approval.

## Version 0.2.0 direct connection verification

The APK uses version code `6` and the same signing certificate as v0.1.2. Installation over the existing app succeeded on API 30 and API 36.

| Check | Result |
| --- | --- |
| Android JVM tests | 20 passed |
| Android lint | 0 errors, 72 warnings, 5 hints |
| Desktop tests | 44 passed; opt-in Android fixture skipped in normal runs |
| Desktop syntax and dependency audit | Passed; 0 vulnerabilities |
| API 30 direct backend and connection UI | 27 passed across verification runs |
| API 30 existing calendar and UI cases | 34 passed |
| API 36 direct backend and connection UI | 27 passed across verification runs |
| API 36 existing provider, UI, reminder, widget and handoff cases | 29 passed |
| Actual desktop SDK to Android provider | Passed over pinned HTTPS through ADB forwarding |
| Full backup import | 215 imported, 0 skipped; retry detected 215 duplicates |

Direct backend cases cover strict HTTP/JSON parsing, real AndroidKeyStore TLS, access scope, CRUD, stale etags, create retries, conflicting payloads, DST/all-day dates, recurring-series safeguards, guest protection and imports. Desktop TLS tests verify that a wrong certificate receives no decrypted HTTP bytes or bearer header. Connection UI cases cover separate display/access choices, document-picker cancellation, actual service notification, activity recreation, rapid stop/start and revocation. The rendered pairing screen was inspected privately.

The final APK passed five independent lifecycle runs on API 30, covering fifteen immediate stop/start cycles, plus a final SAF/TLS run. The final API 36 connection UI and TLS run passed seven cases. One earlier automated document-picker wait was intermittent; focused and full reruns, screenshots and Android activity logs confirmed the actual picker opened. Phone-specific document-provider behavior remains part of physical-device testing.

The API 30 existing cases cover repository operations, calendar views, handoffs, onboarding, settings and the bounded agenda. API 36 also covers real reminder notifications and RemoteViews widgets. Initial repeated test runs reused static operation IDs against the persistent retry journal; test IDs now have a unique namespace so tests preserve within-request retries without conflicting with earlier runs. No production journal was cleared to make those tests pass. A quick stop/start failure on API 30 led to ordered service commands with start-ID-aware shutdown. A separate shutdown case checks that active and queued sockets close.

The opt-in external test connects the official MCP SDK over stdio to Node, then the real Android HTTPS server and CalendarProvider. It exercises all eight tools, excluded-calendar protection, timed/all-day creation, reads, edits, stale revisions, series scope, imports, duplicate detection and deletion. It operates in isolated emulator calendars that are removed afterward. Pairing files and calendar fixtures stay outside the repository.

To run it, first start the isolated Android fixture, then pull its private configuration and metadata to a private folder outside the repository:

```powershell
adb -s emulator-5556 forward tcp:39393 tcp:39393
adb -s emulator-5556 shell am instrument -w -e class io.github.sunsind.sunlith.ComputerConnectionFixtureTest -e deviceLinkExternal true io.github.sunsind.sunlith.test/androidx.test.runner.AndroidJUnitRunner
```

While that command runs, use another terminal to pull `device-link-test.json` and `device-link-test-metadata.json` from `/sdcard/Android/data/io.github.sunsind.sunlith/files/`. Do not print the pairing contents. From `integrations/mcp`, set `SUNLITH_DEVICE_CONFIG` and `SUNLITH_ANDROID_TEST_METADATA` to the absolute private paths, then run:

```powershell
node --test test/android-device.test.js
adb -s emulator-5556 shell run-as io.github.sunsind.sunlith touch files/device-link-test-finished
```

The fixture waits up to three minutes, then removes its calendars and revokes pairing. An optional private import fixture can be supplied with `SUNLITH_ANDROID_IMPORT_FIXTURE` and `SUNLITH_ANDROID_IMPORT_COUNT`; no real event content belongs in source or logs.

Screen-off and forced deep-idle requests succeeded through ADB loopback forwarding on API 36. That verifies service/process behavior, not Wi-Fi or Tailscale delivery during physical-phone Doze. Real Samsung battery restrictions, Tailscale reachability, network changes and long idle periods still require phone testing. A foreground service alone does not guarantee network access through Doze. The app offers a link to its system settings for background battery use.

Version 0.2.0 APK SHA-256:

```text
b76ae486868cc1a0a624360e0c87c2a7da03a3a5083614cd425041da6870e9c9
```

## Computer activity and time alignment checks

The 0.3.0 regression cases cover a shared Day view time column for short and long clock labels, clock labels fitting on one line in 180dp and 340dp widget rows at normal and 200% font size, and French all-day labels fitting within two lines. Computer connection UI checks cover its details and activity pages and cancelling Clear history.

Backend checks cover successful changes, rejected requests, create retries, unchanged edits, import counts, persistence, the 100-entry bound, clearing, damaged history files and safe title truncation. TLS tests verify that only successful authenticated responses update recent contact, old pairing files remain valid, and a revoked pairing cannot update the new pairing's contact time.

History is a bounded activity record, not undo or a transactional audit trail. A process crash during a partial import can leave imported events without a completed import record. Storage failure can leave recent changes in memory only, and the last-contact timestamp saved to disk can lag by up to one minute. Existing event history cannot be reconstructed.

## Version 0.3.0 verification

Version code `7` uses the same signing certificate as v0.2.0 and was installed over the existing emulator app.

| Check | Result |
| --- | --- |
| Android JVM tests | 20 passed |
| Android lint | 0 errors, 74 warnings, 5 hints |
| API 36 full emulator suite | 82 passed in 422.609 seconds |
| API 30 full emulator suite | 80 cases; one test assertion corrected, as explained below |
| API 30 final widget suite | All 5 passed |
| Fold-sized API 36 at 200% text | Day card alignment passed at 1768x2208, density 320 |
| Separate API 36 notification permission recovery | Passed |
| Desktop tests and syntax | 44 passed; opt-in Android fixture skipped in normal runs |
| Desktop dependency audit | 0 vulnerabilities |
| Actual desktop SDK to Android provider | Passed through pinned HTTPS and stdio MCP |

The first API 30 run passed 79 cases. Its only failure was a widget test counting invisible trailing whitespace as clipped text in a French all-day label. The assertion was corrected to measure visible glyphs. The focused case and then all five widget cases passed on API 30; the final test APK passed the complete API 36 suite. The full API 30 suite was not repeated after that test-only correction. Initial emulator runs interrupted by unresponsive System UI were discarded; the recorded suites used cold boots with one emulator at a time.

The desktop integration exercised all eight tools against isolated emulator calendars. Its resulting private history contained create, update, import and delete entries. The fixture removed its calendars and pairing afterward. The actual widget rendering and large-text landscape captures were inspected privately. No personal events or phone pairing were changed, and no screenshots were published.

Physical Samsung widget rendering, Fold posture changes, Wi-Fi/Tailscale delivery and manufacturer battery behavior still need phone testing. A Fold-sized emulator check does not reproduce Samsung's launcher or folding hardware.

Version 0.3.0 APK SHA-256:

```text
ec76f249745b3b7e66f876d2060c0cb11eb7789eebdade474b8eb9b0e849a1c8
```

## Version 0.4.0 verification

Version code `8` uses the same signing certificate as v0.3.0. The APK was installed as an update on Android 11 (API 30) and Android 16 (API 36).

| Check | Result |
| --- | --- |
| Android JVM tests | 26 passed, including six timeline packing cases |
| Android lint | 0 errors, 81 warnings, 6 hints |
| API 30 broad emulator run | 98 cases; two timeline automation failures, corrected and rerun below |
| API 36 broad emulator run | 100 cases; two timeline automation failures, corrected and rerun below |
| API 30 focused customization suite | All 9 passed in 127.818 seconds |
| API 36 final customization suite | All 9 passed in 160.718 seconds |
| Fold-sized API 36 at 200% text | All 5 passed in 37.470 seconds at 1768x2208, density 320 |
| Separate API 36 notification permission recovery | Passed in 8.478 seconds |
| Desktop syntax and tests after fresh dependency install | 44 passed; external Android fixture skipped in normal runs |
| Desktop dependency audit | 0 vulnerabilities |
| Actual desktop SDK to Android calendar provider | Passed through pinned HTTPS and stdio MCP |
| Release packaging | Signing certificate, source equality, local archive links and checksums verified |

The broad runs cover provider operations, reminders, handoffs, setup, layouts, animated navigation, continuous paging, widgets, connection lifecycle, strict protocol parsing and TLS. They each reported failures in the timeline UI automation: gestures could scroll past short events or tap the floating Add/Today controls over a partially visible event. The helpers now reset the hourly area through accessibility, wait for stable event bounds and bring targets clear of the floating controls. All nine customization cases then passed on each API level. Earlier test corrections recognize Android's checked state for selected date buttons and use smaller agenda gestures. The full broad suites were not repeated after the final test-only corrections; the production APK stayed unchanged throughout these runs.

The five Fold-sized cases were `CalendarExperienceTest.dayEventCardsShareTheSameBoundsForDifferentTimeLengths`, `CalendarCustomizationTest.presetsRenderDifferentMonthAndDayViewsWithTheSameCalendarEvents`, `CalendarCustomizationTest.dayPickerChangesTheShownEventsAndTheNewEventDateForEveryDayLayout`, `CalendarCustomizationTest.overlappingTimelineEventsHaveSeparateTapTargetsAndAllDayStaysAboveTheHours`, and `AiOnboardingTest.everyPresetIsSelectableAndPlannerChoiceSurvivesRecreationBeforeConfirmation`. The customization suite also verifies overnight hourly blocks on both dates, dense-event overflow, the saved three-day window, and actual dynamic and neutral accent colors. An extra test request left over from emulator startup failed to connect UiAutomation and was discarded; it is not included in the verification results above.

The external desktop test exercised all eight tools, allowed-calendar scope, creation retries, reads, updates, stale revisions, recurring-series protection, import deduplication and deletion. Its isolated calendars and pairing were removed afterward, along with the private temporary configuration files and ADB forwarding. No personal phone calendar or pairing was changed. Widget and large-text landscape captures were inspected privately; no screenshots or videos were published.

At initial publication, GitHub rejected workflow dispatch with HTTP 422, "Actions has been disabled for this user." After account recovery on November 6, [run 42276063211](https://github.com/SunSinD/Sunlith/actions/runs/42276063211) passed all four jobs on the exact `v0.4.0` commit, `9207786`: Android build/unit tests/lint and companion install/syntax/tests on Linux, Windows and macOS, plus the Linux dependency audit. CI does not run emulator instrumentation and its APK is signed separately from the published download; the emulator and published-APK checks above remain separate results.

Physical Samsung launcher rendering, Fold posture changes, Wi-Fi/Tailscale delivery and manufacturer battery behavior still need phone testing. The hourly timeline places timed events lasting 24 hours or more in its banner area. Tapping an empty hour opens the editor at its default start time. These limits remain separate from the verified overlap, overnight and date-selection behavior.

Version 0.4.0 APK SHA-256:

```text
fab9ce155ee2b4316223a5fa967ba3d4395d3228a6535d3c4bcf61df4960354e
```

## Unreleased 0.4.1 candidate verification

Before the icon work, an unreleased v0.4.1 candidate with version code `9` fixed a three-day Week timeline loading gap at month boundaries. The checks in this section belong to that earlier candidate, not the final v0.5.0 build. The new regression failed against the published v0.4.0 APK, then passed against the packaged v0.4.1 APK for both Monday-first (January 30–February 1, 2027) and Sunday-first (October 30–November 1, 2026) calendars. It checks that all three dates and the first date's event remain available after selecting the next month's date, changing the Week layout, and switching between three and seven days. The layout-change path also failed against the initial candidate before adding the required range refreshes.

| Check | Result |
| --- | --- |
| Android JVM tests | 26 passed |
| Android lint | 0 errors, 81 warnings, 6 hints |
| Full Android 16 / API 36 suite | 99 passed, 2 skipped, 0 failures in 525.679 seconds |
| Fold-sized API 36 at 200% text | All 5 named cases from v0.4.0 repeated and passed in 38.278 seconds at 1768x2208, density 320 |
| Separate API 36 notification permission recovery | 1 passed in 8.306 seconds |
| Actual desktop SDK to Android calendar provider | All eight tools passed over pinned HTTPS and stdio MCP; fixture cleanup passed |
| Desktop syntax and tests after fresh dependency install | 44 passed, 1 opt-in fixture skipped |
| Desktop dependency audit | 0 vulnerabilities |

The API 30 skips are wallpaper-based Material You colors, which require API 31 or newer, and the separately enabled external desktop fixture. API-level annotations exclude French app locales and notification-permission recovery on API 30. The API 36 full suite skips the external desktop fixture and permission recovery; both passed separately afterward. The external fixture removed its isolated calendars and pairing, and its temporary private desktop files and ADB forwarding were removed. No personal calendar or pairing was accessed.

An initial API 36 full run had 98 passes, two skips and one UI automation failure. Android's event log showed the test event's 15-minute reminder appearing at 14:45 and the automated Search tap clicking that notification, which opened event details. General UI fixtures now disable reminders; reminder behavior remains covered by the separate notification tests. All four focused UI cases passed before this correction. Final full-suite results use the corrected fixture. Startup runs blocked by an Android System UI ANR and runs stopped while extending the regression are excluded.

A later API 36 run had 98 passes, two skips and one shutdown-fixture failure. Its eight TCP connections could still be waiting in the operating system's accept backlog when shutdown was called. The test now waits until the server has accepted all eight, establishing its working/queued-socket precondition before stopping it. All seven focused TLS cases passed with that synchronization; production server code was unchanged.

The packaged APK was installed directly with `adb install -r`, followed by the final instrumentation APK (SHA-256 `f2146232e34b45a53f4a25f1ac60294db19d6c321cd0e3a0e76ef355c65e0bdc`). Its installed bytes match the packaged APK's SHA-256. Gradle connected tests were not used to replace it with another app build. The signing certificate matches v0.4.0; the old release binaries remain unchanged.

Version 0.4.1 APK SHA-256:

```text
d5c2738d0470ae3b54cd714c545d89d8be994bbaae7666047a5345502192e289
```

## Version 0.5.0 verification

Version code `10` uses the same signing certificate as v0.4.0. The earlier code-9 candidate was not published. All results below use the final packaged APK and the final instrumentation APK, installed directly with ADB on one emulator at a time.

| Check | Result |
| --- | --- |
| Android JVM tests | 26 passed |
| Android lint | 0 errors, 83 warnings, 6 hints |
| Full Android 11 / API 30 suite | 108 passed, 2 skipped, 0 failures in 534.879 seconds |
| Full Android 16 / API 36 suite | 110 passed, 2 skipped, 0 failures in 515.064 seconds |
| Fold-sized API 36 at 200% text | All 5 named cases from v0.4.0 passed in 34.431 seconds at 1768x2208, density 320 |
| Separate API 36 notification permission recovery | 1 passed in 6.404 seconds |
| Actual desktop SDK to Android calendar provider | All eight tools passed through pinned HTTPS and stdio MCP; fixture cleanup passed |
| Desktop syntax and tests after fresh dependency install | 44 passed, 1 opt-in Android fixture skipped |
| Desktop dependency audit | 0 vulnerabilities |
| Release packaging | Source equality, local archive links, APK signing certificate and installed APK hash verified |

The API 30 skips are wallpaper-based Material You colors and the external desktop fixture. API-level annotations exclude the French app-locale and notification-permission cases there. The API 36 skips are the external desktop fixture and permission recovery; both passed separately afterward. Custom demo and performance status messages are excluded from test counts.

New regression coverage checks the three-day month boundary with fixed Monday-first and Sunday-first dates, date rollover without replacing an explicitly chosen date, Today in scrolling and fitting full-month layouts, launcher choices and disk persistence, interrupted-switch repair, recreation, rollback and reopening an active document picker from the launcher. The Appearance test taps the actual icon tiles and waits for the recreated activity. The fitting-grid Today case reproduced Opus's final review finding before the fix, then passed afterward. Both final full suites include that fix. Earlier stopped runs and the first document-picker test setup failure are excluded; the picker test now discovers the actual DocumentsUI window instead of requiring PackageManager visibility.

The running calendar has a stable main activity behind a short launcher activity. Disabling the original direct launcher alias on Android 11 had finished the open app despite `DONT_KILL_APP`; the separate launcher activity fixed that observed behavior. Removing `CLEAR_TOP` also preserves an open document picker when returning from Home. Tests cover both the sequential alias update on API 30 and the atomic update on API 36. Startup repairs an interrupted switch on activity recreation as well as a fresh launch.

A dense-calendar fixture inserts 1,460 reminder-free events over 365 days with roughly 1KB descriptions. It checks rendered initial loading, paging in both directions, a distant date, description search and title search. Times include provider work, UI rendering and input, and are observations from these emulators rather than performance thresholds.

| Dense-calendar operation | API 30 elapsed | API 36 elapsed |
| --- | --- | --- |
| Initial Month agenda | 1,468ms | 1,997ms |
| Page forward | 204ms | 161ms |
| Page backward | 204ms | 159ms |
| Select distant month | 316ms | 220ms |
| Cold description search | 1,318ms | 840ms |
| Warm title search | 1,558ms | 233ms |

A 25ms main-thread heartbeat observed maximum lateness of 281ms on API 30 and 193ms on API 36 across these operations. This is a coarse responsiveness sample, not a frame-jank benchmark. It does not establish performance for arbitrarily dense calendars or justify speculative rewrites. The fixture removed its events and calendar afterward.

The desktop integration exercised scope restrictions, timed/all-day creation, idempotent retries, reads, edits, stale revisions, recurring-series safeguards, import deduplication and deletion. Its isolated calendars and pairing were removed, along with private desktop fixture files and ADB forwarding. No personal events or phone pairing were accessed. Fold display size, density and font scale were restored after testing.

Opus 5.5 High and GPT 6.1 Sol High independently reviewed the changes and earlier requirements. Concrete findings were fixed and included in the final runs. Samsung home-screen shortcut migration, icon-launch/splash animation, physical Fold posture changes, Wi-Fi/Tailscale delivery and manufacturer battery restrictions still require a real phone. Some launchers may require adding the home-screen shortcut again after an icon change. The existing timeline limits described above remain.

Version 0.5.0 APK SHA-256:

```text
37333de456ffb9fc8314cb8987e83d6f005e870d9cd4b2ccc06a29b7592d81d9
```

Final instrumentation APK SHA-256:

```text
095b6ec24b046524d69af0c78f91d72461fa90d2b213951a5917252b7511d681
```

## Version 0.6.0 verification

Version code `11` uses the same signing certificate as v0.5.0. These results use the
final packaged APK and final instrumentation APK, installed directly with ADB on
one emulator at a time. Installed bytes matched the packaged files on both APIs.

| Check | Result |
| --- | --- |
| Android JVM tests | 26 passed |
| Android lint | 0 errors, 111 warnings, 7 hints |
| Full Android 11 / API 30 suite | 130 passed, 2 skipped, 0 failures in 784.245 seconds |
| Full Android 16 / API 36 suite | 132 passed, 2 skipped, 0 failures in 845.856 seconds |
| Focused API 36 regressions | 15 passed in 84.854 seconds |
| Narrow toolbar, English / fr-CA / Arabic RTL | 360dp passed in 10.329 seconds; 320dp passed in 9.113 seconds |
| Fold-sized API 36, 200% text | All 5 named cases from v0.4.0 passed in 33.496 seconds at 1768x2208, density 320 |
| Separate API 36 notification permission recovery | 1 passed in 7.883 seconds after revoking permission and clearing permission flags |
| Actual desktop SDK to Android calendar provider | All eight tools passed through pinned HTTPS and stdio MCP in 2.316 seconds; Android fixture passed in 24.831 seconds |
| Desktop syntax and tests | 44 passed, 1 opt-in Android fixture skipped |
| Desktop dependency audit | 0 vulnerabilities |
| Release packaging | Archive source equality, local links, signing certificate and installed APK hashes verified |

API 30 skipped wallpaper-based Material You colors (requires API 31+) and the
external-client fixture. French per-app locale and notification-recovery tests
are API 33+ and excluded there. API 36 skipped the external-client fixture and
permission-recovery cases in the full run; both passed separately afterward.
Custom demo, widget and performance status messages are excluded from counts.
Neither final full run encountered a System UI ANR.

Coverage includes date drilldown and Back, focus in all three Day layouts,
focus consumption across recreation, preserving the hour during day swipes,
diagonal/cancelled/repeated swipes, full-title month previews, provider colors,
start/end times and overlap targets, theme controls, icon switching, scoped
connection-file sharing and revocation, widget reconfiguration and preference
refresh. The distant Schedule regression uses many all-day entries before a
timed event and verifies that programmatic focus does not repeatedly load more
dates. Empty-date navigation advances to the next event when empty dates are
hidden. The French settings test also handles an already-selected radio button.

Earlier candidates exposed a Schedule focus/continuous-loading interaction and
two test-selector problems (a date tap hit the extra-event preview; a selected
radio lacked a redundant click action). Those were corrected before both final
full runs. Earlier failed candidates and an API 36 run obstructed by a System UI
ANR are retained locally as investigation evidence and are not counted as passes.

Screenshot inspection confirms complete dates at 320dp and 360dp, the two-line
French all-day label, readable normal-size French tabs and mirrored RTL Back.
Week titles still ellipsize inside narrow columns. Full-month titles remain
bounded by their cells, including at 200% text; holding the date or using its
extra-event count opens the full-title sheet. Short timeline blocks may devote
their available height to the time range; opening the event shows its full title.
These are layout limits, not evidence that every title fits in every overview.

The adaptive icon tests render the actual Android drawable with circle and
squircle masks. The calendar paper fills the mask: cream for Light and charcoal
for Dark, without the previous separate white tile and shadow. A truly
transparent adaptive layer rendered black on the tested Android implementation;
legacy icons were wrapped in white. See [the icon research](RESEARCH-REFINEMENT.md).

The dense-calendar fixture inserts 1,460 reminder-free events across 365 days.
These elapsed times include provider work, rendering and input on the emulators:

| Operation | API 30 | API 36 |
| --- | --- | --- |
| Initial Month agenda | 1,402ms | 1,701ms |
| Page forward | 226ms | 160ms |
| Page backward | 230ms | 148ms |
| Select distant month | 378ms | 239ms |
| Cold description search | 1,681ms | 841ms |
| Warm title search | 1,223ms | 960ms |

Maximum lateness of the 25ms main-thread heartbeat was 239ms on API 30 and 187ms
on API 36. This is a coarse responsiveness sample, not a frame-jank benchmark or
a guarantee for arbitrarily dense calendars. Loaded-event counts were
732, 1,088, 1,204 and 264; searches returned 10 description matches and 4 title
matches.

The external desktop fixture exercised all eight tools against isolated real
provider calendars. Private desktop files, Android exports, completion sentinel
and ADB forwarding were removed. Both emulators were stopped after restoring
display settings. No personal calendar or phone pairing was accessed. A
158-file fingerprint was unchanged throughout frozen runtime verification.

Opus 5.5 High, GPT 6.1 Sol High, Muse Wonder and Gemini 4 Argon independently
reviewed the final source, artifacts and runtime evidence after these runs.
All four reported no release blockers within their respective scopes: calendar
behavior and rendered layouts; lifecycle and regressions; connection, icons and
packaging; settings, accessibility and localization. These were read-only reviews,
not additional test executions. A minor degraded-path limitation remains: if the
FileProvider share route is unavailable and the save-file fallback is used,
recreating that screen loses its Share again shortcut, although the saved file
and pairing remain valid.

Real-device Taildrop delivery, manufacturer battery restrictions, launcher
shortcut refresh behavior and physical TalkBack interaction remain unverified.
Tailscale supplies network reachability; explicit app authorization still uses
chosen calendars, a bearer token and pinned TLS. Emulator results do not replace
those physical-device checks or prove the absence of all bugs.

Version 0.6.0 APK SHA-256:

```text
0469289ab9f6e545afae7a603379b61f7b6fc937ebd604b17bb8c24eeab02836
```

Final instrumentation APK SHA-256:

```text
3694b91eb10eabad9c8cf26aff25f892341a7cc32613e0574f1be8406b0193ec
```

Signing certificate SHA-256:

```text
059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039
```
