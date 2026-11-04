package com.lowgx

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------- Shizuku helper ----------
object Sh {
    private const val REQ = 1001

    fun alive() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun granted() = runCatching { alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun ask() { runCatching { if (alive()) Shizuku.requestPermission(REQ) } }

    fun run(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }
}

// ---------- Models & Enums ----------
data class Game(val name: String, val pkg: String)

val GAMES = listOf(
    Game("Free Fire MAX", "com.dts.freefiremax"),
    Game("Free Fire", "com.dts.freefireth"),
    Game("PUBG Mobile", "com.tencent.ig"),
    Game("Mobile Legends", "com.mobile.legends"),
    Game("Genshin Impact", "com.miHoYo.GenshinImpact"),
)

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
    val resolution: Int = 100, // 50..100
    val fps: String = "Max",   // 30, 60, 90, 120, Max
    val msaa4x: Boolean = false,
    val driver: GpuDriver = GpuDriver.Angle,
    val gameMode: GameModeOpt = GameModeOpt.Performance,
    val fixedPerf: Boolean = true,
    val animSpeed: AnimSpeed = AnimSpeed.Fast,
    val netBoost: Boolean = true,
    val batterySaver: Boolean = false
)

// ---------- Core Execution Engine (Real Android Shell Commands) ----------
fun applyAll(selected: List<Game>, cfg: ConfigState): String {
    if (selected.isEmpty()) return "Pilih sekurang-kurangnya satu game."

    val cmds = mutableListOf<String>()
    val pkgs = selected.joinToString(",") { it.pkg }

    // 1. Graphics Driver + ANGLE Low Texture
    when (cfg.driver) {
        GpuDriver.Default -> {
            cmds += "settings delete global angle_gl_driver_selection_pkgs"
            cmds += "settings delete global angle_gl_driver_selection_values"
            cmds += "settings delete global updatable_driver_production_opt_in_apps"
            cmds += "setprop debug.angle.feature_overrides_enabled \"\""
            cmds += "setprop debug.hwui.renderer opengl"
        }
        GpuDriver.GameDriver -> {
            cmds += "settings delete global angle_gl_driver_selection_pkgs"
            cmds += "settings delete global angle_gl_driver_selection_values"
            cmds += "settings put global updatable_driver_production_opt_in_apps $pkgs"
            cmds += "setprop debug.hwui.renderer opengl"
        }
        GpuDriver.Angle -> {
            val vals = selected.joinToString(",") { "angle" }
            val features = when (cfg.texMode) {
                TexMode.Blur -> "disableAnisotropicFiltering:forceTextureLodOffset${cfg.texLevel}"
                TexMode.Pixel -> "forceNearestFiltering:disableAnisotropicFiltering:forceTextureLodOffset${cfg.texLevel}"
            }
            cmds += "settings put global angle_gl_driver_selection_pkgs $pkgs"
            cmds += "settings put global angle_gl_driver_selection_values $vals"
            cmds += "setprop debug.angle.feature_overrides_enabled $features"
        }
        GpuDriver.Vulkan -> {
            cmds += "settings delete global angle_gl_driver_selection_pkgs"
            cmds += "settings delete global angle_gl_driver_selection_values"
            cmds += "setprop debug.hwui.renderer skiagl"
            cmds += "setprop debug.vulkan.layers \"\""
        }
    }

    // 2. Game Resolution (Android 12+ cmd game downscale) & FPS Limit & Game Mode
    val downscaleRatio = if (cfg.resolution >= 100) "disable" else (cfg.resolution / 100f).toString()
    val fpsArg = if (cfg.fps == "Max") "disable" else cfg.fps

    selected.forEach { g ->
        // Set Game Mode (1=Standard, 2=Performance, 3=Battery)
        cmds += "cmd game mode ${cfg.gameMode.id} ${g.pkg} 2>/dev/null || true"
        // Set Downscale & FPS via GameManager service
        cmds += "cmd game set --mode ${cfg.gameMode.id} --downscale $downscaleRatio --fps $fpsArg ${g.pkg} 2>/dev/null || true"
    }

    // System Refresh Rate sync with FPS Limit
    val maxRefresh = if (cfg.fps == "Max") "120.0" else "${cfg.fps}.0"
    val minRefresh = if (cfg.batterySaver) "60.0" else maxRefresh
    cmds += "settings put system peak_refresh_rate $maxRefresh"
    cmds += "settings put system min_refresh_rate $minRefresh"

    // 3. Anti-aliasing 4x MSAA
    cmds += "setprop debug.egl.force_msaa ${if (cfg.msaa4x) "true" else "false"}"
    cmds += "service call SurfaceFlinger 1008 i32 ${if (cfg.msaa4x) 1 else 0} 2>/dev/null || true"

    // 4. Fixed Performance Mode (Lock Clocks)
    cmds += "cmd power set-fixed-performance-mode-enabled ${cfg.fixedPerf}"

    // 5. System Animation Scale
    val sc = cfg.animSpeed.scale
    cmds += "settings put global window_animation_scale $sc"
    cmds += "settings put global transition_animation_scale $sc"
    cmds += "settings put global animator_duration_scale $sc"

    // 6. Internet Boost & Low Ping
    if (cfg.netBoost) {
        // Elak Wi-Fi tidur dan matikan background scanning yang menyebabkan lag/jitter
        cmds += "settings put global wifi_sleep_policy 2"
        cmds += "settings put global wifi_scan_always_enabled 0"
        cmds += "settings put global ble_scan_always_enabled 0"
        cmds += "settings put global network_recommendations_enabled 0"
        // DNS Cloudflare (1.1.1.1) untuk masa resolusi paket paling pantas
        cmds += "settings put global private_dns_mode hostname"
        cmds += "settings put global private_dns_specifier one.one.one.one"
        // Kurangkan latency TCP
        cmds += "setprop net.tcp.nodelay 1"
        // Hentikan sinkronisasi automatik di latar belakang semasa bermain
        cmds += "content insert --uri content://settings/global --bind name:s:auto_sync --bind value:s:0 2>/dev/null || true"
    } else {
        cmds += "settings put global wifi_scan_always_enabled 1"
        cmds += "settings put global private_dns_mode opportunistic"
    }

    // 7. Battery Saver
    if (cfg.batterySaver) {
        cmds += "cmd power set-mode 1"
        cmds += "cmd power set-adaptive-power-saver-enabled true"
        cmds += "settings put global app_standby_enabled 1"
        cmds += "settings put global forced_app_standby_enabled 1"
    } else {
        cmds += "cmd power set-mode 0"
        cmds += "settings put global forced_app_standby_enabled 0"
    }

    // 8. Force-stop selected games supaya tetapan terus berkuat kuasa
    selected.forEach { g ->
        cmds += "am force-stop ${g.pkg}"
    }

    val out = Sh.run(cmds.joinToString(" && "))
    return "Tetapan berjaya digunakan (${cfg.driver.label}, Res ${cfg.resolution}%, FPS ${cfg.fps}, Mode ${cfg.gameMode.label})." +
        if (out.isNotBlank()) "\n$out" else ""
}

