package com.xnish.space

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random


private object AppSecurity {

    // Gantikan dengan SHA-256 sijil APK release XNISHz.
    private const val EXPECTED_SHA256 =
        "F36834732F213CACCC34D34DC8B46A52BAD77483E2556BC25179582492A04129"

    fun checkSignature(context: Context): Boolean {
        return runCatching {
            val expected = EXPECTED_SHA256
                .filterNot { it == ':' || it.isWhitespace() }
                .uppercase(Locale.ROOT)

            // Jangan benarkan fingerprint kosong atau tidak sah.
            if (!expected.matches(Regex("[0-9A-F]{64}"))) {
                return@runCatching false
            }

            val pm = context.packageManager

            val signatures = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )

                val signingInfo = info.signingInfo
                    ?: return@runCatching false

                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.toList()
                } else {
                    signingInfo.signingCertificateHistory.toList()
                }

            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )

                @Suppress("DEPRECATION")
                info.signatures?.toList().orEmpty()
            }

            signatures.any { signature ->
                val digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(signature.toByteArray())

                val fingerprint = digest.joinToString("") {
                    "%02X".format(
                        Locale.ROOT,
                        it.toInt() and 0xFF
                    )
                }

                fingerprint.equals(expected, ignoreCase = true)
            }

        }.getOrDefault(false)
    }

    fun detectEmulator(): Boolean {
        val brand = Build.BRAND.lowercase(Locale.ROOT)
        val device = Build.DEVICE.lowercase(Locale.ROOT)
        val model = Build.MODEL.lowercase(Locale.ROOT)
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val product = Build.PRODUCT.lowercase(Locale.ROOT)
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
        val hardware = Build.HARDWARE.lowercase(Locale.ROOT)

        return (
            (brand.startsWith("generic") &&
                device.startsWith("generic")) ||

            fingerprint.startsWith("generic") ||
            fingerprint.contains("vbox") ||

            model.contains("emulator") ||
            model.contains("android sdk built for") ||

            manufacturer.contains("genymotion") ||

            hardware == "goldfish" ||
            hardware == "ranchu" ||
            hardware == "vbox86" ||

            product.contains("sdk_gphone") ||
            product.contains("emulator")
        )
    }
}


// -----------------------------------------------------------------------------
// Shizuku helper
// -----------------------------------------------------------------------------
object Sh {
    private const val REQ = 1001

    fun alive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun ask() {
        runCatching {
            if (alive()) Shizuku.requestPermission(REQ)
        }
    }

    fun run(cmd: String): String {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val process = method.invoke(
            null,
            arrayOf("sh", "-c", cmd),
            null,
            null
        ) as Process

        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        process.waitFor()
        return (output + errors).trim()
    }
}

// -----------------------------------------------------------------------------
// App models and installed-app scanner
// -----------------------------------------------------------------------------
data class Game(
    val name: String,
    val pkg: String,
    val isGame: Boolean,
    val icon: ImageBitmap? = null
)

private val KNOWN_GAME_PKGS = listOf(
    "freefire", "mobile.legends", "tencent", "pubg", "genshin", "mihoyo",
    "roblox", "callofduty", "activision", "supercell", "brawlstars",
    "clash", "minecraft", "mojang", "carx", "ea.game", "fifa", "efootball",
    "konami", "garena", "honorofkings", "levelinfinite", "riotgames", "valorant"
)

private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap? = runCatching {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return@runCatching drawable.bitmap.asImageBitmap()
    }

    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    bitmap.asImageBitmap()
}.getOrNull()

private fun loadInstalledApps(context: Context): List<Game> {
    val packageManager = context.packageManager
    return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        .mapNotNull { app ->
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) return@mapNotNull null
            if (app.packageName == context.packageName) return@mapNotNull null
            if (packageManager.getLaunchIntentForPackage(app.packageName) == null) return@mapNotNull null

            val packageLower = app.packageName.lowercase()
            val isCategoryGame = app.category == ApplicationInfo.CATEGORY_GAME ||
                (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            val isKnownGame = KNOWN_GAME_PKGS.any(packageLower::contains)
            val icon = runCatching {
                drawableToImageBitmap(packageManager.getApplicationIcon(app))
            }.getOrNull()

            Game(
                name = packageManager.getApplicationLabel(app).toString(),
                pkg = app.packageName,
                isGame = isCategoryGame || isKnownGame,
                icon = icon
            )
        }
        .sortedWith(compareByDescending<Game> { it.isGame }.thenBy { it.name.lowercase() })
}

enum class TexMode { Blur, Pixel }

enum class GpuDriver(val label: String, val sub: String) {
    Default("Default", "SYSTEM"),
    GameDriver("Game Driver", "UPDATABLE"),
    Angle("ANGLE", "LOW GRAPHICS"),
    Vulkan("Vulkan", "EXPERIMENTAL")
}

