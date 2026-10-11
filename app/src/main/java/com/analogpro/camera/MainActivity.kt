package com.analogpro.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.math.*
import kotlin.random.Random

// ============================================================================
// 1. MODELOS DE DATOS: FORMATOS, ASPECTO, LENTES, PELÍCULAS Y VFX
// ============================================================================

enum class CaptureFormat(val label: String) {
    JPG("JPG"),
    PNG("PNG"),
    RAW("RAW (.DNG)"),
    RAW_JPG("RAW+JPG")
}

enum class AspectRatioMode(val label: String, val ratioPortrait: Float) {
    RATIO_16_9("16:9", 9f / 16f),
    RATIO_4_3("4:3", 3f / 4f),
    OPEN_GATE("OPEN GATE", 0f) // 0f = Usa todo el área activa del sensor sin recortar
}

enum class FlashMode(val label: String) {
    OFF("OFF"),
    AUTO("AUTO"),
    ON("ON"),
    TORCH("LUZ CONTINUA"),
    DISPOSABLE("FLASH DESECHABLE")
}

enum class ActiveDockMenu {
    NONE, FILMS, VFX, PRO, LENS
}

data class HighlightPoint(
    val normX: Float, // 0f..1f
    val normY: Float, // 0f..1f
    val intensity: Float // 0f..1f
)

data class LiveLightAnalysis(
    val sunX: Float = 0.25f,
    val sunY: Float = 0.20f,
    val peakIntensity: Float = 0.6f,
    val avgLuminance: Float = 110f,
    val isLowLight: Boolean = false,
    val highlights: List<HighlightPoint> = emptyList()
)

data class CameraLensOption(
    val cameraId: String,
    val title: String,
    val facing: Int,
    val focalLengthMm: Float,
    val aperture: Float,
    val maxMp: String
)

data class FilmPreset(
    val id: String,
    val brand: String,
    val name: String,
    val isoTag: String,
    val matrix: FloatArray,
    val defaultGrain: Float,
    val defaultNoise: Float,
    val vignette: Float,
    val disposableFlashLook: Boolean = false
)

val BUILT_IN_PRESETS = listOf(
    FilmPreset(
        "normal_std", "STD", "Natural Sensor", "ISO 100",
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ),
        defaultGrain = 0.0f, defaultNoise = 0.0f, vignette = 0.0f
    ),
    FilmPreset(
        "fuji_cc", "FUJIFILM", "Classic Chrome", "ISO 200",
        floatArrayOf(
            0.95f, 0.05f, 0.00f, 0f, -5f,
            0.02f, 0.92f, 0.06f, 0f, -5f,
            0.00f, 0.08f, 0.88f, 0f,  5f,
            0f,    0f,    0f,    1f,  0f
        ),
        defaultGrain = 0.22f, defaultNoise = 0.08f, vignette = 0.22f
    ),
    FilmPreset(
        "fuji_velvia", "FUJIFILM", "Velvia 50", "ISO 50",
        floatArrayOf(
            1.25f, -0.10f, -0.05f, 0f, -8f,
           -0.05f,  1.20f, -0.05f, 0f, -5f,
           -0.05f, -0.10f,  1.30f, 0f, -5f,
            0f,     0f,     0f,    1f,  0f
        ),
        defaultGrain = 0.12f, defaultNoise = 0.04f, vignette = 0.20f
    ),
    FilmPreset(
        "fuji_acros", "FUJIFILM", "Neopan Acros", "ISO 100",
        floatArrayOf(
            0.35f, 0.55f, 0.10f, 0f, -10f,
            0.35f, 0.55f, 0.10f, 0f, -10f,
            0.35f, 0.55f, 0.10f, 0f, -10f,
            0f,    0f,    0f,    1f,   0f
        ),
        defaultGrain = 0.28f, defaultNoise = 0.05f, vignette = 0.30f
    ),
    FilmPreset(
        "kodak_portra", "KODAK", "Portra 400", "ISO 400",
        floatArrayOf(
            1.08f, 0.04f, -0.02f, 0f, 8f,
            0.02f, 1.02f,  0.00f, 0f, 4f,
           -0.02f, 0.02f,  0.92f, 0f, -4f,
            0f,    0f,     0f,    1f, 0f
        ),
        defaultGrain = 0.25f, defaultNoise = 0.10f, vignette = 0.18f
    ),
    FilmPreset(
        "kodak_gold", "KODAK", "Gold 200", "ISO 200",
        floatArrayOf(
            1.14f, 0.05f, 0.00f, 0f, 12f,
            0.03f, 1.05f, 0.00f, 0f,  8f,
            0.00f, 0.00f, 0.84f, 0f, -8f,
            0f,    0f,    0f,    1f,  0f
        ),
        defaultGrain = 0.32f, defaultNoise = 0.15f, vignette = 0.25f
    ),
    FilmPreset(
        "kodak_ektar", "KODAK", "Ektar 100", "ISO 100",
        floatArrayOf(
            1.18f, -0.04f, -0.02f, 0f, -4f,
           -0.02f,  1.10f, -0.02f, 0f, -2f,
           -0.04f, -0.02f,  1.15f, 0f,  2f,
            0f,     0f,     0f,    1f,  0f
        ),
        defaultGrain = 0.14f, defaultNoise = 0.05f, vignette = 0.15f
    ),
    FilmPreset(
        "fuji_quicksnap", "DESECHABLE", "Fuji QuickSnap", "ISO 400",
        floatArrayOf(
            0.98f, 0.08f, 0.02f, 0f, 6f,
            0.04f, 1.08f, 0.04f, 0f, 12f,
            0.02f, 0.06f, 0.90f, 0f, 8f,
            0f,    0f,    0f,    1f, 0f
        ),
        defaultGrain = 0.45f, defaultNoise = 0.25f, vignette = 0.50f, disposableFlashLook = true
    ),
    FilmPreset(
        "kodak_funsaver", "DESECHABLE", "Kodak FunSaver", "ISO 800",
        floatArrayOf(
            1.15f, 0.06f, 0.00f, 0f, 14f,
            0.04f, 1.00f, 0.02f, 0f, 6f,
            0.00f, 0.04f, 0.82f, 0f, 10f,
            0f,    0f,    0f,    1f, 0f
        ),
        defaultGrain = 0.55f, defaultNoise = 0.32f, vignette = 0.55f, disposableFlashLook = true
    )
)

val BUILT_IN_ASSET_LUTS = listOf(
    "Fujifilm_Classic_Neg.cube",
    "Kodak_Vision3_500T.cube",
    "Kodak_TriX_400_BW.cube",
    "Disposable_Warm_Flash.cube"
)

data class SensorDiagnostic(
    val cameraId: String,
    val facing: String,
    val hardwareLevel: String,
    val maxResolutionMp: String,
    val maxJpegSize: Size,
    val maxVideoSize: Size,
    val maxRawSize: Size?,
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val physicalSizeMm: String,
    val isoRange: Range<Int>,
    val exposureTimeRangeNs: Range<Long>,
    val evRange: Range<Int>,
    val evStep: Float,
    val minFocusDistance: Float,
    val maxZoom: Float,
    val maxFlashStrength: Int,
    val apertures: FloatArray
)

// ============================================================================
// 2. GENERADOR DE IMÁGENES REPRESENTATIVAS PARA CADA FILM / LUT
// ============================================================================

object FilmThumbnailGenerator {
    private val cache = mutableMapOf<String, Bitmap>()
    private var baseSampleScene: Bitmap? = null

    private fun getBaseSample(): Bitmap {
        baseSampleScene?.let { return it }
        val w = 160
        val h = 120
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Cielo atardecer / horizonte cinematográfico
        p.shader = LinearGradient(
            0f, 0f, 0f, h * 0.65f,
            intArrayOf(
                android.graphics.Color.rgb(35, 85, 145),
                android.graphics.Color.rgb(185, 130, 110),
                android.graphics.Color.rgb(240, 180, 120)
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h * 0.65f, p)

        // Sol cálido
        p.shader = null
        p.color = android.graphics.Color.rgb(255, 225, 160)
        c.drawCircle(w * 0.72f, h * 0.32f, 16f, p)

        // Montañas / Paisaje verde y tonos piel/tierra
        p.color = android.graphics.Color.rgb(45, 95, 65)
        val path = Path().apply {
            moveTo(0f, h.toFloat())
            lineTo(0f, h * 0.58f)
            lineTo(w * 0.38f, h * 0.42f)
            lineTo(w * 0.75f, h * 0.62f)
            lineTo(w.toFloat(), h * 0.48f)
            lineTo(w.toFloat(), h.toFloat())
            close()
        }
        c.drawPath(path, p)

        // Franja de color retrato / cálido y rojo clásico para apreciar la respuesta del film
        p.color = android.graphics.Color.rgb(215, 70, 55)
        c.drawCircle(w * 0.28f, h * 0.74f, 14f, p)
        p.color = android.graphics.Color.rgb(235, 185, 155)
        c.drawCircle(w * 0.52f, h * 0.76f, 14f, p)

        baseSampleScene = bmp
        return bmp
    }

    fun getThumbnailForPreset(preset: FilmPreset): Bitmap {
        cache[preset.id]?.let { return it }
        val src = getBaseSample()
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(preset.matrix))
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        cache[preset.id] = out
        return out
    }
}

// ============================================================================
// 3. PARSER DE ARCHIVOS 3D LUT (.CUBE)
// ============================================================================

class CubeLut3D(val name: String, val size: Int, val table: FloatArray) {
    fun lookup(r: Int, g: Int, b: Int): Int {
        val rf = (r / 255f) * (size - 1)
        val gf = (g / 255f) * (size - 1)
        val bf = (b / 255f) * (size - 1)

        val ri = rf.toInt().coerceIn(0, size - 1)
        val gi = gf.toInt().coerceIn(0, size - 1)
        val bi = bf.toInt().coerceIn(0, size - 1)

        val idx = ((bi * size * size) + (gi * size) + ri) * 3
        if (idx + 2 >= table.size) return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        val outR = (table[idx] * 255f).toInt().coerceIn(0, 255)
        val outG = (table[idx + 1] * 255f).toInt().coerceIn(0, 255)
        val outB = (table[idx + 2] * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
    }
}

object CubeLutParser {
    fun parseStream(stream: InputStream, customName: String): CubeLut3D? {
        return try {
            val reader = BufferedReader(InputStreamReader(stream))
            var lutSize = 17
            val values = ArrayList<Float>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("TITLE") || trimmed.startsWith("DOMAIN")) continue
                if (trimmed.startsWith("LUT_3D_SIZE")) {
                    lutSize = trimmed.split("\\s+".toRegex())[1].toInt()
                    continue
                }
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size >= 3) {
                    val r = parts[0].toFloatOrNull()
                    val g = parts[1].toFloatOrNull()
                    val b = parts[2].toFloatOrNull()
                    if (r != null && g != null && b != null) {
                        values.add(r)
                        values.add(g)
                        values.add(b)
                    }
                }
            }
            if (values.isEmpty()) null else CubeLut3D(customName, lutSize, values.toFloatArray())
        } catch (e: Exception) {
            null
        }
    }

    fun parseUri(context: Context, uri: Uri, customName: String): CubeLut3D? {
        return context.contentResolver.openInputStream(uri)?.use { parseStream(it, customName) }
    }

    fun parseAsset(context: Context, assetFileName: String): CubeLut3D? {
        return context.assets.open("luts/$assetFileName").use {
            parseStream(it, assetFileName.removeSuffix(".cube").replace("_", " "))
        }
    }
}

