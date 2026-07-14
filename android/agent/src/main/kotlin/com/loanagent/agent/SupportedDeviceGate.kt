package com.loanagent.agent

import android.os.Build
import java.util.Locale

/**
 * Fleet currently supports only Redmi Note 12 Turbo (CN model 23049RAD8C / codename marble).
 *
 * Do not accept bare "marble" alone — POCO F5 shares that codename.
 */
object SupportedDeviceGate {
    const val REQUIRED_LABEL = "Redmi Note 12 Turbo"

    data class Snapshot(
        val manufacturer: String,
        val brand: String,
        val model: String,
        val device: String,
        val product: String,
        val marketName: String?,
    ) {
        fun summaryLine(): String =
            listOfNotNull(
                manufacturer.takeIf { it.isNotBlank() },
                brand.takeIf { it.isNotBlank() && !it.equals(manufacturer, ignoreCase = true) },
                marketName?.takeIf { it.isNotBlank() } ?: model.takeIf { it.isNotBlank() },
                device.takeIf { it.isNotBlank() },
            ).joinToString(" / ")
    }

    fun snapshotFromBuild(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
        model: String = Build.MODEL.orEmpty(),
        device: String = Build.DEVICE.orEmpty(),
        product: String = Build.PRODUCT.orEmpty(),
        marketName: String? = readMarketName(),
    ): Snapshot = Snapshot(
        manufacturer = manufacturer,
        brand = brand,
        model = model,
        device = device,
        product = product,
        marketName = marketName,
    )

    fun isSupported(snapshot: Snapshot = snapshotFromBuild()): Boolean {
        val tokens = listOf(
            snapshot.model,
            snapshot.device,
            snapshot.product,
            snapshot.marketName.orEmpty(),
        ).joinToString(" ").lowercase(Locale.US)

        // CN retail model code
        if (tokens.contains("23049rad8c")) return true
        // Explicit market / model name — not bare "marble" (shared with POCO F5)
        if (tokens.contains("note 12 turbo") || tokens.contains("note12 turbo")) return true
        return false
    }

    fun unsupportedMessage(snapshot: Snapshot = snapshotFromBuild()): String =
        "当前仅支持 $REQUIRED_LABEL。\n" +
            "本机识别为：${snapshot.summaryLine().ifBlank { "未知型号" }}\n" +
            "不会连接云端心跳。请更换为 $REQUIRED_LABEL 后再安装。"

    private fun readMarketName(): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            val value = get.invoke(null, "ro.product.marketname", "") as? String
            value?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: ReflectiveOperationException) {
            null
        }
    }
}
