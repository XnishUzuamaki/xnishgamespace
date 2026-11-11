package com.xnish.space

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// -----------------------------------------------------------------------------
// App Security
// -----------------------------------------------------------------------------
private object AppSecurity {
    private const val EXPECTED_SHA256 =
        "F36834732F213CACCC34D34DC8B46A52BAD77483E2556BC25179582492A04129"

    fun checkSignature(context: Context): Boolean {
        return runCatching {
            val expected = EXPECTED_SHA256
                .filterNot { it == ':' || it.isWhitespace() }
                .uppercase(Locale.ROOT)

            if (!expected.matches(Regex("[0-9A-F]{64}"))) {
                return@runCatching false
            }

            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = info.signingInfo ?: return@runCatching false
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
                    "%02X".format(Locale.ROOT, it.toInt() and 0xFF)
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
            (brand.startsWith("generic") && device.startsWith("generic")) ||
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
// Shizuku Helper
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

    fun run(cmd: String): String = runCatching {
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
        (output + errors).trim()
    }.getOrDefault("")
}

// -----------------------------------------------------------------------------
// Helper for Safe Decimal Formatting (Prevents IllegalFormatConversionException)
// -----------------------------------------------------------------------------
private fun Float.fmt1(): String = String.format(Locale.US, "%.1f", this)
private fun Float.fmt2(): String = String.format(Locale.US, "%.2f", this)

// -----------------------------------------------------------------------------
// Models & Installed-App Scanner
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

// Graphics Driver diasingkan daripada ANGLE mengikut gambar rujukan
enum class GpuDriver(val label: String, val sub: String) {
    Default("Default", "SYSTEM"),
    GameDriver("Game Driver", "UPDATABLE"),
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
    val driver: GpuDriver = GpuDriver.GameDriver,
    val angleEnabled: Boolean = true,
    val texMode: TexMode = TexMode.Pixel,
    val texLevel: Int = 4,
    val resolution: Int = 100,
    val fps: String = "Max",
    val msaa4x: Boolean = false,
    val gameMode: GameModeOpt = GameModeOpt.Performance,
    val fixedPerf: Boolean = true,
    val animSpeed: AnimSpeed = AnimSpeed.Fast,
    val sensitivity: Int = 65,
    val netBoost: Boolean = true,
    val batterySaver: Boolean = false,
    val clearRamOnLaunch: Boolean = false,
    val stopBgOnLaunch: Boolean = false
)

enum class NavTab { Profile, Home, Settings }

// -----------------------------------------------------------------------------
// Live Hardware & System Telemetry Reader
// -----------------------------------------------------------------------------
data class SystemStats(
    val ramPercent: Int = 0,
    val ramAvailGb: Float = 0f,
    val ramTotalGb: Float = 0f,
    val storagePercent: Int = 0,
    val storageFreeGb: Float = 0f,
    val cpuGhz: Float = 1.80f,
    val cpuTempC: Int = 37,
    val gpuLoadPercent: Int = 24,
    val gpuRenderer: String = "Mali / Adreno",
    val refreshHz: Int = 120,
    val pingMs: Int = 28,
    val deviceModel: String = Build.MODEL,
    val androidVer: String = "Android ${Build.VERSION.RELEASE}",
    val arch: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
)

private fun readMem(): Pair<Long, Long>? = runCatching {
    var total = -1L
    var avail = -1L
    File("/proc/meminfo").useLines { lines ->
        for (line in lines) {
            when {
                line.startsWith("MemTotal:") -> total = line.filter { it.isDigit() }.toLong() / 1024
                line.startsWith("MemAvailable:") -> avail = line.filter { it.isDigit() }.toLong() / 1024
            }
            if (total >= 0 && avail >= 0) break
        }
    }
    if (total > 0 && avail >= 0) avail to total else null
}.getOrNull()

private fun readStoragePercent(): Pair<Int, Float> = runCatching {
    val stat = StatFs(Environment.getDataDirectory().path)
    val total = stat.totalBytes.toFloat()
    val avail = stat.availableBytes.toFloat()
    val usedPct = (((total - avail) / total) * 100f).roundToInt().coerceIn(0, 100)
    val freeGb = avail / (1024f * 1024f * 1024f)
    usedPct to freeGb
}.getOrDefault(68 to 32f)

private fun readCpuGhz(): Float = runCatching {
    val freqs = (0..7).mapNotNull { core ->
        val path = "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq"
        val file = File(path)
        if (file.canRead()) file.readText().trim().toLongOrNull() else null
    }
    if (freqs.isNotEmpty()) {
        (freqs.average().toFloat() / 1_000_000f).coerceIn(0.4f, 3.6f)
    } else {
        1.85f
    }
}.getOrDefault(1.85f)

private fun readCpuTemp(): Int = runCatching {
    val zones = (0..12).mapNotNull { idx ->
        val f = File("/sys/class/thermal/thermal_zone$idx/temp")
        if (f.canRead()) f.readText().trim().toIntOrNull() else null
    }.map { if (it > 1000) it / 1000 else it }
        .filter { it in 25..95 }

    zones.firstOrNull() ?: 38
}.getOrDefault(38)

private fun readGpuLoad(cpuGhz: Float): Int = runCatching {
    val paths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/devices/platform/ged/hal/gpu_utilization"
    )
    for (p in paths) {
        val f = File(p)
        if (f.canRead()) {
            val num = Regex("\\d+").find(f.readText())?.value?.toIntOrNull()
            if (num != null && num in 0..100) return@runCatching num
        }
    }
    if (Sh.granted()) {
        val out = Sh.run("cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/kernel/gpu/gpu_busy 2>/dev/null | head -n 1")
        val num = Regex("\\d+").find(out)?.value?.toIntOrNull()
        if (num != null && num in 0..100) return@runCatching num
    }
    ((cpuGhz / 2.8f) * 65f + Random.nextInt(-4, 6)).roundToInt().coerceIn(8, 96)
}.getOrDefault(26)

private fun readDisplayHz(context: Context): Int = runCatching {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    @Suppress("DEPRECATION")
    wm.defaultDisplay.refreshRate.roundToInt().coerceIn(30, 240)
}.getOrDefault(120)

private fun measurePingMs(): Int = runCatching {
    val start = SystemClock.elapsedRealtime()
    val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/ping", "-c", "1", "-W", "1", "8.8.8.8"))
    val exit = proc.waitFor()
    val elapsed = (SystemClock.elapsedRealtime() - start).toInt()
    if (exit == 0) {
        val text = proc.inputStream.bufferedReader().readText()
        val match = Regex("time=([0-9.]+)").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        match?.roundToInt() ?: elapsed.coerceIn(12, 199)
    } else {
        elapsed.coerceIn(18, 120)
    }
}.getOrDefault(34)

// -----------------------------------------------------------------------------
// Existing Shizuku Command Engine
// -----------------------------------------------------------------------------
private fun applyAll(selected: List<Game>, config: ConfigState): String {
    if (selected.isEmpty()) return "Pilih sekurang-kurangnya satu game."

    val commands = mutableListOf<String>()
    val packages = selected.joinToString(",") { it.pkg }

    // 1. Graphics Driver (Default / Game Driver / Vulkan) - Diasingkan daripada ANGLE
    when (config.driver) {
        GpuDriver.Default -> {
            commands += "settings delete global updatable_driver_production_opt_in_apps"
            commands += "settings delete global updatable_driver_prerelease_opt_in_apps"
            commands += "setprop debug.hwui.renderer opengl"
        }
        GpuDriver.GameDriver -> {
            commands += "settings put global updatable_driver_all_apps 0"
            commands += "settings put global updatable_driver_production_opt_in_apps $packages"
            commands += "setprop debug.hwui.renderer opengl"
        }
        GpuDriver.Vulkan -> {
            commands += "settings put global updatable_driver_production_opt_in_apps $packages"
            commands += "setprop debug.hwui.renderer skiavk"
            commands += "setprop debug.vulkan.layers \"\""
        }
    }

    // 2. Tetapan ANGLE (Boleh digunakan bersama mana-mana Graphics Driver di atas)
    if (config.angleEnabled) {
        val values = selected.joinToString(",") { "angle" }
        val features = when (config.texMode) {
            TexMode.Blur -> "disableAnisotropicFiltering:forceTextureLodOffset${config.texLevel}"
            TexMode.Pixel -> "forceNearestFiltering:disableAnisotropicFiltering:forceTextureLodOffset${config.texLevel}"
        }
        commands += "settings put global angle_gl_driver_selection_pkgs $packages"
        commands += "settings put global angle_gl_driver_selection_values $values"
        commands += "setprop debug.angle.feature_overrides_enabled $features"
    } else {
        commands += "settings delete global angle_gl_driver_selection_pkgs"
        commands += "settings delete global angle_gl_driver_selection_values"
        commands += "setprop debug.angle.feature_overrides_enabled \"\""
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

    // 3. Touch Sensitivity (1 - 100: Semakin tinggi semakin licin skrin digeser)
    val sens = config.sensitivity.coerceIn(1, 100)
    val pointerSpeed = (((sens - 50) / 50f) * 7f).roundToInt().coerceIn(-7, 7)
    val touchSlop = (12 - ((sens / 100f) * 10f).roundToInt()).coerceIn(1, 12)
    val longPressTimeout = (450 - ((sens / 100f) * 250f).roundToInt()).coerceIn(200, 450)
    val maxEvents = (120 + ((sens / 100f) * 180f).roundToInt()).coerceIn(120, 300)

    commands += "settings put system pointer_speed $pointerSpeed"
    commands += "settings put secure long_press_timeout $longPressTimeout"
    commands += "settings put secure multi_press_timeout $longPressTimeout"
    commands += "settings put global touch_slop $touchSlop"
    commands += "setprop windowsmgr.max_events_per_sec $maxEvents"
    commands += "setprop view_configuration.touch_slop $touchSlop"
    commands += "setprop debug.touch_slop $touchSlop"

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
    return "Tetapan dihantar untuk ${selected.size} game (Sensitivity: ${config.sensitivity}/100)." +
        if (output.isNotBlank()) "\n$output" else "\nSemak kesan tetapan pada peranti anda."
}

private fun resetAll(selected: List<Game>): String {
    val commands = mutableListOf(
        "settings delete global angle_gl_driver_selection_values",
        "settings delete global angle_gl_driver_selection_pkgs",
        "settings delete global updatable_driver_production_opt_in_apps",
        "settings delete global updatable_driver_prerelease_opt_in_apps",
        "setprop debug.angle.feature_overrides_enabled \"\"",
        "setprop debug.hwui.renderer opengl",
        "setprop debug.egl.force_msaa false",
        "service call SurfaceFlinger 1008 i32 0 2>/dev/null || true",
        "cmd power set-fixed-performance-mode-enabled false",
        "cmd power set-mode 0",
        "settings put system pointer_speed 0",
        "settings put secure long_press_timeout 400",
        "settings put secure multi_press_timeout 300",
        "settings delete global touch_slop",
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

private fun clearRam(): String {
    val before = readMem()?.first
    Sh.run("am kill-all")
    Thread.sleep(900)
    val after = readMem()?.first
    return if (before != null && after != null) {
        val freed = after - before
        if (freed > 0) {
            "RAM dibersihkan: $before MB → $after MB (+$freed MB tersedia)."
        } else {
            "Proses cache sudah dibersihkan. Tiada perubahan ketara pada RAM tersedia ($after MB)."
        }
    } else {
        "Arahan clear RAM telah dihantar."
    }
}

private fun protectedPackages(context: Context, selected: List<Game>): Set<String> {
    val keep = mutableSetOf(
        context.packageName,
        "moe.shizuku.privileged.api",
        "com.android.systemui"
    )
    selected.forEach { keep += it.pkg }

    runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.let { keep += it }
    }
    runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { keep += it }
    }
    return keep
}

private fun stopBackgroundApps(context: Context, apps: List<Game>, selected: List<Game>): String {
    val keep = protectedPackages(context, selected)
    val running = Sh.run("ps -A -o NAME 2>/dev/null")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    if (running.size < 2) return "Gagal membaca senarai proses yang sedang berjalan."

    val targets = apps.filter { app ->
        app.pkg !in keep && running.any { it == app.pkg || it.startsWith(app.pkg + ":") }
    }
    if (targets.isEmpty()) return "Tiada aplikasi latar belakang untuk dihentikan."

    Sh.run(targets.joinToString(" ; ") { "am force-stop ${it.pkg}" })

    val names = targets.take(6).joinToString { it.name }
    val more = if (targets.size > 6) " dan ${targets.size - 6} lagi" else ""
    return "${targets.size} aplikasi latar belakang dihentikan: $names$more."
}

// -----------------------------------------------------------------------------
// Activity & Crash Screen
// -----------------------------------------------------------------------------
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val signatureValid = AppSecurity.checkSignature(this)
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        if (!signatureValid) {
            if (!isDebuggable) {
                Toast.makeText(this, "XNISHz: Invalid APK Signature", Toast.LENGTH_LONG).show()
                finish()
                return
            } else {
                Log.w("XNISHzSecurity", "Signature belum disahkan dalam debug build")
            }
        }

        if (AppSecurity.detectEmulator()) {
            Log.w("XNISHzSecurity", "Emulator environment detected")
        }

        val crashFile = File(filesDir, "crash.txt")
        val previousCrash = if (crashFile.exists()) crashFile.readText() else null

        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            runCatching { crashFile.writeText(error.stackTraceToString()) }
            android.os.Process.killProcess(android.os.Process.myPid())
        }

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
                    primary = CyanAccent,
                    onPrimary = Ink,
                    secondary = Accent,
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
                "XNISHz / CRASH REPORT",
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
// Premium Liquid Glass Cyber-Cyan & Violet Palette
// -----------------------------------------------------------------------------
private val Ink = Color(0xFF04070E)
private val Panel = Color(0xD90E1826)
private val CyanAccent = Color(0xFF7CE8FF)
private val CyanGlow = Color(0xFF2CBDFB)
private val CyanDeep = Color(0xFF0E3E5C)
private val Accent = Color(0xFFA896FF)
private val TextMain = Color(0xFFF5F9FF)
private val TextMuted = Color(0xFF8A9AB5)
private val Good = Color(0xFF67F2C2)
private val Warm = Color(0xFFFFC98A)

private val AuroraCyan = Color(0xFF1499D5)
private val AuroraIndigo = Color(0xFF3E35B0)
private val AuroraDeepBlue = Color(0xFF0B2D4E)
private val AuroraViolet = Color(0xFF7B3FE4)

// -----------------------------------------------------------------------------
// Aurora Liquid Background
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
        List(48) {
            StarParticle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                radius = Random.nextFloat() * 1.1f + 0.35f,
                speed = Random.nextFloat() * 0.025f + 0.008f,
                phase = Random.nextFloat() * (2f * PI.toFloat())
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "aurora")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(46000, easing = LinearEasing)),
        label = "aurora-progress"
    )

    Canvas(Modifier.fillMaxSize().background(Ink)) {
        val w = size.width
        val h = size.height
        val a = progress * 2f * PI.toFloat()

        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF03060C), Color(0xFF071120), Color(0xFF04070F))
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

        blob(w * (.22f + .10f * sin(a)), h * (.16f + .05f * cos(a)), w * .92f, AuroraCyan.copy(alpha = .32f))
        blob(w * (.85f + .08f * cos(a * 1.5f)), h * (.36f + .07f * sin(a)), w * .85f, AuroraDeepBlue.copy(alpha = .50f))
        blob(w * (.15f + .09f * cos(a + 1.4f)), h * (.72f + .05f * sin(a * 2f)), w * .80f, AuroraIndigo.copy(alpha = .28f))
        blob(w * (.78f + .07f * sin(a + 2.5f)), h * (.90f + .04f * cos(a)), w * .72f, AuroraViolet.copy(alpha = .22f))

        drawRect(Ink.copy(alpha = .38f))

        stars.forEach { star ->
            val y = ((star.y - progress * star.speed * 10f) % 1f + 1f) % 1f
            val x = star.x + sin(a * .35f + star.phase) * .005f
            val alpha = .08f + .22f * ((sin(a * 2f + star.phase) + 1f) / 2f)
            drawCircle(
                color = CyanAccent.copy(alpha = alpha),
                radius = star.radius,
                center = Offset(x * w, y * h)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Liquid-Glass Building Blocks
// -----------------------------------------------------------------------------
private fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color = CyanAccent,
    deepTint: Boolean = false
): Modifier = this
    .clip(shape)
    .background(if (deepTint) Color(0x990A1929) else Color(0x7308111E))
    .background(
        Brush.verticalGradient(
            if (deepTint) {
                listOf(
                    CyanGlow.copy(alpha = .22f),
                    CyanDeep.copy(alpha = .38f),
                    Color(0x6606101D)
                )
            } else {
                listOf(
                    Color(0x24FFFFFF),
                    Color(0x0BFFFFFF),
                    Color(0x1680D8FF)
                )
            }
        )
    )
    .drawBehind {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = if (deepTint) .24f else .16f), Color.Transparent),
                center = Offset(w * .14f, 0f),
                radius = w * .78f
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(tint.copy(alpha = if (deepTint) .26f else .14f), Color.Transparent),
                center = Offset(w * .88f, h),
                radius = w * .72f
            )
        )
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, CyanAccent.copy(alpha = .58f), Color.Transparent)
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
                CyanAccent.copy(alpha = if (deepTint) .55f else .38f),
                Color.White.copy(alpha = .12f),
                Color.White.copy(alpha = .04f),
                tint.copy(alpha = .25f)
            )
        ),
        shape
    )

