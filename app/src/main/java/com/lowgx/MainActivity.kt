package com.lowgx

/*
 * LowGX - Graphics optimizer (Shizuku + ANGLE) - Liquid Glass purple UI
 *
 * build.gradle.kts (app) dependencies:
 *   implementation("dev.rikka.shizuku:api:13.1.5")
 *   implementation("dev.rikka.shizuku:provider:13.1.5")
 *   implementation(platform("androidx.compose:compose-bom:2024.09.00"))
 *   implementation("androidx.compose.material3:material3")
 *   implementation("androidx.compose.material:material-icons-extended")
 *   implementation("androidx.activity:activity-compose:1.9.2")
 *
 * AndroidManifest.xml:
 *   <provider
 *       android:name="rikka.shizuku.ShizukuProvider"
 *       android:authorities="${applicationId}.shizuku"
 *       android:multiprocess="false"
 *       android:enabled="true"
 *       android:exported="true"
 *       android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
 *   <queries><package android:name="com.dts.freefiremax"/></queries>
 *
 * minSdk 31 (Android 12+) disyorkan.
 */

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.random.Random

// ---------- Shizuku helper ----------
object Sh {
    private const val REQ = 1001

    fun alive() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun granted() = runCatching { alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun ask() { runCatching { if (alive()) Shizuku.requestPermission(REQ) } }

    // newProcess() private di Shizuku 13.x -> guna reflection
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

// ---------- Model ----------
data class Game(val name: String, val pkg: String)

val GAMES = listOf(
    Game("Free Fire MAX", "com.dts.freefiremax"),
    Game("Free Fire", "com.dts.freefireth"),
    Game("PUBG Mobile", "com.tencent.ig"),
    Game("Mobile Legends", "com.mobile.legends"),
    Game("Genshin Impact", "com.miHoYo.GenshinImpact"),
)

enum class TexMode { Blur, Pixel }

// Level 1..4 -> forceTextureLodOffset1..4 (makin besar = tekstur makin rendah)
fun applyAll(selected: List<Game>, mode: TexMode, level: Int): String {
    if (selected.isEmpty()) return "Pilih sekurang-kurangnya satu game."
    val pkgs = selected.joinToString(",") { it.pkg }
    val vals = selected.joinToString(",") { "angle" }
    val features = when (mode) {
        TexMode.Blur -> "disableAnisotropicFiltering:forceTextureLodOffset$level"
        TexMode.Pixel -> "forceNearestFiltering:disableAnisotropicFiltering:forceTextureLodOffset$level"
    }
    val cmd = "settings put global angle_gl_driver_selection_pkgs $pkgs && " +
        "settings put global angle_gl_driver_selection_values $vals && " +
        "setprop debug.angle.feature_overrides_enabled $features" +
        selected.joinToString("") { " && am force-stop ${it.pkg}" }
    val out = Sh.run(cmd)
    return "Siap: ${mode.name}, level $level (game ditutup, buka semula)." + if (out.isNotBlank()) "\n$out" else ""
}

fun resetAll(selected: List<Game>): String {
    val cmd = "settings delete global angle_gl_driver_selection_values && " +
        "settings delete global angle_gl_driver_selection_pkgs && " +
        "setprop debug.angle.feature_overrides_enabled \"\"" +
        selected.joinToString("") { " && am force-stop ${it.pkg}" }
    val out = Sh.run(cmd)
    return "Dikembalikan ke asal." + if (out.isNotBlank()) "\n$out" else ""
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
            // orb cahaya
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
fun Seg(text: String, sel: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (sel) Brush.linearGradient(listOf(Lilac, Purple3)) else Brush.linearGradient(listOf(Color.White.copy(.10f), Color.White.copy(.04f))))
            .border(1.dp, Color.White.copy(if (sel) .7f else .2f), shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(text, fontWeight = FontWeight.Bold, color = if (sel) Color(0xFF1B0B3F) else Color.White) }
}

// ---------- Screen ----------
@Composable
fun LowGXApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shizuku by remember { mutableStateOf("Memeriksa…") }
    var mode by remember { mutableStateOf(TexMode.Pixel) }
    var level by remember { mutableIntStateOf(4) }
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Glass(Modifier.size(64.dp), radius = 20) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("GX", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Lilac) } }
                Column {
                    Text("LowGX", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Graphics optimizer for any games", color = Color.White.copy(.7f), fontSize = 14.sp)
                }
            }

            Glass(Modifier.fillMaxWidth(), tint = Purple3) {
                Text("Low Graphics · ANGLE", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${if (mode == TexMode.Blur) "Smooth" else "Pixel"} · level $level", color = Lilac)
            }

            Glass(Modifier.fillMaxWidth(), onClick = { if (Sh.alive() && !Sh.granted()) Sh.ask(); refresh() }) {
                Text("Shizuku", fontSize = 20.sp, color = Color.White)
                Text(shizuku, color = if (shizuku == "Connected") Color(0xFF8DF5A8) else Color(0xFFFFB4AB))
            }

            Glass(Modifier.fillMaxWidth()) {
                Text("Select games", fontSize = 20.sp, color = Color.White)
                Spacer(Modifier.height(10.dp))
                GAMES.forEach { g ->
                    val on = g in selected
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable { if (on) selected.remove(g) else selected.add(g) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(on, { if (it) selected.add(g) else selected.remove(g) }, colors = CheckboxDefaults.colors(checkedColor = Lilac, checkmarkColor = Purple1))
                        Text(g.name, color = Color.White)
                    }
                }
            }

            Glass(Modifier.fillMaxWidth()) {
                Text("Low Texture", fontSize = 22.sp, color = Color.White)
                Text("Reduce texture detail", color = Color.White.copy(.7f))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TexMode.values().forEach { m -> Seg(if (m == TexMode.Blur) "Smooth" else "Pixel", mode == m, Modifier.weight(1f)) { mode = m } }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Texture level", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    Text("$level · ${if (level == 4) "Lowest" else if (level == 1) "Mild" else "Low"}", color = Lilac, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..4).forEach { l -> Seg("$l", level == l, Modifier.weight(1f)) { level = l } }
                }
                Spacer(Modifier.height(10.dp))
                Text("Higher number = lower graphics quality and better performance.", color = Color.White.copy(.65f), fontSize = 13.sp)
            }

            if (status.isNotEmpty()) Text(status, color = Color.White.copy(.75f), fontSize = 12.sp)

            Seg("Cek ANGLE support", false, Modifier.fillMaxWidth()) {
                if (!Sh.granted()) { status = "Shizuku belum bersedia"; return@Seg }
                scope.launch { busy = true; status = withContext(Dispatchers.IO) { runCatching { checkAngle() }.getOrElse { it.message ?: "Ralat" } }; busy = false }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Seg("Reset", false, Modifier.weight(1f)) {
                    if (!Sh.granted()) { status = "Shizuku belum bersedia"; return@Seg }
                    scope.launch { busy = true; status = withContext(Dispatchers.IO) { runCatching { resetAll(selected.toList()) }.getOrElse { it.message ?: "Ralat" } }; busy = false }
                }
                Seg(if (busy) "…" else "Save changes", true, Modifier.weight(2f)) {
                    if (!Sh.granted()) { status = "Shizuku belum bersedia"; return@Seg }
                    scope.launch { busy = true; status = withContext(Dispatchers.IO) { runCatching { applyAll(selected.toList(), mode, level) }.getOrElse { it.message ?: "Ralat" } }; busy = false }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
