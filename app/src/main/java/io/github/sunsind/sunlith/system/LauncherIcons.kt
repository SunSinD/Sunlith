package io.github.sunsind.sunlith.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.sunsind.sunlith.MainActivity
import io.github.sunsind.sunlith.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Switches the home-screen icon between the launcher activity-aliases declared in the manifest.
 * MainActivity itself never changes state, so explicit intents, widgets and notifications keep working.
 */
object LauncherIcons {
    enum class Style(val key: String, val alias: String, val icon: Int, val label: Int) {
        Light("light", "LauncherLight", R.mipmap.ic_launcher, R.string.launcher_icon_light),
        Dark("dark", "LauncherDark", R.mipmap.ic_launcher_dark, R.string.launcher_icon_dark),
        Neutral("neutral", "LauncherNeutral", R.mipmap.ic_launcher_neutral, R.string.launcher_icon_neutral),
    }

    /** Matches android:enabled in the manifest: only the Light alias is enabled on install and upgrade. */
    val Default = Style.Light
    val availableStyles = listOf(Style.Light, Style.Dark)
    private const val KEY = "launcher_icon"
    private const val FLAGS = PackageManager.DONT_KILL_APP

    fun component(context: Context, style: Style) =
        ComponentName(context.packageName, MainActivity::class.java.name.substringBeforeLast('.') + "." + style.alias)

    /** The style the user chose. Unknown or missing values fall back to the manifest default. */
    fun saved(context: Context): Style {
        val key = prefs(context).getString(KEY, null)
        return availableStyles.firstOrNull { it.key == key } ?: Default
    }

    /** Aliases the launcher can currently see. Healthy state is exactly one. */
    fun enabled(context: Context): List<Style> {
        val pm = context.packageManager
        return Style.entries.filter { style ->
            when (pm.getComponentEnabledSetting(component(context, style))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> style == Default
                else -> false
            }
        }
    }

    /**
     * Saves [style] and enables only its alias. Returns false and keeps the previous icon if the system refuses.
     * Run off the main thread.
     */
    @Synchronized
    fun select(context: Context, style: Style): Boolean {
        if (style == Style.Neutral) return select(context, Default)
        val prefs = prefs(context)
        val previous = prefs.getString(KEY, null)
        if (!prefs.edit().putString(KEY, style.key).commit()) return false
        val ok = runCatching { switchTo(context, style) }.isSuccess
        if (!ok) {
            val editor = prefs.edit()
            if (previous == null) editor.remove(KEY) else editor.putString(KEY, previous)
            editor.commit()
        }
        return ok
    }

    fun reopenPending(context: Context): Boolean = prefs(context).getBoolean("launcher_reopen_pending", false)

    fun reopenRequests(context: Context): Flow<Boolean> = callbackFlow {
        val preferences = prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "launcher_reopen_pending") trySend(reopenPending(context))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(reopenPending(context))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    @Synchronized
    fun selectAndRequestReopen(context: Context, style: Style): Boolean {
        if (!select(context, style)) return false
        return prefs(context).edit().putBoolean("launcher_reopen_pending", true).commit()
    }

    @Synchronized
    fun consumeReopen(context: Context): Boolean {
        if (!reopenPending(context)) return false
        return prefs(context).edit().remove("launcher_reopen_pending").commit()
    }

    /** Repairs a half-finished switch, for example after the process died between two calls on API 32 and lower. */
    @Synchronized
    fun reconcile(context: Context) {
        val wanted = saved(context)
        if (enabled(context) == listOf(wanted)) return
        runCatching { switchTo(context, wanted) }.onFailure {
            if (enabled(context).isEmpty()) runCatching { switchTo(context, Default) }
        }
    }

    private fun switchTo(context: Context, style: Style) {
        val pm = context.packageManager
        val target = component(context, style)
        val others = Style.entries.filter { it != style }.map { component(context, it) }
        if (Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(
                listOf(PackageManager.ComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, FLAGS)) +
                    others.map { PackageManager.ComponentEnabledSetting(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, FLAGS) },
            )
        } else {
            switchSequentially(context, style) { component, state -> pm.setComponentEnabledSetting(component, state, FLAGS) }
        }
    }

    /**
     * API 26 to 32 path, exposed for tests. Enables the new alias before disabling the others so the launcher never
     * sees zero entries, and restores the previous states if any call fails.
     */
    internal fun switchSequentially(context: Context, style: Style, set: (ComponentName, Int) -> Unit) {
        val pm = context.packageManager
        val before = Style.entries.associate { component(context, it) to pm.getComponentEnabledSetting(component(context, it)) }
        val target = component(context, style)
        try {
            set(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            before.keys.filter { it != target }.forEach { set(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
        } catch (error: RuntimeException) {
            val defaultComponent = component(context, Default)
            fun on(entry: Map.Entry<ComponentName, Int>) = entry.value == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (entry.value == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && entry.key == defaultComponent)
            before.entries.sortedByDescending(::on).forEach { entry ->
                runCatching { pm.setComponentEnabledSetting(entry.key, entry.value, FLAGS) }
            }
            throw error
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("sunlith", Context.MODE_PRIVATE)
}