fun resetAll(selected: List<Game>): String {
    val cmds = mutableListOf(
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
    selected.forEach { g ->
        cmds += "cmd game reset ${g.pkg} 2>/dev/null || true"
        cmds += "am force-stop ${g.pkg}"
    }
    val out = Sh.run(cmds.joinToString(" && "))
    return "Semua tetapan telah dikembalikan ke asal." + if (out.isNotBlank()) "\n$out" else ""
}

fun checkAngle(): String {
    val out = Sh.run("ls -d /system/*/*angle* /vendor/*/*angle* 2>/dev/null")
    return if (out.isBlank()) "ANGLE tidak dijumpai di peranti ini." else "ANGLE disokong:\n$out"
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = java.io.File(filesDir, "crash.txt")
        val prev = if (f.exists()) f.readText() else null
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            runCatching { f.writeText(e.stackTraceToString()) }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (prev != null) CrashView(prev) { f.delete(); recreate() } else LowGXApp()
            }
        }
    }
}

@Composable
fun CrashView(log: String, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Purple1).statusBarsPadding().padding(16.dp)) {
        Text("LowGX crash log (salin & hantar)", color = Color.White, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(log.take(3500), color = Color(0xFFFFB4AB), fontSize = 11.sp)
            }
        }
        Button(onClick = onClear) { Text("Padam log & cuba lagi") }
    }
}

