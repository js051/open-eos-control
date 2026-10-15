package dev.openeos.control.ui

internal data class MediaMutationFixture(
    val metadata: List<MetadataVector>,
    val readbacks: List<ReadbackVector>,
    val batches: List<BatchVector>,
    val rosters: List<RosterVector>,
)
internal data class MetadataVector(val id: String, val profile: String, val field: String, val raw: Any?, val expected: Any?)
internal data class ReadbackVector(val id: String, val profile: String, val field: String, val target: Int,
    val requestedId: String, val returnedIdPresent: Boolean, val returnedId: Any?, val raw: Any?, val expected: String)
internal data class BatchVector(val id: String, val events: List<String>, val outcomes: List<String>, val dispatched: List<Int>)
internal data class RosterVector(val id: String, val selected: List<String>, val loaded: Set<String>, val visible: Set<String>,
    val roster: List<String>, val eligible: List<String>, val hidden: Int, val unloaded: Int)

internal data class MediaMutationEligibilityFixture(val vectors: List<EligibilityVector>, val snapshots: List<EligibilitySnapshot>)
internal data class EligibilityVector(val id: String, val field: String, val requestedId: String, val loaded: Boolean,
    val supported: Boolean, val item: Map<String, Any?>, val eligible: Boolean)
internal data class EligibilitySnapshot(val id: String, val field: String, val selected: Map<String, Any?>,
    val loaded: Map<String, Any?>, val eligible: Boolean)