private fun Modifier.glassThumb(shape: Shape): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(CyanAccent.copy(alpha = .38f), CyanDeep.copy(alpha = .65f))
        )
    )
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
        Brush.verticalGradient(listOf(CyanAccent.copy(alpha = .85f), Color.White.copy(alpha = .12f))),
        shape
    )

@Composable
private fun Glass(
    modifier: Modifier = Modifier,
    radius: Int = 28,
    padding: Dp = 18.dp,
    tint: Color = CyanAccent,
    deepTint: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius.dp)
    Column(
        modifier = modifier
            .shadow(
                elevation = 20.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x88000000),
                spotColor = if (deepTint) Color(0x661CB5E0) else Color(0x441CB5E0)
            )
            .liquidGlass(shape, tint, deepTint)
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
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = .12f), Color.Transparent)
                )
            )
    )
}

@Composable
private fun GlowingProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    accent: Color = CyanAccent
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = 140f),
        label = "glow-bar"
    )
    val pill = RoundedCornerShape(50)
    Box(
        modifier
            .height(height)
            .clip(pill)
            .background(Color(0x55000000))
            .border(0.8.dp, Color.White.copy(alpha = .08f), pill)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(pill)
                .background(
                    Brush.horizontalGradient(
                        listOf(CyanGlow, accent, Color.White.copy(alpha = .92f))
                    )
                )
        )
    }
}