// ---------- Colors ----------
val Purple1 = Color(0xFF0E0820)
val Purple2 = Color(0xFF2A1058)
val Purple3 = Color(0xFF6D3BD2)
val Lilac = Color(0xFFC9A8FF)

// ---------- Particles ----------
private class P(val x: Float, val y: Float, val r: Float, val spd: Float, val ph: Float)

@Composable
fun ParticleBackground() {
    val particles = remember { List(70) { P(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 3f + 1f, Random.nextFloat() * 0.04f + 0.01f, Random.nextFloat() * 6.28f) } }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { t = it / 1_000_000_000f }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Purple1, Purple2, Purple1)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Purple3.copy(.45f), Color.Transparent)), radius = size.width * .7f, center = Offset(size.width * .15f, size.height * .2f))
            drawCircle(Brush.radialGradient(listOf(Lilac.copy(.25f), Color.Transparent)), radius = size.width * .6f, center = Offset(size.width * .9f, size.height * .75f))
            particles.forEach { p ->
                val y = ((p.y - t * p.spd) % 1f + 1f) % 1f
                val x = p.x + kotlin.math.sin(t * .6f + p.ph) * 0.02f
                val a = (0.25f + 0.5f * kotlin.math.abs(kotlin.math.sin(t + p.ph)))
                drawCircle(Lilac.copy(alpha = a), p.r * 2f, Offset(x * size.width, y * size.height))
            }
        }
    }
}

// ---------- Liquid glass ----------
@Composable
fun Glass(modifier: Modifier = Modifier, radius: Int = 28, onClick: (() -> Unit)? = null, tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius.dp)
    Column(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(tint.copy(.20f), tint.copy(.06f), tint.copy(.12f))))
            .border(1.2.dp, Brush.linearGradient(listOf(Color.White.copy(.65f), Color.White.copy(.05f), Color.White.copy(.35f))), shape)
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(20.dp),
        content = content
    )
}

@Composable
fun Seg(text: String, sel: Boolean, modifier: Modifier = Modifier, sub: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .height(if (sub != null) 60.dp else 48.dp)
            .clip(shape)
            .background(if (sel) Brush.linearGradient(listOf(Lilac, Purple3)) else Brush.linearGradient(listOf(Color.White.copy(.10f), Color.White.copy(.04f))))
            .border(1.dp, Color.White.copy(if (sel) .7f else .2f), shape)
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (sel) Color(0xFF1B0B3F) else Color.White)
        if (sub != null) {
            Text(sub, fontSize = 9.sp, color = if (sel) Color(0xFF1B0B3F).copy(.75f) else Color.White.copy(.55f))
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, badge: String? = null, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                if (badge != null) {
                    Text(
                        badge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD580),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFFFD580).copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(subtitle, fontSize = 12.sp, color = Color.White.copy(.65f))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Purple1, checkedTrackColor = Lilac)
        )
    }
}

