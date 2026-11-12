package io.github.sunsind.sunlith.system

import android.content.Context

/** Completion is local to this installation; calendar descriptions contain only the tag. */
class TodoStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("todo_done", Context.MODE_PRIVATE)

    fun isCompleted(key: String): Boolean = preferences.contains(key)

    fun setCompleted(key: String, completed: Boolean, now: Long = System.currentTimeMillis()) {
        preferences.edit().apply {
            if (completed) putLong(key, now) else remove(key)
        }.apply()
    }
}
