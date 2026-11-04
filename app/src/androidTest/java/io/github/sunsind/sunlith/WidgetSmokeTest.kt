package io.github.sunsind.sunlith

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.widget.AgendaWidgetReceiver
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetSmokeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    @Test
    fun widgetDisplaysRealProviderEventsAndOpensTheEditor() {
        requireEmulator()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        DemoFixtureTest().seedDemoCalendarAndLaunch()
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0")
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        lateinit var host: AppWidgetHost
        lateinit var widgetContainer: FrameLayout
        var widgetId = 0
        instrumentation.runOnMainSync {
            host = AppWidgetHost(context, 20261104)
            host.startListening()
            widgetId = host.allocateAppWidgetId()
        }
        try {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AgendaWidgetReceiver::class.java)
            assertTrue("The emulator must allow the dedicated instrumentation widget host.", manager.bindAppWidgetIdIfAllowed(widgetId, component))
            manager.updateAppWidgetOptions(widgetId, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 340)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 340)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 440)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 440)
            })
            instrumentation.runOnMainSync {
                val density = context.resources.displayMetrics.density
                widgetContainer = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(8, 8, 8)) }
                val view = host.createView(activity, widgetId, manager.getAppWidgetInfo(widgetId))
                val layout = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (440 * density).toInt(), Gravity.CENTER)
                layout.setMargins((20 * density).toInt(), 0, (20 * density).toInt(), 0)
                widgetContainer.addView(view, layout)
                activity.addContentView(widgetContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                AgendaWidgetReceiver.updateAll(context)
            }
            val driving = By.res(context.packageName, "widget_title").text("Driving class")
            assertTrue("The widget must load Driving class through its real collection service.", device.wait(Until.hasObject(driving), 15_000))
            assertTrue("The widget must load the next day's real provider event.", device.wait(Until.hasObject(By.res(context.packageName, "widget_title").text("Dentist appointment")), 10_000))
            val file = File(context.getExternalFilesDir(null), "sunlith-widget.png")
            assertTrue(device.takeScreenshot(file))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("widgetScreenshot", file.absolutePath)
                putString("widgetHost", "Android AppWidgetHost in instrumentation; actual RemoteViews and provider data.")
            })
            requireNotNull(device.findObject(driving)).click()
            device.waitForIdle()
            instrumentation.runOnMainSync { (widgetContainer.parent as? ViewGroup)?.removeView(widgetContainer) }
            val edit = By.pkg(context.packageName).desc(context.getString(R.string.action_edit))
            val openedDetails = device.wait(Until.hasObject(edit), 10_000)
            if (!openedDetails) {
                device.takeScreenshot(File(context.getExternalFilesDir(null), "widget-failure.png"))
                device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "widget-failure.xml"))
            }
            assertTrue("Tapping a widget event must open that event's editable details.", openedDetails)
            requireNotNull(device.findObject(edit)).click()
            assertTrue("The widget event must open the correct saved event in the editor.", device.wait(Until.hasObject(By.pkg(context.packageName).clazz("android.widget.EditText").text("Driving class")), 10_000))
            requireNotNull(device.findObject(By.pkg(context.packageName).desc(context.getString(R.string.action_close)))).click()
            requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).desc(context.getString(R.string.action_back))), 10_000)).click()
            device.waitForIdle()
            instrumentation.runOnMainSync {
                activity.addContentView(widgetContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val add = requireNotNull(device.wait(Until.findObject(By.res(context.packageName, "widget_add").desc(context.getString(R.string.action_new_event))), 10_000)) { "The widget must expose an accessible add button." }
            add.click()
            device.waitForIdle()
            instrumentation.runOnMainSync { (widgetContainer.parent as? ViewGroup)?.removeView(widgetContainer) }
            assertTrue("The widget add action must open the event editor.", device.wait(Until.hasObject(By.pkg(context.packageName).text(context.getString(R.string.editor_new))), 10_000))
        } finally {
            instrumentation.runOnMainSync {
                host.deleteAppWidgetId(widgetId)
                host.stopListening()
            }
            instrumentation.uiAutomation.executeShellCommand("appwidget revokebind --package ${context.packageName} --user 0")
                .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        }
    }
}