enum class GameModeOpt(val label: String, val id: Int) {
    Standard("Standard", 1),
    Performance("Performance", 2),
    Saver("Saver", 3)
}

enum class AnimSpeed(val label: String, val scale: String) {
    Normal("Normal", "1.0"),
    Fast("Fast", "0.5"),
    Off("Off", "0.0")
}

data class ConfigState(
    val texMode: TexMode = TexMode.Pixel,
    val texLevel: Int = 4,
    val resolution: Int = 100,
    val fps: String = "Max",
    val msaa4x: Boolean = false,
    val driver: GpuDriver = GpuDriver.Angle,
    val gameMode: GameModeOpt = GameModeOpt.Performance,
    val fixedPerf: Boolean = true,
    val animSpeed: AnimSpeed = AnimSpeed.Fast,
    val netBoost: Boolean = true,
    val batterySaver: Boolean = false
)

// -----------------------------------------------------------------------------
// Existing Shizuku command engine
// -----------------------------------------------------------------------------
private fun applyAll(selected: List<Game>, config: ConfigState): String {
    if (selected.isEmpty()) return "Pilih sekurang-kurangnya satu game."

    val commands = mutableListOf<String>()
    val packages = selected.joinToString(",") { it.pkg }

    when (config.driver) {
        GpuDriver.Default -> {
            commands += "settings delete global angle_gl_driver_selection_pkgs"
            commands += "settings delete global angle_gl_driver_selection_values"
            commands += "settings delete global updatable_driver_production_opt_in_apps"
            commands += "setprop debug.angle.feature_overrides_enabled \"\""
            commands += "setprop debug.hwui.renderer opengl"
        }
        GpuDriver.GameDriver -> {
            commands += "settings delete global angle_gl_driver_selection_pkgs"
            commands += "settings delete global angle_gl_driver_selection_values"
            commands += "settings put global updatable_driver_production_opt_in_apps $packages"
            commands += "setprop debug.hwui.renderer opengl"
        }
        GpuDriver.Angle -> {
            val values = selected.joinToString(",") { "angle" }
            val features = when (config.texMode) {
                TexMode.Blur -> "disableAnisotropicFiltering:forceTextureLodOffset${config.texLevel}"
                TexMode.Pixel -> "forceNearestFiltering:disableAnisotropicFiltering:forceTextureLodOffset${config.texLevel}"
            }
            commands += "settings put global angle_gl_driver_selection_pkgs $packages"
            commands += "settings put global angle_gl_driver_selection_values $values"
            commands += "setprop debug.angle.feature_overrides_enabled $features"
        }
        GpuDriver.Vulkan -> {
            commands += "settings delete global angle_gl_driver_selection_pkgs"
            commands += "settings delete global angle_gl_driver_selection_values"
            commands += "setprop debug.hwui.renderer skiagl"
            commands += "setprop debug.vulkan.layers \"\""
        }
    }

    val downscale = if (config.resolution >= 100) "disable" else (config.resolution / 100f).toString()
    val fpsArgument = if (config.fps == "Max") "disable" else config.fps

    selected.forEach { game ->
        commands += "cmd game mode ${config.gameMode.id} ${game.pkg} 2>/dev/null || true"
        commands += "cmd game set --mode ${config.gameMode.id} --downscale $downscale --fps $fpsArgument ${game.pkg} 2>/dev/null || true"
    }

    val maxRefresh = if (config.fps == "Max") "120.0" else "${config.fps}.0"
    val minRefresh = if (config.batterySaver) "60.0" else maxRefresh
    commands += "settings put system peak_refresh_rate $maxRefresh"
    commands += "settings put system min_refresh_rate $minRefresh"

    commands += "setprop debug.egl.force_msaa ${if (config.msaa4x) "true" else "false"}"
    commands += "service call SurfaceFlinger 1008 i32 ${if (config.msaa4x) 1 else 0} 2>/dev/null || true"
    commands += "cmd power set-fixed-performance-mode-enabled ${config.fixedPerf}"

    commands += "settings put global window_animation_scale ${config.animSpeed.scale}"
    commands += "settings put global transition_animation_scale ${config.animSpeed.scale}"
    commands += "settings put global animator_duration_scale ${config.animSpeed.scale}"

    if (config.netBoost) {
        commands += "settings put global wifi_sleep_policy 2"
        commands += "settings put global wifi_scan_always_enabled 0"
        commands += "settings put global ble_scan_always_enabled 0"
        commands += "settings put global network_recommendations_enabled 0"
        commands += "settings put global private_dns_mode hostname"
        commands += "settings put global private_dns_specifier one.one.one.one"
        commands += "setprop net.tcp.nodelay 1"
        commands += "content insert --uri content://settings/global --bind name:s:auto_sync --bind value:s:0 2>/dev/null || true"
    } else {
        commands += "settings put global wifi_scan_always_enabled 1"
        commands += "settings put global private_dns_mode opportunistic"
    }

    if (config.batterySaver) {
        commands += "cmd power set-mode 1"
        commands += "cmd power set-adaptive-power-saver-enabled true"
        commands += "settings put global app_standby_enabled 1"
        commands += "settings put global forced_app_standby_enabled 1"
    } else {
        commands += "cmd power set-mode 0"
        commands += "settings put global forced_app_standby_enabled 0"
    }

    selected.forEach { game -> commands += "am force-stop ${game.pkg}" }

    val output = Sh.run(commands.joinToString(" && "))
    return "Tetapan dihantar untuk ${selected.size} game." +
        if (output.isNotBlank()) "\n$output" else "\nSemak kesan tetapan pada peranti anda."
}

