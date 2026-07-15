package com.loanagent.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface EffectLedger {
    fun claim(command: PlaybookCommand): LedgerClaim
    fun entry(taskId: String): LedgerEntry?
    fun pendingRecovery(): List<LedgerEntry> = emptyList()
    fun storeEffectCommitted(command: PlaybookCommand, result: PlaybookResult): Boolean
    fun storeFinal(command: PlaybookCommand, result: PlaybookResult): Boolean
    fun markReported(taskId: String): Boolean = false
    fun release(taskId: String)
}

enum class LedgerStage {
    STARTED,
    EFFECT_COMMITTED,
    FINAL,
    CORRUPT,
}

data class LedgerEntry(
    val taskId: String,
    val playbook: String,
    val effectClass: EffectClass,
    val stage: LedgerStage,
    val result: PlaybookResult? = null,
    val reported: Boolean = false,
)

sealed interface LedgerClaim {
    data object Acquired : LedgerClaim
    data object InFlight : LedgerClaim
    data object CommitFailed : LedgerClaim
    data class Existing(val entry: LedgerEntry) : LedgerClaim
    data class Corrupt(val reason: String) : LedgerClaim
}

class MemoryEffectLedger : EffectLedger {
    private val entries = LinkedHashMap<String, LedgerEntry>()
    private val active = mutableSetOf<String>()

    @Synchronized
    override fun claim(command: PlaybookCommand): LedgerClaim {
        val taskId = command.taskId.trim()
        if (taskId in active) return LedgerClaim.InFlight
        entries[taskId]?.let { existing ->
            active += taskId
            return LedgerClaim.Existing(existing)
        }
        entries[taskId] = LedgerEntry(
            taskId = taskId,
            playbook = command.playbook,
            effectClass = command.effectClass,
            stage = LedgerStage.STARTED,
        )
        active += taskId
        trim()
        return LedgerClaim.Acquired
    }

    @Synchronized
    override fun entry(taskId: String): LedgerEntry? = entries[taskId]

    @Synchronized
    override fun pendingRecovery(): List<LedgerEntry> =
        entries.values.filterNot { it.reported }

    @Synchronized
    override fun storeEffectCommitted(
        command: PlaybookCommand,
        result: PlaybookResult,
    ): Boolean {
        entries[command.taskId] = LedgerEntry(
            command.taskId,
            command.playbook,
            command.effectClass,
            LedgerStage.EFFECT_COMMITTED,
            result.copy(effectCommitted = true),
        )
        trim()
        return true
    }

    @Synchronized
    override fun storeFinal(command: PlaybookCommand, result: PlaybookResult): Boolean {
        entries[command.taskId] = LedgerEntry(
            command.taskId,
            command.playbook,
            command.effectClass,
            LedgerStage.FINAL,
            result,
        )
        trim()
        return true
    }

    @Synchronized
    override fun markReported(taskId: String): Boolean {
        val existing = entries[taskId] ?: return false
        entries[taskId] = existing.copy(reported = true)
        return true
    }

    @Synchronized
    override fun release(taskId: String) {
        active -= taskId
    }

