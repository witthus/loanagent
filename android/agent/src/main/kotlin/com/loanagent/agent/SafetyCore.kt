package com.loanagent.agent

import java.util.concurrent.atomic.AtomicLong

class WindowGenerationTracker {
    private val value = AtomicLong()
    private val lock = Any()
    private var lastPackageName: String? = null

    fun observePackage(packageName: String?): Long = synchronized(lock) {
        // Ignore null package events — OEM/system noise must not bump the lease generation.
        if (packageName == null) {
            return value.get()
        }
        if (packageName != lastPackageName) {
            lastPackageName = packageName
            value.incrementAndGet()
        } else {
            value.get()
        }
    }

    fun current(): Long = value.get()
}

object StrictSelectorParser {
    private const val MAX_VALUE_LENGTH = 256
    private val allowedKeys = setOf(
        "viewId",
        "text",
        "contentDescription",
        "className",
        "clickable",
    )
    private val locatingKeys = setOf("viewId", "text", "contentDescription", "className")

    fun parse(raw: String): Selector {
        val entries = raw.split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
        require(entries.isNotEmpty()) { "Empty selector" }
        val values = LinkedHashMap<String, String>()
        entries.forEach { entry ->
            val separator = entry.indexOf('=')
            require(separator > 0) { "Malformed selector entry" }
            val key = entry.substring(0, separator).trim()
            val value = entry.substring(separator + 1).trim()
            require(key in allowedKeys) { "Unknown selector key: $key" }
            require(key !in values) { "Duplicate selector key: $key" }
            require(value.isNotEmpty()) { "Empty selector value: $key" }
            require(value.length <= MAX_VALUE_LENGTH) { "Selector value too long: $key" }
            values[key] = value
        }
        require(values.keys.any(locatingKeys::contains)) {
            "Selector requires a locating field"
        }
        val clickable = values["clickable"]?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Invalid clickable")
            }
        }
        return Selector(
            viewId = values["viewId"],
            text = values["text"],
            contentDescription = values["contentDescription"],
            className = values["className"],
            clickable = clickable,
        )
    }
}
