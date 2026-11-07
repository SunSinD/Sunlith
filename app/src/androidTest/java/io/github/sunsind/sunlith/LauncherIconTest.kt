package io.github.sunsind.sunlith

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.system.LauncherIcons
import io.github.sunsind.sunlith.system.LauncherIcons.Style
import io.github.sunsind.sunlith.widget.AgendaWidgetReceiver
import java.io.File
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pm = context.packageManager
    private val main = ComponentName(context, MainActivity::class.java)
    private lateinit var original: Style

    @Before
    fun setUp() {
        requireEmulator()
        original = LauncherIcons.saved(context)
    }

    @After
    fun tearDown() {
        finishAll()
        if (::original.isInitialized) assertTrue(LauncherIcons.select(context, original))
    }

    @Test
    fun adaptiveArtworkUsesThePageInsteadOfABlackOrWhiteSurround() {
        val mark = requireNotNull(ContextCompat.getDrawable(context, R.drawable.sunlith_mark))
        val rendered = Bitmap.createBitmap(660, 660, Bitmap.Config.ARGB_8888)
        mark.setBounds(0, 0, 660, 660)
        mark.draw(Canvas(rendered))
        val paper = Color.parseColor("#FDF8EA")
        for (y in 170..205) for (x in 80..580) {
            assertEquals("The onboarding mark must keep its paper continuous below the pins.", paper, rendered.getPixel(x, y))
        }
        File(context.filesDir, "launcher-onboarding-mark.png").outputStream().use {
            assertTrue(rendered.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        rendered.recycle()
        LauncherIcons.availableStyles.forEach { style ->
            val drawable = requireNotNull(ContextCompat.getDrawable(context, style.icon))
            val bitmap = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            val paper = Color.parseColor(if (style == Style.Light) "#FDF8EA" else "#282623")
            assertEquals("The page must reach the top of the launcher mask.", paper, bitmap.getPixel(108, 20))
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                assertFalse("$style must not expose Android's black base or a white wrapper.", pixel == Color.BLACK || pixel == Color.WHITE)
            }
            File(context.filesDir, "launcher-${style.key}.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }

    @Test
    fun manifestKeepsLightAsTheOnlyDefaultLauncherEntry() {
        Style.entries.forEach { style ->
            val info = pm.getActivityInfo(LauncherIcons.component(context, style), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(style == Style.Light, info.enabled)
            assertEquals(LauncherActivity::class.java.name, info.targetActivity)
        }
        assertEquals(Style.Light, LauncherIcons.Default)
        val launchers = pm.queryIntentActivities(launcherQuery(), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse("MainActivity must not carry its own launcher entry.", launchers.any { it.activityInfo.name == main.className })
    }

    @Test
    fun everyStyleLeavesExactlyOneLaunchableEntryAndIsSavedToDisk() {
        (LauncherIcons.availableStyles + LauncherIcons.availableStyles.first()).forEach { style ->
            assertTrue(LauncherIcons.select(context, style))
            assertLauncherIs(style)
            assertEquals(style, LauncherIcons.saved(context))
            val prefs = File(context.dataDir, "shared_prefs/sunlith.xml").readText()
            assertTrue("The choice must be on disk for the next process.", prefs.contains("<string name=\"launcher_icon\">${style.key}</string>"))
            assertAppEntryPointsStillWork()
        }
    }

    @Test
    fun switchingKeepsTheRunningActivityAndSurvivesRecreation() {
        assertTrue(LauncherIcons.select(context, Style.Light))
        context.startActivity(launchIntent())
        var activity = waitForResumed(null)
        listOf(Style.Dark, Style.Light, Style.Dark).forEach { style ->
            assertTrue(LauncherIcons.select(context, style))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(1_000)
            assertFalse("Switching to $style must not finish the open app.", activity.isFinishing || activity.isDestroyed)
            assertLauncherIs(style)
        }
        val old = activity
        pm.setComponentEnabledSetting(LauncherIcons.component(context, Style.Neutral), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        assertEquals(listOf(Style.Dark, Style.Neutral), LauncherIcons.enabled(context))
        instrumentation.runOnMainSync { old.recreate() }
        activity = waitForResumed(old)
        val repairedBy = SystemClock.elapsedRealtime() + 5_000
        while (LauncherIcons.enabled(context) != listOf(Style.Dark) && SystemClock.elapsedRealtime() < repairedBy) SystemClock.sleep(10)
        assertLauncherIs(Style.Dark)
        assertEquals(Style.Dark, LauncherIcons.saved(context))

        finishAll()
        context.startActivity(launchIntent())
        waitForResumed(activity)
        assertLauncherIs(Style.Dark)
    }

    @Test
    fun reconcileRepairsAHalfFinishedSwitchAndUnknownValues() {
        assertTrue(LauncherIcons.select(context, Style.Dark))
        pm.setComponentEnabledSetting(LauncherIcons.component(context, Style.Neutral), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        assertEquals(listOf(Style.Dark, Style.Neutral), LauncherIcons.enabled(context))
        LauncherIcons.reconcile(context)
        assertLauncherIs(Style.Dark)

        Style.entries.forEach {
            pm.setComponentEnabledSetting(LauncherIcons.component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        LauncherIcons.reconcile(context)
        assertLauncherIs(Style.Dark)

        context.getSharedPreferences("sunlith", Context.MODE_PRIVATE).edit().putString("launcher_icon", "sepia").commit()
        LauncherIcons.reconcile(context)
        assertLauncherIs(Style.Light)
    }

    @Test
    fun legacyNeutralChoiceMigratesToOneLightLauncherEntry() {
        context.getSharedPreferences("sunlith", Context.MODE_PRIVATE).edit().putString("launcher_icon", "neutral").commit()
        pm.setComponentEnabledSetting(LauncherIcons.component(context, Style.Neutral), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        LauncherIcons.reconcile(context)
        assertEquals(Style.Light, LauncherIcons.saved(context))
        assertLauncherIs(Style.Light)
        assertEquals(listOf(Style.Light, Style.Dark), LauncherIcons.availableStyles)
        assertTrue(LauncherIcons.select(context, Style.Neutral))
        assertLauncherIs(Style.Light)
    }

    @Test
    fun relaunchingFromTheIconPreservesAnOpenDocumentPicker() {
        val device = UiDevice.getInstance(instrumentation)
        context.startActivity(launchIntent())
        val activity = waitForResumed(null)
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/calendar").addCategory(Intent.CATEGORY_OPENABLE)
        instrumentation.runOnMainSync { activity.startActivityForResult(picker, 1701) }
        val pickerPackage = requireNotNull(device.wait(Until.findObject(By.pkg(Pattern.compile(".*documentsui"))), 10_000)).applicationPackage
        device.pressHome()
        context.startActivity(requireNotNull(pm.getLaunchIntentForPackage(context.packageName)))
        assertTrue("Opening Sunlith must resume its document picker.", device.wait(Until.hasObject(By.pkg(pickerPackage)), 10_000))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
    }

    @Test
    fun failedSequentialSwitchRollsBackToThePreviousIcon() {
        assertTrue(LauncherIcons.select(context, Style.Light))
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            LauncherIcons.switchSequentially(context, Style.Dark) { component, state ->
                if (++calls == 2) throw IllegalStateException("Simulated PackageManager failure")
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
        assertLauncherIs(Style.Light)
    }

    private fun assertLauncherIs(style: Style) {
        val alias = LauncherIcons.component(context, style)
        val entries = pm.queryIntentActivities(launcherQuery(), 0)
        assertEquals(listOf(alias.className), entries.map { it.activityInfo.name })
        val entry = entries.single()
        assertEquals(LauncherActivity::class.java.name, entry.activityInfo.targetActivity)
        assertEquals(style.icon, entry.activityInfo.iconResource)
        assertEquals(context.getString(R.string.app_name), entry.loadLabel(pm).toString())
        assertEquals(alias, pm.getLaunchIntentForPackage(context.packageName)?.component)
        assertEquals(listOf(style), LauncherIcons.enabled(context))
    }

    private fun assertAppEntryPointsStillWork() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(main))
        assertEquals(main.className, pm.resolveActivity(Intent(context, MainActivity::class.java), 0)?.activityInfo?.name)
        val insert = Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.item/event").setPackage(context.packageName)
        assertEquals(main.className, pm.resolveActivity(insert, 0)?.activityInfo?.name)
        val widget = ComponentName(context, AgendaWidgetReceiver::class.java)
        assertTrue(AppWidgetManager.getInstance(context).getInstalledProvidersForPackage(context.packageName, null).any { it.provider == widget })
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(widget))
    }

    private fun launcherQuery() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)

    private fun launchIntent(): Intent = requireNotNull(pm.getLaunchIntentForPackage(context.packageName)) { "Sunlith has no launcher entry." }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun waitForResumed(old: Activity?): Activity {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var found: Activity? = null
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull { it !== old }
            }
            found?.let { instrumentation.waitForIdleSync(); return it }
            SystemClock.sleep(100)
        }
        error("MainActivity did not resume.")
    }

    private fun finishAll() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().forEach(Activity::finish)
        }
        instrumentation.waitForIdleSync()
    }
}