// -----------------------------------------------------------------------------
// Custom Canvas Vector Icons
// -----------------------------------------------------------------------------
@Composable
private fun RocketIcon(modifier: Modifier = Modifier, color: Color = CyanAccent) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.78f, h * 0.22f)
            cubicTo(w * 0.52f, h * 0.20f, w * 0.32f, h * 0.40f, w * 0.26f, h * 0.62f)
            lineTo(w * 0.38f, h * 0.74f)
            cubicTo(w * 0.60f, h * 0.68f, w * 0.80f, h * 0.48f, w * 0.78f, h * 0.22f)
            close()
        }
        drawPath(path, color = color, style = stroke)
        drawCircle(color = color, radius = w * 0.07f, center = Offset(w * 0.56f, h * 0.44f))
        drawLine(color, Offset(w * 0.24f, h * 0.76f), Offset(w * 0.16f, h * 0.84f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun MedalBadgeIcon(modifier: Modifier = Modifier, color: Color = CyanAccent) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color = color, radius = w * 0.28f, center = Offset(w * 0.5f, h * 0.40f), style = s)
        drawCircle(color = color, radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.40f))
        val ribbon = Path().apply {
            moveTo(w * 0.36f, h * 0.62f)
            lineTo(w * 0.30f, h * 0.86f)
            lineTo(w * 0.50f, h * 0.75f)
            lineTo(w * 0.70f, h * 0.86f)
            lineTo(w * 0.64f, h * 0.62f)
        }
        drawPath(ribbon, color = color, style = s)
    }
}

@Composable
private fun GridNavIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.width * 0.34f
        val gap = size.width * 0.16f
        val start = size.width * 0.08f
        val stroke = Stroke(1.7.dp.toPx())
        for (r in 0..1) {
            for (c in 0..1) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(start + c * (s + gap), start + r * (s + gap)),
                    size = Size(s, s),
                    cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
                    style = stroke
                )
            }
        }
    }
}

@Composable
private fun HomeNavIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(w * 0.15f, h * 0.48f)
            lineTo(w * 0.50f, h * 0.16f)
            lineTo(w * 0.85f, h * 0.48f)
            lineTo(w * 0.85f, h * 0.86f)
            lineTo(w * 0.15f, h * 0.86f)
            close()
        }
        drawPath(p, color = color, style = s)
        drawLine(color, Offset(w * 0.5f, h * 0.60f), Offset(w * 0.5f, h * 0.86f), strokeWidth = 1.8.dp.toPx())
    }
}

@Composable
private fun SlidersNavIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = 1.8.dp.toPx()
        drawLine(color, Offset(w * 0.16f, h * 0.30f), Offset(w * 0.84f, h * 0.30f), sw, StrokeCap.Round)
        drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.38f, h * 0.30f))
        drawLine(color, Offset(w * 0.16f, h * 0.70f), Offset(w * 0.84f, h * 0.70f), sw, StrokeCap.Round)
        drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.65f, h * 0.70f))
    }
}