// ============================================================================
// 4. ANALIZADOR DE LUZ EN TIEMPO REAL Y MOTOR DE REVELADO + VFX INTERACTIVOS
// ============================================================================

object AnalogImageProcessor {

    /**
     * Analiza un pequeño bitmap del visor en tiempo real para detectar:
     * 1. La posición del Sol / fuente de luz principal (sunX, sunY).
     * 2. Los puntos de altas luces (highlights) donde deben engancharse los Flares Anamórficos.
     * 3. Si la escena está en Baja Luz para sugerir el Flash.
     */
    fun analyzeSceneLight(smallBmp: Bitmap): LiveLightAnalysis {
        val w = smallBmp.width
        val h = smallBmp.height
        if (w <= 0 || h <= 0) return LiveLightAnalysis()

        val pixels = IntArray(w * h)
        smallBmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumLuma = 0f
        var maxLuma = 0f
        var weightedX = 0f
        var weightedY = 0f
        var weightSum = 0f
        val rawHighlights = mutableListOf<HighlightPoint>()

        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val p = pixels[y * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                sumLuma += luma

                if (luma > maxLuma) {
                    maxLuma = luma
                }
                if (luma > 200f) {
                    val wgt = (luma - 195f).pow(2)
                    weightedX += (x.toFloat() / w) * wgt
                    weightedY += (y.toFloat() / h) * wgt
                    weightSum += wgt
                }
                if (luma > 228f) {
                    rawHighlights.add(
                        HighlightPoint(
                            normX = x.toFloat() / w,
                            normY = y.toFloat() / h,
                            intensity = ((luma - 220f) / 35f).coerceIn(0.2f, 1f)
                        )
                    )
                }
            }
        }

        val count = ((w / 2) * (h / 2)).coerceAtLeast(1)
        val avgLuma = sumLuma / count
        val sunX = if (weightSum > 0f) (weightedX / weightSum).coerceIn(0.05f, 0.95f) else 0.25f
        val sunY = if (weightSum > 0f) (weightedY / weightSum).coerceIn(0.05f, 0.95f) else 0.20f

        // Filtrar los puntos de altas luces más intensos y separados entre sí para los Flares Anamórficos
        val filteredHighlights = mutableListOf<HighlightPoint>()
        for (pt in rawHighlights.sortedByDescending { it.intensity }) {
            if (filteredHighlights.none { hypot(it.normX - pt.normX, it.normY - pt.normY) < 0.14f }) {
                filteredHighlights.add(pt)
                if (filteredHighlights.size >= 6) break
            }
        }

