package com.xnish.space

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

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

        val crashFile = java.io.File(filesDir, "crash.txt")
        val previousCrash = if (crashFile.exists()) crashFile.readText() else null

        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            runCatching { crashFile.writeText(error.stackTraceToString()) }
            android.os.Process.killProcess(android.os.Process.myPid())
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
    Box(Modifier.fillMaxSize().background(Ink).padding(20.dp)) {
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
// Liquid-glass palette
// -----------------------------------------------------------------------------
private val Ink = Color(0xFF080B14)
private val InkBlue = Color(0xFF10182A)
private val Panel = Color(0xD9162034)
private val PanelSoft = Color(0x99141D30)
private val Accent = Color(0xFFB8A4FF)
private val AccentSoft = Color(0xFF8D83D8)
private val AccentDeep = Color(0xFF6957B8)
private val TextMain = Color(0xFFF4F2FC)
private val TextMuted = Color(0xFF9DA9C2)
private val StrokeGlass = Color(0x40E5E5FF)
private val Good = Color(0xFF8BE0BC)
private val Warm = Color(0xFFF0C98B)

// -----------------------------------------------------------------------------
// Moving zodiac constellations
// -----------------------------------------------------------------------------
private data class StarParticle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val phase: Float
)

private data class ZodiacConstellation(
    val symbol: String,
    val x: Float,
    val y: Float,
    val points: List<Offset>,
    val links: List<Pair<Int, Int>>
)

