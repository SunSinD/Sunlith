package io.github.sunsind.sunlith.system

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AppUpdaterTest {
    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var transport: FakeTransport
    private lateinit var platform: FakePlatform
    private lateinit var store: MemoryStore
    private val scopes = mutableListOf<CoroutineScope>()
    private val apk = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val apkName = "Sunlith-v0.7.0.apk"
    private val apkUrl = "https://github.com/SunSinD/Sunlith/releases/download/v0.7.0/$apkName"
    private val sumsUrl = "https://github.com/SunSinD/Sunlith/releases/download/v0.7.0/SHA256SUMS.txt"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sunlith-update").toFile()
        dir = File(root, AppUpdater.DIR)
        transport = FakeTransport()
        platform = FakePlatform()
        store = MemoryStore()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        root.deleteRecursively()
    }

    @Test
    fun versionsCompareNumericallyAcrossMultipleDigits() {
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("0.6.10") > v("0.6.9"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertEquals(v("0.7.0"), UpdateVersion.fromTag("v0.7.0"))
        assertEquals("0.10.2", v("0.10.2").toString())
    }

    @Test
    fun versionParsingRejectsLooseForms() {
        listOf("0.7", "0.7.0.1", "v0.7.0", "0.7.0-beta.1", "01.2.3", "0.7.0 ", "", "0.٧.0", "1234567.0.0").forEach {
            assertNull(it, UpdateVersion.parse(it))
        }
        assertNull(UpdateVersion.fromTag("0.7.0"))
        assertNull(UpdateVersion.fromTag("V0.7.0"))
    }

    @Test
    fun releaseParsesExactAssetsAndDigests() {
        val release = requireNotNull(AppUpdateRelease.parse(releaseJson(), v("0.6.10")))
        assertEquals(v("0.7.0"), release.version)
        assertEquals(URI(apkUrl), release.apk.url)
        assertEquals(apk.size.toLong(), release.apk.size)
        assertEquals(sha256(apk), release.apk.sha256)
        assertEquals(URI(sumsUrl), release.sums.url)
        assertNotNull(AppUpdateRelease.parse(releaseJson(version = "0.10.0"), v("0.9.12")))
    }

    @Test
    fun olderOrEqualReleaseIsUpToDateWithoutInspectingAssets() {
        assertNull(AppUpdateRelease.parse(releaseJson(assets = "[]"), v("0.7.0")))
        assertNull(AppUpdateRelease.parse(releaseJson(assets = "[]"), v("0.10.0")))
    }

    @Test
    fun draftPrereleaseAndLooseTagsAreRejected() {
        problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.parse(releaseJson(draft = true), v("0.6.10")) }
        problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.parse(releaseJson(prerelease = true), v("0.6.10")) }
        problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.parse(releaseJson(tag = "0.7.0"), v("0.6.10")) }
        problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.parse(releaseJson(tag = "v0.7.0-rc.1"), v("0.6.10")) }
        problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.parse(releaseJson().decodeToString().replace("\"draft\":false,", "").toByteArray(), v("0.6.10")) }
    }

    @Test
    fun assetUrlsMustMatchTheRepositoryTagAndName() {
        listOf(
            "https://github.com/Someone/Sunlith/releases/download/v0.7.0/$apkName",
            "http://github.com/SunSinD/Sunlith/releases/download/v0.7.0/$apkName",
            "https://github.com/SunSinD/Sunlith/releases/download/v0.6.10/$apkName",
            "https://example.com/SunSinD/Sunlith/releases/download/v0.7.0/$apkName",
            "https://github.com/SunSinD/Sunlith/releases/download/v0.7.0/$apkName?x=1",
        ).forEach { url ->
            problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.parse(releaseJson(apkUrl = url), v("0.6.10")) }
        }
    }

    @Test
    fun duplicateMissingOrUnfinishedAssetsFail() {
        problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.parse(releaseJson(assets = "[${apkAsset()},${apkAsset()},${sumsAsset()}]"), v("0.6.10")) }
        problem(UpdateProblem.NOT_FOUND) { AppUpdateRelease.parse(releaseJson(assets = "[${sumsAsset()}]"), v("0.6.10")) }
        problem(UpdateProblem.NOT_FOUND) { AppUpdateRelease.parse(releaseJson(assets = "[${apkAsset()}]"), v("0.6.10")) }
        problem(UpdateProblem.NOT_FOUND) { AppUpdateRelease.parse(releaseJson(assets = "[${apkAsset(state = "starter")},${sumsAsset()}]"), v("0.6.10")) }
    }

    @Test
    fun digestSizeAndTypeAreStrict() {
        listOf(
            apkAsset(digest = null), apkAsset(digest = "sha1:" + "a".repeat(40)), apkAsset(digest = "sha256:" + "g".repeat(64)),
            apkAsset(size = 0), apkAsset(size = AppUpdateRelease.MAX_APK + 1), apkAsset(type = "text/html"),
        ).forEach { asset ->
            problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.parse(releaseJson(assets = "[$asset,${sumsAsset()}]"), v("0.6.10")) }
        }
        assertNotNull(AppUpdateRelease.parse(releaseJson(assets = "[${apkAsset(type = "application/octet-stream")},${sumsAsset()}]"), v("0.6.10")))
    }

    @Test
    fun duplicateKeysAndOversizeMetadataFail() {
        problem(UpdateProblem.NOT_VERIFIED) {
            AppUpdateRelease.parse(releaseJson().decodeToString().replace("{\"tag_name\"", "{\"draft\":true,\"tag_name\"").toByteArray(), v("0.6.10"))
        }
        problem(UpdateProblem.NOT_VERIFIED) { UpdateJson.parse(ByteArray(AppUpdateRelease.MAX_METADATA.toInt() + 1) { ' '.code.toByte() }) }
        problem(UpdateProblem.NOT_VERIFIED) { UpdateJson.parse("[".repeat(40).toByteArray()) }
        problem(UpdateProblem.NOT_VERIFIED) { UpdateJson.parse(byteArrayOf(0x7b, 0xff.toByte(), 0x7d)) }
    }

    @Test
    fun checksumFileNeedsExactlyOneExactEntry() {
        val hash = sha256(apk)
        assertEquals(hash, AppUpdateRelease.checksumFor("$hash  $apkName\n${"b".repeat(64)}  Sunlith-companion-v0.7.0.zip\n".toByteArray(), apkName))
        assertEquals(hash, AppUpdateRelease.checksumFor("${hash.uppercase()} *$apkName\r\n".toByteArray(), apkName))
        problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.checksumFor("$hash  $apkName.bak\n".toByteArray(), apkName) }
        problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.checksumFor("$hash  $apkName\n$hash  $apkName\n".toByteArray(), apkName) }
        problem(UpdateProblem.NOT_VERIFIED) { AppUpdateRelease.checksumFor("$hash  $apkName\nnot a checksum line\n".toByteArray(), apkName) }
    }

    @Test
    fun candidatePackageSignerAndVersionAreAuthoritative() {
        val installed = facts("0.6.10", 21)
        assertEquals(CandidateCheck.NEWER, checkCandidate(installed, facts("0.7.0", 22), v("0.7.0")))
        assertEquals(CandidateCheck.NOT_NEWER, checkCandidate(installed, facts("0.7.0", 21), v("0.7.0")))
        assertEquals(CandidateCheck.INVALID, checkCandidate(installed, null, v("0.7.0")))
        assertEquals(CandidateCheck.INVALID, checkCandidate(installed, facts("0.7.0", 22).copy(packageName = "io.github.other"), v("0.7.0")))
        assertEquals(CandidateCheck.INVALID, checkCandidate(installed, facts("0.7.0", 22).copy(signers = setOf("other"), signerHistory = setOf("other")), v("0.7.0")))
        assertEquals(CandidateCheck.INVALID, checkCandidate(installed, facts("0.7.0", 22).copy(signers = emptySet()), v("0.7.0")))
        assertEquals(CandidateCheck.INVALID, checkCandidate(installed, facts("0.7.1", 22), v("0.7.0")))
        val rotated = facts("0.7.0", 22).copy(signers = setOf("new"), signerHistory = setOf("key", "new"))
        assertEquals(CandidateCheck.NEWER, checkCandidate(installed, rotated, v("0.7.0")))
        val multiple = facts("0.7.0", 22).copy(signers = setOf("key", "extra"), signerHistory = setOf("key", "extra"), multipleSigners = true)
        assertEquals(CandidateCheck.INVALID, checkCandidate(installed, multiple, v("0.7.0")))
    }

    @Test
    fun redirectSafetyAndInstallerChoice() {
        val github = setOf("github.com")
        assertTrue(UpdateFetcher.safe(URI(apkUrl), github))
        assertFalse(UpdateFetcher.safe(URI(apkUrl.replace("https", "http")), github))
        assertFalse(UpdateFetcher.safe(URI("https://user@github.com/x"), github))
        assertFalse(UpdateFetcher.safe(URI("https://github.com:8443/x"), github))
        assertFalse(UpdateFetcher.safe(URI("https://github.com.evil.example/x"), github))
        assertFalse(UpdateFetcher.safe(URI("/relative"), github))
        assertEquals("com.android.packageinstaller", AppUpdateIntents.chooseInstaller(listOf("com.android.packageinstaller")))
        assertEquals("com.google.android.packageinstaller", AppUpdateIntents.chooseInstaller(listOf("com.oem.files", "com.google.android.packageinstaller")))
        assertNull(AppUpdateIntents.chooseInstaller(listOf("com.oem.a", "com.oem.b")))
        assertNull(AppUpdateIntents.chooseInstaller(emptyList()))
    }

    @Test
    fun creatingTheControllerStaysOfflineAndCheckReadsOnlyMetadata() = runBlocking<Unit> {
        serveRelease()
        val controller = controller()
        controller.awaitIdle()
        assertTrue(transport.requests.isEmpty())
        assertEquals(AppUpdateState.Idle, controller.state.value)
        controller.check()
        controller.awaitIdle()
        assertEquals(AppUpdateState.Available("0.7.0"), controller.state.value)
        assertEquals(listOf(AppUpdateRelease.LATEST_URL), transport.requests.map { it.first })
        assertTrue(transport.requests.single().second)
    }

    @Test
    fun upToDateComesFromTheInstalledVersion() = runBlocking<Unit> {
        serveRelease()
        platform.installed = facts("0.7.0", 22)
        val controller = controller()
        controller.awaitIdle()
        controller.check()
        controller.awaitIdle()
        assertEquals(AppUpdateState.UpToDate, controller.state.value)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun downloadFollowsAllowedRedirectsAndKeepsOnlyTheVerifiedFile() = runBlocking<Unit> {
        serveRelease()
        val updater = updater()
        val release = (updater.check() as CheckResult.Available).release
        val seen = mutableListOf<Long>()
        val result = updater.download(release) { bytes, total -> assertEquals(apk.size.toLong(), total); seen += bytes }
        val pending = (result as DownloadResult.Ready).pending
        assertEquals(22L, pending.versionCode)
        assertEquals(pending, store.pending)
        assertEquals(listOf(apkName), dir.list()!!.toList())
        assertTrue(apk.contentEquals(File(dir, apkName).readBytes()))
        assertEquals(seen.sorted(), seen)
        assertEquals(apk.size.toLong(), seen.last())
        assertTrue(transport.requests.drop(1).all { !it.second })
        assertTrue(transport.requests.any { it.first.startsWith("https://release-assets.githubusercontent.com/") })
    }

    @Test
    fun redirectsOutsideGitHubOrToPlainHttpFail() = runBlocking<Unit> {
        for (target in listOf("https://evil.example/apk", "http://release-assets.githubusercontent.com/apk", "https://api.github.com/apk")) {
            serveRelease()
            transport.routes[apkUrl] = { redirect(target) }
            transport.routes[target] = { ok(apk) }
            val updater = updater()
            val release = (updater.check() as CheckResult.Available).release
            problem(UpdateProblem.NOT_VERIFIED) { updater.download(release) { _, _ -> } }
            assertTrue(dir.list().orEmpty().isEmpty())
            assertNull(store.pending)
        }
    }

    @Test
    fun tooManyRedirectsFail() = runBlocking<Unit> {
        serveRelease()
        (0..UpdateFetcher.MAX_HOPS).forEach { hop ->
            transport.routes[if (hop == 0) apkUrl else "https://github.com/hop$hop"] = { redirect("https://github.com/hop${hop + 1}") }
        }
        val updater = updater()
        val release = (updater.check() as CheckResult.Available).release
        problem(UpdateProblem.NOT_VERIFIED) { updater.download(release) { _, _ -> } }
    }

    @Test
    fun tamperedApkOrChecksumFailsAndLeavesNoFiles() = runBlocking<Unit> {
        val altered = apk.copyOf().also { it[10] = (it[10] + 1).toByte() }
        serveRelease()
        transport.routes[ASSET_URL] = { ok(altered) }
        val updater = updater()
        var release = (updater.check() as CheckResult.Available).release
        problem(UpdateProblem.NOT_VERIFIED) { updater.download(release) { _, _ -> } }
        assertTrue(dir.list().orEmpty().isEmpty())

        serveRelease(sums = "${"0".repeat(64)}  $apkName\n".toByteArray())
        release = (updater.check() as CheckResult.Available).release
        problem(UpdateProblem.NOT_VERIFIED) { updater.download(release) { _, _ -> } }
        assertTrue(dir.list().orEmpty().isEmpty())
        assertNull(store.pending)
    }

    @Test
    fun bodiesLongerThanDeclaredFail() = runBlocking<Unit> {
        serveRelease()
        transport.routes[ASSET_URL] = { ok(apk + byteArrayOf(1), length = -1) }
        val updater = updater()
        val release = (updater.check() as CheckResult.Available).release
        problem(UpdateProblem.NOT_VERIFIED) { updater.download(release) { _, _ -> } }
        transport.routes[ASSET_URL] = { UpdateResponse(200, null, null, apk.size + 1L, ByteArrayInputStream(apk)) }
        problem(UpdateProblem.NOT_VERIFIED) { updater.download(release) { _, _ -> } }
        transport.routes[ASSET_URL] = { ok(apk.copyOf(apk.size - 1), length = -1) }
        problem(UpdateProblem.NETWORK) { updater.download(release) { _, _ -> } }
        transport.routes[AppUpdateRelease.LATEST_URL] = { UpdateResponse(200, null, "application/json", AppUpdateRelease.MAX_METADATA + 1, ByteArrayInputStream(ByteArray(0))) }
        problem(UpdateProblem.NOT_VERIFIED) { updater.check() }
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test
    fun serverAndNetworkErrorsMapToShortProblems() = runBlocking<Unit> {
        val updater = updater()
        transport.routes[AppUpdateRelease.LATEST_URL] = { UpdateResponse(403, null, "application/json", 0, ByteArrayInputStream(ByteArray(0))) }
        problem(UpdateProblem.RATE_LIMITED) { updater.check() }
        transport.routes[AppUpdateRelease.LATEST_URL] = { UpdateResponse(404, null, "application/json", 0, ByteArrayInputStream(ByteArray(0))) }
        problem(UpdateProblem.NOT_FOUND) { updater.check() }
        transport.routes[AppUpdateRelease.LATEST_URL] = { UpdateResponse(502, null, "text/html", 0, ByteArrayInputStream(ByteArray(0))) }
        problem(UpdateProblem.NETWORK) { updater.check() }
        transport.routes[AppUpdateRelease.LATEST_URL] = { throw IOException("offline") }
        problem(UpdateProblem.NETWORK) { updater.check() }
        transport.routes[AppUpdateRelease.LATEST_URL] = { ok("<html>portal</html>".toByteArray(), type = "text/html") }
        problem(UpdateProblem.NETWORK) { updater.check() }
    }

    @Test
    fun archiveFactsDecideAfterDownload() = runBlocking<Unit> {
        serveRelease()
        val updater = updater()
        val release = (updater.check() as CheckResult.Available).release
        platform.archive = facts("0.7.0", 21)
        assertEquals(DownloadResult.UpToDate, updater.download(release) { _, _ -> })
        assertTrue(dir.list().orEmpty().isEmpty())
        platform.archive = facts("0.7.0", 22).copy(signers = setOf("stranger"), signerHistory = setOf("stranger"))
        problem(UpdateProblem.NOT_VERIFIED) { updater.download(release) { _, _ -> } }
        platform.archive = null
        problem(UpdateProblem.NOT_VERIFIED) { updater.download(release) { _, _ -> } }
        assertTrue(dir.list().orEmpty().isEmpty())
        assertNull(store.pending)
    }

    @Test
    fun cancellingABlockedDownloadClosesItAndCleansUp() = runBlocking<Unit> {
        serveRelease()
        val stream = BlockingStream(apk.copyOf(70_000))
        transport.routes[ASSET_URL] = { UpdateResponse(200, null, "application/octet-stream", apk.size.toLong(), stream) }
        val controller = controller()
        controller.awaitIdle()
        controller.check()
        controller.awaitIdle()
        controller.update()
        assertTrue(stream.blocked.await(5, TimeUnit.SECONDS))
        assertTrue(controller.state.value is AppUpdateState.Downloading)
        controller.cancel()
        assertEquals(AppUpdateState.Available("0.7.0"), controller.state.value)
        controller.awaitIdle()
        assertTrue(stream.closed.count == 0L)
        assertEquals(AppUpdateState.Available("0.7.0"), controller.state.value)
        assertTrue(dir.list().orEmpty().isEmpty())
        assertNull(store.pending)
        assertNull(controller.command.value)
    }

    @Test
    fun cancellationDoesNotPublishAnErrorWhenTheClosedStreamReturnsEof() = runBlocking<Unit> {
        serveRelease()
        val blocked = CountDownLatch(1)
        val closed = CountDownLatch(1)
        transport.routes[ASSET_URL] = {
            UpdateResponse(200, null, "application/octet-stream", apk.size.toLong(), object : InputStream() {
                override fun read(): Int = error("Use buffered read")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    blocked.countDown()
                    check(closed.await(5, TimeUnit.SECONDS))
                    return -1
                }
                override fun close() { closed.countDown() }
            })
        }
        val controller = controller()
        controller.awaitIdle()
        controller.check(); controller.awaitIdle()
        controller.update()
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        controller.cancel(); controller.awaitIdle()
        assertEquals(AppUpdateState.Available("0.7.0"), controller.state.value)
        assertNull(store.pending)
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test
    fun updateAfterCancelQueuesWithVisibleProgressUntilTheOldConnectionReturns() = runBlocking<Unit> {
        serveRelease()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        transport.routes[ASSET_URL] = {
            if (++calls == 1) {
                blocked.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw IOException("Cancelled connection returned")
            }
            ok(apk)
        }
        val controller = controller()
        controller.awaitIdle()
        controller.check(); controller.awaitIdle()
        controller.update()
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            controller.cancel()
            controller.update()
            assertTrue(controller.state.value is AppUpdateState.Downloading)
        } finally { release.countDown() }
        controller.awaitIdle()
        assertEquals(AppUpdateState.Ready("0.7.0"), controller.state.value)
        assertEquals(2, calls)
    }

    @Test
    fun cancellingWhileConnectingClosesTheConnection() = runBlocking<Unit> {
        val connecting = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val blocked = object : UpdateTransport {
            override fun open(uri: URI, api: Boolean): UpdateResponse = error("Missing request control")
            override fun open(uri: URI, api: Boolean, request: UpdateRequest): UpdateResponse {
                request.bind { disconnected.countDown() }
                connecting.countDown()
                check(disconnected.await(5, TimeUnit.SECONDS))
                throw IOException("closed")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val task = scope.launch { UpdateFetcher(blocked).read(URI(AppUpdateRelease.LATEST_URL), UpdateFetcher.Kind.METADATA, 100) }
        assertTrue(connecting.await(5, TimeUnit.SECONDS))
        task.cancel()
        task.join()
        assertEquals(0L, disconnected.count)
        assertTrue(task.isCancelled)
    }

    @Test
    fun redirectsShareOneOverallDeadline() = runBlocking<Unit> {
        var clock = 0L
        var requests = 0
        val timed = UpdateTransport { _, _ ->
            requests++
            clock += 31_000_000_000L
            redirect(AppUpdateRelease.LATEST_URL)
        }
        problem(UpdateProblem.NETWORK) {
            UpdateFetcher(timed) { clock }.read(URI(AppUpdateRelease.LATEST_URL), UpdateFetcher.Kind.METADATA, 100)
        }
        assertEquals(1, requests)
    }

    @Test
    fun installerFailureRetryReusesTheVerifiedDownload() = runBlocking<Unit> {
        serveRelease()
        val controller = controller()
        controller.awaitIdle()
        controller.check(); controller.awaitIdle()
        controller.update(); controller.awaitIdle()
        val requests = transport.requests.size
        controller.onLaunchFailed()
        controller.retry(); controller.awaitIdle()
        assertEquals(AppUpdateState.Ready("0.7.0"), controller.state.value)
        assertEquals(requests, transport.requests.size)
        assertTrue(File(dir, apkName).isFile)
    }

    @Test
    fun restoreReverifiesTheSavedFileAndNeverTouchesOtherCacheFiles() = runBlocking<Unit> {
        val pairing = File(root, "pairing-share/Sunlith pairing.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        val sibling = File(root, "unrelated.bin").apply { writeText("keep") }
        serveRelease()
        val updater = updater()
        val release = (updater.check() as CheckResult.Available).release
        val pending = (updater.download(release) { _, _ -> } as DownloadResult.Ready).pending
        assertEquals(pending, updater.restore())

        File(dir, apkName).appendBytes(byteArrayOf(0))
        assertNull(updater.restore())
        assertTrue(dir.list().orEmpty().isEmpty())

        updater.download(release) { _, _ -> }
        platform.installed = facts("0.7.0", 22)
        assertNull(updater.restore())
        assertTrue(dir.list().orEmpty().isEmpty())

        File(dir, "stray.apk.download").writeText("stray")
        assertNull(updater.restore())
        assertTrue(dir.list().orEmpty().isEmpty())
        assertEquals("keep", pairing.readText())
        assertEquals("keep", sibling.readText())
    }

    @Test
    fun cleanupAfterReplaceOnlyRemovesInstalledOrOrphanedDownloads() = runBlocking<Unit> {
        serveRelease()
        val updater = updater()
        val release = (updater.check() as CheckResult.Available).release
        updater.download(release) { _, _ -> }
        updater.cleanUpAfterReplace()
        assertEquals(listOf(apkName), dir.list()!!.toList())
        platform.installed = facts("0.7.0", 22)
        updater.cleanUpAfterReplace()
        assertTrue(dir.list().orEmpty().isEmpty())
        assertNull(store.pending)
    }

    @Test
    fun readyUpdateOpensTheInstallerOnceAndItsReturnIsNotSuccess() = runBlocking<Unit> {
        serveRelease()
        val controller = controller()
        controller.attach()
        controller.awaitIdle()
        controller.check(); controller.awaitIdle()
        controller.update(); controller.awaitIdle()
        assertEquals(AppUpdateState.Installing("0.7.0"), controller.state.value)
        val command = controller.command.value as UpdateCommand.OpenInstaller
        assertEquals(File(dir, apkName), command.file)
        assertTrue(controller.consume(command))
        assertFalse(controller.consume(command))
        assertNull(controller.command.value)

        controller.onInstallerReturned()
        assertEquals(AppUpdateState.Ready("0.7.0"), controller.state.value)
        assertNull(controller.command.value)
        assertTrue(File(dir, apkName).isFile)

        controller.install(); controller.awaitIdle()
        assertEquals(AppUpdateState.Installing("0.7.0"), controller.state.value)
        assertTrue(controller.consume(controller.command.value!!))
        platform.installed = facts("0.7.0", 22)
        controller.onInstallerReturned(); controller.awaitIdle()
        assertEquals(AppUpdateState.UpToDate, controller.state.value)
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test
    fun deniedInstallPermissionWaitsForTheUserAndGrantedContinuesOnce() = runBlocking<Unit> {
        serveRelease()
        platform.canInstall = false
        val controller = controller()
        controller.attach()
        controller.awaitIdle()
        controller.check(); controller.awaitIdle()
        controller.update(); controller.awaitIdle()
        assertEquals(AppUpdateState.NeedsPermission("0.7.0"), controller.state.value)
        assertNull(controller.command.value)

        controller.install(); controller.awaitIdle()
        assertEquals(UpdateCommand.OpenPermission, controller.command.value)
        assertTrue(controller.consume(UpdateCommand.OpenPermission))
        controller.onPermissionReturned()
        assertEquals(AppUpdateState.NeedsPermission("0.7.0"), controller.state.value)
        assertNull(controller.command.value)

        platform.canInstall = true
        controller.onPermissionReturned()
        assertEquals(AppUpdateState.Installing("0.7.0"), controller.state.value)
        assertTrue(controller.command.value is UpdateCommand.OpenInstaller)
        controller.onPermissionReturned()
        assertTrue(controller.consume(controller.command.value!!))
        assertNull(controller.command.value)
    }

    @Test
    fun recreationRestoresReadyWithoutLaunchingOrNetwork() = runBlocking<Unit> {
        serveRelease()
        val first = controller()
        first.awaitIdle()
        first.check(); first.awaitIdle()
        first.update(); first.awaitIdle()
        assertEquals(AppUpdateState.Ready("0.7.0"), first.state.value)
        assertNull(first.command.value)
        val requests = transport.requests.size

        val second = controller()
        second.attach()
        second.awaitIdle()
        assertEquals(AppUpdateState.Ready("0.7.0"), second.state.value)
        assertNull(second.command.value)
        assertEquals(requests, transport.requests.size)
        second.onInstallerReturned()
        second.onPermissionReturned()
        assertEquals(AppUpdateState.Ready("0.7.0"), second.state.value)
        assertNull(second.command.value)
    }

    @Test
    fun leavingTheScreenDropsAnUnlaunchedInstallerCommand() = runBlocking<Unit> {
        serveRelease()
        val controller = controller()
        controller.attach()
        controller.awaitIdle()
        controller.check(); controller.awaitIdle()
        controller.update(); controller.awaitIdle()
        assertTrue(controller.command.value is UpdateCommand.OpenInstaller)
        controller.detach()
        assertNull(controller.command.value)
        assertEquals(AppUpdateState.Ready("0.7.0"), controller.state.value)
    }

    @Test
    fun failureOffersRetryAndRetryChecksAgain() = runBlocking<Unit> {
        transport.routes[AppUpdateRelease.LATEST_URL] = { throw IOException("offline") }
        val controller = controller()
        controller.awaitIdle()
        controller.update()
        controller.install()
        assertTrue(transport.requests.isEmpty())
        controller.check(); controller.awaitIdle()
        assertEquals(AppUpdateState.Failed(UpdateProblem.NETWORK), controller.state.value)
        serveRelease()
        controller.retry(); controller.awaitIdle()
        assertEquals(AppUpdateState.Available("0.7.0"), controller.state.value)
        assertEquals(2, transport.requests.size)
    }

    private fun v(text: String) = requireNotNull(UpdateVersion.parse(text)) { text }

    private fun facts(version: String, code: Long) =
        PackageFacts(APP_PACKAGE, version, code, setOf("key"), setOf("key"), multipleSigners = false)

    private fun updater() = AppUpdater(transport, platform, dir, store)

    private fun controller(): AppUpdateController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        return AppUpdateController(updater(), scope)
    }

    private fun serveRelease(sums: ByteArray = "${sha256(apk)}  $apkName\n${"c".repeat(64)}  Sunlith-companion-v0.7.0.zip\n".toByteArray()) {
        val json = releaseJson(sumsBytes = sums)
        transport.routes[AppUpdateRelease.LATEST_URL] = { ok(json, type = "application/json; charset=utf-8") }
        transport.routes[sumsUrl] = { redirect(SUMS_ASSET_URL) }
        transport.routes[SUMS_ASSET_URL] = { ok(sums) }
        transport.routes[apkUrl] = { redirect(ASSET_URL) }
        transport.routes[ASSET_URL] = { ok(apk) }
    }

    private fun releaseJson(
        version: String = "0.7.0", tag: String = "v$version", draft: Boolean = false, prerelease: Boolean = false,
        apkUrl: String? = null, sumsBytes: ByteArray? = null, assets: String? = null,
    ): ByteArray {
        val list = assets ?: "[${sumsAsset(version, sumsBytes)},${apkAsset(version, url = apkUrl)}]"
        return """{"tag_name":"$tag","name":"Sunlith v$version","draft":$draft,"prerelease":$prerelease,"body":"Notes","assets":$list}""".toByteArray()
    }

    private fun apkAsset(
        version: String = "0.7.0", url: String? = null, size: Long = apk.size.toLong(), type: String = "application/vnd.android.package-archive",
        state: String = "uploaded", digest: String? = "sha256:" + sha256(apk),
    ): String {
        val name = "Sunlith-v$version.apk"
        val link = url ?: "https://github.com/SunSinD/Sunlith/releases/download/v$version/$name"
        val digestField = if (digest == null) "" else ""","digest":"$digest""""
        return """{"name":"$name","state":"$state","size":$size,"content_type":"$type","browser_download_url":"$link","uploader":{"login":"SunSinD"}$digestField}"""
    }

    private fun sumsAsset(version: String = "0.7.0", bytes: ByteArray? = null): String {
        val content = bytes ?: "${sha256(apk)}  Sunlith-v$version.apk\n".toByteArray()
        return """{"name":"SHA256SUMS.txt","state":"uploaded","size":${content.size},"content_type":"text/plain; charset=utf-8","browser_download_url":"https://github.com/SunSinD/Sunlith/releases/download/v$version/SHA256SUMS.txt","digest":"sha256:${sha256(content)}"}"""
    }

    private inline fun problem(expected: UpdateProblem, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (e: UpdateFailure) {
            assertEquals(expected, e.problem)
        }
    }

    private class FakeTransport : UpdateTransport {
        val routes = mutableMapOf<String, () -> UpdateResponse>()
        val requests = mutableListOf<Pair<String, Boolean>>()
        override fun open(uri: URI, api: Boolean): UpdateResponse {
            synchronized(requests) { requests += uri.toString() to api }
            return routes[uri.toString()]?.invoke() ?: UpdateResponse(404, null, null, 0, ByteArrayInputStream(ByteArray(0)))
        }
    }

    private inner class FakePlatform : UpdatePlatform {
        @Volatile var installed = facts("0.6.10", 21)
        @Volatile var archive: PackageFacts? = facts("0.7.0", 22)
        @Volatile var canInstall = true
        override fun installed() = installed
        override fun archive(file: File) = if (file.isFile) archive else null
        override fun canInstallPackages() = canInstall
    }

    private class MemoryStore : PendingStore {
        @Volatile var pending: PendingUpdate? = null
        override fun load() = pending
        override fun save(pending: PendingUpdate?) { this.pending = pending }
    }

    private class BlockingStream(private val first: ByteArray) : InputStream() {
        val blocked = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private var sent = false
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!sent) {
                sent = true
                first.copyInto(b, off, 0, minOf(len, first.size))
                return minOf(len, first.size)
            }
            blocked.countDown()
            closed.await(10, TimeUnit.SECONDS)
            throw IOException("closed")
        }
        override fun close() { closed.countDown() }
    }

    private companion object {
        const val ASSET_URL = "https://release-assets.githubusercontent.com/github-production-release-asset/1/apk?sig=a%2Bb&rsct=application%2Foctet-stream"
        const val SUMS_ASSET_URL = "https://release-assets.githubusercontent.com/github-production-release-asset/1/sums?sig=c"

        fun ok(bytes: ByteArray, type: String = "application/octet-stream", length: Long = bytes.size.toLong()) =
            UpdateResponse(200, null, type, length, ByteArrayInputStream(bytes))

        fun redirect(location: String) = UpdateResponse(302, location, null, 0, ByteArrayInputStream(ByteArray(0)))
    }
}
