package io.github.sunsind.sunlith

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.lifecycle.ViewModelProvider
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.system.APP_PACKAGE
import io.github.sunsind.sunlith.system.AndroidUpdatePlatform
import io.github.sunsind.sunlith.system.CandidateCheck
import io.github.sunsind.sunlith.system.UpdateVersion
import io.github.sunsind.sunlith.system.checkCandidate
import io.github.sunsind.sunlith.system.AppUpdateController
import io.github.sunsind.sunlith.system.AppUpdateIntents
import io.github.sunsind.sunlith.system.AppUpdateRelease
import io.github.sunsind.sunlith.system.AppUpdater
import io.github.sunsind.sunlith.system.PackageFacts
import io.github.sunsind.sunlith.system.PendingStore
import io.github.sunsind.sunlith.system.PendingUpdate
import io.github.sunsind.sunlith.system.UpdatePlatform
import io.github.sunsind.sunlith.system.UpdateResponse
import io.github.sunsind.sunlith.system.UpdateTransport
import io.github.sunsind.sunlith.system.sha256
import io.github.sunsind.sunlith.ui.AppUpdateSection
import io.github.sunsind.sunlith.ui.AppUpdateViewModel
import io.github.sunsind.sunlith.ui.SunlithTheme
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var device: UiDevice
    private var activity: ComponentActivity? = null
    private var preferences: SharedPreferencesSnapshot? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dir get() = File(context.cacheDir, AppUpdater.DIR)
    private val opened = CopyOnWriteArrayList<Intent>()
    private var localizedContext: android.content.Context? = null
    private val apk = ByteArray(120_000) { (it * 7 % 253).toByte() }
    private val transport = Transport()
    private val platform = Platform()

    @Before
    fun setUp() {
        requireEmulator()
        device = UiDevice.getInstance(instrumentation)
        check(dir.listFiles().isNullOrEmpty()) { "Updater cache must be empty before this fixture." }
        preferences = SharedPreferencesSnapshot(context)
        activity = instrumentation.startActivitySync(Intent().setComponent(
            ComponentName(context.packageName, ComponentActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
    }

    @After
    fun tearDown() {
        transport.apkGate?.countDown()
        platform.archiveGate?.countDown()
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        instrumentation.waitForIdleSync()
        scope.cancel()
        dir.deleteRecursively()
        preferences?.restore()
    }

    @Test
    fun installerIntentUsesAReadOnlyContentUriForTheUpdaterFileOnly() {
        dir.mkdirs()
        val file = File(dir, "Sunlith-v0.7.0.apk").apply { writeBytes(apk) }
        val intent = AppUpdateIntents.installer(context, file)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        val uri = requireNotNull(intent.data)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals(listOf("app_update", "Sunlith-v0.7.0.apk"), uri.pathSegments)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(apk.contentEquals(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }))

        val outside = File.createTempFile("not-an-update-", ".apk", context.cacheDir).apply { writeBytes(apk) }
        try {
            FileProvider.getUriForFile(context, AppUpdateIntents.authority(context), outside)
            fail("Only the updater and pairing folders may be shared.")
        } catch (_: IllegalArgumentException) {
        } finally { outside.delete() }

        val permission = AppUpdateIntents.permission(context)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.action)
        assertEquals(Uri.parse("package:${context.packageName}"), permission.data)
    }

    @Test
    fun installedApkArchiveHasTheRealPackageVersionAndSigners() {
        val platform = AndroidUpdatePlatform(context)
        val installed = platform.installed()
        val archive = requireNotNull(platform.archive(File(context.applicationInfo.sourceDir)))
        assertEquals(APP_PACKAGE, archive.packageName)
        assertEquals(installed.versionName, archive.versionName)
        assertEquals(installed.versionCode, archive.versionCode)
        assertTrue(installed.signers.isNotEmpty())
        assertEquals(installed.signers, archive.signers)
        assertEquals(CandidateCheck.NOT_NEWER,
            checkCandidate(installed, archive, requireNotNull(UpdateVersion.parse(installed.versionName))))
    }

    @Test
    fun productionSectionRetainsItsViewModelAcrossRealActivityRecreation() {
        val before = requireNotNull(activity)
        lateinit var model: AppUpdateViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(before)[AppUpdateViewModel::class.java]
            before.setContent { UpdateHost { AppUpdateSection() } }
        }
        runBlocking { model.controller.awaitIdle() }
        node(By.text(text(R.string.app_update_check)))
        instrumentation.runOnMainSync { before.recreate() }
        waitUntil {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull { it !== before }?.let { activity = it }
            }
            activity !== before
        }
        instrumentation.runOnMainSync {
            val after = requireNotNull(activity)
            assertTrue(model === ViewModelProvider(after)[AppUpdateViewModel::class.java])
            after.setContent { UpdateHost { AppUpdateSection() } }
        }
        node(By.text(text(R.string.app_update_check)))
        assertTrue(opened.isEmpty())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun manualFlowAsksPermissionOnceAndTheInstallerReturnIsNotSuccess() {
        platform.canInstall = false
        val controller = show()
        assertTrue(transport.requests.isEmpty())
        node(By.text(text(R.string.app_update_check))).click()
        node(By.text(text(R.string.app_update_available, "0.7.0")))
        assertEquals(1, transport.requests.size)
        node(By.text(text(R.string.app_update_action_update))).click()
        node(By.text(text(R.string.app_update_permission)))
        assertTrue(opened.isEmpty())

        node(By.text(text(R.string.app_update_action_allow))).click()
        waitUntil { opened.size == 1 }
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, opened[0].action)
        instrumentation.runOnMainSync { controller.onPermissionReturned() }
        node(By.text(text(R.string.app_update_permission)))

        show(controller)
        node(By.text(text(R.string.app_update_permission)))
        idle()
        assertEquals(1, opened.size)

        platform.canInstall = true
        instrumentation.runOnMainSync { controller.onPermissionReturned() }
        waitUntil { opened.size == 2 }
        val install = opened[1]
        assertEquals(Intent.ACTION_VIEW, install.action)
        assertEquals("application/vnd.android.package-archive", install.type)
        assertEquals("content", install.data?.scheme)
        assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        node(By.text(text(R.string.app_update_installing, "0.7.0")))

        instrumentation.runOnMainSync { controller.onInstallerReturned() }
        node(By.text(text(R.string.app_update_ready, "0.7.0")))
        assertFalse(device.hasObject(By.text(text(R.string.app_update_up_to_date))))
        show(controller)
        node(By.text(text(R.string.app_update_ready, "0.7.0")))
        idle()
        assertEquals(2, opened.size)
        assertTrue(File(dir, "Sunlith-v0.7.0.apk").isFile)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun checkingTheFileKeepsTheRowAndADisabledActionInPlace() {
        val controller = show()
        node(By.text(text(R.string.app_update_check))).click()
        node(By.text(text(R.string.app_update_available, "0.7.0")))
        val download = CountDownLatch(1).also { transport.apkGate = it }
        val verify = CountDownLatch(1).also { platform.archiveGate = it }
        node(By.text(text(R.string.app_update_action_update))).click()
        node(By.text(text(R.string.app_update_percent, SPLIT * 100 / apk.size)))
        val downloading = node(tag("app_update_row")).visibleBounds
        val cancel = node(tag("app_update_action")).visibleBounds

        download.countDown()
        node(By.text(text(R.string.app_update_preparing)))
        assertPreparingKeepsItsShape(downloading.height(), downloading.bottom - cancel.bottom)

        verify.countDown()
        node(By.text(text(R.string.app_update_installing, "0.7.0")))
        waitUntil { opened.size == 1 }
        val install = node(tag("app_update_action"))
        node(By.text(text(R.string.app_update_action_install)))
        assertTrue(install.isEnabled)

        instrumentation.runOnMainSync { controller.onInstallerReturned() }
        node(By.text(text(R.string.app_update_ready, "0.7.0")))
        val ready = node(tag("app_update_row")).visibleBounds
        val readyAction = node(tag("app_update_action")).visibleBounds
        val again = CountDownLatch(1).also { platform.archiveGate = it }
        node(By.text(text(R.string.app_update_action_install))).click()
        node(By.text(text(R.string.app_update_preparing)))
        assertPreparingKeepsItsShape(ready.height(), ready.bottom - readyAction.bottom)
        again.countDown()
        node(By.text(text(R.string.app_update_installing, "0.7.0")))
        waitUntil { opened.size == 2 }
        assertEquals(3, transport.requests.size)
    }

    private fun assertPreparingKeepsItsShape(minHeight: Int, actionBottomInset: Int) {
        node(By.text(text(R.string.app_update_preparing_detail)))
        node(tag("app_update_progress"))
        val row = node(tag("app_update_row")).visibleBounds
        val action = node(tag("app_update_action"))
        node(By.text(text(R.string.app_update_action_install)))
        assertFalse(action.isEnabled)
        assertTrue("$row must not shrink below $minHeight", row.height() >= minHeight - 1)
        assertEquals("The action must keep its position at the bottom of the row", actionBottomInset.toDouble(),
            (row.bottom - action.visibleBounds.bottom).toDouble(), 1.0)
        val shown = opened.size
        action.click()
        idle()
        assertTrue(device.hasObject(By.text(text(R.string.app_update_preparing))))
        assertEquals(shown, opened.size)
    }

    @Test
    fun offlineFailureOffersRetry() {
        transport.offline = true
        show()
        node(By.text(text(R.string.app_update_check))).click()
        node(By.text(text(R.string.app_update_error_network)))
        transport.offline = false
        node(By.text(text(R.string.action_retry))).click()
        node(By.text(text(R.string.app_update_available, "0.7.0")))
    }

    @Test
    fun availableUpdateFitsANarrowColumnAtDoubleTextSize() {
        showNarrow()
        node(By.text(text(R.string.app_update_check))).click()
        val title = node(By.text(text(R.string.app_update_available, "0.7.0")))
        assertNarrowControlsFit(title)
    }

    @Test
    fun preparingFitsFrenchAtDoubleTextSizeWithoutAnActiveInstallAction() {
        showNarrow(Locale.CANADA_FRENCH)
        node(By.text(text(R.string.app_update_check))).click()
        node(By.text(text(R.string.app_update_available, "0.7.0")))
        val verify = CountDownLatch(1).also { platform.archiveGate = it }
        node(By.text(text(R.string.app_update_action_update))).click()
        val title = node(By.text(text(R.string.app_update_preparing)))
        node(By.text(text(R.string.app_update_preparing_detail)))
        node(tag("app_update_progress"))
        assertNarrowControlsFit(title, R.string.app_update_action_install)
        assertFalse(node(tag("app_update_action")).isEnabled)
        assertTrue(opened.isEmpty())
        verify.countDown()
        waitUntil { opened.size == 1 }
    }

    private fun showNarrow(locale: Locale? = null) {
        val controller = controller()
        val config = Configuration(requireNotNull(activity).resources.configuration).apply { locale?.let(::setLocale) }
        val localized = requireNotNull(activity).createConfigurationContext(config)
        localizedContext = localized
        instrumentation.runOnMainSync {
            requireNotNull(activity).setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, 2f),
                    LocalContext provides localized, LocalConfiguration provides config,
                    LocalActivityResultRegistryOwner provides requireNotNull(activity)) {
                    UpdateHost {
                        Box(Modifier.width(200.dp).semantics { testTagsAsResourceId = true }.testTag("app_update_narrow")) {
                            AppUpdateSection(controller) { opened += it }
                        }
                    }
                }
            }
        }
    }

    private fun assertNarrowControlsFit(title: UiObject2, actionLabel: Int = R.string.app_update_action_update) {
        val action = node(tag("app_update_action"))
        val label = node(By.text(text(actionLabel)))
        val column = node(tag("app_update_narrow")).visibleBounds
        listOf(title.visibleBounds, action.visibleBounds, label.visibleBounds).forEach { bounds ->
            assertTrue("$bounds must stay inside $column", bounds.left >= column.left && bounds.right <= column.right && bounds.height() > 0)
        }
        assertTrue(action.visibleBounds.height() >= 48 * context.resources.displayMetrics.density - 1)
    }

    private fun controller(): AppUpdateController {
        val updater = AppUpdater(transport, platform, dir, Store())
        lateinit var controller: AppUpdateController
        instrumentation.runOnMainSync { controller = AppUpdateController(updater, scope) }
        return controller
    }

    private fun show(existing: AppUpdateController? = null): AppUpdateController {
        val controller = existing ?: controller()
        instrumentation.runOnMainSync {
            requireNotNull(activity).setContent { UpdateHost { AppUpdateSection(controller) { opened += it } } }
        }
        idle()
        return controller
    }

    private fun text(id: Int, vararg arguments: Any): String = (localizedContext ?: requireNotNull(activity)).getString(id, *arguments)

    @Composable
    private fun UpdateHost(content: @Composable () -> Unit) {
        SunlithTheme("light", false) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
        }
    }

    private fun tag(value: String): BySelector = By.pkg(context.packageName).res(value)

    private fun node(selector: BySelector): UiObject2 {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        val found = device.wait(Until.findObject(selector), 8_000)
        if (found == null) {
            val output = requireNotNull(context.getExternalFilesDir(null))
            val stem = "app-update-failure-${android.os.SystemClock.elapsedRealtime()}"
            device.takeScreenshot(File(output, "$stem.png"))
            device.dumpWindowHierarchy(File(output, "$stem.xml"))
        }
        return requireNotNull(found) { "Missing update control: $selector" }
    }

    private fun idle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the update flow." }
            idle()
        }
    }

    private inner class Transport : UpdateTransport {
        @Volatile var offline = false
        @Volatile var apkGate: CountDownLatch? = null
        val requests = CopyOnWriteArrayList<Pair<String, Boolean>>()
        override fun open(uri: URI, api: Boolean): UpdateResponse {
            requests += uri.toString() to api
            if (offline) throw IOException("offline")
            val sums = "${sha256(apk)}  Sunlith-v0.7.0.apk\n".toByteArray()
            val base = "https://github.com/SunSinD/Sunlith/releases/download/v0.7.0/"
            val body = when (uri.toString()) {
                AppUpdateRelease.LATEST_URL -> """{"tag_name":"v0.7.0","draft":false,"prerelease":false,"assets":[
                    {"name":"Sunlith-v0.7.0.apk","state":"uploaded","size":${apk.size},"content_type":"application/vnd.android.package-archive","browser_download_url":"${base}Sunlith-v0.7.0.apk","digest":"sha256:${sha256(apk)}"},
                    {"name":"SHA256SUMS.txt","state":"uploaded","size":${sums.size},"content_type":"text/plain","browser_download_url":"${base}SHA256SUMS.txt","digest":"sha256:${sha256(sums)}"}]}""".toByteArray()
                "${base}SHA256SUMS.txt" -> sums
                "${base}Sunlith-v0.7.0.apk" -> apk
                else -> return UpdateResponse(404, null, null, 0, ByteArrayInputStream(ByteArray(0)))
            }
            val gate = apkGate
            val stream = if (gate != null && body === apk) GatedStream(body, gate) else ByteArrayInputStream(body)
            return UpdateResponse(200, null, "application/octet-stream", body.size.toLong(), stream)
        }
    }

    private inner class Platform : UpdatePlatform {
        @Volatile var canInstall = true
        @Volatile var archiveGate: CountDownLatch? = null
        override fun installed() = PackageFacts(APP_PACKAGE, "0.6.10", 21, setOf("key"), setOf("key"), false)
        override fun archive(file: File) = if (archiveGate?.await(8, TimeUnit.SECONDS) != false && file.isFile) PackageFacts(APP_PACKAGE, "0.7.0", 22, setOf("key"), setOf("key"), false) else null
        override fun canInstallPackages() = canInstall
    }

    /** Serves the first [SPLIT] bytes, then waits, so the test can look at the row mid-download. */
    private class GatedStream(bytes: ByteArray, private val gate: CountDownLatch) : ByteArrayInputStream(bytes) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= SPLIT) gate.await(8, TimeUnit.SECONDS)
            return super.read(b, off, if (pos < SPLIT) minOf(len, SPLIT - pos) else len)
        }
    }

    private class Store : PendingStore {
        @Volatile private var pending: PendingUpdate? = null
        override fun load() = pending
        override fun save(pending: PendingUpdate?) { this.pending = pending }
    }

    private companion object {
        const val SPLIT = 65_536
    }
}
