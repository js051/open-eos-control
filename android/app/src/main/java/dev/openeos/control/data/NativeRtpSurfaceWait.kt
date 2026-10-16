package dev.openeos.control.data

/**
 * A one-shot wait for a usable preview Surface, distinct from decoder/presentation progress.
 * Decoded output releases Core readiness immediately; later input/output cannot extend this wait.
 * The session serializes these transitions with its decoder owner. No camera action lives here.
 */
internal class NativeRtpSurfaceWait(private val timeoutNanos: Long) {
    init { require(timeoutNanos > 0) }
    private var hasDecodedOutput = false
    private var attached = false
    private var renderingEnabled = true
    private var retired = false
    private var waitingSinceNanos: Long? = null

    @Synchronized fun onDecodedOutput(nowNanos: Long) {
        if (retired) return
        hasDecodedOutput = true
        beginIfNeeded(nowNanos)
    }

    @Synchronized fun onSurfaceAttached() {
        if (retired) return
        attached = true
        waitingSinceNanos = null
    }

    @Synchronized fun onSurfaceDetached(nowNanos: Long) {
        if (retired) return
        attached = false
        beginIfNeeded(nowNanos)
    }

    @Synchronized fun setRenderingEnabled(enabled: Boolean, nowNanos: Long) {
        if (retired || renderingEnabled == enabled) return
        renderingEnabled = enabled
        if (enabled) beginIfNeeded(nowNanos) else waitingSinceNanos = null
    }

    @Synchronized fun retire() {
        retired = true
        waitingSinceNanos = null
    }

    @Synchronized fun expired(nowNanos: Long): Boolean =
        !retired && waitingSinceNanos?.let { nowNanos - it >= timeoutNanos } == true

    private fun beginIfNeeded(nowNanos: Long) {
        if (hasDecodedOutput && renderingEnabled && !attached && waitingSinceNanos == null) {
            waitingSinceNanos = nowNanos
        }
    }
}
