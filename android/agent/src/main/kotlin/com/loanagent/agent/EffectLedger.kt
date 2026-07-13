package com.loanagent.agent

import android.content.Context

interface EffectLedger {
    fun hasSeen(taskId: String): Boolean
    fun effectCommitted(taskId: String): Boolean
    fun record(taskId: String, playbook: String, effectCommitted: Boolean)
}

class MemoryEffectLedger : EffectLedger {
    private data class Entry(val playbook: String, val effectCommitted: Boolean)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    override fun hasSeen(taskId: String): Boolean = entries.containsKey(taskId)

    @Synchronized
    override fun effectCommitted(taskId: String): Boolean =
        entries[taskId]?.effectCommitted == true

    @Synchronized
    override fun record(taskId: String, playbook: String, effectCommitted: Boolean) {
        entries[taskId] = Entry(playbook, effectCommitted)
        while (entries.size > MAX_ENTRIES) {
            val first = entries.keys.first()
            entries.remove(first)
        }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}

class SharedPreferencesEffectLedger(
    context: Context,
) : EffectLedger {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun hasSeen(taskId: String): Boolean = prefs.contains(key(taskId))

    override fun effectCommitted(taskId: String): Boolean =
        prefs.getString(key(taskId), null)?.endsWith("|1") == true

    override fun record(taskId: String, playbook: String, effectCommitted: Boolean) {
        val flag = if (effectCommitted) "1" else "0"
        prefs.edit().putString(key(taskId), "$playbook|$flag").apply()
    }

    private fun key(taskId: String): String = "task:$taskId"

    companion object {
        private const val PREFS = "playbook_effect_ledger"
    }
}