// ---------- Screen ----------
@Composable
fun LowGXApp() {
    val scope = rememberCoroutineScope()
    var shizuku by remember { mutableStateOf("Memeriksa…") }
    var cfg by remember { mutableStateOf(ConfigState()) }
    val selected = remember { mutableStateListOf(GAMES[0]) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        shizuku = when {
            !Sh.alive() -> "Tidak berjalan – mulakan Shizuku"
            !Sh.granted() -> "Ketik untuk beri kebenaran"
            else -> "Connected"
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Box(Modifier.fillMaxSize()) {
        ParticleBackground()
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Glass(Modifier.size(64.dp), radius = 20) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("GX", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Lilac)
                    }
                }
                Column {
                    Text("LowGX Space", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Graphics, Performance & Network Optimizer", color = Color.White.copy(.7f), fontSize = 13.sp)
                }
            }

            // Shizuku Status
            Glass(Modifier.fillMaxWidth(), onClick = { if (Sh.alive() && !Sh.granted()) Sh.ask(); refresh() }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Shizuku Service", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(shizuku, color = if (shizuku == "Connected") Color(0xFF8DF5A8) else Color(0xFFFFB4AB), fontSize = 13.sp)
                    }
                    Text("Refresh", color = Lilac, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            // Select Games
            Glass(Modifier.fillMaxWidth()) {
                Text("Select Games", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                GAMES.forEach { g ->
                    val on = g in selected
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable { if (on) selected.remove(g) else selected.add(g) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(on, { if (it) selected.add(g) else selected.remove(g) }, colors = CheckboxDefaults.colors(checkedColor = Lilac, checkmarkColor = Purple1))
                        Text(g.name, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // ==================== GRAPHICS SECTION ====================
            Glass(Modifier.fillMaxWidth(), tint = Purple3) {
                Text("GRAPHICS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Lilac, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(14.dp))

                // Game Resolution Slider (50% - 100%)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Game resolution", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    Text(
                        "${cfg.resolution}% · ${if (cfg.resolution == 100) "Native" else "Scaled"}",
                        color = Lilac, fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                }
                Slider(
                    value = cfg.resolution.toFloat(),
                    onValueChange = { cfg = cfg.copy(resolution = (it / 5).roundToInt() * 5) },
                    valueRange = 50f..100f,
                    steps = 9,
                    colors = SliderDefaults.colors(thumbColor = Lilac, activeTrackColor = Lilac)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("50% (High FPS)", fontSize = 11.sp, color = Color.White.copy(.5f))
                    Text("100% (Native)", fontSize = 11.sp, color = Color.White.copy(.5f))
                }

                Spacer(Modifier.height(16.dp))

                // FPS Limit
                Text("FPS limit", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                val fpsOptions = listOf("30", "60", "90", "120", "Max")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fpsOptions.forEach { f ->
                        Seg(f, cfg.fps == f, Modifier.weight(1f)) { cfg = cfg.copy(fps = f) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Anti-aliasing 4x MSAA
                ToggleRow(
                    title = "Anti-aliasing 4x MSAA",
                    subtitle = "Applies after the game restarts",
                    checked = cfg.msaa4x,
                    badge = "PRO"
                ) { cfg = cfg.copy(msaa4x = it) }

                Spacer(Modifier.height(16.dp))

                // Graphics Driver
                Text("Graphics driver", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GpuDriver.values().forEach { drv ->
                        Seg(drv.label, cfg.driver == drv, Modifier.weight(1f), sub = drv.sub) {
                            cfg = cfg.copy(driver = drv)
                        }
                    }
                }

                // Low Texture Settings (Active when ANGLE is chosen)
                if (cfg.driver == GpuDriver.Angle) {
                    Spacer(Modifier.height(18.dp))
                    Divider(color = Color.White.copy(alpha = 0.15f))
                    Spacer(Modifier.height(14.dp))
                    Text("ANGLE Low Texture", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Lilac)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TexMode.values().forEach { m ->
                            Seg(if (m == TexMode.Blur) "Smooth" else "Pixel", cfg.texMode == m, Modifier.weight(1f)) {
                                cfg = cfg.copy(texMode = m)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Texture LOD Level", color = Color.White, fontSize = 14.sp)
                        Text("Level ${cfg.texLevel}", color = Lilac, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..4).forEach { l ->
                            Seg("$l", cfg.texLevel == l, Modifier.weight(1f)) { cfg = cfg.copy(texLevel = l) }
                        }
                    }
                }
            }

            // ==================== PERFORMANCE SECTION ====================
            Glass(Modifier.fillMaxWidth()) {
                Text("PERFORMANCE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Lilac, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(14.dp))

                // Game Mode
                Text("Game Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameModeOpt.values().forEach { gm ->
                        Seg(gm.label, cfg.gameMode == gm, Modifier.weight(1f)) {
                            cfg = cfg.copy(
                                gameMode = gm,
                                batterySaver = if (gm == GameModeOpt.Saver) true else cfg.batterySaver
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Fixed performance mode
                ToggleRow(
                    title = "Fixed performance mode",
                    subtitle = "Lock CPU/GPU clocks for stability",
                    checked = cfg.fixedPerf
                ) { cfg = cfg.copy(fixedPerf = it) }

                Spacer(Modifier.height(14.dp))

                // System animation
                Text("System animation", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimSpeed.values().forEach { anim ->
                        Seg(anim.label, cfg.animSpeed == anim, Modifier.weight(1f)) {
                            cfg = cfg.copy(animSpeed = anim)
                        }
                    }
                }
            }

            // ==================== NETWORK & BATTERY SECTION ====================
            Glass(Modifier.fillMaxWidth()) {
                Text("NETWORK & POWER", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Lilac, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(10.dp))

                ToggleRow(
                    title = "Internet Boost & Low Ping",
                    subtitle = "Disable Wi-Fi/BLE background scan, lock Wi-Fi awake, Cloudflare DNS & TCP NoDelay",
                    checked = cfg.netBoost,
                    badge = "FAST"
                ) { cfg = cfg.copy(netBoost = it) }

                Spacer(Modifier.height(8.dp))
                Divider(color = Color.White.copy(alpha = 0.12f))
                Spacer(Modifier.height(8.dp))

                ToggleRow(
                    title = "Extreme Battery Saver",
                    subtitle = "Enable system power save, limit background standby & cap minimum refresh rate",
                    checked = cfg.batterySaver,
                    badge = "ECO"
                ) {
                    cfg = cfg.copy(
                        batterySaver = it,
                        fixedPerf = if (it) false else cfg.fixedPerf
                    )
                }
            }

            // Status Output
            if (status.isNotEmpty()) {
                Glass(Modifier.fillMaxWidth(), radius = 18) {
                    Text(status, color = Color(0xFF8DF5A8), fontSize = 12.sp)
                }
            }

            // Action Buttons
            Seg("Check ANGLE Support", false, Modifier.fillMaxWidth()) {
                if (!Sh.granted()) { status = "Shizuku belum bersedia"; return@Seg }
                scope.launch {
                    busy = true
                    status = withContext(Dispatchers.IO) { runCatching { checkAngle() }.getOrElse { it.message ?: "Ralat" } }
                    busy = false
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Seg("Reset to default", false, Modifier.weight(1f)) {
                    if (!Sh.granted()) { status = "Shizuku belum bersedia"; return@Seg }
                    scope.launch {
                        busy = true
                        status = withContext(Dispatchers.IO) {
                            runCatching { resetAll(selected.toList()) }.getOrElse { it.message ?: "Ralat" }
                        }
                        cfg = ConfigState()
                        busy = false
                    }
                }
                Seg(if (busy) "Applying…" else "Save & Boost", true, Modifier.weight(1.5f)) {
                    if (!Sh.granted()) { status = "Shizuku belum bersedia"; return@Seg }
                    scope.launch {
                        busy = true
                        status = withContext(Dispatchers.IO) {
                            runCatching { applyAll(selected.toList(), cfg) }.getOrElse { it.message ?: "Ralat" }
                        }
                        busy = false
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
