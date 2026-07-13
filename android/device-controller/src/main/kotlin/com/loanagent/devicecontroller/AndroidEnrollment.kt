package com.loanagent.devicecontroller

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.security.MessageDigest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AndroidEnrollmentIdentity(private val context: Context) {
    fun read(): EnrollmentDeviceIdentity {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        val deviceId = MessageDigest.getInstance("SHA-256")
            .digest("${context.packageName}:$androidId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return EnrollmentDeviceIdentity(
            deviceId = deviceId,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            controllerVersion = controllerVersion(),
        )
    }

    @Suppress("DEPRECATION")
    private fun controllerVersion(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return packageInfo.versionName ?: "unknown"
    }
}

class HttpsEnrollmentClient(
    private val resolver: HostAddressResolver = HostAddressResolver(
        java.net.InetAddress::getAllByName,
    ),
) : EnrollmentGateway {
    override fun enroll(request: EnrollmentRequest): EnrollmentOutcome {
        val validation = TrustedDownloadPolicy(
            trustedHosts = setOf(request.trustedHost),
            resolver = resolver,
        ).validateAndResolve(request.endpoint)
        if (validation.code != DownloadUrlValidationCode.VALID) {
            return if (validation.code == DownloadUrlValidationCode.DNS_FAILED) {
                EnrollmentOutcome.NETWORK_ERROR
            } else {
                EnrollmentOutcome.SERVER_ERROR
            }
        }
        return runCatching { execute(request, validation) }
            .getOrElse { EnrollmentOutcome.NETWORK_ERROR }
    }

    private fun execute(
        request: EnrollmentRequest,
        validation: DownloadUrlValidationResult,
    ): EnrollmentOutcome {
        val body = JSONObject()
            .put("token", request.token)
            .put(
                "device",
                JSONObject()
                    .put("device_id", request.identity.deviceId)
                    .put("manufacturer", request.identity.manufacturer)
                    .put("model", request.identity.model)
                    .put("android_version", request.identity.androidVersion)
                    .put("controller_version", request.identity.controllerVersion),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        val client = PinnedHttpsClientFactory.create(
            validation,
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS,
        )
        val httpRequest = Request.Builder()
            .url(request.endpoint)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(httpRequest).execute().use { response ->
            when (response.code) {
                200 -> EnrollmentOutcome.SUCCESS
                409 ->
                    EnrollmentOutcome.TOKEN_DEVICE_CONFLICT

                410 -> EnrollmentOutcome.TOKEN_EXPIRED
                401 -> EnrollmentOutcome.TOKEN_INVALID
                in 500..599 -> EnrollmentOutcome.SERVER_ERROR
                else -> EnrollmentOutcome.SERVER_ERROR
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 30_000L
    }
}
