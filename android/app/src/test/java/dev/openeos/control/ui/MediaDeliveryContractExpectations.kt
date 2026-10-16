package dev.openeos.control.ui

import org.json.JSONObject

/** The checked-in synthetic contract is bounded before parsing and has no I/O capabilities. */
internal object ContractExpectations {
    private val vectors = load()
    val closeAfterCancelConfirmed = expected("close_after_cancel", "saveConfirmed")
    val deleteCompletedOutput = expected("close_after_cancel", "deleteCompletedOutput")
    val cleanupFailureNeedsInspection = expected("cleanup_failed", "inspectDestination")
    val cleanupFailureConfirmed = expected("cleanup_failed", "saveConfirmed")
    val deleteAfterTerminalHistoryFailure = expected("terminal_write_failed_cold", "deleteCompletedOutput")

    private fun expected(id: String, key: String): Boolean =
        requireNotNull(vectors[id]).getJSONObject("expected").getBoolean(key)

    private fun load(): Map<String, JSONObject> {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/outcome-vectors.json")).use { it.readNBytes(32 * 1024 + 1) }
        require(bytes.size <= 32 * 1024)
        val contract = JSONObject(bytes.toString(Charsets.UTF_8))
        require(contract.getString("contract") == "open-eos-media-delivery-observation")
        require(contract.getInt("version") == 1)
        val limits = contract.getJSONObject("limits")
        require(limits.getInt("maximumVectors") == 32 && limits.getInt("maximumEventsPerVector") == 12)
        require(limits.getInt("maximumRows") == 100 && limits.getInt("maximumJSONBytes") == 32768)
        val invariants = contract.getJSONObject("invariants")
        for (name in listOf("cloudSyncConfirmed", "currentFileExistenceProvenByHistory",
            "cameraIdentityInferredFromFilename", "automaticResumeOnRead", "persistDestinationLocator", "persistSourceCapability")) {
            require(!invariants.getBoolean(name))
        }
        val values = contract.getJSONArray("vectors")
        require(values.length() in 1..32)
        val result = mutableMapOf<String, JSONObject>()
        for (index in 0 until values.length()) {
            val vector = values.getJSONObject(index)
            val id = vector.getString("id")
            require(id.isNotBlank() && id.length <= 80)
            require(vector.getJSONArray("events").length() in 1..12)
            require(vector.getJSONArray("targets").length() in 1..8)
            require(result.put(id, vector) == null)
        }
        return result
    }
}