private fun resetAll(selected: List<Game>): String {
    val commands = mutableListOf(
        "settings delete global angle_gl_driver_selection_values",
        "settings delete global angle_gl_driver_selection_pkgs",
        "settings delete global updatable_driver_production_opt_in_apps",
        "setprop debug.angle.feature_overrides_enabled \"\"",
        "setprop debug.hwui.renderer opengl",
        "setprop debug.egl.force_msaa false",
        "service call SurfaceFlinger 1008 i32 0 2>/dev/null || true",
        "cmd power set-fixed-performance-mode-enabled false",
        "cmd power set-mode 0",
        "settings put global window_animation_scale 1.0",
        "settings put global transition_animation_scale 1.0",
        "settings put global animator_duration_scale 1.0",
        "settings put global wifi_scan_always_enabled 1",
        "settings put global ble_scan_always_enabled 1",
        "settings put global private_dns_mode opportunistic",
        "settings put global forced_app_standby_enabled 0",
        "settings delete system peak_refresh_rate",
        "settings delete system min_refresh_rate"
    )

    selected.forEach { game ->
        commands += "cmd game reset ${game.pkg} 2>/dev/null || true"
        commands += "am force-stop ${game.pkg}"
    }

    val output = Sh.run(commands.joinToString(" && "))
    return "Arahan reset telah dihantar." + if (output.isNotBlank()) "\n$output" else ""
}

private fun checkAngle(): String {
    val output = Sh.run("ls -d /system/*/*angle* /vendor/*/*angle* 2>/dev/null")
    return if (output.isBlank()) {
        "ANGLE tidak dijumpai pada laluan sistem yang diperiksa."
    } else {
        "ANGLE yang ditemui:\n$output"
    }
}

// -----------------------------------------------------------------------------
// Activity and crash screen
// -----------------------------------------------------------------------------
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Signature Checker
        val signatureValid = AppSecurity.checkSignature(this)

        // Benarkan pembangunan/debug tanpa fingerprint release.
        val isDebuggable =
            (applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

        if (!signatureValid) {

            if (!isDebuggable) {
                Toast.makeText(
                    this,
                    "XNISHz: Invalid APK Signature",
                    Toast.LENGTH_LONG
                ).show()

                finish()
                return
            } else {
                Log.w(
                    "XNISHzSecurity",
                    "Signature belum disahkan dalam debug build"
                )
            }
        }

        // 2. Emulator Detection
        if (AppSecurity.detectEmulator()) {
            Log.w(
                "XNISHzSecurity",
                "Emulator environment detected"
            )
        }

        // 3. Load UI asal
        val crashFile = java.io.File(filesDir, "crash.txt")
        val previousCrash =
            if (crashFile.exists()) crashFile.readText() else null

        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            runCatching {
                crashFile.writeText(error.stackTraceToString())
            }

            android.os.Process.killProcess(
                android.os.Process.myPid()
            )
        }

        // Edge-to-edge: aurora background draws behind status & navigation bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Ink,
                    secondary = AccentSoft,
                    background = Ink,
                    surface = Panel
                )
            ) {
                if (previousCrash != null) {
                    CrashView(previousCrash) {
                        crashFile.delete()
                        recreate()
                    }
                } else {
                    LowGXApp()
                }
            }
        }
    }
}

@Composable
private fun CrashView(log: String, onClear: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink).statusBarsPadding().padding(20.dp)) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "LOWGX / CRASH REPORT",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(log.take(5000), color = Color(0xFFFFB4AB), fontSize = 11.sp)
                }
            }
            Button(onClick = onClear) { Text("Padam log dan cuba lagi") }
        }
    }
}

