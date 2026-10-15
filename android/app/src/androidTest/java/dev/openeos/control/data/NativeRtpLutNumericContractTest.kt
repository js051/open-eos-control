package dev.openeos.control.data

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real external texture/GLES/Surface output against fixed shared bytes, independent of video decoding. */
@RunWith(AndroidJUnit4::class)
class NativeRtpLutNumericContractTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realGpuSurfaceMatchesSixFixedDisplayValueVectors() = runBlocking {
        val fixture = loadFixture()
        val definitions = fixture.getJSONArray("luts")
        val vectors = fixture.getJSONArray("vectors")
        val byteVectors = (0 until vectors.length()).map { vectors.getJSONObject(it) }.filter { it.has("inputRgb8") }
        assertEquals("All six shared decoded-byte cases must reach the real GPU", 6, byteVectors.size)
        val ready = CompletableFuture<Surface>()
        compose.setContent {
            AndroidView(factory = { context -> SurfaceView(context).also { view ->
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (width > 0 && height > 0) ready.complete(holder.surface)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
            } }, modifier = Modifier.size(128.dp, 96.dp))
        }
        val target = ready.get(8, TimeUnit.SECONDS)
        for (vector in byteVectors) {
            val id = vector.getString("id")
            val definition = (0 until definitions.length()).map { definitions.getJSONObject(it) }
                .single { it.getString("id") == vector.getString("lutId") }
            val lut = NativeVideoLut(definition.getInt("size"), definition.getJSONArray("domainMin").floats(),
                definition.getJSONArray("domainMax").floats(), definition.getJSONArray("values").floats())
            val input = vector.getJSONArray("inputRgb8").bytes()
            val expected = vector.getJSONArray("expectedRgb8").bytes()
            val presented = CompletableFuture<Unit>()
            val renderer = NativeRtpLutRenderer(target, lut, { true }, { presented.complete(Unit) }, {
                presented.completeExceptionally(AssertionError("$id: real GPU renderer failed"))
            })
            var bitmap: Bitmap? = null
            var primaryFailure: Throwable? = null
            try {
                val source = withTimeout(4_000) { renderer.inputSurface() }
                // A real opaque sRGB Canvas buffer enters the same external texture used by MediaCodec.
                // Uniform color deliberately separates LUT numeric evidence from decoder/orientation evidence.
                val canvas = source.lockCanvas(null)
                try { canvas.drawColor(Color.rgb(input[0], input[1], input[2])) }
                finally { source.unlockCanvasAndPost(canvas) }
                presented.get(4, TimeUnit.SECONDS)
                assertFalse("$id: GPU failed before PixelCopy", renderer.hasFailed)
                val captured = copy(target).also { bitmap = it }
                for ((x, y) in listOf(8 to 8, 55 to 8, 32 to 24, 8 to 39, 55 to 39)) {
                    val pixel = captured.getPixel(x, y)
                    val actual = intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    for (channel in 0..2) assertTrue(
                        "$id xy=$x,$y channel=$channel actual=${actual[channel]} expected=${expected[channel]} tolerance=3",
                        kotlin.math.abs(actual[channel] - expected[channel]) <= 3,
                    )
                }
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                bitmap?.recycle()
                // The renderer owns its input Surface; the activity owns the visible target Surface.
                runCatching { renderer.close() }.exceptionOrNull()?.let { cleanupFailure ->
                    primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                }
            }
        }
    }

    private fun loadFixture(): JSONObject {
        // A capped read also works on the minimum supported Android API; no read-all allocation.
        val buffer = ByteArray(64 * 1024 + 1)
        var count = 0
        InstrumentationRegistry.getInstrumentation().context.assets.open("numeric-vectors.json").use { stream ->
            while (count < buffer.size) {
                val read = stream.read(buffer, count, buffer.size - count)
                if (read < 0) break
                require(read > 0) { "Numeric fixture read stalled" }
                count += read
            }
        }
        require(count in 1..64 * 1024) { "Numeric LUT contract exceeds 64 KiB or is empty" }
        return JSONObject(String(buffer, 0, count, Charsets.UTF_8)).also { root ->
            assertEquals(1, root.getInt("schemaVersion"))
            assertEquals("srgb-display-values", root.getString("colorEncoding"))
            assertEquals("trilinear", root.getString("interpolation"))
            assertEquals("red-fastest", root.getString("tableOrder"))
            assertEquals("nearest-ties-up-after-output-clamp", root.getString("uint8Rounding"))
            assertEquals(12, root.getJSONArray("vectors").length())
            val luts = root.getJSONArray("luts")
            require(luts.length() in 1..16)
            val lutIds = mutableSetOf<String>()
            for (index in 0 until luts.length()) {
                val lut = luts.getJSONObject(index)
                val id = lut.getString("id")
                require(id.length in 1..120 && lutIds.add(id))
                val size = lut.getInt("size")
                require(size in 2..3 && lut.getDouble("size") == size.toDouble())
                val minimum = lut.getJSONArray("domainMin").floats()
                val maximum = lut.getJSONArray("domainMax").floats()
                require(minimum.size == 3 && maximum.size == 3)
                require((0..2).all { minimum[it] < maximum[it] })
                require(lut.getJSONArray("values").floats().size == size * size * size * 3)
            }
            val vectors = root.getJSONArray("vectors")
            val vectorIds = mutableSetOf<String>()
            for (index in 0 until vectors.length()) {
                val vector = vectors.getJSONObject(index)
                val id = vector.getString("id")
                require(id.length in 1..120 && vectorIds.add(id))
                require(vector.getString("lutId") in lutIds)
                require(vector.getJSONArray("inputRgb").floats().size == 3)
                val expected = vector.getJSONArray("expectedRgb").floats()
                require(expected.size == 3 && expected.all { it in 0f..1f })
                require(vector.has("inputRgb8") == vector.has("expectedRgb8"))
                if (vector.has("inputRgb8")) {
                    val inputBytes = vector.getJSONArray("inputRgb8").bytes()
                    vector.getJSONArray("expectedRgb8").bytes()
                    val inputRgb = vector.getJSONArray("inputRgb")
                    require((0..2).all { kotlin.math.abs(inputBytes[it] / 255.0 - inputRgb.getDouble(it)) <= 1e-15 })
                }
            }
        }
    }

    private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat().also { value -> require(value.isFinite()) } }

    private fun JSONArray.bytes(): IntArray {
        require(length() == 3)
        return IntArray(3) { index -> getInt(index).also { value ->
            require(value in 0..255 && getDouble(index) == value.toDouble())
        } }
    }

    private fun copy(surface: Surface): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val done = CompletableFuture<Int>()
        var submitted = false
        return try {
            PixelCopy.request(surface, bitmap, { done.complete(it) }, Handler(Looper.getMainLooper()))
            submitted = true
            assertEquals("Real GPU PixelCopy status", PixelCopy.SUCCESS, done.get(4, TimeUnit.SECONDS).toInt())
            bitmap
        } catch (failure: Throwable) {
            // PixelCopy may still write after a timeout. Retire only after its callback,
            // retaining at most this one bounded 64x48 bitmap if the callback never arrives.
            if (submitted) done.whenComplete { _, _ -> bitmap.recycle() } else bitmap.recycle()
            throw failure
        }
    }
}