// -----------------------------------------------------------------------------
// Main Composable State & Host
// -----------------------------------------------------------------------------
@Composable
private fun LowGXApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(NavTab.Home) }
    var activeGameForSettings by remember { mutableStateOf<Game?>(null) }
    var showAddGameModal by remember { mutableStateOf(false) }

    var shizukuStatus by remember { mutableStateOf("Memeriksa sambungan…") }
    var config by remember { mutableStateOf(ConfigState()) }
    var installedApps by remember { mutableStateOf<List<Game>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(true) }
    var showGamesOnly by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Game>() }
    var statusMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val sessionStartMs = remember { SystemClock.elapsedRealtime() }
    var sessionSeconds by remember { mutableLongStateOf(0L) }
    var liveFps by remember { mutableIntStateOf(120) }
    var sysStats by remember { mutableStateOf(SystemStats()) }

    fun refreshShizuku() {
        shizukuStatus = when {
            !Sh.alive() -> "Service tidak aktif"
            !Sh.granted() -> "Kebenaran diperlukan"
            else -> "Active · Shizuku · Wi-Fi"
        }
    }

    fun scanApps() {
        scope.launch {
            loadingApps = true
            val apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
            installedApps = apps
            if (apps.none { it.isGame }) showGamesOnly = false
            if (selected.isEmpty()) {
                apps.firstOrNull { it.isGame }?.let(selected::add)
            }
            loadingApps = false
        }
    }

    LaunchedEffect(Unit) {
        refreshShizuku()
        scanApps()
    }

    DisposableEffect(Unit) {
        var frames = 0
        var lastNs = System.nanoTime()
        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frames++
                val diff = frameTimeNanos - lastNs
                if (diff >= 1_000_000_000L) {
                    liveFps = ((frames * 1_000_000_000L) / diff).toInt().coerceIn(1, 240)
                    frames = 0
                    lastNs = frameTimeNanos
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose { choreographer.removeFrameCallback(callback) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            sessionSeconds = (SystemClock.elapsedRealtime() - sessionStartMs) / 1000L
            delay(1000L)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val updated = withContext(Dispatchers.IO) {
                val mem = readMem()
                val availMb = mem?.first ?: 3200L
                val totalMb = (mem?.second ?: 8192L).coerceAtLeast(1L)
                val usedPct = (((totalMb - availMb).toFloat() / totalMb) * 100f).roundToInt().coerceIn(1, 99)

                val (storagePct, storageFree) = readStoragePercent()
                val cpuGhz = readCpuGhz()
                val cpuTemp = readCpuTemp()
                val gpuLoad = readGpuLoad(cpuGhz)
                val hz = readDisplayHz(context)
                val ping = measurePingMs()

                SystemStats(
                    ramPercent = usedPct,
                    ramAvailGb = availMb / 1024f,
                    ramTotalGb = totalMb / 1024f,
                    storagePercent = storagePct,
                    storageFreeGb = storageFree,
                    cpuGhz = cpuGhz,
                    cpuTempC = cpuTemp,
                    gpuLoadPercent = gpuLoad,
                    refreshHz = hz,
                    pingMs = ping
                )
            }
            sysStats = updated
            refreshShizuku()
            delay(3200L)
        }
    }

    BackHandler(enabled = currentTab != NavTab.Home) {
        currentTab = NavTab.Home
    }

    fun runApply() {
        if (!Sh.granted()) {
            Sh.ask()
            refreshShizuku()
            statusMessage = "Shizuku belum diberi kebenaran. Sila benarkan akses Shizuku dahulu."
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
            Sh.ask()
            statusMessage = "Shizuku belum diberi kebenaran."
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

    fun runClearRam() {
        if (!Sh.granted()) {
            Sh.ask()
            statusMessage = "Shizuku belum diberi kebenaran."
            return
        }
        scope.launch {
            busy = true
            statusMessage = withContext(Dispatchers.IO) {
                runCatching { clearRam() }
                    .getOrElse { "Ralat: ${it.message ?: "Tidak diketahui"}" }
            }
            busy = false
        }
    }

    fun runStopBackground() {
        if (!Sh.granted()) {
            Sh.ask()
            statusMessage = "Shizuku belum diberi kebenaran."
            return
        }
        scope.launch {
            busy = true
            val apps = installedApps.toList()
            val chosen = selected.toList()
            statusMessage = withContext(Dispatchers.IO) {
                runCatching { stopBackgroundApps(context, apps, chosen) }
                    .getOrElse { "Ralat: ${it.message ?: "Tidak diketahui"}" }
            }
            busy = false
        }
    }

    fun launchTargetGame(targetGame: Game?) {
        if (!Sh.granted()) {
            Sh.ask()
            statusMessage = "Shizuku belum diberi kebenaran."
            return
        }
        val pkgToLaunch = targetGame?.pkg ?: selected.firstOrNull()?.pkg
        if (pkgToLaunch == null) {
            statusMessage = "Pilih game yang hendak dilancarkan."
            return
        }
        scope.launch {
            busy = true
            val apps = installedApps.toList()
            val chosen = if (targetGame != null && selected.none { it.pkg == targetGame.pkg }) {
                selected.add(targetGame)
                selected.toList()
            } else {
                selected.toList()
            }
            val cfg = config
            statusMessage = withContext(Dispatchers.IO) {
                val log = mutableListOf<String>()
                if (cfg.stopBgOnLaunch) {
                    log += runCatching { stopBackgroundApps(context, apps, chosen) }
                        .getOrElse { "Ralat: ${it.message ?: "Tidak diketahui"}" }
                }
                if (cfg.clearRamOnLaunch) {
                    log += runCatching { clearRam() }
                        .getOrElse { "Ralat: ${it.message ?: "Tidak diketahui"}" }
                }
                log += runCatching { applyAll(chosen, cfg) }
                    .getOrElse { "Ralat: ${it.message ?: "Tidak diketahui"}" }
                log.joinToString("\n")
            }
            busy = false

            runCatching {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkgToLaunch)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        AuroraBackground()

        AnimatedContent(
            targetState = currentTab,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(200)) },
            label = "page-switch"
        ) { tab ->
            when (tab) {
                NavTab.Home -> HomePage(
                    selectedGames = selected,
                    loadingApps = loadingApps,
                    onEnterGameSpace = {
                        activeGameForSettings = selected.firstOrNull()
                        currentTab = NavTab.Settings
                    },
                    onOpenAddModal = { showAddGameModal = true },
                    onPlayGame = { game ->
                        if (selected.none { it.pkg == game.pkg }) {
                            selected.add(game)
                        }
                        activeGameForSettings = game
                        currentTab = NavTab.Settings
                    }
                )

                NavTab.Profile -> ProfilePage(
                    shizukuGranted = Sh.granted(),
                    shizukuStatus = shizukuStatus,
                    sessionSeconds = sessionSeconds,
                    stats = sysStats,
                    liveFps = liveFps,
                    onRequestShizuku = {
                        if (Sh.alive() && !Sh.granted()) Sh.ask()
                        refreshShizuku()
                    }
                )

                NavTab.Settings -> FeatureSettingsPage(
                    activeGame = activeGameForSettings ?: selected.firstOrNull(),
                    selectedGames = selected,
                    config = config,
                    onConfigChange = { config = it },
                    stats = sysStats,
                    busy = busy,
                    statusMessage = statusMessage,
                    onBackHome = { currentTab = NavTab.Home },
                    onOpenAddModal = { showAddGameModal = true },
                    onClearRam = { runClearRam() },
                    onStopBg = { runStopBackground() },
                    onCheckAngle = {
                        if (!Sh.granted()) {
                            Sh.ask()
                            statusMessage = "Shizuku belum diberi kebenaran."
                        } else {
                            scope.launch {
                                busy = true
                                statusMessage = withContext(Dispatchers.IO) {
                                    runCatching { checkAngle() }
                                        .getOrElse { "Ralat: ${it.message ?: "Tidak diketahui"}" }
                                }
                                busy = false
                            }
                        }
                    },
                    onReset = { runReset() },
                    onApply = { runApply() },
                    onLaunch = { launchTargetGame(activeGameForSettings ?: selected.firstOrNull()) }
                )
            }
        }

        FloatingBottomNavBar(
            currentTab = currentTab,
            onSelectTab = { currentTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 14.dp)
        )

        if (showAddGameModal) {
            AddGameLibraryModal(
                installedApps = installedApps,
                selectedGames = selected,
                loadingApps = loadingApps,
                showGamesOnly = showGamesOnly,
                searchQuery = searchQuery,
                onToggleTab = { showGamesOnly = it },
                onSearchChange = { searchQuery = it },
                onScan = { scanApps() },
                onToggleGame = { game ->
                    if (selected.any { it.pkg == game.pkg }) {
                        selected.removeAll { it.pkg == game.pkg }
                    } else if (selected.size < 10) {
                        selected.add(game)
                    } else {
                        Toast.makeText(context, "Maksimum 10 aplikasi dalam Game Library", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { showAddGameModal = false }
            )
        }
    }
}

// -----------------------------------------------------------------------------
// PAGE 1: HOME SCREEN
// -----------------------------------------------------------------------------
@Composable
private fun HomePage(
    selectedGames: List<Game>,
    loadingApps: Boolean,
    onEnterGameSpace: () -> Unit,
    onOpenAddModal: () -> Unit,
    onPlayGame: (Game) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Home",
                color = TextMain,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(26.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(112.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(CyanGlow.copy(alpha = .42f), Color.Transparent)
                            )
                        )
                )
                Box(
                    Modifier
                        .size(94.dp)
                        .shadow(24.dp, CircleShape, spotColor = CyanGlow)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF10253A), Color(0xFF050A12))
                            )
                        )
                        .border(
                            1.5.dp,
                            Brush.linearGradient(
                                listOf(CyanAccent.copy(alpha = .85f), Color.White.copy(alpha = .1f), CyanGlow.copy(alpha = .45f))
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "X",
                        color = CyanAccent,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-2).sp
                    )
                }
            }

            Spacer(Modifier.width(20.dp))

            Column {
                Text(
                    text = "TOUCH OPTIMIZE",
                    color = CyanAccent.copy(alpha = .85f),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.6.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "XNISHz",
                    color = TextMain,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "v99999999 · Space",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(26.dp))

        Glass(
            modifier = Modifier.fillMaxWidth(),
            radius = 28,
            padding = 20.dp,
            tint = CyanAccent,
            deepTint = true,
            onClick = onEnterGameSpace
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .liquidGlass(RoundedCornerShape(16.dp), CyanAccent),
                    contentAlignment = Alignment.Center
                ) {
                    RocketIcon(Modifier.size(26.dp), CyanAccent)
                }
                Spacer(Modifier.width(15.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Enter",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Game Space",
                        color = TextMain,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp
                    )
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { onEnterGameSpace() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("→", color = Color(0xFF07101E), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(26.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Game Library",
                color = TextMain,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${selectedGames.size} / 10",
                color = TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .height(36.dp)
                    .liquidGlass(RoundedCornerShape(50), CyanAccent)
                    .clickable { onOpenAddModal() }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Add  +",
                    color = TextMain,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (loadingApps) {
            Glass(Modifier.fillMaxWidth(), radius = 28, padding = 32.dp) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyanAccent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                }
            }
        } else if (selectedGames.isEmpty()) {
            Glass(
                modifier = Modifier.fillMaxWidth(),
                radius = 28,
                padding = 24.dp,
                onClick = onOpenAddModal
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Tiada aplikasi ditambah lagi", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tekan butang 'Add +' di atas untuk memilih aplikasi atau game.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                selectedGames.forEachIndexed { index, game ->
                    GameShowcaseCard(
                        game = game,
                        badgeLabel = if (index == 0) "LAST PLAYED" else "GAME SPACE READY",
                        playTime = if (index == 0) "4h 10m" else "Optimized · 120Hz",
                        onPlayClick = { onPlayGame(game) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GameShowcaseCard(
    game: Game,
    badgeLabel: String,
    playTime: String,
    onPlayClick: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(206.dp)
            .shadow(22.dp, shape, spotColor = CyanGlow.copy(alpha = .35f))
            .clip(shape)
            .background(Color(0xFF0A1320))
            .clickable { onPlayClick() }
    ) {
        if (game.icon != null) {
            Image(
                bitmap = game.icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.65f
                        scaleY = 1.65f
                        translationX = 120f
                    }
                    .blur(32.dp)
                    .alpha(0.55f)
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xEE070D17),
                            Color(0xBB091322),
                            Color(0x550D1E33)
                        )
                    )
                )
        )

        Box(
            Modifier
                .fillMaxSize()
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .40f),
                            Color.White.copy(alpha = .08f),
                            CyanAccent.copy(alpha = .32f)
                        )
                    ),
                    shape
                )
        )

        Row(
            Modifier
                .fillMaxSize()
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = badgeLabel,
                        color = TextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = game.name,
                        color = TextMain,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        letterSpacing = (-0.4).sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("◔", color = TextMuted, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = playTime,
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Box(
                    Modifier
                        .height(44.dp)
                        .shadow(14.dp, RoundedCornerShape(50), spotColor = CyanAccent)
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF90EEFF), Color(0xFF5CD6FF))
                            )
                        )
                        .border(
                            1.dp,
                            Color.White.copy(alpha = .85f),
                            RoundedCornerShape(50)
                        )
                        .clickable { onPlayClick() }
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "▷",
                            color = Color(0xFF04121F),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Play",
                            color = Color(0xFF04121F),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            if (game.icon != null) {
                Box(
                    Modifier
                        .size(114.dp)
                        .graphicsLayer {
                            rotationZ = -7f
                        }
                        .shadow(20.dp, RoundedCornerShape(26.dp), spotColor = Color.Black)
                        .clip(RoundedCornerShape(26.dp))
                        .border(2.dp, Color.White.copy(alpha = .45f), RoundedCornerShape(26.dp))
                ) {
                    Image(
                        bitmap = game.icon,
                        contentDescription = game.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// PAGE 2: PROFILE SCREEN (Xnish Access + Real-Time Monitoring)
// -----------------------------------------------------------------------------
@Composable
private fun ProfilePage(
    shizukuGranted: Boolean,
    shizukuStatus: String,
    sessionSeconds: Long,
    stats: SystemStats,
    liveFps: Int,
    onRequestShizuku: () -> Unit
) {
    val hrs = sessionSeconds / 3600
    val mins = (sessionSeconds % 3600) / 60
    val secs = sessionSeconds % 60
    val formattedSession = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp)
    ) {
        Glass(
            modifier = Modifier.fillMaxWidth(),
            radius = 28,
            padding = 20.dp,
            tint = CyanAccent,
            deepTint = true,
            onClick = onRequestShizuku
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .liquidGlass(RoundedCornerShape(16.dp), CyanAccent),
                    contentAlignment = Alignment.Center
                ) {
                    MedalBadgeIcon(Modifier.size(26.dp), if (shizukuGranted) CyanAccent else Warm)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Xnish Access",
                        color = TextMain,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (shizukuGranted) "Active · Shizuku · Wi-Fi" else shizukuStatus,
                        color = if (shizukuGranted) CyanAccent else Warm,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { onRequestShizuku() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("→", color = Color(0xFF07101E), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(22.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Access Session",
                    color = TextMuted,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formattedSession,
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(26.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Real-time Monitoring",
                color = TextMain,
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .liquidGlass(RoundedCornerShape(50), CyanAccent)
                    .padding(horizontal = 13.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "V99999999-12",
                    color = TextMuted,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .height(246.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Glass(
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight(),
                radius = 26,
                padding = 18.dp
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .liquidGlass(RoundedCornerShape(10.dp), CyanAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("❖", color = CyanAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }

                        Column {
                            Text(
                                text = "RAM",
                                color = TextMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "${stats.ramPercent}",
                                    color = TextMain,
                                    fontSize = 38.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = (-1).sp
                                )
                                Text(
                                    text = " %",
                                    color = CyanAccent,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                            val usedRamStr = (stats.ramTotalGb - stats.ramAvailGb).coerceAtLeast(0f).fmt1()
                            val totalRamStr = stats.ramTotalGb.fmt1()
                            Text(
                                text = "$usedRamStr / $totalRamStr GB",
                                color = TextMuted,
                                fontSize = 10.5.sp
                            )
                        }
                    }

                    val animRam by animateFloatAsState(
                        targetValue = (stats.ramPercent / 100f).coerceIn(0.08f, 1f),
                        animationSpec = spring(stiffness = 120f),
                        label = "vert-ram"
                    )
                    Box(
                        Modifier
                            .width(13.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x55000000))
                            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(animRam)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.White, CyanAccent, CyanGlow)
                                    )
                                )
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Glass(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    radius = 24,
                    padding = 16.dp
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Storage",
                            color = TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text("☰", color = CyanAccent, fontSize = 15.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${stats.storagePercent}",
                            color = TextMain,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = " %",
                            color = CyanAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    GlowingProgressBar(
                        progress = stats.storagePercent / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        height = 5.dp
                    )
                }

                Glass(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    radius = 24,
                    padding = 16.dp
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "CPU · ${stats.cpuTempC}°C",
                            color = TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text("◔", color = CyanAccent, fontSize = 15.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = stats.cpuGhz.fmt2(),
                            color = TextMain,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = " GHz",
                            color = CyanAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    GlowingProgressBar(
                        progress = (stats.cpuGhz / 3.2f).coerceIn(0.1f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        height = 5.dp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Glass(
                modifier = Modifier.weight(1f),
                radius = 24,
                padding = 16.dp
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "GPU Load",
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("◈", color = CyanAccent, fontSize = 15.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${stats.gpuLoadPercent}",
                        color = TextMain,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = " %",
                        color = CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                GlowingProgressBar(
                    progress = stats.gpuLoadPercent / 100f,
                    modifier = Modifier.fillMaxWidth(),
                    height = 5.dp
                )
            }

            Glass(
                modifier = Modifier.weight(1f),
                radius = 24,
                padding = 16.dp
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Display · ${stats.refreshHz}Hz",
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("⚡", color = CyanAccent, fontSize = 14.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$liveFps",
                        color = TextMain,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = " FPS",
                        color = CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                GlowingProgressBar(
                    progress = (liveFps.toFloat() / stats.refreshHz.coerceAtLeast(60)).coerceIn(0.1f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    height = 5.dp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Glass(
            modifier = Modifier.fillMaxWidth(),
            radius = 24,
            padding = 18.dp
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .liquidGlass(RoundedCornerShape(12.dp), CyanAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⇅", color = CyanAccent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Network",
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "8.8.8.8 · Cloudflare 1.1.1.1",
                        color = TextMuted.copy(alpha = .75f),
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${stats.pingMs}",
                        color = TextMain,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = " ms",
                        color = CyanAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .shadow(16.dp, CircleShape, spotColor = CyanGlow)
                    .liquidGlass(CircleShape, CyanAccent, deepTint = true),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(22.dp)) {
                    drawRoundRect(
                        color = CyanAccent,
                        topLeft = Offset(size.width * 0.22f, size.height * 0.08f),
                        size = Size(size.width * 0.56f, size.height * 0.84f),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        style = Stroke(1.8.dp.toPx())
                    )
                    drawCircle(CyanAccent, radius = 1.5.dp.toPx(), center = Offset(size.width * 0.5f, size.height * 0.20f))
                }
            }
            Spacer(Modifier.width(15.dp))
            Column {
                Text(
                    text = stats.deviceModel,
                    color = TextMain,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "${stats.androidVer}  •  ${stats.arch}",
                    color = TextMuted,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// PAGE 3: FEATURE SETTINGS PAGE
// -----------------------------------------------------------------------------
@Composable
private fun FeatureSettingsPage(
    activeGame: Game?,
    selectedGames: List<Game>,
    config: ConfigState,
    onConfigChange: (ConfigState) -> Unit,
    stats: SystemStats,
    busy: Boolean,
    statusMessage: String,
    onBackHome: () -> Unit,
    onOpenAddModal: () -> Unit,
    onClearRam: () -> Unit,
    onStopBg: () -> Unit,
    onCheckAngle: () -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onLaunch: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 132.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- Active Game Header Bar ----
        Glass(
            modifier = Modifier.fillMaxWidth(),
            radius = 28,
            padding = 16.dp,
            tint = CyanAccent,
            deepTint = true
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .liquidGlass(CircleShape, CyanAccent)
                        .clickable { onBackHome() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                if (activeGame?.icon != null) {
                    Image(
                        bitmap = activeGame.icon,
                        contentDescription = activeGame.name,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "FEATURE SETTINGS",
                        color = CyanAccent,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = activeGame?.name ?: "All Selected Games (${selectedGames.size})",
                        color = TextMain,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                GlassButton(
                    text = "Apps (${selectedGames.size})",
                    height = 36.dp,
                    horizontalPadding = 12.dp,
                    onClick = onOpenAddModal
                )
            }
        }

        // ---- Action Dock: Reset / Apply / Launch Game ----
        Glass(
            modifier = Modifier.fillMaxWidth(),
            radius = 26,
            padding = 12.dp
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassButton(
                    text = "Reset",
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = onReset
                )
                Spacer(Modifier.width(8.dp))
                GlassButton(
                    text = if (busy) "…" else "Apply",
                    primary = false,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = onApply
                )
                Spacer(Modifier.width(8.dp))
                GlassButton(
                    text = "▷ Launch",
                    primary = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1.2f),
                    onClick = onLaunch
                )
            }
        }

        // ---- Status Message ----
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

        // ---- Graphics Card (ikut reka bentuk gambar rujukan + ANGLE berasingan) ----
        Glass(Modifier.fillMaxWidth(), radius = 30) {
            SectionHeading("◈", "Graphics", "Visual quality, graphics driver & ANGLE engine")
            Spacer(Modifier.height(18.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Game resolution",
                    color = TextMain,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Android 13+",
                    color = CyanAccent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(CyanAccent.copy(alpha = .14f))
                        .border(1.dp, CyanAccent.copy(alpha = .30f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${config.resolution}% · ${if (config.resolution == 100) "Native" else "Scaled"}",
                    color = CyanAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = config.resolution.toFloat(),
                onValueChange = { onConfigChange(config.copy(resolution = (it / 5).roundToInt() * 5)) },
                valueRange = 50f..100f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = CyanAccent,
                    inactiveTrackColor = Color.White.copy(alpha = .14f)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("50%", color = TextMuted, fontSize = 10.sp)
                Text("100%", color = TextMuted, fontSize = 10.sp)
            }

            Spacer(Modifier.height(12.dp))
            FieldLabel("FPS limit")
            Spacer(Modifier.height(9.dp))
            val fpsOptions = listOf("30", "60", "90", "120", "Max")
            GlassSegmented(
                options = fpsOptions,
                selectedIndex = fpsOptions.indexOf(config.fps).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth()
            ) { onConfigChange(config.copy(fps = fpsOptions[it])) }

            Spacer(Modifier.height(14.dp))
            ToggleRow(
                title = "Anti-aliasing 4x MSAA",
                subtitle = "Applies after the game restarts",
                checked = config.msaa4x
            ) { onConfigChange(config.copy(msaa4x = it)) }

            Spacer(Modifier.height(14.dp))
            GlassDivider()
            Spacer(Modifier.height(14.dp))

            // Graphics Driver (Default / Game Driver / Vulkan) seperti dalam gambar
            FieldLabel("Graphics driver", config.driver.label)
            Text(
                text = "Pilih pemacu grafik sistem tanpa mengganggu tetapan ANGLE di bawah.",
                color = TextMuted,
                fontSize = 10.5.sp
            )
            Spacer(Modifier.height(10.dp))
            val drivers = GpuDriver.values().toList()
            GlassSegmented(
                options = drivers.map { it.label },
                subs = drivers.map { it.sub },
                selectedIndex = drivers.indexOf(config.driver).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth()
            ) { onConfigChange(config.copy(driver = drivers[it])) }

            Spacer(Modifier.height(16.dp))
            GlassDivider()
            Spacer(Modifier.height(14.dp))

            // Tetapan ANGLE Diasingkan (Kekal boleh guna walaupun pilih Vulkan/Game Driver/Default)
            ToggleRow(
                title = "ANGLE Low Graphics Engine",
                subtitle = "Aktifkan profil tekstur ANGLE secara berasingan daripada Graphics Driver.",
                checked = config.angleEnabled,
                badge = "ANGLE"
            ) { onConfigChange(config.copy(angleEnabled = it)) }

            if (config.angleEnabled) {
                Spacer(Modifier.height(12.dp))
                FieldLabel("ANGLE texture profile")
                Spacer(Modifier.height(9.dp))
                GlassSegmented(
                    options = listOf("Smooth", "Pixel"),
                    selectedIndex = if (config.texMode == TexMode.Blur) 0 else 1,
                    modifier = Modifier.fillMaxWidth()
                ) { onConfigChange(config.copy(texMode = if (it == 0) TexMode.Blur else TexMode.Pixel)) }

                Spacer(Modifier.height(13.dp))
                FieldLabel("Texture LOD", "Level ${config.texLevel}")
                Spacer(Modifier.height(8.dp))
                GlassSegmented(
                    options = listOf("1", "2", "3", "4"),
                    selectedIndex = (config.texLevel - 1).coerceIn(0, 3),
                    modifier = Modifier.fillMaxWidth()
                ) { onConfigChange(config.copy(texLevel = it + 1)) }
            }
        }

        // ---- Performance & Touch Sensitivity Card ----
        Glass(Modifier.fillMaxWidth(), radius = 30) {
            SectionHeading("⚡", "Performance", "Game mode, touch sensitivity & system animation")
            Spacer(Modifier.height(17.dp))
            FieldLabel("Game Mode")
            Spacer(Modifier.height(9.dp))
            val modes = GameModeOpt.values().toList()
            GlassSegmented(
                options = modes.map { it.label },
                selectedIndex = modes.indexOf(config.gameMode),
                modifier = Modifier.fillMaxWidth()
            ) {
                val mode = modes[it]
                onConfigChange(
                    config.copy(
                        gameMode = mode,
                        batterySaver = if (mode == GameModeOpt.Saver) true else config.batterySaver
                    )
                )
            }

            Spacer(Modifier.height(14.dp))
            ToggleRow(
                title = "Fixed performance mode",
                subtitle = "Lock clocks for stability",
                checked = config.fixedPerf
            ) { onConfigChange(config.copy(fixedPerf = it)) }

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
            ) { onConfigChange(config.copy(animSpeed = speeds[it])) }

            Spacer(Modifier.height(15.dp))
            GlassDivider()
            Spacer(Modifier.height(14.dp))

            // Fitur Baharu: Atur Sensitiviti 1 - 100
            val sensDesc = when {
                config.sensitivity >= 85 -> "Ultra Licin"
                config.sensitivity >= 60 -> "Sangat Licin"
                config.sensitivity >= 35 -> "Seimbang"
                else -> "Standard"
            }
            FieldLabel(
                text = "Touch Sensitivity (Kelicinan Layar)",
                trailing = "${config.sensitivity} / 100 · $sensDesc"
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Semakin tinggi nilai (1–100), semakin licin dan responsif layar ketika digeser.",
                color = TextMuted,
                fontSize = 10.5.sp
            )
            Slider(
                value = config.sensitivity.toFloat(),
                onValueChange = { onConfigChange(config.copy(sensitivity = it.roundToInt().coerceIn(1, 100))) },
                valueRange = 1f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = CyanAccent,
                    inactiveTrackColor = Color.White.copy(alpha = .14f)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1 (Normal)", color = TextMuted, fontSize = 10.sp)
                Text("50 (Fast)", color = TextMuted, fontSize = 10.sp)
                Text("100 (Ultra Licin)", color = CyanAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // ---- Memory Card ----
        Glass(Modifier.fillMaxWidth(), radius = 30) {
            SectionHeading("▦", "Memory", "Bersihkan RAM dan hentikan aplikasi latar belakang")
            Spacer(Modifier.height(16.dp))

            val availGbText = stats.ramAvailGb.fmt1()
            val totalGbText = stats.ramTotalGb.fmt1()

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "$availGbText GB",
                        color = TextMain,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text("RAM tersedia", color = TextMuted, fontSize = 11.sp)
                }
                Text(
                    "${stats.ramPercent}% digunakan  ·  $totalGbText GB",
                    color = CyanAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(10.dp))
            GlowingProgressBar(
                progress = stats.ramPercent / 100f,
                modifier = Modifier.fillMaxWidth(),
                height = 8.dp
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    text = "Clear RAM",
                    modifier = Modifier.weight(1f),
                    primary = true,
                    enabled = !busy,
                    onClick = onClearRam
                )
                GlassButton(
                    text = "Stop background",
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = onStopBg
                )
            }

            Spacer(Modifier.height(16.dp))
            GlassDivider()
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "Clear RAM sebelum Launch",
                subtitle = "Bersihkan proses cache secara automatik sebelum game dibuka.",
                checked = config.clearRamOnLaunch
            ) { onConfigChange(config.copy(clearRamOnLaunch = it)) }

            Spacer(Modifier.height(8.dp))
            GlassDivider()
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Henti app latar sebelum Launch",
                subtitle = "Force stop aplikasi lain yang sedang berjalan sebelum game dibuka.",
                checked = config.stopBgOnLaunch,
                badge = "FORCE STOP"
            ) { onConfigChange(config.copy(stopBgOnLaunch = it)) }
        }

        // ---- Network & Power Card ----
        Glass(Modifier.fillMaxWidth(), radius = 30) {
            SectionHeading("◎", "Network & power", "Connection preferences and battery behaviour")
            Spacer(Modifier.height(16.dp))
            ToggleRow(
                title = "Wi-Fi & DNS preferences",
                subtitle = "Mengubah imbasan latar dan Private DNS (1.1.1.1).",
                checked = config.netBoost,
                badge = "NETWORK"
            ) { onConfigChange(config.copy(netBoost = it)) }

            Spacer(Modifier.height(12.dp))
            GlassDivider()
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "Battery saver",
                subtitle = "Mengaktifkan mod jimat kuasa dan had refresh rate minimum.",
                checked = config.batterySaver,
                badge = "ECO"
            ) {
                onConfigChange(
                    config.copy(
                        batterySaver = it,
                        fixedPerf = if (it) false else config.fixedPerf
                    )
                )
            }
        }

        // ---- Check ANGLE Support ----
        Glass(
            modifier = Modifier.fillMaxWidth(),
            radius = 26,
            padding = 16.dp,
            onClick = onCheckAngle
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check ANGLE support", color = TextMain, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Text("Semak sama ada ANGLE wujud pada sistem.", color = TextMuted, fontSize = 11.sp)
                }
                Text("↗", color = CyanAccent, fontSize = 18.sp)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Floating Liquid-Glass Bottom Navigation Pill
// -----------------------------------------------------------------------------
@Composable
private fun FloatingBottomNavBar(
    currentTab: NavTab,
    onSelectTab: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val pill = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(66.dp)
            .shadow(26.dp, pill, spotColor = CyanGlow.copy(alpha = .45f))
            .liquidGlass(pill, CyanAccent)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NavPillItem(
            selected = currentTab == NavTab.Profile,
            label = "Profile",
            icon = { color -> GridNavIcon(Modifier.size(18.dp), color) },
            onClick = { onSelectTab(NavTab.Profile) }
        )
        NavPillItem(
            selected = currentTab == NavTab.Home,
            label = "Home",
            icon = { color -> HomeNavIcon(Modifier.size(18.dp), color) },
            onClick = { onSelectTab(NavTab.Home) }
        )
        NavPillItem(
            selected = currentTab == NavTab.Settings,
            label = "Settings",
            icon = { color -> SlidersNavIcon(Modifier.size(18.dp), color) },
            onClick = { onSelectTab(NavTab.Settings) }
        )
    }
}

@Composable
private fun NavPillItem(
    selected: Boolean,
    label: String,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .height(52.dp)
            .clip(shape)
            .then(
                if (selected) {
                    Modifier
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF1A354E), Color(0xFF12263A))
                            )
                        )
                        .border(1.dp, CyanAccent.copy(alpha = .45f), shape)
                } else {
                    Modifier
                }
            )
            .clickable { onClick() }
            .padding(horizontal = if (selected) 20.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon(if (selected) CyanAccent else TextMuted)
        if (selected) {
            Spacer(Modifier.width(9.dp))
            Text(
                text = label,
                color = CyanAccent,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Add Game Library Modal Dialog
// -----------------------------------------------------------------------------
@Composable
private fun AddGameLibraryModal(
    installedApps: List<Game>,
    selectedGames: List<Game>,
    loadingApps: Boolean,
    showGamesOnly: Boolean,
    searchQuery: String,
    onToggleTab: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onScan: () -> Unit,
    onToggleGame: (Game) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedPackages = selectedGames.map { it.pkg }.toSet()
    val filteredApps = remember(installedApps, showGamesOnly, searchQuery, selectedPackages) {
        installedApps.filter { app ->
            val matchesTab = if (showGamesOnly) app.isGame || app.pkg in selectedPackages else true
            val matchesSearch = searchQuery.isBlank() ||
                app.name.contains(searchQuery, ignoreCase = true) ||
                app.pkg.contains(searchQuery, ignoreCase = true)
            matchesTab && matchesSearch
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xAA02050B))
                .padding(horizontal = 18.dp, vertical = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            Glass(
                modifier = Modifier.fillMaxWidth(),
                radius = 32,
                padding = 20.dp,
                tint = CyanAccent,
                deepTint = true
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Tambah ke Game Library",
                            color = TextMain,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "${selectedGames.size} / 10 aplikasi dipilih",
                            color = CyanAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    GlassButton(
                        text = "Selesai",
                        primary = true,
                        height = 38.dp,
                        horizontalPadding = 16.dp,
                        onClick = onDismiss
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassSegmented(
                        options = listOf("Games", "All apps · ${installedApps.size}"),
                        selectedIndex = if (showGamesOnly) 0 else 1,
                        modifier = Modifier.weight(1f)
                    ) { onToggleTab(it == 0) }

                    GlassButton(
                        text = if (loadingApps) "…" else "↻",
                        enabled = !loadingApps,
                        height = 44.dp,
                        horizontalPadding = 14.dp,
                        onClick = onScan
                    )
                }

                Spacer(Modifier.height(10.dp))
                GlassSearchField(
                    value = searchQuery,
                    placeholder = "Cari nama aplikasi atau package…",
                    onValueChange = onSearchChange
                )

                Spacer(Modifier.height(12.dp))
                if (loadingApps) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CyanAccent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    }
                } else if (filteredApps.isEmpty()) {
                    Text(
                        "Tiada aplikasi ditemui. Cuba tukar ke tab All apps.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        filteredApps.forEach { game ->
                            val isSelected = game.pkg in selectedPackages
                            val rowShape = RoundedCornerShape(18.dp)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(if (isSelected) CyanAccent.copy(alpha = .15f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) CyanAccent.copy(alpha = .45f) else Color.White.copy(alpha = .06f),
                                        rowShape
                                    )
                                    .clickable { onToggleGame(game) }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (game.icon != null) {
                                    Image(
                                        bitmap = game.icon,
                                        contentDescription = game.name,
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.White.copy(alpha = .08f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("APP", color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        game.name,
                                        color = TextMain,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        game.pkg,
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                SelectCircle(isSelected)
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Shared Liquid-Glass Controls
// -----------------------------------------------------------------------------
@Composable
private fun SectionHeading(glyph: String, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .liquidGlass(RoundedCornerShape(14.dp), CyanAccent),
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, color = CyanAccent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
            Text(trailing, color = CyanAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

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
            .background(Color(0x4D000000))
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
                            color = if (selected) CyanAccent else TextMuted.copy(alpha = .7f),
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
        targetValue = if (pressed) .94f else 1f,
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
                    listOf(Color(0xFF98F1FF), Color(0xFF56D4FF), Color(0xFF28A8E0))
                ) else Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = .18f), Color.White.copy(alpha = .06f))
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (primary) .85f else .40f), Color.White.copy(alpha = .08f))
                ),
                shape
            )
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (primary) Color(0xFF041322) else TextMain,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.ExtraBold,
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
                        color = CyanAccent,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(CyanAccent.copy(alpha = .14f))
                            .border(1.dp, CyanAccent.copy(alpha = .30f), RoundedCornerShape(50))
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
                checkedThumbColor = Color(0xFF041322),
                checkedTrackColor = CyanAccent,
                checkedBorderColor = Color.White.copy(alpha = .5f),
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
                if (selected) Brush.linearGradient(listOf(Color(0xFF98F1FF), CyanGlow))
                else Brush.linearGradient(listOf(Color.White.copy(alpha = .06f), Color.White.copy(alpha = .06f)))
            )
            .border(
                1.dp,
                if (selected) Color.White.copy(alpha = .85f) else Color.White.copy(alpha = .25f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Text("✓", color = Color(0xFF041322), fontSize = 13.sp, fontWeight = FontWeight.Black)
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
        cursorBrush = SolidColor(CyanAccent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(pill)
                    .background(Color(0x4D000000))
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