// -----------------------------------------------------------------------------
// Palette
// -----------------------------------------------------------------------------
private val Ink = Color(0xFF070A14)
private val InkBlue = Color(0xFF10182A)
private val Panel = Color(0xD9162034)
private val PanelSoft = Color(0x99141D30)
private val Accent = Color(0xFFB8A4FF)
private val AccentSoft = Color(0xFF8D83D8)
private val AccentDeep = Color(0xFF6957B8)
private val TextMain = Color(0xFFF7F6FF)
private val TextMuted = Color(0xFFA7B1CB)
private val StrokeGlass = Color(0x40E5E5FF)
private val Good = Color(0xFF7BF0C0)
private val Warm = Color(0xFFFFC98A)

private val AuroraIndigo = Color(0xFF5B4BFF)
private val AuroraCyan = Color(0xFF16B7D8)
private val AuroraRose = Color(0xFFD14BC4)
private val AuroraBlue = Color(0xFF2E6BFF)

// -----------------------------------------------------------------------------
// Modern aurora background (slow drifting colour fields + faint star dust)
// -----------------------------------------------------------------------------
private data class StarParticle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val phase: Float
)

@Composable
private fun AuroraBackground() {
    val stars = remember {
        List(55) {
            StarParticle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                radius = Random.nextFloat() * 1.1f + 0.4f,
                speed = Random.nextFloat() * 0.03f + 0.01f,
                phase = Random.nextFloat() * (2f * PI.toFloat())
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "aurora")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(48000, easing = LinearEasing)),
        label = "aurora-progress"
    )

    Canvas(Modifier.fillMaxSize().background(Ink)) {
        val w = size.width
        val h = size.height
        val a = progress * 2f * PI.toFloat()

        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF060914), Color(0xFF0A0F22), Color(0xFF070A18))
            )
        )

        fun blob(cx: Float, cy: Float, r: Float, c: Color) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(c, Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r
                ),
                radius = r,
                center = Offset(cx, cy)
            )
        }

        blob(w * (.18f + .14f * sin(a)), h * (.14f + .07f * cos(a)), w * .95f, AuroraIndigo.copy(alpha = .60f))
        blob(w * (.92f + .08f * cos(a * 2f)), h * (.42f + .08f * sin(a)), w * .85f, AuroraCyan.copy(alpha = .40f))
        blob(w * (.10f + .10f * cos(a + 1.6f)), h * (.74f + .06f * sin(a * 2f)), w * .85f, AuroraRose.copy(alpha = .34f))
        blob(w * (.82f + .08f * sin(a + 3f)), h * (.95f + .03f * cos(a)), w * .75f, AuroraBlue.copy(alpha = .38f))

        // Soft scrim so text stays readable on top of the colour fields.
        drawRect(Ink.copy(alpha = .30f))

        stars.forEach { star ->
            val y = ((star.y - progress * star.speed * 10f) % 1f + 1f) % 1f
            val x = star.x + sin(a * .35f + star.phase) * .006f
            val alpha = .10f + .26f * ((sin(a * 2f + star.phase) + 1f) / 2f)
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = star.radius,
                center = Offset(x * w, y * h)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Liquid-glass building blocks
// -----------------------------------------------------------------------------

/**
 * Liquid glass surface: dark translucent base, light veil, specular bloom in the
 * top-left, tinted caustic in the bottom-right, a bright top edge and a gradient rim.
 */
private fun Modifier.liquidGlass(shape: Shape, tint: Color = Accent): Modifier = this
    .clip(shape)
    .background(Color(0x4D0A1022))
    .background(
        Brush.verticalGradient(
            listOf(Color(0x2EFFFFFF), Color(0x12FFFFFF), Color(0x1CFFFFFF))
        )
    )
    .drawBehind {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = .20f), Color.Transparent),
                center = Offset(w * .12f, 0f),
                radius = w * .75f
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(tint.copy(alpha = .16f), Color.Transparent),
                center = Offset(w, h),
                radius = w * .7f
            )
        )
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = .55f), Color.Transparent)
            ),
            start = Offset(w * .10f, 1.dp.toPx()),
            end = Offset(w * .90f, 1.dp.toPx()),
            strokeWidth = 1.2.dp.toPx()
        )
    }
    .border(
        1.dp,
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = .60f),
                Color.White.copy(alpha = .08f),
                Color.White.copy(alpha = .02f),
                Color.White.copy(alpha = .28f)
            )
        ),
        shape
    )

/** Brighter glass used for the sliding selection thumb in segmented controls. */
private fun Modifier.glassThumb(shape: Shape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .36f), Accent.copy(alpha = .34f))))
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = .35f), Color.Transparent),
                center = Offset(size.width * .3f, 0f),
                radius = size.width * .8f
            )
        )
    }
    .border(
        1.dp,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .75f), Color.White.copy(alpha = .12f))),
        shape
    )

