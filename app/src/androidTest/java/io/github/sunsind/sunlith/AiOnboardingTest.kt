package io.github.sunsind.sunlith

import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.Locale
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiOnboardingTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var english: Context
    private lateinit var device: UiDevice
    private var activity: Activity? = null
    private var oldIntro: Boolean? = null
    private var oldComputerSetup: Boolean? = null
    private var oldLocales: LocaleList? = null

    @Before
    fun setUp() {
        requireEmulator()
        context = instrumentation.targetContext
        english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        device = UiDevice.getInstance(instrumentation)
        val prefs = context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE)
        oldIntro = if (prefs.contains("ai_intro_done")) prefs.getBoolean("ai_intro_done", false) else null
        oldComputerSetup = if (prefs.contains("computer_setup_requested")) prefs.getBoolean("computer_setup_requested", false) else null
        prefs.edit().putBoolean("ai_intro_done", false).remove("computer_setup_requested").commit()
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)
            oldLocales = locales.applicationLocales
            locales.applicationLocales = LocaleList.forLanguageTags("en")
        }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        node(R.string.ai_intro_title)
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().apply {
            if (oldIntro == null) remove("ai_intro_done") else putBoolean("ai_intro_done", oldIntro!!)
            if (oldComputerSetup == null) remove("computer_setup_requested") else putBoolean("computer_setup_requested", oldComputerSetup!!)
        }.commit()
        if (Build.VERSION.SDK_INT >= 33) oldLocales?.let { context.getSystemService(LocaleManager::class.java).applicationLocales = it }
    }

    @Test
    fun firstLaunchOffersComputerConnectionAndAnAccessibleSkip() {
        assertTrue(node(R.string.action_set_up_ai).isEnabled)
        val skip = node(R.string.action_without_ai)
        assertTrue(skip.isEnabled)
        assertTrue(skip.visibleBounds.width() > 0 && skip.visibleBounds.height() > 0)
        assertFalse(context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).getBoolean("ai_intro_done", false))
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "sunlith-ai-intro.png")))
        skip.click()
        awaitCalendar()
        assertTrue(context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).getBoolean("ai_intro_done", false))
        requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).desc(english.getString(R.string.action_more))), 10_000)).click()
        node(R.string.title_settings).click()
        node(R.string.ai_setup_title).click()
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "sunlith-ai-setup.png")))
        scrollToText(english.getString(R.string.action_continue_calendar)).click()
        awaitCalendar()
    }

    @Test
    fun firstLaunchOpensComputerSetupWithoutClaimingAConnection() {
        node(R.string.action_set_up_ai).click()
        node(R.string.device_link_title)
        node(R.string.device_link_consent)
        assertFalse(io.github.sunsind.sunlith.device.DeviceLink.isPaired(context))
        assertFalse(device.hasObject(By.pkg(context.packageName).text("Codex")))
        assertTrue(context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).getBoolean("ai_intro_done", false))
        device.pressBack()
        awaitCalendar()
    }

    @Test
    fun assistantGuidesRemainAvailableFromSettings() {
        node(R.string.action_without_ai).click()
        awaitCalendar()
        requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).desc(english.getString(R.string.action_more))), 10_000)).click()
        node(R.string.title_settings).click()
        scrollToText(english.getString(R.string.ai_setup_title)).click()
        node(R.string.ai_setup_title)
        requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).text(english.getString(R.string.action_open_setup, "Codex"))), 10_000))
        node(R.string.action_continue_calendar)
        val providers = listOf("Codex", "Claude", "Cursor", "Grok", "OpenCode", "Antigravity", "Muse Code", "Pi")
        providers.forEach { provider ->
            val label = scrollToText(provider)
            assertTrue(label.isEnabled)
            label.click()
        }
        scrollToText(english.getString(R.string.ai_setup_support))
        assertTrue(scrollToText(english.getString(R.string.action_open_setup, "Pi")).isEnabled)
        assertTrue(context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).getBoolean("ai_intro_done", false))
        scrollToText(english.getString(R.string.action_continue_calendar)).click()
        awaitCalendar()
        assertTrue(context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).getBoolean("ai_intro_done", false))
    }

    private fun node(id: Int): UiObject2 = requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).text(english.getString(id))), 15_000)) {
        "Expected onboarding control was not shown: ${english.getString(id)}"
    }

    private fun awaitCalendar() {
        val intro = By.pkg(context.packageName).text(english.getString(R.string.ai_intro_title))
        val calendar = By.pkg(context.packageName).desc(english.getString(R.string.action_new_event))
        assertTrue(device.wait(Until.gone(intro), 10_000))
        assertTrue(device.wait(Until.hasObject(calendar), 15_000))
    }

    private fun scrollToText(text: String): UiObject2 {
        repeat(10) {
            device.wait(Until.findObject(By.pkg(context.packageName).text(text)), 500)?.let { return it }
            requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).scrollable(true)), 5_000)).scroll(Direction.DOWN, 0.65f)
        }
        error("Expected setup option was not reachable: $text")
    }
}