        return LiveLightAnalysis(
            sunX = sunX,
            sunY = sunY,
            peakIntensity = (maxLuma / 255f).coerceIn(0.2f, 1f),
            avgLuminance = avgLuma,
            isLowLight = avgLuma < 44f,
            highlights = filteredHighlights
        )
    }

    fun processImage(
        source: Bitmap,
        preset: FilmPreset,
        customLut: CubeLut3D?,
        grainStrength: Float,
        noiseStrength: Float,
        lightLeakStrength: Float,
        anamorphicFlareStrength: Float,
        opticalFlareStrength: Float,
        macroBlurStrength: Float,
        focusNormX: Float,
        focusNormY: Float,
        lightAnalysis: LiveLightAnalysis,
        exposureBias: Float,
        polaroidEnabled: Boolean,
        disposableFlashActive: Boolean,
        metadataStamp: String
    ): Bitmap {
        val width = source.width
        val height = source.height
        val workingBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(workingBitmap)

        // 1. Matriz de Color (Fujifilm / Kodak) + Compensación de Exposición
        val expScale = 2.0f.pow(exposureBias)
        val expMatrix = ColorMatrix(
            floatArrayOf(
                expScale, 0f, 0f, 0f, 0f,
                0f, expScale, 0f, 0f, 0f,
                0f, 0f, expScale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val filmMatrix = ColorMatrix(preset.matrix)
        filmMatrix.preConcat(expMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(filmMatrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)

        // 2. Aplicar 3D LUT (.cube) si está activo
        if (customLut != null) {
            val pixels = IntArray(width * height)
            workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                pixels[i] = customLut.lookup(r, g, b)
            }
            workingBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }

        // 3. Desenfoque de Escena / Bokeh para Objetos Cercanos (Macro Depth Blur alrededor del punto enfocado)
        if (macroBlurStrength > 0.02f) {
            val smallW = (width / 10).coerceAtLeast(64)
            val smallH = (height / 10).coerceAtLeast(64)
            val blurredSmall = Bitmap.createScaledBitmap(workingBitmap, smallW, smallH, true)
            val blurredFull = Bitmap.createScaledBitmap(blurredSmall, width, height, true)
            blurredSmall.recycle()

            val maskBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(maskBmp)
            maskCanvas.drawBitmap(blurredFull, 0f, 0f, null)
            blurredFull.recycle()

            val fx = focusNormX * width
            val fy = focusNormY * height
            val radius = max(width, height) * (0.48f - macroBlurStrength * 0.22f)
            val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                shader = RadialGradient(
                    fx, fy, radius,
                    intArrayOf(
                        android.graphics.Color.BLACK,
                        android.graphics.Color.argb(180, 0, 0, 0),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            maskCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clearPaint)
            canvas.drawBitmap(maskBmp, 0f, 0f, null)
            maskBmp.recycle()
        }

        // 4. Simulación de Flash de Cámara Desechable
        if (disposableFlashActive || preset.disposableFlashLook) {
            val flashGradient = RadialGradient(
                width / 2f, height / 2f, max(width, height) * 0.65f,
                intArrayOf(
                    android.graphics.Color.argb(60, 255, 248, 225),
                    android.graphics.Color.argb(0, 0, 0, 0),
                    android.graphics.Color.argb(150, 8, 18, 14)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            val flashPaint = Paint().apply {
                shader = flashGradient
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }

        // 5. VFX: LIGHT LEAKS INTERACTIVOS (Responden a la posición real del Sol / Luz en la cámara)
        if (lightLeakStrength > 0.02f) {
            val sx = lightAnalysis.sunX * width
            val sy = lightAnalysis.sunY * height
            val leakRadius = max(width, height) * (0.65f + lightLeakStrength * 0.45f)
            val alphaMain = (lightLeakStrength * 185 * lightAnalysis.peakIntensity.coerceAtLeast(0.45f)).toInt().coerceIn(0, 235)
            val alphaSecondary = (alphaMain * 0.65f).toInt()

            val leakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                shader = RadialGradient(
                    sx, sy, leakRadius,
                    intArrayOf(
                        android.graphics.Color.argb(alphaMain, 255, 140, 40),
                        android.graphics.Color.argb(alphaSecondary, 255, 55, 30),
                        android.graphics.Color.argb((alphaSecondary * 0.4f).toInt(), 210, 20, 90),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.35f, 0.70f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), leakPaint)
        }

        // 6. VFX: ANAMORPHIC LIGHT LEAKS & STREAKS (Enganchados a los puntos de altas luces detectados)
        if (anamorphicFlareStrength > 0.02f) {
            val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            }
            val points = if (lightAnalysis.highlights.isNotEmpty()) {
                lightAnalysis.highlights
            } else {
                listOf(HighlightPoint(lightAnalysis.sunX, lightAnalysis.sunY, 0.8f))
            }

            for (pt in points) {
                val hx = pt.normX * width
                val hy = pt.normY * height
                val streakHalfW = width * (0.42f + 0.38f * anamorphicFlareStrength * pt.intensity)
                val streakHalfH = (height * 0.018f * (0.6f + anamorphicFlareStrength)).coerceAtLeast(4f)
                val aCore = (anamorphicFlareStrength * pt.intensity * 225).toInt().coerceIn(40, 245)

                streakPaint.shader = LinearGradient(
                    hx - streakHalfW, hy, hx + streakHalfW, hy,
                    intArrayOf(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.argb((aCore * 0.45f).toInt(), 0, 175, 255),
                        android.graphics.Color.argb(aCore, 165, 235, 255),
                        android.graphics.Color.argb((aCore * 0.45f).toInt(), 0, 175, 255),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.32f, 0.5f, 0.68f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawOval(
                    RectF(hx - streakHalfW, hy - streakHalfH, hx + streakHalfW, hy + streakHalfH),
                    streakPaint
                )
            }
        }

        // 7. VFX: FLARES ÓPTICOS (Vector desde el sol pasando por el centro óptico del lente)
        if (opticalFlareStrength > 0.02f) {
            val sx = lightAnalysis.sunX * width
            val sy = lightAnalysis.sunY * height
            val cx = width / 2f
            val cy = height / 2f
            val vx = cx - sx
            val vy = cy - sy

            val flarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            }
            val ghosts = listOf(0.35f, 0.75f, 1.15f, 1.55f)
            val baseAlpha = (opticalFlareStrength * 145).toInt().coerceIn(20, 200)

            for ((idx, factor) in ghosts.withIndex()) {
                val gx = sx + vx * factor
                val gy = sy + vy * factor
                val rad = min(width, height) * (0.04f + idx * 0.025f)
                flarePaint.shader = RadialGradient(
                    gx, gy, rad,
                    intArrayOf(
                        android.graphics.Color.argb(baseAlpha, 255, 210, 130),
                        android.graphics.Color.argb((baseAlpha * 0.6f).toInt(), 110, 220, 190),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.7f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(gx, gy, rad, flarePaint)
            }
        }

        // 8. Viñeteo Óptico
        if (preset.vignette > 0.01f) {
            val vigAlpha = (preset.vignette * 190).toInt().coerceIn(0, 255)
            val vigGradient = RadialGradient(
                width / 2f, height / 2f, max(width, height) * 0.72f,
                intArrayOf(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.argb(vigAlpha, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            val vigPaint = Paint().apply { shader = vigGradient }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vigPaint)
        }

        // 9. Generador de Grano de Película y Ruido Cromático
        if (grainStrength > 0.01f || noiseStrength > 0.01f) {
            val overlayW = (width / 2).coerceIn(256, 720)
            val overlayH = (height / 2).coerceIn(256, 720)
            val grainPixels = IntArray(overlayW * overlayH)
            val grainAmp = (grainStrength * 95).toInt()
            val chromaAmp = (noiseStrength * 85).toInt()
            val rnd = Random(System.currentTimeMillis())

            for (i in grainPixels.indices) {
                val mono = if (grainAmp > 0) rnd.nextInt(-grainAmp, grainAmp) else 0
                val cr = if (chromaAmp > 0) rnd.nextInt(-chromaAmp, chromaAmp) else 0
                val cg = if (chromaAmp > 0) rnd.nextInt(-chromaAmp, chromaAmp) else 0
                val cb = if (chromaAmp > 0) rnd.nextInt(-chromaAmp, chromaAmp) else 0

                val r = (128 + mono + cr).coerceIn(0, 255)
                val g = (128 + mono + cg).coerceIn(0, 255)
                val b = (128 + mono + cb).coerceIn(0, 255)
                val alpha = ((grainStrength + noiseStrength).coerceAtMost(1f) * 95).toInt()
                grainPixels[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }

            val grainBmp = Bitmap.createBitmap(grainPixels, overlayW, overlayH, Bitmap.Config.ARGB_8888)
            val grainPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
            }
            canvas.drawBitmap(
                grainBmp,
                Rect(0, 0, overlayW, overlayH),
                Rect(0, 0, width, height),
                grainPaint
            )
            grainBmp.recycle()
        }

        // 10. Marco Polaroid Clásico (Opcional en VFX)
        if (!polaroidEnabled) return workingBitmap

        val sideBorder = (min(width, height) * 0.065f).toInt()
        val topBorder = sideBorder
        val bottomBorder = (min(width, height) * 0.22f).toInt()

        val polaroidW = width + sideBorder * 2
        val polaroidH = height + topBorder + bottomBorder
        val polaroidBitmap = Bitmap.createBitmap(polaroidW, polaroidH, Bitmap.Config.ARGB_8888)
        val pCanvas = Canvas(polaroidBitmap)

        pCanvas.drawColor(android.graphics.Color.rgb(247, 245, 238))

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(215, 212, 204)
            style = Paint.Style.STROKE
            strokeWidth = (sideBorder * 0.08f).coerceAtLeast(2f)
        }
        pCanvas.drawBitmap(workingBitmap, sideBorder.toFloat(), topBorder.toFloat(), null)
        pCanvas.drawRect(
            sideBorder.toFloat(),
            topBorder.toFloat(),
            (sideBorder + width).toFloat(),
            (topBorder + height).toFloat(),
            shadowPaint
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(95, 92, 86)
            textSize = bottomBorder * 0.15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        pCanvas.drawText(
            metadataStamp,
            polaroidW / 2f,
            topBorder + height + (bottomBorder * 0.58f),
            textPaint
        )

        workingBitmap.recycle()
        return polaroidBitmap
    }
}

// ============================================================================
// 5. UTILIDADES DE PERSISTENCIA EN GALERÍA Y MATRIZ ANTI-DEFORMACIÓN (NO SQUEEZE)
// ============================================================================

object MediaStoreHelper {
    fun loadSavedAnalogProImages(context: Context): List<Uri> {
        val list = mutableListOf<Uri>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: ""
                    if (name.startsWith("ANALOG_")) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        list.add(uri)
                        if (list.size >= 120) break
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun loadSmallThumbnail(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                BitmapFactory.decodeStream(s, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Calcula la matriz exacta para el TextureView en cualquier rotación (Vertical u Horizontal)
 * para que la imagen NUNCA sufra "squeeze" (aplastamiento) ni se pierda al girar el teléfono.
 */
fun configureTextureTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    previewSize: Size
) {
    if (viewWidth == 0 || viewHeight == 0) return
    val windowManager = textureView.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    @Suppress("DEPRECATION")
    val rotation = windowManager.defaultDisplay.rotation

    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()

    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(
            viewHeight.toFloat() / previewSize.height,
            viewWidth.toFloat() / previewSize.width
        )
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
    } else {
        // En retrato: escalar proporcionalmente (Center-Crop sin deformar)
        val bufferAspect = previewSize.height.toFloat() / previewSize.width.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (viewAspect > bufferAspect) {
            scaleX = 1f
            scaleY = viewAspect / bufferAspect
        } else {
            scaleX = bufferAspect / viewAspect
            scaleY = 1f
        }
        matrix.setScale(scaleX, scaleY, centerX, centerY)
        if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
    }
    textureView.setTransform(matrix)
}

// ============================================================================
// 6. ACTIVIDAD PRINCIPAL
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD4AF37),
                    secondary = Color(0xFFE53935),
                    background = Color(0xFF080808),
                    surface = Color(0xFF141414)
                )
            ) {
                AnalogProApp()
            }
        }
    }
}

@Composable
fun AnalogProApp() {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermissions = perms[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            val perms = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            permLauncher.launch(perms.toTypedArray())
        }
    }

    if (hasPermissions) {
        CameraMainScreen()
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF080808)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ANALOG PRO CINE", color = Color(0xFFD4AF37), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("Concede permiso de Cámara y Micrófono para continuar", color = Color.LightGray, fontSize = 13.sp)
            }
        }
    }
}

// ============================================================================
// 7. PANTALLA PRINCIPAL CON DISEÑO IDÉNTICO A LA REFERENCIA (VERTICAL Y HORIZONTAL)
// ============================================================================

@SuppressLint("MissingPermission")
@Composable
fun CameraMainScreen() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    // Lista completa de lentes / cámaras disponibles en el teléfono
    val availableLenses = remember {
        mutableStateListOf<CameraLensOption>().apply {
            try {
                for (id in cameraManager.cameraIdList) {
                    val ch = cameraManager.getCameraCharacteristics(id)
                    val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                    val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4.2f)
                    val apertures = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)
                    val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val maxSz = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
                    val mp = String.format(Locale.US, "%.0fMP", (maxSz.width * maxSz.height) / 1_000_000f)
                    val focal = focals.firstOrNull() ?: 4.2f
                    val lensType = when {
                        facing == CameraCharacteristics.LENS_FACING_FRONT -> "Frontal (${focal}mm)"
                        focal < 2.5f -> "Ultra Gran Angular (${focal}mm)"
                        focal > 6.0f -> "Teleobjetivo (${focal}mm)"
                        else -> "Principal Wide (${focal}mm)"
                    }
                    add(CameraLensOption(id, lensType, facing, focal, apertures.firstOrNull() ?: 1.8f, mp))
                }
            } catch (_: Exception) {}
        }
    }

    var currentCameraId by remember {
        mutableStateOf(
            availableLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }?.cameraId
                ?: availableLenses.firstOrNull()?.cameraId ?: "0"
        )
    }
    var sensorInfo by remember { mutableStateOf<SensorDiagnostic?>(null) }
    var activeDockMenu by remember { mutableStateOf(ActiveDockMenu.NONE) }

    // Estado de Batería real para el HUD superior izquierdo
    var batteryPercent by remember { mutableStateOf(85) }
    LaunchedEffect(Unit) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) batteryPercent = (level * 100) / scale
    }

    // Controles PRO (ISO, Velocidad, EV, Enfoque Manual, Formato, Relación de Aspecto, Flash e Intensidad)
    var isManualMode by remember { mutableStateOf(false) }
    var selectedIso by remember { mutableStateOf(100) }
    var selectedShutterNs by remember { mutableStateOf(33_333_333L) } // 1/30s
    var exposureEvSteps by remember { mutableStateOf(0) }
    var manualFocusDistance by remember { mutableStateOf(0f) } // 0f = Auto, >0 = Dioptrías macro
    var flashMode by remember { mutableStateOf(FlashMode.OFF) }
    var flashStrengthLevel by remember { mutableStateOf(1f) } // 0.1f..1.0f
    var captureFormat by remember { mutableStateOf(CaptureFormat.JPG) }
    var aspectRatioMode by remember { mutableStateOf(AspectRatioMode.RATIO_16_9) }

    // Zoom con gestos (Pinch-to-Zoom) y Enfoque por toque (Tap-to-Focus)
    var zoomFactor by remember { mutableStateOf(1.0f) }
    var focusNormX by remember { mutableStateOf(0.5f) }
    var focusNormY by remember { mutableStateOf(0.5f) }

    // Controles FILMS y LUTs en tiempo real
    var selectedPreset by remember { mutableStateOf(BUILT_IN_PRESETS[1]) } // Classic Chrome por defecto
    var customLut by remember { mutableStateOf<CubeLut3D?>(null) }

    // Controles VFX en tiempo real
    var lightLeakStrength by remember { mutableStateOf(0.35f) }
    var anamorphicFlareStrength by remember { mutableStateOf(0.40f) }
    var opticalFlareStrength by remember { mutableStateOf(0.20f) }
    var grainIntensity by remember { mutableStateOf(selectedPreset.defaultGrain) }
    var noiseIntensity by remember { mutableStateOf(selectedPreset.defaultNoise) }
    var macroBlurStrength by remember { mutableStateOf(0.0f) }
    var polaroidFrameEnabled by remember { mutableStateOf(false) }

    // Análisis de Luz e Interacción Solar en Tiempo Real
    var liveLight by remember { mutableStateOf(LiveLightAnalysis()) }
    var lowLightDismissed by remember { mutableStateOf(false) }

    // Grabación de Video en Máxima Calidad
    var isRecordingVideo by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var currentVideoFile by remember { mutableStateOf<File?>(null) }

    // Galería Persistente (Carga todas las fotos guardadas en DCIM/AnalogPro al iniciar)
    val capturedUris = remember { mutableStateListOf<Uri>() }
    var latestThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var showGalleryScreen by remember { mutableStateOf(false) }
    var showHardwareModal by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }

    fun refreshGalleryFromMediaStore() {
        scope.launch(Dispatchers.IO) {
            val saved = MediaStoreHelper.loadSavedAnalogProImages(context)
            val thumb = saved.firstOrNull()?.let { MediaStoreHelper.loadSmallThumbnail(context, it) }
            withContext(Dispatchers.Main) {
                capturedUris.clear()
                capturedUris.addAll(saved)
                latestThumbnail = thumb
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshGalleryFromMediaStore()
    }

    // Temporizador de grabación de video
    LaunchedEffect(isRecordingVideo) {
        if (isRecordingVideo) {
            recordingSeconds = 0
            while (isRecordingVideo) {
                delay(1000L)
                recordingSeconds++
            }
        } else {
            recordingSeconds = 0
        }
    }

    // Importador de LUT .cube externo
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val parsed = CubeLutParser.parseUri(context, uri, "Custom .CUBE")
                withContext(Dispatchers.Main) {
                    if (parsed != null) {
                        customLut = parsed
                        Toast.makeText(context, "LUT cargado: ${parsed.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Referencias Camera2
    var cameraDevice by remember { mutableStateOf<CameraDevice?>(null) }
    var captureSession by remember { mutableStateOf<CameraCaptureSession?>(null) }
    var jpegReader by remember { mutableStateOf<ImageReader?>(null) }
    var rawReader by remember { mutableStateOf<ImageReader?>(null) }
    var currentCharacteristics by remember { mutableStateOf<CameraCharacteristics?>(null) }
    var activePreviewSize by remember { mutableStateOf(Size(1920, 1080)) }
    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }

    // Muestreo en tiempo real del visor para Flares Anamórficos, Posición del Sol y Detección de Baja Luz
    LaunchedEffect(Unit) {
        while (true) {
            delay(95L)
            val tv = textureViewRef.value
            if (tv != null && tv.isAvailable) {
                val small = withContext(Dispatchers.Main) {
                    try { tv.getBitmap(56, 56) } catch (_: Exception) { null }
                }
                if (small != null) {
                    val analysis = withContext(Dispatchers.Default) {
                        AnalogImageProcessor.analyzeSceneLight(small)
                    }
                    small.recycle()
                    liveLight = analysis
                }
            }
        }
    }

    // Aplicar previsualización de color del Film en tiempo real sobre el TextureView (API 31+ RenderEffect)
    LaunchedEffect(selectedPreset, exposureEvSteps, textureViewRef.value) {
        val tv = textureViewRef.value ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val evBias = exposureEvSteps * (sensorInfo?.evStep ?: 0.33f)
            val expScale = 2.0f.pow(evBias)
            val expMat = ColorMatrix(
                floatArrayOf(
                    expScale, 0f, 0f, 0f, 0f,
                    0f, expScale, 0f, 0f, 0f,
                    0f, 0f, expScale, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val cm = ColorMatrix(selectedPreset.matrix)
            cm.preConcat(expMat)
            tv.setRenderEffect(RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm)))
        }
    }

    // Recalcular transformación anti-squeeze cuando el usuario rota el teléfono o cambia el formato (16:9 / 4:3 / OpenGate)
    LaunchedEffect(isLandscape, aspectRatioMode, activePreviewSize) {
        delay(40L)
        val tv = textureViewRef.value ?: return@LaunchedEffect
        configureTextureTransform(tv, tv.width, tv.height, activePreviewSize)
    }

    fun applyPreviewParameters(afTapPoint: PointF? = null) {
        val cam = cameraDevice ?: return
        val session = captureSession ?: return
        val tex = textureViewRef.value?.surfaceTexture ?: return
        val chars = currentCharacteristics ?: return
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(Surface(tex))

                // Zoom mediante SCALER_CROP_REGION
                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeArray != null && zoomFactor > 1.0f) {
                    val cropW = (activeArray.width() / zoomFactor).toInt()
                    val cropH = (activeArray.height() / zoomFactor).toInt()
                    val cropX = (activeArray.width() - cropW) / 2
                    val cropY = (activeArray.height() - cropH) / 2
                    set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                }

                // Enfoque por toque (Tap-to-Focus) o Enfoque Manual Macro
                if (manualFocusDistance > 0.05f) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
                } else if (afTapPoint != null && activeArray != null) {
                    val mx = (afTapPoint.x * activeArray.width()).toInt().coerceIn(0, activeArray.width() - 1)
                    val my = (afTapPoint.y * activeArray.height()).toInt().coerceIn(0, activeArray.height() - 1)
                    val halfBox = (min(activeArray.width(), activeArray.height()) * 0.08f).toInt()
                    val rect = Rect(
                        (mx - halfBox).coerceAtLeast(0),
                        (my - halfBox).coerceAtLeast(0),
                        (mx + halfBox).coerceAtMost(activeArray.width()),
                        (my + halfBox).coerceAtMost(activeArray.height())
                    )
                    val metering = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1))
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_REGIONS, metering)
                    set(CaptureRequest.CONTROL_AE_REGIONS, metering)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }

                // Exposición Manual vs Auto
                if (isManualMode && sensorInfo?.supportsManualSensor == true) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutterNs)
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureEvSteps)
                }

                // Flash / Luz Continua con control de intensidad
                if (flashMode == FlashMode.TORCH) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val maxStr = sensorInfo?.maxFlashStrength ?: 1
                        if (maxStr > 1) {
                            val level = (flashStrengthLevel * maxStr).roundToInt().coerceIn(1, maxStr)
                            try { cameraManager.turnOnTorchWithStrengthLevel(currentCameraId, level) } catch (_: Exception) {}
                        }
                    }
                } else {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            session.setRepeatingRequest(req.build(), null, null)
        } catch (_: Exception) {}
    }

    fun inspectAndOpenCamera(camId: String, texView: TextureView) {
        try {
            cameraDevice?.close()
            val chars = cameraManager.getCameraCharacteristics(camId)
            currentCharacteristics = chars

            val facingInt = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            val hwLevelInt = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val hwLevelName = when (hwLevelInt) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3 (Máximo Control Pro + RAW + Reproc)"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL (Control Manual Total)"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                else -> "LEGACY"
            }

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val supportsManual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

            val map: StreamConfigurationMap? = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val maxJpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
            val maxVideo = map?.getOutputSizes(MediaRecorder::class.java)?.filter { it.width <= 3840 }?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
            val maxRaw = if (supportsRaw) map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } else null

            // Seleccionar tamaño de preview acorde al sensor para no deformar
            val previewCandidates = map?.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1920, 1080))
            val chosenPreview = previewCandidates.filter { it.width <= 1920 && it.height <= 1920 }
                .maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
            activePreviewSize = chosenPreview

            val mp = String.format(Locale.US, "%.1f MP (%dx%d)", (maxJpeg.width * maxJpeg.height) / 1_000_000f, maxJpeg.width, maxJpeg.height)
            val physSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val physString = if (physSize != null) String.format(Locale.US, "%.2f x %.2f mm", physSize.width, physSize.height) else "Desconocido"

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-12, 12)
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.33f
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8f
            val maxFlashStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            } else 1
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)

            sensorInfo = SensorDiagnostic(
                cameraId = camId,
                facing = if (facingInt == CameraCharacteristics.LENS_FACING_FRONT) "Frontal" else "Trasera",
                hardwareLevel = hwLevelName,
                maxResolutionMp = mp,
                maxJpegSize = maxJpeg,
                maxVideoSize = maxVideo,
                maxRawSize = maxRaw,
                supportsRaw = supportsRaw,
                supportsManualSensor = supportsManual,
                physicalSizeMm = physString,
                isoRange = isoRange,
                exposureTimeRangeNs = expRange,
                evRange = evRange,
                evStep = evStep,
                minFocusDistance = minFocus,
                maxZoom = maxZoom,
                maxFlashStrength = maxFlashStr,
                apertures = apertures
            )

            jpegReader?.close()
            jpegReader = ImageReader.newInstance(maxJpeg.width, maxJpeg.height, ImageFormat.JPEG, 2)

            rawReader?.close()
            rawReader = if (supportsRaw && maxRaw != null) {
                ImageReader.newInstance(maxRaw.width, maxRaw.height, ImageFormat.RAW_SENSOR, 2)
            } else null

            cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surfaceTexture = texView.surfaceTexture ?: return
                    surfaceTexture.setDefaultBufferSize(chosenPreview.width, chosenPreview.height)
                    configureTextureTransform(texView, texView.width, texView.height, chosenPreview)
                    val previewSurface = Surface(surfaceTexture)

                    val targets = mutableListOf(previewSurface)
                    jpegReader?.surface?.let { targets.add(it) }
                    rawReader?.surface?.let { targets.add(it) }

                    camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            applyPreviewParameters()
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, null)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(isManualMode, selectedIso, selectedShutterNs, exposureEvSteps, manualFocusDistance, flashMode, flashStrengthLevel, zoomFactor, captureSession) {
        applyPreviewParameters()
    }

    // Grabación de Video en la Mayor Calidad Posible (4K / 1080p High Bitrate)
    fun toggleVideoRecording() {
        val cam = cameraDevice ?: return
        val texView = textureViewRef.value ?: return
        val info = sensorInfo ?: return

        if (isRecordingVideo) {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            isRecordingVideo = false

            currentVideoFile?.let { file ->
                scope.launch(Dispatchers.IO) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AnalogPro")
                        }
                    }
                    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { input -> input.copyTo(out) }
                        }
                        file.delete()
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Video ${info.maxVideoSize.width}x${info.maxVideoSize.height} guardado en Galería", Toast.LENGTH_SHORT).show()
                        inspectAndOpenCamera(currentCameraId, texView)
                    }
                }
            }
        } else {
            try {
                val outFile = File(context.cacheDir, "ANALOG_VID_${System.currentTimeMillis()}.mp4")
                currentVideoFile = outFile
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(outFile.absolutePath)
                    setVideoEncodingBitRate(45_000_000) // 45 Mbps Cinema Quality
                    setVideoFrameRate(30)
                    setVideoSize(info.maxVideoSize.width, info.maxVideoSize.height)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    prepare()
                }
                mediaRecorder = recorder

                val surfaceTexture = texView.surfaceTexture ?: return
                surfaceTexture.setDefaultBufferSize(activePreviewSize.width, activePreviewSize.height)
                val previewSurface = Surface(surfaceTexture)
                val recordSurface = recorder.surface

                cam.createCaptureSession(listOf(previewSurface, recordSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(previewSurface)
                            addTarget(recordSurface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        session.setRepeatingRequest(req.build(), null, null)
                        recorder.start()
                        isRecordingVideo = true
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, null)
            } catch (e: Exception) {
                Toast.makeText(context, "Error al iniciar video: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Disparo Fotográfico con todos los VFX interactivos y guardado persistente
    fun capturePhoto() {
        val texView = textureViewRef.value ?: return
        val info = sensorInfo ?: return
        val chars = currentCharacteristics ?: return
        isCapturing = true

        scope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dateLabel = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())

                // 1. Captura RAW (.DNG 16-bit) si corresponde
                if ((captureFormat == CaptureFormat.RAW || captureFormat == CaptureFormat.RAW_JPG) && info.supportsRaw && rawReader != null) {
                    val cam = cameraDevice
                    val session = captureSession
                    if (cam != null && session != null) {
                        var lastResult: TotalCaptureResult? = null
                        val rawReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(rawReader!!.surface)
                            if (isManualMode && info.supportsManualSensor) {
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso)
                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutterNs)
                            } else {
                                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureEvSteps)
                            }
                            if (flashMode == FlashMode.ON || flashMode == FlashMode.DISPOSABLE) {
                                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                            }
                        }

                        rawReader?.setOnImageAvailableListener({ reader ->
                            val image: Image? = reader.acquireLatestImage()
                            val res = lastResult
                            if (image != null && res != null) {
                                val dngValues = ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, "ANALOG_${timestamp}.dng")
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AnalogPro/RAW")
                                    }
                                }
                                val dngUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, dngValues)
                                if (dngUri != null) {
                                    context.contentResolver.openOutputStream(dngUri)?.use { out ->
                                        val dngCreator = DngCreator(chars, res)
                                        dngCreator.writeImage(out, image)
                                        dngCreator.close()
                                    }
                                }
                                image.close()
                            } else {
                                image?.close()
                            }
                        }, Handler(Looper.getMainLooper()))

                        session.capture(rawReq.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                lastResult = result
                            }
                        }, Handler(Looper.getMainLooper()))
                    }
                }

                // 2. Captura Procesada con Film + LUT + Light Leaks Interactivos + Anamorphic + Grano
                if (captureFormat != CaptureFormat.RAW || !info.supportsRaw) {
                    var sourceBitmap: Bitmap? = null
                    val cam = cameraDevice
                    val session = captureSession
                    val jReader = jpegReader

                    if (cam != null && session != null && jReader != null) {
                        sourceBitmap = suspendCancellableCoroutine { cont ->
                            jReader.setOnImageAvailableListener({ reader ->
                                val img = reader.acquireLatestImage()
                                if (img != null) {
                                    val buffer = img.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    img.close()
                                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                    if (cont.isActive) cont.resume(decoded)
                                } else if (cont.isActive) {
                                    cont.resume(null)
                                }
                            }, Handler(Looper.getMainLooper()))

                            val stillReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(jReader.surface)
                                if (isManualMode && info.supportsManualSensor) {
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                    set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso)
                                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutterNs)
                                } else {
                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureEvSteps)
                                }
                                if (flashMode == FlashMode.ON || flashMode == FlashMode.DISPOSABLE) {
                                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                                }
                            }
                            session.capture(stillReq.build(), null, Handler(Looper.getMainLooper()))
                        }
                    }

                    if (sourceBitmap == null) {
                        sourceBitmap = withContext(Dispatchers.Main) { texView.bitmap }
                    }

                    if (sourceBitmap != null) {
                        val shutterLabel = if (selectedShutterNs >= 1_000_000_000L) {
                            "${selectedShutterNs / 1_000_000_000L}\""
                        } else {
                            "1/${(1_000_000_000L / selectedShutterNs).coerceAtLeast(1)}s"
                        }
                        val stamp = "${selectedPreset.brand} ${selectedPreset.name}  •  ISO $selectedIso  •  $shutterLabel  •  $dateLabel"

                        val processed = AnalogImageProcessor.processImage(
                            source = sourceBitmap,
                            preset = selectedPreset,
                            customLut = customLut,
                            grainStrength = grainIntensity,
                            noiseStrength = noiseIntensity,
                            lightLeakStrength = lightLeakStrength,
                            anamorphicFlareStrength = anamorphicFlareStrength,
                            opticalFlareStrength = opticalFlareStrength,
                            macroBlurStrength = macroBlurStrength,
                            focusNormX = focusNormX,
                            focusNormY = focusNormY,
                            lightAnalysis = liveLight,
                            exposureBias = (exposureEvSteps * info.evStep) + (if (flashMode != FlashMode.OFF) (flashStrengthLevel - 0.5f) * 0.6f else 0f),
                            polaroidEnabled = polaroidFrameEnabled,
                            disposableFlashActive = (flashMode == FlashMode.DISPOSABLE),
                            metadataStamp = stamp
                        )

                        val isPng = (captureFormat == CaptureFormat.PNG)
                        val ext = if (isPng) "png" else "jpg"
                        val mime = if (isPng) "image/png" else "image/jpeg"

                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "ANALOG_${timestamp}.$ext")
                            put(MediaStore.Images.Media.MIME_TYPE, mime)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AnalogPro")
                            }
                        }

                        val savedUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (savedUri != null) {
                            context.contentResolver.openOutputStream(savedUri)?.use { out ->
                                val compressFormat = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                                processed.compress(compressFormat, 100, out)
                            }
                        }
                        processed.recycle()
                    }
                }

                refreshGalleryFromMediaStore()
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    Toast.makeText(context, "Guardada en Galería (${captureFormat.label})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showGalleryScreen) {
        DarkroomGalleryScreen(
            capturedUris = capturedUris,
            onRefresh = { refreshGalleryFromMediaStore() },
            onClose = { showGalleryScreen = false }
        )
        return
    }

    // ========================================================================
    // COMPONENTES REUTILIZABLES DEL VISOR, DOCK (FILMS/VFX/PRO/LENS) Y SHUTTER
    // ========================================================================

    @Composable
    fun ViewfinderArea(modifier: Modifier) {
        Box(
            modifier = modifier
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        val maxZ = sensorInfo?.maxZoom ?: 8f
                        zoomFactor = (zoomFactor * zoomChange).coerceIn(1.0f, maxZ)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val nx = (offset.x / size.width).coerceIn(0.05f, 0.95f)
                        val ny = (offset.y / size.height).coerceIn(0.05f, 0.95f)
                        focusNormX = nx
                        focusNormY = ny
                        applyPreviewParameters(PointF(nx, ny))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Contenedor con relación de aspecto (16:9, 4:3 o OpenGate) sin aplastar la imagen
            val targetModifier = when (aspectRatioMode) {
                AspectRatioMode.OPEN_GATE -> Modifier.fillMaxSize()
                AspectRatioMode.RATIO_16_9 -> if (isLandscape) Modifier.aspectRatio(16f / 9f) else Modifier.aspectRatio(9f / 16f)
                AspectRatioMode.RATIO_4_3 -> if (isLandscape) Modifier.aspectRatio(4f / 3f) else Modifier.aspectRatio(3f / 4f)
            }

            Box(
                modifier = targetModifier
                    .fillMaxSize()
                    .background(
                        if (polaroidFrameEnabled) Color(0xFFF7F5EE) else Color.Black
                    )
                    .padding(
                        start = if (polaroidFrameEnabled) 12.dp else 0.dp,
                        end = if (polaroidFrameEnabled) 12.dp else 0.dp,
                        top = if (polaroidFrameEnabled) 12.dp else 0.dp,
                        bottom = if (polaroidFrameEnabled) 36.dp else 0.dp
                    )
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            textureViewRef.value = this
                            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                                configureTextureTransform(this, right - left, bottom - top, activePreviewSize)
                            }
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    inspectAndOpenCamera(currentCameraId, this@apply)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    configureTextureTransform(this@apply, w, h, activePreviewSize)
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // CAPA EN TIEMPO REAL DE VFX INTERACTIVOS, ANAMÓRFICOS, LIGHT LEAKS Y RETÍCULA HUD
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // 1. Light Leaks interactivos según la posición del sol en tiempo real
                    if (lightLeakStrength > 0.02f) {
                        val sx = liveLight.sunX * w
                        val sy = liveLight.sunY * h
                        val rad = max(w, h) * (0.6f + lightLeakStrength * 0.45f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFFF8C28).copy(alpha = (lightLeakStrength * 0.55f).coerceIn(0f, 0.8f)),
                                    Color(0xFFFF371E).copy(alpha = (lightLeakStrength * 0.35f).coerceIn(0f, 0.6f)),
                                    Color.Transparent
                                ),
                                center = Offset(sx, sy),
                                radius = rad
                            ),
                            center = Offset(sx, sy),
                            radius = rad
                        )
                    }

                    // 2. Anamorphic Flares en tiempo real sobre las altas luces detectadas
                    if (anamorphicFlareStrength > 0.02f) {
                        val pts = if (liveLight.highlights.isNotEmpty()) liveLight.highlights
                        else listOf(HighlightPoint(liveLight.sunX, liveLight.sunY, 0.7f))

                        for (pt in pts) {
                            val hx = pt.normX * w
                            val hy = pt.normY * h
                            val halfW = w * (0.38f + 0.35f * anamorphicFlareStrength * pt.intensity)
                            val alpha = (anamorphicFlareStrength * pt.intensity * 0.85f).coerceIn(0.15f, 0.9f)
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color(0xFF00B4FF).copy(alpha = alpha * 0.5f),
                                        Color(0xFFB4F0FF).copy(alpha = alpha),
                                        Color(0xFF00B4FF).copy(alpha = alpha * 0.5f),
                                        Color.Transparent
                                    ),
                                    startX = hx - halfW,
                                    endX = hx + halfW
                                ),
                                start = Offset(hx - halfW, hy),
                                end = Offset(hx + halfW, hy),
                                strokeWidth = 6.dp.toPx()
                            )
                        }
                    }

                    // 3. Flares Ópticos en tiempo real
                    if (opticalFlareStrength > 0.02f) {
                        val sx = liveLight.sunX * w
                        val sy = liveLight.sunY * h
                        val vx = (w / 2f) - sx
                        val vy = (h / 2f) - sy
                        for ((idx, f) in listOf(0.4f, 0.85f, 1.3f).withIndex()) {
                            val gx = sx + vx * f
                            val gy = sy + vy * f
                            val r = min(w, h) * (0.04f + idx * 0.02f)
                            drawCircle(
                                color = Color(0xFFFFD885).copy(alpha = (opticalFlareStrength * 0.35f).coerceIn(0.05f, 0.5f)),
                                radius = r,
                                center = Offset(gx, gy)
                            )
                        }
                    }

                    // 4. Indicador de Desenfoque Macro / Objetos Cercanos alrededor del punto de enfoque
                    if (macroBlurStrength > 0.02f) {
                        val fx = focusNormX * w
                        val fy = focusNormY * h
                        val rad = max(w, h) * (0.45f - macroBlurStrength * 0.2f)
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    Color.Black.copy(alpha = (macroBlurStrength * 0.45f).coerceIn(0.1f, 0.5f))
                                ),
                                center = Offset(fx, fy),
                                radius = rad
                            )
                        )
                    }

                    // 5. Esquinas de encuadre blancas (estilo monitor de cine de las imágenes de referencia)
                    val m = 16.dp.toPx()
                    val cornerLen = 42.dp.toPx()
                    val strokeW = 1.3.dp.toPx()
                    val bracketColor = Color.White.copy(alpha = 0.85f)

                    // Top-Left
                    drawLine(bracketColor, Offset(m, m), Offset(m + cornerLen, m), strokeW)
                    drawLine(bracketColor, Offset(m, m), Offset(m, m + cornerLen), strokeW)
                    // Top-Right
                    drawLine(bracketColor, Offset(w - m, m), Offset(w - m - cornerLen, m), strokeW)
                    drawLine(bracketColor, Offset(w - m, m), Offset(w - m, m + cornerLen), strokeW)
                    // Bottom-Left
                    drawLine(bracketColor, Offset(m, h - m), Offset(m + cornerLen, h - m), strokeW)
                    drawLine(bracketColor, Offset(m, h - m), Offset(m, h - m - cornerLen), strokeW)

                    // 6. Retícula Central / Punto de Enfoque Táctil [ + ]
                    val fx = focusNormX * w
                    val fy = focusNormY * h
                    val boxHalf = 34.dp.toPx()
                    val bLen = 12.dp.toPx()
                    val crossHalf = 7.dp.toPx()

                    // 4 esquinas del cuadro de enfoque
                    drawLine(bracketColor, Offset(fx - boxHalf, fy - boxHalf), Offset(fx - boxHalf + bLen, fy - boxHalf), strokeW)
                    drawLine(bracketColor, Offset(fx - boxHalf, fy - boxHalf), Offset(fx - boxHalf, fy - boxHalf + bLen), strokeW)

                    drawLine(bracketColor, Offset(fx + boxHalf, fy - boxHalf), Offset(fx + boxHalf - bLen, fy - boxHalf), strokeW)
                    drawLine(bracketColor, Offset(fx + boxHalf, fy - boxHalf), Offset(fx + boxHalf, fy - boxHalf + bLen), strokeW)

                    drawLine(bracketColor, Offset(fx - boxHalf, fy + boxHalf), Offset(fx - boxHalf + bLen, fy + boxHalf), strokeW)
                    drawLine(bracketColor, Offset(fx - boxHalf, fy + boxHalf), Offset(fx - boxHalf, fy + boxHalf - bLen), strokeW)

                    drawLine(bracketColor, Offset(fx + boxHalf, fy + boxHalf), Offset(fx + boxHalf - bLen, fy + boxHalf), strokeW)
                    drawLine(bracketColor, Offset(fx + boxHalf, fy + boxHalf), Offset(fx + boxHalf, fy + boxHalf - bLen), strokeW)

                    // Cruz central +
                    drawLine(bracketColor, Offset(fx - crossHalf, fy), Offset(fx + crossHalf, fy), strokeW)
                    drawLine(bracketColor, Offset(fx, fy - crossHalf), Offset(fx, fy + crossHalf), strokeW)
                }
            }

            // BARRA SUPERIOR DEL HUD: [Batería] [AUTO/PRO] [16:9] -------- [● REC]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 26.dp, vertical = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Icono de Batería estilo cámara de cine
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .border(1.5.dp, Color.White, RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                    ) {
                        Box(modifier = Modifier.size(6.dp, 10.dp).background(Color.White))
                        Spacer(Modifier.width(2.dp))
                        Box(modifier = Modifier.size(6.dp, 10.dp).background(if (batteryPercent > 30) Color.White else Color.Transparent))
                        Spacer(Modifier.width(2.dp))
                        Box(modifier = Modifier.size(6.dp, 10.dp).background(if (batteryPercent > 65) Color.White else Color.Transparent))
                    }

                    // Etiqueta AUTO / PRO (clicable)
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(3.dp),
                        modifier = Modifier.clickable { isManualMode = !isManualMode }
                    ) {
                        Text(
                            text = if (isManualMode) "PRO" else "AUTO",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Selector rápido de Relación de Aspecto (16:9 / 4:3 / OPEN GATE)
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(3.dp),
                        modifier = Modifier.clickable {
                            val vals = AspectRatioMode.values()
                            aspectRatioMode = vals[(aspectRatioMode.ordinal + 1) % vals.size]
                        }
                    ) {
                        Text(
                            text = aspectRatioMode.label,
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Indicador de Zoom cuando es > 1.0x
                    if (zoomFactor > 1.05f) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, Color(0xFFD4AF37))
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.1fx", zoomFactor),
                                color = Color(0xFFD4AF37),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Indicador ● REC (arriba a la derecha, clicable para grabar video en 4K)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { toggleVideoRecording() }
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                if (isRecordingVideo) Color(0xFFFF1744) else Color(0xFFE53935),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isRecordingVideo) String.format(Locale.US, "REC %02d:%02d", recordingSeconds / 60, recordingSeconds % 60) else "REC",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
            }

            // AVISO INTELIGENTE DE BAJA LUZ (Para activar Flash con un toque)
            AnimatedVisibility(
                visible = liveLight.isLowLight && flashMode == FlashMode.OFF && !lowLightDismissed,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
            ) {
                Surface(
                    color = Color(0xCC1A1A1A),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFFD4AF37))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.FlashOn, contentDescription = null, tint = Color(0xFFD4AF37), modifier = Modifier.size(16.dp))
                        Text("Baja luz detectada", color = Color.White, fontSize = 11.sp)
                        Text(
                            "ACTIVAR FLASH",
                            color = Color(0xFFD4AF37),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { flashMode = FlashMode.TORCH }
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.Gray,
                            modifier = Modifier.size(14.dp).clickable { lowLightDismissed = true }
                        )
                    }
                }
            }

            // TELEMETRÍA INFERIOR IZQUIERDA (4K / 30 fps / ISO100 + Film Activo)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 26.dp, bottom = 26.dp)
            ) {
                Text(
                    text = if ((sensorInfo?.maxVideoSize?.width ?: 1920) >= 3840) "4K" else "FHD",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isManualMode) "1/${(1_000_000_000L / selectedShutterNs).coerceAtLeast(1)}s" else "30 fps",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp
                )
                Text(
                    text = "ISO$selectedIso",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${selectedPreset.brand} ${selectedPreset.name}" + (if (customLut != null) " + LUT" else ""),
                    color = Color(0xFFD4AF37),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // DOCK FLOTANTE TRANSLÚCIDO (FILMS | VFX | PRO | LENS)
            // En Vertical se ubica abajo a la derecha sobre la barra de disparo; en Horizontal se ubica vertical a la derecha
            if (!isLandscape) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 18.dp)
                        .background(Color(0xAA141414), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockButton(
                        icon = Icons.Default.MovieFilter,
                        label = "FILMS",
                        selected = activeDockMenu == ActiveDockMenu.FILMS,
                        onClick = { activeDockMenu = if (activeDockMenu == ActiveDockMenu.FILMS) ActiveDockMenu.NONE else ActiveDockMenu.FILMS }
                    )
                    DockButton(
                        icon = Icons.Default.AutoFixHigh,
                        label = "VFX",
                        selected = activeDockMenu == ActiveDockMenu.VFX,
                        onClick = { activeDockMenu = if (activeDockMenu == ActiveDockMenu.VFX) ActiveDockMenu.NONE else ActiveDockMenu.VFX }
                    )
                    DockButton(
                        icon = Icons.Default.Camera,
                        label = "PRO",
                        selected = activeDockMenu == ActiveDockMenu.PRO,
                        onClick = { activeDockMenu = if (activeDockMenu == ActiveDockMenu.PRO) ActiveDockMenu.NONE else ActiveDockMenu.PRO }
                    )
                    DockButton(
                        icon = Icons.Default.CenterFocusStrong,
                        label = "LENS",
                        selected = activeDockMenu == ActiveDockMenu.LENS,
                        onClick = { activeDockMenu = if (activeDockMenu == ActiveDockMenu.LENS) ActiveDockMenu.NONE else ActiveDockMenu.LENS }
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 14.dp)
                        .background(Color(0xAA141414), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DockButton(
                        icon = Icons.Default.MovieFilter,
                        label = "FILMS",
                        selected = activeDockMenu == ActiveDockMenu.FILMS,
                        onClick = { activeDockMenu = if (activeDockMenu == ActiveDockMenu.FILMS) ActiveDockMenu.NONE else ActiveDockMenu.FILMS }
                    )
                    DockButton(
                        icon = Icons.Default.AutoFixHigh,
                        label = "VFX",
                        selected = activeDockMenu == ActiveDockMenu.VFX,
                        onClick = { activeDockMenu = if (activeDockMenu == ActiveDockMenu.VFX) ActiveDockMenu.NONE else ActiveDockMenu.VFX }
                    )
                    DockButton(
                        icon = Icons.Default.Camera,
                        label = "PRO",
                        selected = activeDockMenu == ActiveDockMenu.PRO,
                        onClick = { activeDockMenu = if (activeDockMenu == ActiveDockMenu.PRO) ActiveDockMenu.NONE else ActiveDockMenu.PRO }
                    )
                    DockButton(
                        icon = Icons.Default.CenterFocusStrong,
                        label = "LENS",
                        selected = activeDockMenu == ActiveDockMenu.LENS,
                        onClick = { activeDockMenu = if (activeDockMenu == ActiveDockMenu.LENS) ActiveDockMenu.NONE else ActiveDockMenu.LENS }
                    )
                }
            }

            // PANEL EMERGENTE SEGÚN EL BOTÓN SELECCIONADO (FILMS / VFX / PRO / LENS)
            if (activeDockMenu != ActiveDockMenu.NONE) {
                Box(
                    modifier = Modifier
                        .align(if (isLandscape) Alignment.BottomCenter else Alignment.BottomCenter)
                        .padding(bottom = if (isLandscape) 14.dp else 88.dp, start = 14.dp, end = if (isLandscape) 94.dp else 14.dp)
                        .fillMaxWidth()
                        .background(Color(0xE6121212), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    when (activeDockMenu) {
                        ActiveDockMenu.FILMS -> {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("FILMS ANALÓGICOS & 3D LUTS", color = Color(0xFFD4AF37), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "Importar .CUBE",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            modifier = Modifier
                                                .background(Color(0xFF2B2B2B), RoundedCornerShape(6.dp))
                                                .clickable { lutPicker.launch("*/*") }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                        if (customLut != null) {
                                            Text(
                                                "Quitar LUT",
                                                color = Color(0xFFE53935),
                                                fontSize = 11.sp,
                                                modifier = Modifier.clickable { customLut = null }.padding(4.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                // Tarjetas de Película con Imagen Representativa generada con el perfil real
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(BUILT_IN_PRESETS) { preset ->
                                        val isSel = preset.id == selectedPreset.id
                                        val thumbBmp = remember(preset.id) { FilmThumbnailGenerator.getThumbnailForPreset(preset) }
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .width(92.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSel) Color(0xFFD4AF37) else Color(0xFF1E1E1E))
                                                .border(
                                                    width = if (isSel) 2.dp else 1.dp,
                                                    color = if (isSel) Color(0xFFD4AF37) else Color.DarkGray,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    selectedPreset = preset
                                                    grainIntensity = preset.defaultGrain
                                                    noiseIntensity = preset.defaultNoise
                                                }
                                                .padding(4.dp)
                                        ) {
                                            Image(
                                                bitmap = thumbBmp.asImageBitmap(),
                                                contentDescription = preset.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(54.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                preset.brand,
                                                color = if (isSel) Color.Black else Color.Gray,
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                preset.name,
                                                color = if (isSel) Color.Black else Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(BUILT_IN_ASSET_LUTS) { assetLut ->
                                        AssistChip(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    val parsed = CubeLutParser.parseAsset(context, assetLut)
                                                    withContext(Dispatchers.Main) { customLut = parsed }
                                                }
                                            },
                                            label = { Text("LUT: " + assetLut.removeSuffix(".cube").replace("_", " "), fontSize = 10.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        ActiveDockMenu.VFX -> {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text("VFX EN TIEMPO REAL (INTERACTIVOS CON EL SOL Y ALTAS LUCES)", color = Color(0xFFD4AF37), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = polaroidFrameEnabled,
                                        onClick = { polaroidFrameEnabled = !polaroidFrameEnabled },
                                        label = { Text("Marco Polaroid", fontSize = 11.sp) }
                                    )
                                    FilterChip(
                                        selected = flashMode == FlashMode.DISPOSABLE,
                                        onClick = {
                                            flashMode = if (flashMode == FlashMode.DISPOSABLE) FlashMode.OFF else FlashMode.DISPOSABLE
                                        },
                                        label = { Text("Flash Desechable", fontSize = 11.sp) }
                                    )
                                }
                                CompactSliderRow("Light Leaks Solares", lightLeakStrength) { lightLeakStrength = it }
                                CompactSliderRow("Flares Anamórficos (Altas Luces)", anamorphicFlareStrength) { anamorphicFlareStrength = it }
                                CompactSliderRow("Flares Ópticos de Lente", opticalFlareStrength) { opticalFlareStrength = it }
                                CompactSliderRow("Grano de Película", grainIntensity) { grainIntensity = it }
                                CompactSliderRow("Desenfoque Cercano (Bokeh)", macroBlurStrength) { macroBlurStrength = it }
                            }
                        }

                        ActiveDockMenu.PRO -> {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(
                                            selected = !isManualMode,
                                            onClick = { isManualMode = false },
                                            label = { Text("AUTO", fontSize = 10.sp) }
                                        )
                                        FilterChip(
                                            selected = isManualMode,
                                            onClick = { isManualMode = true },
                                            label = { Text("MANUAL", fontSize = 10.sp) }
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { showHardwareModal = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("SENSOR MAX (${captureFormat.label})", color = Color(0xFFD4AF37), fontSize = 10.sp)
                                    }
                                }

                                // Selector de Formato (JPG / PNG / RAW DNG) y Relación de Aspecto (16:9 / 4:3 / OPEN GATE)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CaptureFormat.values().forEach { fmt ->
                                        FilterChip(
                                            selected = captureFormat == fmt,
                                            onClick = { captureFormat = fmt },
                                            label = { Text(fmt.label, fontSize = 10.sp) }
                                        )
                                    }
                                }

                                if (isManualMode) {
                                    val isoR = sensorInfo?.isoRange ?: Range(100, 3200)
                                    Text("ISO: $selectedIso", color = Color.LightGray, fontSize = 11.sp)
                                    Slider(
                                        value = selectedIso.toFloat().coerceIn(isoR.lower.toFloat(), isoR.upper.toFloat()),
                                        onValueChange = { selectedIso = it.toInt() },
                                        valueRange = isoR.lower.toFloat()..isoR.upper.toFloat().coerceAtMost(6400f)
                                    )
                                    Text("Velocidad: 1/${(1_000_000_000L / selectedShutterNs).coerceAtLeast(1)}s", color = Color.LightGray, fontSize = 11.sp)
                                    Slider(
                                        value = selectedShutterNs.toFloat(),
                                        onValueChange = { selectedShutterNs = it.toLong() },
                                        valueRange = 250_000f..100_000_000f
                                    )
                                } else {
                                    val evR = sensorInfo?.evRange ?: Range(-12, 12)
                                    Text("Compensación EV: $exposureEvSteps", color = Color.LightGray, fontSize = 11.sp)
                                    Slider(
                                        value = exposureEvSteps.toFloat(),
                                        onValueChange = { exposureEvSteps = it.toInt() },
                                        valueRange = evR.lower.toFloat()..evR.upper.toFloat()
                                    )
                                }

                                // Enfoque Manual Macro
                                val maxDiopter = (sensorInfo?.minFocusDistance ?: 10f).coerceAtLeast(1f)
                                Text(
                                    if (manualFocusDistance < 0.1f) "Enfoque: Automático (Toque en pantalla)" else String.format(Locale.US, "Enfoque Macro Cercano: %.1f dpt", manualFocusDistance),
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )
                                Slider(
                                    value = manualFocusDistance,
                                    onValueChange = { manualFocusDistance = it },
                                    valueRange = 0f..maxDiopter
                                )

                                // Control de Modo de Flash e Intensidad del Flash
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FlashMode.values().forEach { fm ->
                                        FilterChip(
                                            selected = flashMode == fm,
                                            onClick = { flashMode = fm },
                                            label = { Text(fm.label, fontSize = 9.sp) }
                                        )
                                    }
                                }
                                CompactSliderRow("Intensidad del Flash", flashStrengthLevel) { flashStrengthLevel = it.coerceIn(0.1f, 1.0f) }
                            }
                        }

                        ActiveDockMenu.LENS -> {
                            Column {
                                Text("SELECCIÓN DE LENTE / CÁMARA FÍSICA", color = Color(0xFFD4AF37), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(availableLenses) { lens ->
                                        val isCur = lens.cameraId == currentCameraId
                                        Surface(
                                            color = if (isCur) Color(0xFFD4AF37) else Color(0xFF242424),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.clickable {
                                                currentCameraId = lens.cameraId
                                                textureViewRef.value?.let { inspectAndOpenCamera(lens.cameraId, it) }
                                            }
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                                Text(
                                                    "ID ${lens.cameraId} • ${lens.maxMp}",
                                                    color = if (isCur) Color.Black else Color(0xFFD4AF37),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    lens.title,
                                                    color = if (isCur) Color.Black else Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    "f/${lens.aperture}",
                                                    color = if (isCur) Color.DarkGray else Color.Gray,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ActiveDockMenu.NONE -> {}
                    }
                }
            }
        }
    }

    @Composable
    fun GalleryThumbnailButton() {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E1E1E))
                .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                .clickable { showGalleryScreen = true },
            contentAlignment = Alignment.Center
        ) {
            if (latestThumbnail != null) {
                Image(
                    bitmap = latestThumbnail!!.asImageBitmap(),
                    contentDescription = "Última foto",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Galería", tint = Color.White)
            }
        }
    }

    @Composable
    fun FlipCameraButton() {
        IconButton(
            onClick = {
                if (availableLenses.size > 1) {
                    val curIdx = availableLenses.indexOfFirst { it.cameraId == currentCameraId }.coerceAtLeast(0)
                    val nextLens = availableLenses[(curIdx + 1) % availableLenses.size]
                    currentCameraId = nextLens.cameraId
                    textureViewRef.value?.let { inspectAndOpenCamera(nextLens.cameraId, it) }
                }
            },
            modifier = Modifier
                .size(52.dp)
                .background(Color(0xFF1A1A1A), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
        ) {
            Icon(Icons.Default.FlipCameraIos, contentDescription = "Cambiar Cámara", tint = Color.White)
        }
    }

    @Composable
    fun MetallicShutterAndVideoCluster(isVerticalCluster: Boolean) {
        if (isVerticalCluster) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Botón metálico principal de disparo con acentos rojos (idéntico a la imagen de referencia)
                ShutterRingButton(isCapturing = isCapturing, onClick = { capturePhoto() })
                // Botón dedicado de Grabación de Video al lado del disparador
                VideoRecordSideButton(isRecording = isRecordingVideo, onClick = { toggleVideoRecording() })
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ShutterRingButton(isCapturing = isCapturing, onClick = { capturePhoto() })
                VideoRecordSideButton(isRecording = isRecordingVideo, onClick = { toggleVideoRecording() })
            }
        }
    }

    // ========================================================================
    // ESTRUCTURA PRINCIPAL ADAPTATIVA: HORIZONTAL vs VERTICAL
    // ========================================================================
    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
            ViewfinderArea(modifier = Modifier.weight(1f).fillMaxHeight())

            // Barra derecha de disparo (como en la imagen de referencia horizontal)
            Column(
                modifier = Modifier
                    .width(106.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF121212))
                    .padding(vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                FlipCameraButton()
                MetallicShutterAndVideoCluster(isVerticalCluster = true)
                GalleryThumbnailButton()
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
            ViewfinderArea(modifier = Modifier.weight(1f).fillMaxWidth())

            // Barra inferior de disparo (como en la imagen de referencia vertical)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .background(Color(0xFF121212))
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GalleryThumbnailButton()
                MetallicShutterAndVideoCluster(isVerticalCluster = false)
                FlipCameraButton()
            }
        }
    }

    // Modal de Diagnóstico Completo del Sensor
    if (showHardwareModal && sensorInfo != null) {
        val info = sensorInfo!!
        AlertDialog(
            onDismissRequest = { showHardwareModal = false },
            containerColor = Color(0xFF161616),
            title = {
                Text("Capacidad Máxima del Sensor", color = Color(0xFFD4AF37), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("• Cámara ID: ${info.cameraId} (${info.facing})", color = Color.White, fontSize = 12.sp)
                    Text("• Nivel ISP Camera2: ${info.hardwareLevel}", color = Color.White, fontSize = 12.sp)
                    Text("• Resolución Foto Máxima: ${info.maxResolutionMp}", color = Color.White, fontSize = 12.sp)
                    Text("• Resolución Video Máxima: ${info.maxVideoSize.width}x${info.maxVideoSize.height}", color = Color.White, fontSize = 12.sp)
                    Text("• Tamaño Físico del Sensor: ${info.physicalSizeMm}", color = Color.White, fontSize = 12.sp)
                    Text(
                        "• Captura RAW (.DNG 16-bit): ${if (info.supportsRaw) "SOPORTADO (${info.maxRawSize?.width}x${info.maxRawSize?.height})" else "No expuesto"}",
                        color = if (info.supportsRaw) Color(0xFF81C784) else Color(0xFFFFB74D),
                        fontSize = 12.sp
                    )
                    Text("• Rango ISO Nativo: ${info.isoRange.lower} - ${info.isoRange.upper}", color = Color.White, fontSize = 12.sp)
                    Text("• Zoom Máximo: ${info.maxZoom}x", color = Color.White, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showHardwareModal = false }) {
                    Text("CERRAR", color = Color(0xFFD4AF37))
                }
            }
        )
    }
}

@Composable
fun DockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) Color(0xFFD4AF37) else Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = if (selected) Color(0xFFD4AF37) else Color.White,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun ShutterRingButton(isCapturing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clickable(enabled = !isCapturing) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            // Anillo metálico exterior
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(Color(0xFFE0E0E0), Color(0xFF757575), Color(0xFFF5F5F5), Color(0xFF616161), Color(0xFFE0E0E0))
                ),
                radius = r,
                style = Stroke(width = 6.dp.toPx())
            )
            // Detalles rojos estilo cámara de cine en el anillo
            drawArc(
                color = Color(0xFFE53935),
                startAngle = -15f,
                sweepAngle = 30f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx())
            )
            drawArc(
                color = Color(0xFFE53935),
                startAngle = 75f,
                sweepAngle = 30f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx())
            )
            // Centro blanco puro
            drawCircle(
                color = if (isCapturing) Color.LightGray else Color.White,
                radius = r - 8.dp.toPx()
            )
        }
    }
}

