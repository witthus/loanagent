package com.loanagent.agent

/**
 * Process-wide cloud identity / connection snapshot.
 * Written by Debug cloud bridge; readable from main UI without Debug class refs.
 */
data class BoundAccountSnapshot(
    val accountId: String,
    val displayName: String?,
    val role: String,
    val status: String,
)

data class CloudBridgeSnapshot(
    val bridgeRunning: Boolean = false,
    val controlPlaneHost: String? = null,
    val deviceDisplayName: String? = null,
    val geoLabel: String? = null,
    val boundAccount: BoundAccountSnapshot? = null,
    val lastHeartbeatAtMs: Long? = null,
    val lastHeartbeatOk: Boolean? = null,
    val lastHeartbeatError: String? = null,
    val lastPollAtMs: Long? = null,
    val lastPollOk: Boolean? = null,
    val lastPollError: String? = null,
    val mqttConnected: Boolean? = null,
    val wifiConnected: Boolean? = null,
    val cellularOk: Boolean? = null,
)

object CloudBridgeStatusHub {
    @Volatile
    private var snapshot: CloudBridgeSnapshot = CloudBridgeSnapshot()

    fun get(): CloudBridgeSnapshot = snapshot

    fun update(transform: (CloudBridgeSnapshot) -> CloudBridgeSnapshot) {
        synchronized(this) {
            snapshot = transform(snapshot)
        }
    }

    fun resetForTests() {
        snapshot = CloudBridgeSnapshot()
    }
}