@Composable
private fun ZodiacBackground() {
    val particles = remember {
        List(105) {
            StarParticle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                radius = Random.nextFloat() * 1.45f + 0.45f,
                speed = Random.nextFloat() * 0.035f + 0.008f,
                phase = Random.nextFloat() * (2f * PI.toFloat())
            )
        }
    }

    val constellations = remember {
        listOf(
            ZodiacConstellation("♈", .15f, .16f, listOf(Offset(.18f,.18f), Offset(.43f,.35f), Offset(.62f,.23f), Offset(.82f,.43f)), listOf(0 to 1, 1 to 2, 2 to 3)),
            ZodiacConstellation("♉", .78f, .12f, listOf(Offset(.16f,.20f), Offset(.37f,.45f), Offset(.60f,.20f), Offset(.79f,.48f), Offset(.60f,.76f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4)),
            ZodiacConstellation("♊", .34f, .35f, listOf(Offset(.20f,.16f), Offset(.25f,.82f), Offset(.72f,.18f), Offset(.76f,.83f), Offset(.25f,.49f), Offset(.75f,.50f)), listOf(0 to 1, 2 to 3, 0 to 4, 4 to 5, 5 to 2)),
            ZodiacConstellation("♋", .90f, .39f, listOf(Offset(.17f,.30f), Offset(.43f,.15f), Offset(.66f,.39f), Offset(.44f,.68f), Offset(.18f,.56f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4)),
            ZodiacConstellation("♌", .13f, .57f, listOf(Offset(.18f,.25f), Offset(.42f,.18f), Offset(.61f,.40f), Offset(.80f,.22f), Offset(.65f,.72f)), listOf(0 to 1, 1 to 2, 2 to 3, 2 to 4)),
            ZodiacConstellation("♍", .72f, .57f, listOf(Offset(.18f,.18f), Offset(.35f,.42f), Offset(.50f,.18f), Offset(.68f,.48f), Offset(.82f,.24f), Offset(.72f,.78f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 3 to 5)),
            ZodiacConstellation("♎", .23f, .79f, listOf(Offset(.16f,.34f), Offset(.42f,.18f), Offset(.72f,.34f), Offset(.25f,.68f), Offset(.65f,.68f)), listOf(0 to 1, 1 to 2, 0 to 3, 3 to 4, 4 to 2)),
            ZodiacConstellation("♏", .84f, .78f, listOf(Offset(.12f,.24f), Offset(.34f,.42f), Offset(.53f,.20f), Offset(.68f,.52f), Offset(.86f,.35f), Offset(.76f,.78f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 3 to 5)),
            ZodiacConstellation("♐", .48f, .88f, listOf(Offset(.16f,.20f), Offset(.40f,.43f), Offset(.70f,.15f), Offset(.60f,.70f), Offset(.87f,.83f)), listOf(0 to 1, 1 to 2, 1 to 3, 3 to 4)),
            ZodiacConstellation("♑", .06f, .34f, listOf(Offset(.16f,.20f), Offset(.38f,.48f), Offset(.62f,.22f), Offset(.81f,.55f)), listOf(0 to 1, 1 to 2, 2 to 3)),
            ZodiacConstellation("♒", .56f, .19f, listOf(Offset(.14f,.32f), Offset(.34f,.18f), Offset(.55f,.38f), Offset(.76f,.18f), Offset(.86f,.55f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4)),
            ZodiacConstellation("♓", .96f, .58f, listOf(Offset(.20f,.18f), Offset(.45f,.42f), Offset(.78f,.18f), Offset(.45f,.78f)), listOf(0 to 1, 1 to 2, 1 to 3))
        )
    }

    val transition = rememberInfiniteTransition(label = "zodiac-motion")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(42000, easing = LinearEasing)),
        label = "zodiac-progress"
    )

    val symbolPaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textAlign = AndroidPaint.Align.CENTER
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Canvas(Modifier.fillMaxSize()) {
            val phase = progress * 2f * PI.toFloat()

            drawRect(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D1423), Color(0xFF090D18), Color(0xFF101329))
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x333E5AA1), Color.Transparent),
                    center = Offset(size.width * .10f, size.height * .18f),
                    radius = size.width * .66f
                ),
                radius = size.width * .66f,
                center = Offset(size.width * .10f, size.height * .18f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x222F6B83), Color.Transparent),
                    center = Offset(size.width * .94f, size.height * .78f),
                    radius = size.width * .58f
                ),
                radius = size.width * .58f,
                center = Offset(size.width * .94f, size.height * .78f)
            )

            particles.forEach { star ->
                val y = ((star.y - progress * star.speed * 10f) % 1f + 1f) % 1f
                val x = star.x + sin(phase * .35f + star.phase) * .006f
                val alpha = .16f + .30f * ((sin(phase + star.phase) + 1f) / 2f)
                drawCircle(
                    color = Color(0xFFD9D3FF).copy(alpha = alpha),
                    radius = star.radius,
                    center = Offset(x * size.width, y * size.height)
                )
            }

            constellations.forEachIndexed { index, constellation ->
                val driftX = sin(phase + index * .72f) * size.width * .008f
                val driftY = cos(phase * .72f + index * .61f) * size.height * .006f
                val center = Offset(
                    constellation.x * size.width + driftX,
                    constellation.y * size.height + driftY
                )
                val radiusX = size.width * .062f
                val radiusY = size.height * .032f
                val points = constellation.points.map { point ->
                    Offset(
                        center.x + (point.x - .5f) * radiusX * 2f,
                        center.y + (point.y - .5f) * radiusY * 2f
                    )
                }

                constellation.links.forEach { (from, to) ->
                    drawLine(
                        color = AccentSoft.copy(alpha = .17f),
                        start = points[from],
                        end = points[to],
                        strokeWidth = 1.dp.toPx()
                    )
                }
                points.forEachIndexed { pointIndex, point ->
                    val pulse = .72f + .28f * sin(phase * 1.2f + pointIndex + index)
                    drawCircle(
                        color = Color(0xFFD8D0FF).copy(alpha = .52f * pulse),
                        radius = 1.7.dp.toPx(),
                        center = point
                    )
                    drawCircle(
                        color = Accent.copy(alpha = .08f),
                        radius = 5.dp.toPx(),
                        center = point
                    )
                }

                symbolPaint.textSize = 19.sp.toPx()
                symbolPaint.color = AndroidColor.argb(34, 198, 187, 255)
                drawContext.canvas.nativeCanvas.drawText(
                    constellation.symbol,
                    center.x,
                    center.y + 7.dp.toPx(),
                    symbolPaint
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Reusable liquid-glass components
// -----------------------------------------------------------------------------
@Composable
private fun Glass(
    modifier: Modifier = Modifier,
    radius: Int = 25,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xE61C2940),
                        Color(0xD9141D30),
                        Color(0xCC111A2B)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(Color.White.copy(.22f), Color.White.copy(.045f), Accent.copy(.17f))
                ),
                shape = shape
            )
            .let { base -> if (onClick != null) base.clickable { onClick() } else base }
            .padding(17.dp),
        content = content
    )
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text = text.uppercase(),
        color = Accent.copy(alpha = .92f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.8.sp
    )
}