@Composable
fun VideoRecordSideButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(Color(0xFF222222), CircleShape)
            .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (isRecording) 14.dp else 16.dp)
                .background(Color(0xFFE53935), if (isRecording) RoundedCornerShape(3.dp) else CircleShape)
        )
    }
}

@Composable
fun CompactSliderRow(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.LightGray, fontSize = 11.sp)
            Text("${(value * 100).toInt()}%", color = Color(0xFFD4AF37), fontSize = 11.sp)
        }
        Slider(value = value, onValueChange = onValueChange, modifier = Modifier.height(28.dp))
    }
}

// ============================================================================
// 8. GALERÍA PERSISTENTE CON ZOOM TÁCTIL Y MODO EDITOR COMPLETO (DARKROOM)
// ============================================================================

@Composable
fun DarkroomGalleryScreen(
    capturedUris: List<Uri>,
    onRefresh: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isEditorMode by remember { mutableStateOf(false) }

    // Gestos de Zoom y Paneo para previsualizar las fotografías en grande
    var viewScale by remember { mutableStateOf(1f) }
    var viewOffset by remember { mutableStateOf(Offset.Zero) }

    // Parámetros del Modo Editor
    var activePreset by remember { mutableStateOf(BUILT_IN_PRESETS[1]) }
    var activeLut by remember { mutableStateOf<CubeLut3D?>(null) }
    var editLightLeak by remember { mutableStateOf(0.25f) }
    var editAnamorphic by remember { mutableStateOf(0.20f) }
    var editGrain by remember { mutableStateOf(0.25f) }
    var editMacroBlur by remember { mutableStateOf(0.0f) }
    var editEv by remember { mutableStateOf(0f) }
    var editPolaroid by remember { mutableStateOf(false) }
    var editDisposableFlash by remember { mutableStateOf(false) }

    // Importador de cualquier foto guardada anteriormente en la galería del teléfono
    val systemPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    withContext(Dispatchers.Main) {
                        selectedBitmap = bmp
                        isEditorMode = true
                        viewScale = 1f
                        viewOffset = Offset.Zero
                    }
                }
            }
        }
    }

    LaunchedEffect(
        selectedBitmap, isEditorMode, activePreset, activeLut,
        editLightLeak, editAnamorphic, editGrain, editMacroBlur,
        editEv, editPolaroid, editDisposableFlash
    ) {
        val src = selectedBitmap ?: return@LaunchedEffect
        if (!isEditorMode) {
            previewBitmap = src
            return@LaunchedEffect
        }
        withContext(Dispatchers.Default) {
            val scaledW = 960
            val scaledH = (960f * src.height / src.width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
            val light = AnalogImageProcessor.analyzeSceneLight(
                Bitmap.createScaledBitmap(scaled, 48, 48, true)
            )
            val result = AnalogImageProcessor.processImage(
                source = scaled,
                preset = activePreset,
                customLut = activeLut,
                grainStrength = editGrain,
                noiseStrength = editGrain * 0.4f,
                lightLeakStrength = editLightLeak,
                anamorphicFlareStrength = editAnamorphic,
                opticalFlareStrength = editLightLeak * 0.5f,
                macroBlurStrength = editMacroBlur,
                focusNormX = 0.5f,
                focusNormY = 0.5f,
                lightAnalysis = light,
                exposureBias = editEv,
                polaroidEnabled = editPolaroid,
                disposableFlashActive = editDisposableFlash,
                metadataStamp = "${activePreset.brand} ${activePreset.name}  •  DARKROOM"
            )
            withContext(Dispatchers.Main) { previewBitmap = result }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).padding(12.dp)
    ) {
        // Barra superior de la Galería / Laboratorio
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    if (selectedBitmap != null) {
                        selectedBitmap = null
                        isEditorMode = false
                    } else {
                        onClose()
                    }
                }
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color(0xFFD4AF37))
                Spacer(Modifier.width(4.dp))
                Text(if (selectedBitmap != null) "GALERÍA" else "CÁMARA", color = Color(0xFFD4AF37))
            }

            Text(
                if (isEditorMode) "MODO EDITOR (DARKROOM)" else "GALERÍA PERSISTENTE (${capturedUris.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            Button(
                onClick = { systemPhotoPicker.launch("image/*") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242424)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color(0xFFD4AF37), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("IMPORTAR FOTO", color = Color(0xFFD4AF37), fontSize = 11.sp)
            }
        }

        if (selectedBitmap == null) {
            Text(
                "Tus fotos permanecen guardadas aquí aunque cierres la aplicación. Toca una foto para hacer zoom o editarla, o importa cualquier foto de tu galería:",
                color = Color.Gray,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(capturedUris) { uri ->
                    var itemThumb by remember(uri) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(uri) {
                        withContext(Dispatchers.IO) {
                            val tb = MediaStoreHelper.loadSmallThumbnail(context, uri)
                            withContext(Dispatchers.Main) { itemThumb = tb }
                        }
                    }
                    Surface(
                        color = Color(0xFF1C1C1C),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                scope.launch(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri)?.use { s ->
                                        val b = BitmapFactory.decodeStream(s)
                                        withContext(Dispatchers.Main) {
                                            selectedBitmap = b
                                            isEditorMode = false
                                            viewScale = 1f
                                            viewOffset = Offset.Zero
                                        }
                                    }
                                }
                            }
                    ) {
                        if (itemThumb != null) {
                            Image(
                                bitmap = itemThumb!!.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Image, contentDescription = null, tint = Color(0xFFD4AF37))
                            }
                        }
                    }
                }
            }
        } else {
            // VISOR DE FOTO CON PINCH-TO-ZOOM Y PANEO TÁCTIL
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF050505))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            viewScale = (viewScale * zoom).coerceIn(1f, 6f)
                            if (viewScale > 1f) {
                                viewOffset += pan
                            } else {
                                viewOffset = Offset.Zero
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Previsualización con Zoom",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = viewScale,
                                scaleY = viewScale,
                                translationX = viewOffset.x,
                                translationY = viewOffset.y
                            )
                    )
                }

                // Botón flotante para alternar entre Vista Zoom y Modo Editor
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (viewScale > 1.05f) {
                        Button(
                            onClick = { viewScale = 1f; viewOffset = Offset.Zero },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E1E1E))
                        ) {
                            Text("Restablecer Zoom (${String.format(Locale.US, "%.1fx", viewScale)})", color = Color.White, fontSize = 11.sp)
                        }
                    }
                    Button(
                        onClick = { isEditorMode = !isEditorMode },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37))
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isEditorMode) "OCULTAR CONTROLES" else "EDITAR CON FILMS & VFX", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // PANEL DE EDICIÓN COMPLETO (FILMS CON MINIATURA, VFX, LIGHT LEAKS, ANAMÓRFICO Y GUARDADO)
            if (isEditorMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BUILT_IN_PRESETS) { preset ->
                            val isSel = activePreset.id == preset.id
                            val thumb = remember(preset.id) { FilmThumbnailGenerator.getThumbnailForPreset(preset) }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(82.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) Color(0xFFD4AF37) else Color(0xFF1C1C1C))
                                    .clickable { activePreset = preset }
                                    .padding(4.dp)
                            ) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = preset.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(4.dp))
                                )
                                Text(preset.name, color = if (isSel) Color.Black else Color.White, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = editPolaroid,
                            onClick = { editPolaroid = !editPolaroid },
                            label = { Text("Marco Polaroid", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = editDisposableFlash,
                            onClick = { editDisposableFlash = !editDisposableFlash },
                            label = { Text("Flash Desechable", fontSize = 11.sp) }
                        )
                    }

                    CompactSliderRow("Light Leaks Solares", editLightLeak) { editLightLeak = it }
                    CompactSliderRow("Flares Anamórficos", editAnamorphic) { editAnamorphic = it }
                    CompactSliderRow("Grano Analógico", editGrain) { editGrain = it }
                    CompactSliderRow("Desenfoque Cercano (Bokeh)", editMacroBlur) { editMacroBlur = it }

                    Text("Exposición: ${String.format(Locale.US, "%.1f EV", editEv)}", color = Color.LightGray, fontSize = 11.sp)
                    Slider(value = editEv, onValueChange = { editEv = it }, valueRange = -2f..2f)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                val orig = selectedBitmap ?: return@Button
                                scope.launch(Dispatchers.IO) {
                                    val light = AnalogImageProcessor.analyzeSceneLight(
                                        Bitmap.createScaledBitmap(orig, 48, 48, true)
                                    )
                                    val finalBmp = AnalogImageProcessor.processImage(
                                        source = orig,
                                        preset = activePreset,
                                        customLut = activeLut,
                                        grainStrength = editGrain,
                                        noiseStrength = editGrain * 0.4f,
                                        lightLeakStrength = editLightLeak,
                                        anamorphicFlareStrength = editAnamorphic,
                                        opticalFlareStrength = editLightLeak * 0.5f,
                                        macroBlurStrength = editMacroBlur,
                                        focusNormX = 0.5f,
                                        focusNormY = 0.5f,
                                        lightAnalysis = light,
                                        exposureBias = editEv,
                                        polaroidEnabled = editPolaroid,
                                        disposableFlashActive = editDisposableFlash,
                                        metadataStamp = "${activePreset.brand} ${activePreset.name}  •  DARKROOM"
                                    )
                                    val values = ContentValues().apply {
                                        put(MediaStore.Images.Media.DISPLAY_NAME, "ANALOG_EDIT_${System.currentTimeMillis()}.jpg")
                                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AnalogPro")
                                        }
                                    }
                                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                    if (uri != null) {
                                        context.contentResolver.openOutputStream(uri)?.use { out ->
                                            finalBmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                                        }
                                    }
                                    finalBmp.recycle()
                                    onRefresh()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Copia editada guardada en Galería", Toast.LENGTH_SHORT).show()
                                        selectedBitmap = null
                                        isEditorMode = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37))
                        ) {
                            Text("GUARDAR FOTO EDITADA", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
