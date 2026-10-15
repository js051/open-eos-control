package dev.openeos.control.ui

internal data class UploadOutcomeVector(val id: String, val dispatched: Boolean, val receipt: Boolean, val cancelled: Boolean, val observation: String, val expected: String)
internal data class UploadAdmissionVector(val id: String, val owner: String, val currentOwner: String?, val token: String, val currentToken: String?, val available: Boolean, val supported: Boolean, val busy: Boolean, val selected: Boolean, val expected: Boolean)
internal data class UploadPublicationVector(val id: String, val stage: String, val owner: String, val currentOwner: String?, val token: String, val currentToken: String?, val expected: Boolean)
internal data class UploadReceiptVector(val id: String, val status: Int, val bound: Boolean, val name: String, val size: Long, val raw: String, val expected: Boolean)
internal data class UploadJourneyVector(val id: String, val events: List<String>, val dispatches: Int, val outcome: String, val publish: Boolean, val replays: Int)
internal data class MediaUploadFixture(val outcomes: List<UploadOutcomeVector>, val admissions: List<UploadAdmissionVector>, val publications: List<UploadPublicationVector>, val receipts: List<UploadReceiptVector>, val journeys: List<UploadJourneyVector>)
