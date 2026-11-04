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
    fun granted() = alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    fun ask() { if (alive() && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQ) }

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

// Level 1..4 -> skala resolusi render (makin besar level = makin rendah kualiti)

fun scaleFor(level: Int) = when (level) { 1 -> 0.75f; 2 -> 0.50f; 3 -> 0.30f; else -> 0.20f }