@Composable
private fun Glass(
    modifier: Modifier = Modifier,
    radius: Int = 30,
    padding: Dp = 18.dp,
    tint: Color = Accent,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius.dp)
    Column(
        modifier = modifier
            .shadow(
                elevation = 22.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x66000000),
                spotColor = Color(0x663A2FB0)
            )
            .liquidGlass(shape, tint)
            .let { base -> if (onClick != null) base.clickable { onClick() } else base }
            .padding(padding),
        content = content
    )
}

@Composable
private fun GlassDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = .08f))
    )
}

@Composable
private fun SectionHeading(glyph: String, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .liquidGlass(RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FieldLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** iOS-style segmented control with an animated liquid-glass thumb. */
@Composable
private fun GlassSegmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    subs: List<String>? = null,
    onSelect: (Int) -> Unit
) {
    val outer = RoundedCornerShape(21.dp)
    val inner = RoundedCornerShape(18.dp)
    val height = if (subs != null) 62.dp else 44.dp

    BoxWithConstraints(
        modifier
            .height(height)
            .clip(outer)
            .background(Color(0x40000000))
            .border(1.dp, Color.White.copy(alpha = .10f), outer)
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(dampingRatio = .72f, stiffness = 380f),
            label = "segment-thumb"
        )

        Box(
            Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .glassThumb(inner)
        )

        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(inner)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(index) }
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else TextMuted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sub = subs?.getOrNull(index)
                    if (sub != null) {
                        Text(
                            sub,
                            color = if (selected) Color.White.copy(alpha = .75f) else TextMuted.copy(alpha = .7f),
                            fontSize = 7.5.sp,
                            letterSpacing = .4.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** Pill button with a springy press animation. */
@Composable
private fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 46.dp,
    horizontalPadding: Dp = 16.dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(height / 2)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .93f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "button-press"
    )

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (enabled) 1f else .45f)
            .height(height)
            .clip(shape)
            .background(
                if (primary) Brush.linearGradient(
                    listOf(Color(0xFFE3DBFF), Color(0xFFA796FF), Color(0xFF7C6EF0))
                ) else Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = .20f), Color.White.copy(alpha = .08f))
                )
            )
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = if (primary) .45f else .22f), Color.Transparent),
                        center = Offset(size.width * .25f, 0f),
                        radius = size.width * .9f
                    )
                )
            }
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (primary) .85f else .45f), Color.White.copy(alpha = .08f))
                ),
                shape
            )
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (primary) Color(0xFF181331) else TextMain,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    badge: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge,
                        color = Warm,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Warm.copy(alpha = .14f))
                            .border(1.dp, Warm.copy(alpha = .25f), RoundedCornerShape(50))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = TextMuted, fontSize = 10.5.sp, lineHeight = 14.5.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF8F7BFF),
                checkedBorderColor = Color.White.copy(alpha = .35f),
                uncheckedThumbColor = Color.White.copy(alpha = .85f),
                uncheckedTrackColor = Color.White.copy(alpha = .14f),
                uncheckedBorderColor = Color.White.copy(alpha = .18f)
            )
        )
    }
}

