package dev.openeos.control.data

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** MediaCodec -> external texture -> LUT -> the ORIGINAL visible Surface. No bitmap video overlay. */
internal class NativeRtpLutRenderer(
    private val target: Surface,
    private val lut: NativeVideoLut?,
    private val canPresent: (Long) -> Boolean,
    private val presented: (Long, NativeRtpFramePixels?) -> Unit,
    private val failed: () -> Unit,
    private val sampleEnabled: () -> Boolean = { false },
) {
    private val thread = HandlerThread("eos-rtp-lut").apply { start() }
    private val handler = Handler(thread.looper)
    private val presentationGate = NativeVideoPresentationGate().apply { configure(true) }
    private val closed = AtomicBoolean(false)
    private val broken = AtomicBoolean(false)
    private val pendingDraw = AtomicBoolean(false)
    private val input = CompletableDeferred<Surface>()
    private val retired = CompletableDeferred<Unit>()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private val textures = IntArray(2)
    private var program = 0
    private val transform = FloatArray(16)
    private val sampleFramebuffer = IntArray(1)
    private val sampleTexture = IntArray(1)
    private var lastSampleNanos = 0L
    private val effectiveLut = lut ?: NativeVideoLut(2, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f),
        (0..1).flatMap { b -> (0..1).flatMap { g -> (0..1).flatMap { r -> listOf(r.toFloat(), g.toFloat(), b.toFloat()) } } }.toFloatArray())

    val hasFailed: Boolean get() = broken.get()

    init {
        handler.post {
            try {
                initialize()
                if (closed.get()) throw IllegalStateException("Renderer retired during initialization")
                input.complete(requireNotNull(decoderSurface))
            } catch (_: Exception) {
                broken.set(true)
                input.completeExceptionally(IllegalStateException("Native LUT GPU setup unavailable"))
                failed()
            }
        }
    }

    suspend fun inputSurface(): Surface = input.await()

    /** Called only after the decoder stopped using its input Surface. Never releases a caller's Surface. */
    suspend fun close() = withContext(NonCancellable) {
        if (closed.compareAndSet(false, true)) {
            presentationGate.configure(false)
            handler.post {
                try { release(); retired.complete(Unit) }
                catch (error: Exception) { retired.completeExceptionally(error) }
                finally { thread.quitSafely() }
            }
        }
        retired.await()
    }

    private fun initialize() {
        check(target.isValid)
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0) && count[0] == 1)
        val config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        window = EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, window, window, context))
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().split(' ')
        check("GL_OES_EGL_image_external_essl3" in extensions)
        val maximum = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, maximum, 0)
        check(effectiveLut.size <= maximum[0])
        program = link(vertexShader, fragmentShader)
        GLES30.glGenTextures(2, textures, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textures[1])
        // Manual texelFetch interpolation does not depend on float-linear filtering extensions.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        for (axis in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, axis, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA32F, effectiveLut.size, effectiveLut.size, effectiveLut.size,
            0, GLES30.GL_RGBA, GLES30.GL_FLOAT, effectiveLut.rgbaTexture())
        GLES30.glGenFramebuffers(1, sampleFramebuffer, 0)
        GLES30.glGenTextures(1, sampleTexture, 0)
        checkGl()
        texture = SurfaceTexture(textures[0]).also { source ->
            source.setDefaultBufferSize(1920, 1080)
            // Callback only schedules one draw; the GL context stays on its dedicated thread.
            source.setOnFrameAvailableListener {
                if (!closed.get() && !broken.get() && pendingDraw.compareAndSet(false, true)) {
                    handler.post {
                        pendingDraw.set(false)
                        if (!closed.get() && !broken.get()) draw()
                    }
                }
            }
            decoderSurface = Surface(source)
        }
    }

    private fun draw() {
        try {
            val ticket = presentationGate.begin() ?: return
            val source = requireNotNull(texture)
            check(target.isValid && EGL14.eglMakeCurrent(display, window, window, context))
            source.updateTexImage()
            val presentationTimeUs = source.timestamp / 1_000L
            if (!canPresent(presentationTimeUs)) return
            source.getTransformMatrix(transform) // decoder crop/rotation; query for every image
            val width = IntArray(1); val height = IntArray(1)
            check(EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, width, 0))
            check(EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, height, 0))
            check(width[0] in 1..8192 && height[0] in 1..8192 && width[0].toLong() * height[0] <= 16_777_216)
            GLES30.glViewport(0, 0, width[0], height[0])
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
            GLES30.glUniform1i(location("source"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textures[1])
            GLES30.glUniform1i(location("cube"), 1)
            GLES30.glUniform1i(location("cubeSize"), effectiveLut.size)
            GLES30.glUniform3fv(location("domainMin"), 1, effectiveLut.domainMinimum(), 0)
            GLES30.glUniform3fv(location("domainMax"), 1, effectiveLut.domainMaximum(), 0)
            GLES30.glUniformMatrix4fv(location("textureTransform"), 1, false, transform, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGl()
            // Recheck retirement immediately before handing this frame to the visible Surface.
            if (!presentationGate.owns(ticket) || !canPresent(presentationTimeUs)) return
            // Read from this exact source texture and immutable LUT, before swap. Never
            // associate a later PixelCopy of the Surface with an earlier frame notification.
            val now = System.nanoTime()
            val sample = if (sampleEnabled() && now - lastSampleNanos >= 250_000_000L) {
                lastSampleNanos = now
                readFramePixels(width[0], height[0])
            } else null
            if (!presentationGate.owns(ticket) || !canPresent(presentationTimeUs)) return
            check(EGL14.eglSwapBuffers(display, window))
            presented(presentationTimeUs, sample)
        } catch (_: Exception) {
            if (broken.compareAndSet(false, true)) failed()
        }
    }

    private fun readFramePixels(width: Int, height: Int): NativeRtpFramePixels {
        val scale = minOf(1.0, 320.0 / maxOf(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sampleTexture[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sampleFramebuffer[0])
        try {
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, sampleTexture[0], 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            val rgba = ByteBuffer.allocateDirect(w * h * 4)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
            checkGl()
            val argb = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val offset = (y * w + x) * 4
                argb[(h - y - 1) * w + x] = (0xff shl 24) or
                    ((rgba.get(offset).toInt() and 255) shl 16) or
                    ((rgba.get(offset + 1).toInt() and 255) shl 8) or (rgba.get(offset + 2).toInt() and 255)
            }
            return NativeRtpFramePixels(w, h, argb)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, width, height)
        }
    }

    private fun location(name: String): Int = GLES30.glGetUniformLocation(program, name).also { check(it >= 0) }

    private fun release() {
        texture?.setOnFrameAvailableListener(null)
        decoderSurface?.release(); decoderSurface = null
        texture?.release(); texture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, window, window, context)
                if (program != 0) GLES30.glDeleteProgram(program)
                GLES30.glDeleteTextures(2, textures, 0)
                GLES30.glDeleteTextures(1, sampleTexture, 0)
                GLES30.glDeleteFramebuffers(1, sampleFramebuffer, 0)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }

    private fun checkGl() { check(GLES30.glGetError() == GLES30.GL_NO_ERROR) }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            try {
                GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
                val status = IntArray(1); GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0)
                return shader
            } catch (error: Exception) { GLES30.glDeleteShader(shader); throw error }
        }
        val v = compile(GLES30.GL_VERTEX_SHADER, vertex)
        var f = 0; var p = 0
        try {
            f = compile(GLES30.GL_FRAGMENT_SHADER, fragment); p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
            val status = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0)
            return p
        } catch (error: Exception) { if (p != 0) GLES30.glDeleteProgram(p); throw error }
        finally { GLES30.glDeleteShader(v); if (f != 0) GLES30.glDeleteShader(f) }
    }

    companion object {
        internal val vertexShader = """#version 300 es
            uniform mat4 textureTransform;
            out highp vec2 uv;
            void main() {
                vec2 p = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                uv = (textureTransform * vec4(p, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        internal val fragmentShader = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp sampler3D;
            uniform samplerExternalOES source;
            uniform sampler3D cube;
            uniform int cubeSize;
            uniform vec3 domainMin;
            uniform vec3 domainMax;
            in highp vec2 uv;
            out vec4 color;
            void main() {
                vec3 p = clamp((texture(source, uv).rgb-domainMin)/(domainMax-domainMin),0.0,1.0)*float(cubeSize-1);
                ivec3 lo=ivec3(floor(p)); ivec3 hi=min(lo+ivec3(1),ivec3(cubeSize-1)); vec3 t=fract(p);
                vec3 a=mix(texelFetch(cube,ivec3(lo.x,lo.y,lo.z),0).rgb,texelFetch(cube,ivec3(hi.x,lo.y,lo.z),0).rgb,t.x);
                vec3 b=mix(texelFetch(cube,ivec3(lo.x,hi.y,lo.z),0).rgb,texelFetch(cube,ivec3(hi.x,hi.y,lo.z),0).rgb,t.x);
                vec3 c=mix(texelFetch(cube,ivec3(lo.x,lo.y,hi.z),0).rgb,texelFetch(cube,ivec3(hi.x,lo.y,hi.z),0).rgb,t.x);
                vec3 d=mix(texelFetch(cube,ivec3(lo.x,hi.y,hi.z),0).rgb,texelFetch(cube,ivec3(hi.x,hi.y,hi.z),0).rgb,t.x);
                color=vec4(clamp(mix(mix(a,b,t.y),mix(c,d,t.y),t.z),0.0,1.0),1.0);
            }
        """.trimIndent()
    }
}
