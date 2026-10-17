package dev.openeos.control.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.OkHttpClient
import okhttp3.Request

data class CameraMediaItem(val id: String, val name: String, val protected: Boolean? = false, val deleteWritable: Boolean? = true)
enum class CameraFeature { MEDIA_DELETE }
data class CameraUiState(
    val mediaItems: List<CameraMediaItem>,
    val mediaThumbnails: Map<String, String> = emptyMap(),
    val mediaThumbnailLoadingIds: Set<String> = emptySet(),
    val mediaPreviewItem: CameraMediaItem? = null,
    val mediaPreviewBytes: String? = null,
    val mediaPreviewLoading: Boolean = false,
    val mediaStreamSource: String? = null,
    val captureReviewItem: CameraMediaItem? = null,
    val captureReviewThumbnail: String? = null,
    val captureReviewLoading: Boolean = false,
    val lastDownloadedMediaName: String? = null,
    val lastDeletedMediaName: String? = null,
)
class Client(val mutationHttpClient: OkHttpClient) {
    val isRealCamera = true
    val enforceAdvertisedOperations = true
    val baseUrl = "http://synthetic.invalid"
    val observedFeatures = mutableSetOf<CameraFeature>()
    var ownerValid = true
    fun supportsMediaDelete() = true
    fun normalizeCameraResource(id: String) = id
    fun requireShutterReleased() { check(ownerValid) { "Synthetic retired owner" } }
    // HOST_DELETE
}
class ViewState(var state: CameraUiState) {
    fun confirm(item: CameraMediaItem) { state = state.withDeletedMedia(item) }
    // HOST_UI_DELETE
}
