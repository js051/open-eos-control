package dev.openeos.control.ui

internal data class CaptureReviewEvidenceFixture(
    val schemaVersion: Int,
    val previousIds: Set<String>,
    val retryDelaysMillis: List<Long>,
    val maximumReads: Int,
    val cases: List<CaptureReviewEvidenceVector>,
    val retryCases: List<CaptureReviewEvidenceRetryVector>,
    val retirementCases: List<CaptureReviewEvidenceRetirementVector>,
)
internal data class CaptureReviewEvidenceVector(
    val id: String, val intent: String, val reads: List<String>, val expected: CaptureReviewEvidenceExpected,
)
internal data class CaptureReviewEvidenceExpected(
    val observation: String, val successfulReads: Int, val failedReads: Int,
    val lastCompletedRead: String?, val candidateId: String?,
)
internal data class CaptureReviewEvidenceRetryVector(
    val id: String, val first: List<String>, val second: List<String>, val expected: CaptureReviewEvidenceExpected,
)
internal data class CaptureReviewEvidenceRetirementVector(
    val id: String, val completedReads: Int, val publish: Boolean, val preservePrevious: Boolean,
)