    private fun trim() {
        while (entries.size > MAX_ENTRIES) {
            val first = entries.keys.first()
            entries.remove(first)
            active.remove(first)
        }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}

class SharedPreferencesEffectLedger(
    context: Context,
    private val prefsName: String = PREFS,
) : EffectLedger {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun claim(command: PlaybookCommand): LedgerClaim = synchronized(PROCESS_LOCK) {
        val taskId = command.taskId.trim()
        val activeKey = activeKey(taskId)
        if (activeKey in activeClaims) return@synchronized LedgerClaim.InFlight
        when (val stored = readEntry(taskId)) {
            StoredEntry.Missing -> Unit
            is StoredEntry.Valid -> {
                activeClaims += activeKey
                return@synchronized LedgerClaim.Existing(stored.entry)
            }
            is StoredEntry.Corrupt -> {
                activeClaims += activeKey
                return@synchronized LedgerClaim.Corrupt(stored.reason)
            }
        }
        val started = LedgerEntry(
            taskId = taskId,
            playbook = command.playbook,
            effectClass = command.effectClass,
            stage = LedgerStage.STARTED,
        )
        if (!write(started)) return@synchronized LedgerClaim.CommitFailed
        activeClaims += activeKey
        LedgerClaim.Acquired
    }

    override fun entry(taskId: String): LedgerEntry? = synchronized(PROCESS_LOCK) {
        when (val stored = readEntry(taskId)) {
            StoredEntry.Missing -> null
            is StoredEntry.Valid -> stored.entry
            is StoredEntry.Corrupt -> readRecovery(taskId)
        }
    }

    override fun pendingRecovery(): List<LedgerEntry> = synchronized(PROCESS_LOCK) {
        prefs.all.keys
            .asSequence()
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
            .mapNotNull { taskId ->
                when (val stored = readEntry(taskId)) {
                    StoredEntry.Missing -> null
                    is StoredEntry.Valid -> stored.entry
                    is StoredEntry.Corrupt -> readRecovery(taskId) ?: LedgerEntry(
                            taskId = taskId,
                            playbook = CORRUPT_PLAYBOOK,
                            effectClass = EffectClass.NON_IDEMPOTENT,
                            stage = LedgerStage.CORRUPT,
                            result = PlaybookResult.unknown(taskId),
                        )
                }
            }
            .filterNot { it.reported }
            .toList()
    }

    override fun storeEffectCommitted(
        command: PlaybookCommand,
        result: PlaybookResult,
    ): Boolean = synchronized(PROCESS_LOCK) {
        if (readEntry(command.taskId) is StoredEntry.Corrupt) {
            return@synchronized false
        }
        write(
            LedgerEntry(
                command.taskId,
                command.playbook,
                command.effectClass,
                LedgerStage.EFFECT_COMMITTED,
                result.copy(effectCommitted = true),
            ),
        )
    }

    override fun storeFinal(
        command: PlaybookCommand,
        result: PlaybookResult,
    ): Boolean = synchronized(PROCESS_LOCK) {
        val finalEntry = LedgerEntry(
            command.taskId,
            command.playbook,
            command.effectClass,
            LedgerStage.FINAL,
            result,
        )
        if (readEntry(command.taskId) is StoredEntry.Corrupt) {
            writeRecovery(finalEntry.copy(stage = LedgerStage.CORRUPT))
        } else {
            write(finalEntry)
        }
    }

    override fun markReported(taskId: String): Boolean = synchronized(PROCESS_LOCK) {
        when (val stored = readEntry(taskId)) {
            StoredEntry.Missing -> false
            is StoredEntry.Valid -> write(stored.entry.copy(reported = true))
            is StoredEntry.Corrupt -> {
                val recovery = readRecovery(taskId) ?: return@synchronized false
                writeRecovery(recovery.copy(reported = true))
            }
        }
    }

    override fun release(taskId: String) {
        synchronized(PROCESS_LOCK) {
            activeClaims -= activeKey(taskId)
        }
    }

    private fun write(entry: LedgerEntry): Boolean =
        prefs.edit().putString(key(entry.taskId), encode(entry).toString()).commit()

    private fun writeRecovery(entry: LedgerEntry): Boolean =
        prefs.edit().putString(recoveryKey(entry.taskId), encode(entry).toString()).commit()

    private fun readRecovery(taskId: String): LedgerEntry? {
        val raw = prefs.getString(recoveryKey(taskId), null) ?: return null
        return runCatching { decode(taskId, JSONObject(raw)) }.getOrNull()
    }

    private sealed interface StoredEntry {
        data object Missing : StoredEntry
        data class Valid(val entry: LedgerEntry) : StoredEntry
        data class Corrupt(val reason: String) : StoredEntry
    }

    private fun readEntry(taskId: String): StoredEntry {
        val raw = prefs.getString(key(taskId), null) ?: return StoredEntry.Missing
        if (!raw.trimStart().startsWith("{")) {
            return runCatching { StoredEntry.Valid(decodeLegacy(taskId, raw)) }
                .getOrElse { StoredEntry.Corrupt("LEGACY_DECODE_FAILED") }
        }
        return runCatching {
            StoredEntry.Valid(decode(taskId, JSONObject(raw)))
        }.getOrElse {
            StoredEntry.Corrupt("LEDGER_CORRUPT")
        }
    }

    private fun decodeLegacy(taskId: String, raw: String): LedgerEntry {
        val playbook = raw.substringBefore("|")
        val committed = raw.endsWith("|1")
        val result = if (committed) {
            PlaybookResult.succeeded(taskId, effectCommitted = true)
        } else {
            PlaybookResult.failed(taskId, "LEGACY_TERMINAL")
        }
        return LedgerEntry(
            taskId,
            playbook,
            if (committed) EffectClass.NON_IDEMPOTENT else EffectClass.IDEMPOTENT,
            LedgerStage.FINAL,
            result,
        )
    }

    private fun encode(entry: LedgerEntry): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("task_id", entry.taskId)
            .put("playbook", entry.playbook)
            .put("effect_class", entry.effectClass.name)
            .put("stage", entry.stage.name)
            .put("reported", entry.reported)
            .apply {
                entry.result?.let { put("result", encodeResult(it)) }
            }

    private fun encodeResult(result: PlaybookResult): JSONObject =
        JSONObject()
            .put("task_id", result.taskId)
            .put("success", result.success)
            .put("status", result.status)
            .put("effect_committed", result.effectCommitted)
            .apply {
                result.errorCode?.let { put("error_code", it) }
                result.resultPayload?.let { put("result_payload", toJson(it)) }
            }

    private fun decode(expectedTaskId: String, json: JSONObject): LedgerEntry {
        require(json.getInt("version") == VERSION)
        val taskId = json.getString("task_id")
        require(taskId == expectedTaskId)
        val playbook = json.getString("playbook")
        require(playbook.isNotBlank())
        val effectClass = EffectClass.valueOf(json.getString("effect_class"))
        val stage = LedgerStage.valueOf(json.getString("stage"))
        val result = json.optJSONObject("result")?.let(::decodeResult)
        require(stage == LedgerStage.STARTED || result != null)
        require(result == null || result.taskId == taskId)
        return LedgerEntry(
            taskId = taskId,
            playbook = playbook,
            effectClass = effectClass,
            stage = stage,
            result = result,
            reported = json.optBoolean("reported", false),
        )
    }

    private fun decodeResult(json: JSONObject): PlaybookResult =
        PlaybookResult(
            taskId = json.getString("task_id"),
            success = json.getBoolean("success"),
            status = json.getString("status"),
            errorCode = json.optString("error_code").ifEmpty { null },
            effectCommitted = json.optBoolean("effect_committed"),
            resultPayload = json.optJSONObject("result_payload")?.let(::toMap),
        )

    private fun toJson(value: Any?): Any =
        when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (key, nested) ->
                    if (key != null) put(key.toString(), toJson(nested))
                }
            }
            is Iterable<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            else -> value
        }

    private fun toMap(json: JSONObject): Map<String, Any?> =
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, fromJson(json.get(key)))
            }
        }

    private fun fromJson(value: Any?): Any? =
        when (value) {
            JSONObject.NULL -> null
            is JSONObject -> toMap(value)
            is JSONArray -> buildList {
                for (index in 0 until value.length()) add(fromJson(value.get(index)))
            }
            else -> value
        }

    private fun activeKey(taskId: String): String = "$prefsName:$taskId"

    private fun key(taskId: String): String = "$KEY_PREFIX$taskId"
    private fun recoveryKey(taskId: String): String = "$RECOVERY_KEY_PREFIX$taskId"

    companion object {
        private const val VERSION = 1
        private const val PREFS = "playbook_effect_ledger"
        private const val KEY_PREFIX = "task:"
        private const val RECOVERY_KEY_PREFIX = "task-recovery:"
        private const val CORRUPT_PLAYBOOK = "ledger_corrupt@1.0"
        private val PROCESS_LOCK = Any()
        private val activeClaims = mutableSetOf<String>()
    }
}
