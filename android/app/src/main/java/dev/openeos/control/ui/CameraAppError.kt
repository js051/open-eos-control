package dev.openeos.control.ui

import androidx.annotation.StringRes
import dev.openeos.control.R

/** App-authored failures keep their identity until the current UI locale renders them. */
internal enum class CameraAppError(@StringRes val messageResource: Int) {
    MAINTENANCE_RETIREMENT_UNCONFIRMED(R.string.maintenance_session_retirement_unconfirmed),
    UPLOAD_FILENAME_UNKNOWN(R.string.camera_error_upload_filename_unknown),
    UPLOAD_SOURCE_UNAVAILABLE(R.string.camera_error_upload_source_unavailable),
    UPLOAD_TOO_LARGE(R.string.camera_error_upload_too_large),
    UPLOAD_SIZE_INVALID(R.string.camera_error_upload_size_invalid),
    UPLOAD_RECEIPT_MISSING(R.string.camera_error_upload_receipt_missing),
    DOWNLOAD_DESTINATION_UNAVAILABLE(R.string.camera_error_download_destination_unavailable),
    DOWNLOAD_DOCUMENT_CREATE_FAILED(R.string.camera_error_download_document_create_failed),
    GALLERY_CREATE_FAILED(R.string.camera_error_gallery_create_failed),
    GALLERY_OUTPUT_UNAVAILABLE(R.string.camera_error_gallery_output_unavailable),
    DOWNLOAD_INCOMPLETE(R.string.camera_error_download_incomplete),
    DOWNLOAD_LENGTH_MISMATCH(R.string.camera_error_download_length_mismatch),
    GALLERY_PUBLISH_FAILED(R.string.camera_error_gallery_publish_failed),
    AUTOMATIC_JPEG_NOT_PUBLISHED(R.string.camera_error_automatic_jpeg_not_published),
    ;

    // CameraUiState already owns a nullable String error and its clearing/retirement lifecycle.
    // Only an entire app-owned token is resolved; never translate a matching diagnostic substring.
    val messageToken: String get() = "[OpenEOS.CameraAppError:$name]"
}

internal class CameraAppException(val error: CameraAppError) : IllegalStateException(error.name)

@StringRes
internal fun cameraAppErrorResource(message: String): Int? =
    CameraAppError.entries.firstOrNull { it.messageToken == message }?.messageResource