@Composable
private fun SectionHeading(
    number: String,
    title: String,
    subtitle: String
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Accent.copy(.13f))
                .border(1.dp, Accent.copy(.22f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Seg(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    sub: String? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .height(if (sub != null) 58.dp else 43.dp)
            .clip(shape)
            .background(
                if (selected) Brush.linearGradient(
                    listOf(Color(0xFFCEC2FF), Color(0xFFA99AF0))
                ) else Brush.linearGradient(
                    listOf(Color.White.copy(.075f), Color.White.copy(.035f))
                )
            )
            .border(
                1.dp,
                if (selected) Color.White.copy(.55f) else StrokeGlass.copy(.75f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color(0xFF171329) else TextMain,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (sub != null) {
            Text(
                sub,
                color = if (selected) Color(0xFF393052).copy(.8f) else TextMuted,
                fontSize = 8.sp,
                maxLines = 1
            )
        }
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
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (badge != null) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        badge,
                        color = Warm,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Warm.copy(.12f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Accent,
                checkedBorderColor = Accent,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Color.White.copy(.12f),
                uncheckedBorderColor = Color.White.copy(.15f)
            )
        )
    }
}

// -----------------------------------------------------------------------------
// Main LowGX screen
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

    Box(Modifier.fillMaxSize()) {
        ZodiacBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 13.dp, bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Brand header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(55.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFD7CEFF), Color(0xFF8B7BDA), Color(0xFF514B91))
                            )
                        )
                        .border(1.dp, Color.White.copy(.48f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("GX", color = Color(0xFF171329), fontSize = 21.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "LOWGX",
                        color = TextMain,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.3.sp
                    )
                    Text("GAME CONTROL CENTER", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.55.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("SPACE", color = Accent, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    Text("v1.0", color = TextMuted, fontSize = 10.sp)
                }
            }

            // Shizuku connection panel
            Glass(Modifier.fillMaxWidth(), radius = 21, onClick = {
                if (Sh.alive() && !Sh.granted()) Sh.ask()
                refreshShizuku()
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(39.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(.07f))
                            .border(1.dp, StrokeGlass, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("S", color = Accent, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SHIZUKU SERVICE", color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(shizukuStatus, color = if (Sh.granted()) Good else Warm, fontSize = 11.sp)
                    }
                    Text("REFRESH  ↗", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                }
            }

            // Game library
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Library / 01")
                        Spacer(Modifier.height(4.dp))
                        Text("Your games", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Pilih aplikasi yang mahu diuruskan.", color = TextMuted, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${selected.size}", color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("SELECTED", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                    }
                }

                Spacer(Modifier.height(15.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Seg("Games", showGamesOnly, Modifier.weight(1f)) { showGamesOnly = true }
                    Seg("All apps · ${installedApps.size}", !showGamesOnly, Modifier.weight(1f)) { showGamesOnly = false }
                    Seg(if (loadingApps) "…" else "↻ Scan", false, Modifier.width(75.dp)) {
                        if (!loadingApps) scanApps()
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text("Cari nama game atau package…", color = TextMuted, fontSize = 12.sp)
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent.copy(.8f),
                        unfocusedBorderColor = StrokeGlass,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain,
                        cursorColor = Accent,
                        focusedContainerColor = Color.White.copy(.035f),
                        unfocusedContainerColor = Color.White.copy(.025f)
                    )
                )

                Spacer(Modifier.height(9.dp))
                if (loadingApps) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                } else if (filteredApps.isEmpty()) {
                    Text(
                        "Tiada aplikasi ditemui. Cuba tab All apps.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 17.dp)
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 255.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        filteredApps.forEach { game ->
                            val isSelected = game.pkg in selectedPackages
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (isSelected) Accent.copy(.105f) else Color.Transparent)
                                    .clickable {
                                        if (isSelected) selected.removeAll { it.pkg == game.pkg }
                                        else selected.add(game)
                                    }
                                    .padding(horizontal = 7.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (game.icon != null) {
                                    Image(
                                        bitmap = game.icon,
                                        contentDescription = game.name,
                                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                    )
                                } else {
                                    Box(
                                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(.07f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("APP", color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(game.name, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(game.pkg, color = TextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) selected.add(game)
                                        else selected.removeAll { it.pkg == game.pkg }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Accent,
                                        checkmarkColor = Ink,
                                        uncheckedColor = TextMuted
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Graphics panel
            Glass(Modifier.fillMaxWidth()) {
                SectionHeading("01", "Graphics", "Visual quality and frame-rate options")
                Spacer(Modifier.height(17.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Game resolution", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${config.resolution}%  ·  ${if (config.resolution == 100) "Native" else "Scaled"}",
                        color = Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = config.resolution.toFloat(),
                    onValueChange = { config = config.copy(resolution = (it / 5).roundToInt() * 5) },
                    valueRange = 50f..100f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color.White.copy(.13f)
                    )
                )

                Spacer(Modifier.height(9.dp))
                Text("FPS limit", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("30", "60", "90", "120", "Max").forEach { fps ->
                        Seg(fps, config.fps == fps, Modifier.weight(1f)) {
                            config = config.copy(fps = fps)
                        }
                    }
                }

                Spacer(Modifier.height(13.dp))
                ToggleRow(
                    title = "4x MSAA",
                    subtitle = "Pilihan ini mungkin tidak disokong oleh semua GPU.",
                    checked = config.msaa4x,
                    badge = "ADVANCED"
                ) { config = config.copy(msaa4x = it) }

                Spacer(Modifier.height(13.dp))
                Divider(color = StrokeGlass)
                Spacer(Modifier.height(13.dp))
                Text("Graphics driver", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GpuDriver.values().forEach { driver ->
                        Seg(
                            driver.label,
                            config.driver == driver,
                            Modifier.weight(1f),
                            sub = driver.sub
                        ) { config = config.copy(driver = driver) }
                    }
                }

                if (config.driver == GpuDriver.Angle) {
                    Spacer(Modifier.height(15.dp))
                    Divider(color = StrokeGlass)
                    Spacer(Modifier.height(13.dp))
                    Text("ANGLE texture profile", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Seg("Smooth", config.texMode == TexMode.Blur, Modifier.weight(1f)) {
                            config = config.copy(texMode = TexMode.Blur)
                        }
                        Seg("Pixel", config.texMode == TexMode.Pixel, Modifier.weight(1f)) {
                            config = config.copy(texMode = TexMode.Pixel)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Texture LOD", color = TextMuted, fontSize = 11.sp)
                        Text("Level ${config.texLevel}", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        (1..4).forEach { level ->
                            Seg("$level", config.texLevel == level, Modifier.weight(1f)) {
                                config = config.copy(texLevel = level)
                            }
                        }
                    }
                }
            }

            // Performance panel
            Glass(Modifier.fillMaxWidth()) {
                SectionHeading("02", "Performance", "Android game mode and interface behaviour")
                Spacer(Modifier.height(16.dp))
                Text("Game mode", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    GameModeOpt.values().forEach { mode ->
                        Seg(mode.label, config.gameMode == mode, Modifier.weight(1f)) {
                            config = config.copy(
                                gameMode = mode,
                                batterySaver = if (mode == GameModeOpt.Saver) true else config.batterySaver
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = "Fixed performance mode",
                    subtitle = "Meminta mod prestasi tetap jika peranti menyokongnya.",
                    checked = config.fixedPerf
                ) { config = config.copy(fixedPerf = it) }

                Spacer(Modifier.height(12.dp))
                Divider(color = StrokeGlass)
                Spacer(Modifier.height(12.dp))
                Text("System animation", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AnimSpeed.values().forEach { speed ->
                        Seg(speed.label, config.animSpeed == speed, Modifier.weight(1f)) {
                            config = config.copy(animSpeed = speed)
                        }
                    }
                }
            }

            // Network and power panel
            Glass(Modifier.fillMaxWidth()) {
                SectionHeading("03", "Network & power", "Connection preferences and battery behaviour")
                Spacer(Modifier.height(15.dp))
                ToggleRow(
                    title = "Wi-Fi & DNS preferences",
                    subtitle = "Mengubah imbasan latar dan Private DNS. Ia tidak menjamin ping lebih rendah.",
                    checked = config.netBoost,
                    badge = "NETWORK"
                ) { config = config.copy(netBoost = it) }

                Spacer(Modifier.height(12.dp))
                Divider(color = StrokeGlass)
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

            if (statusMessage.isNotBlank()) {
                Glass(Modifier.fillMaxWidth(), radius = 17) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape)
                                .background(if (statusMessage.startsWith("Ralat")) Warm else Good)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            statusMessage,
                            color = TextMain,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Glass(Modifier.fillMaxWidth(), radius = 17, onClick = {
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
                    Text("CHECK ANGLE SUPPORT", color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    Text("↗", color = Accent, fontSize = 17.sp)
                }
            }

            Text(
                "LOWGX SPACE  •  GRAPHICS / PERFORMANCE / CONTROL",
                color = TextMuted.copy(.65f),
                fontSize = 8.sp,
                letterSpacing = 1.1.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)
            )
        }

        // Floating action dock
        Glass(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            radius = 23
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${selected.size} GAME${if (selected.size == 1) "" else "S"} SELECTED", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                    Text(if (busy) "Applying settings…" else "Ready to apply", color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { runReset() },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 9.dp),
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StrokeGlass),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)
                ) {
                    Text("Reset", fontSize = 10.sp)
                }
                Spacer(Modifier.width(7.dp))
                Button(
                    onClick = { runApply() },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Ink,
                        disabledContainerColor = Accent.copy(.35f)
                    )
                ) {
                    Text(if (busy) "…" else "Apply", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(7.dp))
                Button(
                    onClick = { launchSelectedGame() },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF27334D),
                        contentColor = TextMain,
                        disabledContainerColor = Color(0xFF27334D).copy(.45f)
                    )
                ) {
                    Text("Launch  ↗", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