@Composable
private fun SelectCircle(selected: Boolean) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (selected) Brush.linearGradient(listOf(Color(0xFFE3DBFF), Color(0xFF8F7BFF)))
                else Brush.linearGradient(listOf(Color.White.copy(alpha = .06f), Color.White.copy(alpha = .06f)))
            )
            .border(
                1.dp,
                if (selected) Color.White.copy(alpha = .8f) else Color.White.copy(alpha = .25f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Text("✓", color = Color(0xFF181331), fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun GlassSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    val pill = RoundedCornerShape(50)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = TextMain, fontSize = 13.sp),
        cursorBrush = SolidColor(Accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(pill)
                    .background(Color(0x40000000))
                    .border(1.dp, Color.White.copy(alpha = .10f), pill)
                    .padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(Modifier.size(15.dp)) {
                    val stroke = 1.6.dp.toPx()
                    drawCircle(
                        color = TextMuted,
                        radius = size.width * .34f,
                        center = Offset(size.width * .42f, size.height * .42f),
                        style = Stroke(stroke)
                    )
                    drawLine(
                        color = TextMuted,
                        start = Offset(size.width * .68f, size.height * .68f),
                        end = Offset(size.width * .95f, size.height * .95f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
                Spacer(Modifier.width(9.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = TextMuted.copy(alpha = .8f), fontSize = 12.sp)
                    }
                    inner()
                }
            }
        }
    )
}

// -----------------------------------------------------------------------------
// Main screen
// -----------------------------------------------------------------------------
@Composable
private fun LowGXApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var shizukuStatus by remember { mutableStateOf("Memeriksa sambungan…") }
    var config by remember { mutableStateOf(ConfigState()) }
    var installedApps by remember { mutableStateOf<List<Game>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(true) }
    var showGamesOnly by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Game>() }
    var statusMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refreshShizuku() {
        shizukuStatus = when {
            !Sh.alive() -> "Service tidak aktif"
            !Sh.granted() -> "Kebenaran diperlukan"
            else -> "Connected"
        }
    }

    fun scanApps() {
        scope.launch {
            loadingApps = true
            val apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
            installedApps = apps
            if (apps.none { it.isGame }) showGamesOnly = false
            if (selected.isEmpty()) apps.firstOrNull { it.isGame }?.let(selected::add)
            loadingApps = false
        }
    }

    LaunchedEffect(Unit) {
        refreshShizuku()
        scanApps()
    }

    val selectedPackages = selected.map { it.pkg }.toSet()
    val filteredApps = remember(installedApps, showGamesOnly, searchQuery, selectedPackages) {
        installedApps.filter { app ->
            val matchesTab = if (showGamesOnly) app.isGame || app.pkg in selectedPackages else true
            val matchesSearch = searchQuery.isBlank() ||
                app.name.contains(searchQuery, ignoreCase = true) ||
                app.pkg.contains(searchQuery, ignoreCase = true)
            matchesTab && matchesSearch
        }
    }

    fun runApply() {
        if (!Sh.granted()) {
            statusMessage = "Shizuku belum diberi kebenaran. Tekan kad Shizuku dahulu."
            return
        }
        if (selected.isEmpty()) {
            statusMessage = "Pilih sekurang-kurangnya satu game."
            return
        }
        scope.launch {
            busy = true
            statusMessage = withContext(Dispatchers.IO) {
                runCatching { applyAll(selected.toList(), config) }
                    .getOrElse { "Ralat: ${it.message ?: "Tidak diketahui"}" }
            }
            busy = false
        }
    }

    fun runReset() {
        if (!Sh.granted()) {
            statusMessage = "Shizuku belum diberi kebenaran. Tekan kad Shizuku dahulu."
            return
        }
        scope.launch {
            busy = true
            statusMessage = withContext(Dispatchers.IO) {
                runCatching { resetAll(selected.toList()) }
                    .getOrElse { "Ralat: ${it.message ?: "Tidak diketahui"}" }
            }
            config = ConfigState()
            busy = false
        }
    }

    fun launchSelectedGame() {
        if (!Sh.granted()) {
            statusMessage = "Shizuku belum diberi kebenaran. Tekan kad Shizuku dahulu."
            return
        }
        val targetPackage = selected.firstOrNull()?.pkg
        if (targetPackage == null) {
            statusMessage = "Pilih game yang hendak dilancarkan."
            return
        }
        scope.launch {
            busy = true
            statusMessage = withContext(Dispatchers.IO) {
                runCatching { applyAll(selected.toList(), config) }
                    .getOrElse { "Ralat: ${it.message ?: "Tidak diketahui"}" }
            }
            busy = false

            runCatching {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                } else {
                    statusMessage += "\nAplikasi tidak menyediakan aktiviti pelancaran."
                }
            }.onFailure {
                statusMessage += "\nGagal membuka game: ${it.message ?: "Ralat"}"
            }
        }
    }

    // Soft pulse for the connection dot
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = .35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "pulse-alpha"
    )

    Box(Modifier.fillMaxSize()) {
        AuroraBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---- Header ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFEDE8FF), Color(0xFF9D8CFF), Color(0xFF4F46C8))
                            )
                        )
                        .drawBehind {
                            drawRect(
                                Brush.radialGradient(
                                    listOf(Color.White.copy(alpha = .55f), Color.Transparent),
                                    center = Offset(size.width * .2f, 0f),
                                    radius = size.width
                                )
                            )
                        }
                        .border(
                            1.dp,
                            Brush.verticalGradient(listOf(Color.White.copy(alpha = .9f), Color.White.copy(alpha = .1f))),
                            RoundedCornerShape(19.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("XN", color = Color(0xFF171329), fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "XNISHz",
                        color = TextMain,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp
                    )
                    Text("Game control center", color = TextMuted, fontSize = 12.sp)
                }
                Text(
                    "Space  v1.0",
                    color = TextMain,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .liquidGlass(RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }

            // ---- Shizuku status ----
            Glass(Modifier.fillMaxWidth(), radius = 28, padding = 14.dp, onClick = {
                if (Sh.alive() && !Sh.granted()) Sh.ask()
                refreshShizuku()
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).liquidGlass(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background((if (Sh.granted()) Good else Warm).copy(alpha = if (Sh.granted()) pulse else 1f))
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Shizuku", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(shizukuStatus, color = if (Sh.granted()) Good else Warm, fontSize = 11.sp)
                    }
                    Text(
                        "Segarkan",
                        color = Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Accent.copy(alpha = .14f))
                            .border(1.dp, Accent.copy(alpha = .28f), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }

            // ---- Game library ----
            Glass(Modifier.fillMaxWidth(), radius = 32) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your games", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                        Text("Pilih aplikasi yang mahu diuruskan.", color = TextMuted, fontSize = 11.5.sp)
                    }
                    Box(
                        Modifier.size(52.dp).liquidGlass(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${selected.size}", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text("dipilih", color = TextMuted, fontSize = 7.sp)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassSegmented(
                        options = listOf("Games", "All apps · ${installedApps.size}"),
                        selectedIndex = if (showGamesOnly) 0 else 1,
                        modifier = Modifier.weight(1f)
                    ) { showGamesOnly = it == 0 }
                    GlassButton(
                        text = if (loadingApps) "…" else "↻ Scan",
                        enabled = !loadingApps,
                        height = 44.dp,
                        horizontalPadding = 14.dp
                    ) { scanApps() }
                }

                Spacer(Modifier.height(10.dp))
                GlassSearchField(
                    value = searchQuery,
                    placeholder = "Cari nama game atau package…",
                    onValueChange = { searchQuery = it }
                )

                Spacer(Modifier.height(10.dp))
                if (loadingApps) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    }
                } else if (filteredApps.isEmpty()) {
                    Text(
                        "Tiada aplikasi ditemui. Cuba tab All apps.",
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(vertical = 18.dp)
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        filteredApps.forEach { game ->
                            val isSelected = game.pkg in selectedPackages
                            val rowShape = RoundedCornerShape(18.dp)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(if (isSelected) Accent.copy(alpha = .14f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) Accent.copy(alpha = .35f) else Color.Transparent,
                                        rowShape
                                    )
                                    .clickable {
                                        if (isSelected) selected.removeAll { it.pkg == game.pkg }
                                        else selected.add(game)
                                    }
                                    .padding(horizontal = 9.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (game.icon != null) {
                                    Image(
                                        bitmap = game.icon,
                                        contentDescription = game.name,
                                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                                    )
                                } else {
                                    Box(
                                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .08f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("APP", color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(game.name, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(game.pkg, color = TextMuted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(8.dp))
                                SelectCircle(isSelected)
                            }
                        }
                    }
                }
            }

            // ---- Graphics ----
            Glass(Modifier.fillMaxWidth(), radius = 32) {
                SectionHeading("◈", "Graphics", "Visual quality and frame-rate options")
                Spacer(Modifier.height(18.dp))

                FieldLabel(
                    "Game resolution",
                    "${config.resolution}%  ·  ${if (config.resolution == 100) "Native" else "Scaled"}"
                )
                Slider(
                    value = config.resolution.toFloat(),
                    onValueChange = { config = config.copy(resolution = (it / 5).roundToInt() * 5) },
                    valueRange = 50f..100f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color.White.copy(alpha = .14f)
                    )
                )

                Spacer(Modifier.height(8.dp))
                FieldLabel("FPS limit")
                Spacer(Modifier.height(9.dp))
                val fpsOptions = listOf("30", "60", "90", "120", "Max")
                GlassSegmented(
                    options = fpsOptions,
                    selectedIndex = fpsOptions.indexOf(config.fps).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth()
                ) { config = config.copy(fps = fpsOptions[it]) }

                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = "4x MSAA",
                    subtitle = "Pilihan ini mungkin tidak disokong oleh semua GPU.",
                    checked = config.msaa4x,
                    badge = "ADVANCED"
                ) { config = config.copy(msaa4x = it) }

                Spacer(Modifier.height(14.dp))
                GlassDivider()
                Spacer(Modifier.height(14.dp))
                FieldLabel("Graphics driver")
                Spacer(Modifier.height(9.dp))
                val drivers = GpuDriver.values().toList()
                GlassSegmented(
                    options = drivers.map { it.label },
                    subs = drivers.map { it.sub },
                    selectedIndex = drivers.indexOf(config.driver),
                    modifier = Modifier.fillMaxWidth()
                ) { config = config.copy(driver = drivers[it]) }

                if (config.driver == GpuDriver.Angle) {
                    Spacer(Modifier.height(16.dp))
                    GlassDivider()
                    Spacer(Modifier.height(14.dp))
                    FieldLabel("ANGLE texture profile")
                    Spacer(Modifier.height(9.dp))
                    GlassSegmented(
                        options = listOf("Smooth", "Pixel"),
                        selectedIndex = if (config.texMode == TexMode.Blur) 0 else 1,
                        modifier = Modifier.fillMaxWidth()
                    ) { config = config.copy(texMode = if (it == 0) TexMode.Blur else TexMode.Pixel) }

                    Spacer(Modifier.height(13.dp))
                    FieldLabel("Texture LOD", "Level ${config.texLevel}")
                    Spacer(Modifier.height(8.dp))
                    GlassSegmented(
                        options = listOf("1", "2", "3", "4"),
                        selectedIndex = (config.texLevel - 1).coerceIn(0, 3),
                        modifier = Modifier.fillMaxWidth()
                    ) { config = config.copy(texLevel = it + 1) }
                }
            }

            // ---- Performance ----
            Glass(Modifier.fillMaxWidth(), radius = 32) {
                SectionHeading("⚡", "Performance", "Android game mode and interface behaviour")
                Spacer(Modifier.height(17.dp))
                FieldLabel("Game mode")
                Spacer(Modifier.height(9.dp))
                val modes = GameModeOpt.values().toList()
                GlassSegmented(
                    options = modes.map { it.label },
                    selectedIndex = modes.indexOf(config.gameMode),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val mode = modes[it]
                    config = config.copy(
                        gameMode = mode,
                        batterySaver = if (mode == GameModeOpt.Saver) true else config.batterySaver
                    )
                }

                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = "Fixed performance mode",
                    subtitle = "Meminta mod prestasi tetap jika peranti menyokongnya.",
                    checked = config.fixedPerf
                ) { config = config.copy(fixedPerf = it) }

                Spacer(Modifier.height(13.dp))
                GlassDivider()
                Spacer(Modifier.height(13.dp))
                FieldLabel("System animation")
                Spacer(Modifier.height(9.dp))
                val speeds = AnimSpeed.values().toList()
                GlassSegmented(
                    options = speeds.map { it.label },
                    selectedIndex = speeds.indexOf(config.animSpeed),
                    modifier = Modifier.fillMaxWidth()
                ) { config = config.copy(animSpeed = speeds[it]) }
            }

            // ---- Network & power ----
            Glass(Modifier.fillMaxWidth(), radius = 32) {
                SectionHeading("◎", "Network & power", "Connection preferences and battery behaviour")
                Spacer(Modifier.height(16.dp))
                ToggleRow(
                    title = "Wi-Fi & DNS preferences",
                    subtitle = "Mengubah imbasan latar dan Private DNS. Ia tidak menjamin ping lebih rendah.",
                    checked = config.netBoost,
                    badge = "NETWORK"
                ) { config = config.copy(netBoost = it) }

                Spacer(Modifier.height(12.dp))
                GlassDivider()
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = "Battery saver",
                    subtitle = "Mengaktifkan mod jimat kuasa dan had refresh rate minimum.",
                    checked = config.batterySaver,
                    badge = "ECO"
                ) {
                    config = config.copy(
                        batterySaver = it,
                        fixedPerf = if (it) false else config.fixedPerf
                    )
                }
            }

            // ---- Status message ----
            if (statusMessage.isNotBlank()) {
                Glass(Modifier.fillMaxWidth(), radius = 24, padding = 15.dp) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .padding(top = 4.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (statusMessage.startsWith("Ralat")) Warm else Good)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            statusMessage,
                            color = TextMain,
                            fontSize = 11.5.sp,
                            lineHeight = 16.5.sp
                        )
                    }
                }
            }

            // ---- Check ANGLE ----
            Glass(Modifier.fillMaxWidth(), radius = 26, padding = 15.dp, onClick = {
                if (!Sh.granted()) {
                    statusMessage = "Shizuku belum diberi kebenaran."
                } else {
                    scope.launch {
                        busy = true
                        statusMessage = withContext(Dispatchers.IO) {
                            runCatching { checkAngle() }.getOrElse { "Ralat: ${it.message ?: "Tidak diketahui"}" }
                        }
                        busy = false
                    }
                }
            }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Check ANGLE support", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Semak sama ada ANGLE wujud pada sistem.", color = TextMuted, fontSize = 10.5.sp)
                    }
                    Text("↗", color = Accent, fontSize = 18.sp)
                }
            }

            Text(
                "XNISHz Space  ·  Graphics  ·  Performance  ·  Control",
                color = TextMuted.copy(alpha = .6f),
                fontSize = 9.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)
            )
        }

        // ---- Floating glass dock ----
        Glass(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            radius = 36,
            padding = 11.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        "${selected.size} game${if (selected.size == 1) "" else "s"}",
                        color = TextMain,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        if (busy) "Applying settings…" else "Ready to apply",
                        color = TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
                GlassButton("Reset", enabled = !busy, horizontalPadding = 14.dp) { runReset() }
                Spacer(Modifier.width(6.dp))
                GlassButton(if (busy) "…" else "Apply", primary = true, enabled = !busy, horizontalPadding = 16.dp) { runApply() }
                Spacer(Modifier.width(6.dp))
                GlassButton("Launch ↗", enabled = !busy, horizontalPadding = 14.dp) { launchSelectedGame() }
            }
        }
    }
}
