package com.nestar.finder

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var bleScanner: BleScanner
    private lateinit var alarmEngine: AlarmEngine

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 权限授予回调：若此时已具备蓝牙扫描权限，自动开始扫描，
        // 避免"授权后扫描不自动恢复"（页面 LaunchedEffect(Unit) 不会因授权而重跑）
        if (::bleScanner.isInitialized && bleScanner.hasPermission()) {
            bleScanner.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleScanner = BleScanner(this)
        alarmEngine = AlarmEngine(this, lifecycleScope)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        // 蓝牙权限由页面进入时按需申请（ScanScreen/MonitorScreen 的 LaunchedEffect 中），
        // 这里不再重复申请，避免首次启动弹两次权限框
        // 提醒默认关闭：必须用户主动"开始搜索"并选中目标后，才在开始按钮里开启
        alarmEngine.enabled = false
        alarmEngine.startWatching(bleScanner.targetRssi)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF25C9FF),
                    onPrimary = Color(0xFF06121F),
                    background = Color(0xFF141235),
                    surface = Color(0xFF1B1946),
                    onSurface = Color(0xFFEDEFFF)
                )
            ) {
                AppRoot(bleScanner, alarmEngine, onRequestPerms = { requestBtPerms() })
            }
        }
    }

    private fun requestBtPerms() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 仅申请扫描权限（最小权限，不再需要 BLUETOOTH_CONNECT）
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(perms)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleScanner.stop()
        bleScanner.release()   // 反注册蓝牙状态广播，避免泄漏
        alarmEngine.stopWatching()
    }
}

// ============ 主题色（对齐参考图：深蓝紫渐变 + 亮青/亮橙/亮绿） ============
private val BG_TOP = Color(0xFF4A3BC0)
private val BG_MID = Color(0xFF2E37B8)
private val BG_BOT = Color(0xFF16215E)
private val ACCENT_CYAN = Color(0xFF25C9FF)
private val CARD_BG = Color(0x33FFFFFF)
private val CARD_BORDER = Color(0x33FFFFFF)

@Composable
fun AppRoot(scanner: BleScanner, alarm: AlarmEngine, onRequestPerms: () -> Unit) {
    // 若有记忆的监控目标，默认直接进监控页；否则先到扫描页
    var tab by rememberSaveable { mutableIntStateOf(if (scanner.selectedAddress.value != null) 1 else 0) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BG_TOP, BG_MID, BG_BOT)))) {
        Box(Modifier.weight(1f)) {
            if (tab == 0) ScanScreen(scanner, onRequestPerms, onAdded = { tab = 1 })
            else MonitorScreen(scanner, alarm, onRequestPerms, onGoScan = { tab = 0 })
        }
        BottomBar(tab) { tab = it }
    }
}

// ============ 底部 Tab ============
@Composable
fun BottomBar(tab: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0x22000000))
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TabItem("扫描", tab == 0) { onChange(0) }
        TabItem("监控", tab == 1) { onChange(1) }
    }
}

@Composable
private fun RowScope.TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickableNoRipple(onClick).padding(vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (selected) ACCENT_CYAN else Color(0xFF8B92C4),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier.size(width = 18.dp, height = 3.dp)
                .background(if (selected) ACCENT_CYAN else Color.Transparent, RoundedCornerShape(2.dp))
        )
    }
}

// ============ 扫描页 ============
@Composable
fun ScanScreen(scanner: BleScanner, onRequestPerms: () -> Unit, onAdded: () -> Unit) {
    val snapshot by scanner.scanSnapshot.collectAsStateWithLifecycle()
    val frozen by scanner.snapshotFrozen.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    val error by scanner.error.collectAsStateWithLifecycle()
    val selectedAddr by scanner.selectedAddress.collectAsStateWithLifecycle()

    // 开始一次全新扫描：清空快照，扫 4 秒后冻结
    fun beginScan() {
        if (!scanner.hasPermission()) { onRequestPerms(); return }
        scanner.resetSnapshot()
        if (!scanner.scanning.value) scanner.start()
    }

    // 扫描倒计时 4..0：显示后冻结列表
    var countdown by remember { mutableIntStateOf(4) }
    LaunchedEffect(scanning, frozen) {
        if (scanning && !frozen) {
            countdown = 4
            while (countdown > 0 && !scanner.snapshotFrozen.value) {
                kotlinx.coroutines.delay(1000)
                countdown -= 1
            }
            if (scanner.scanning.value && !scanner.snapshotFrozen.value) scanner.freezeSnapshot()
        }
    }

    // 首次进入自动扫一次：无权限则申请；有权限则开始扫描（蓝牙关时 start() 会给出"蓝牙未开启"错误）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        if (!scanner.hasPermission()) onRequestPerms() else beginScan()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(26.dp))
            Spacer(Modifier.weight(1f))
            Text("扫描设备", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (!frozen && scanning) "正在扫描附近蓝牙设备… ${countdown}s 后出结果"
            else "共 ${snapshot.size} 个设备 · 点击「监控」加入监听",
            color = Color(0xFFAFB6E6), fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))

        if (error != null) {
            Text("⚠ $error", color = Color(0xFFFF8A9B), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }

        if (!frozen && scanning) {
            // 扫描中：显示进度提示（不显示会乱跳的列表）
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ACCENT_CYAN)
                    Spacer(Modifier.height(14.dp))
                    Text("正在收集设备…", color = Color(0xFF9AA1D0), fontSize = 13.sp)
                }
            }
        } else if (snapshot.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("附近没有发现设备，点下方重新扫描", color = Color(0xFF8B92C4), fontSize = 13.sp)
            }
        } else {
            // 冻结后的稳定列表（可滚动）
            Box(Modifier.weight(1f)) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                    items(snapshot, key = { it.address }) { d ->
                        val isSel = d.address == selectedAddr
                        DeviceRow(d, isSel) {
                            scanner.selectTarget(d.address, d.name)
                            onAdded()
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                scanner.stop()
                beginScan()
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT_CYAN),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(if (frozen || !scanning) "重新扫描" else "扫描中…", color = Color(0xFF06121F), fontWeight = FontWeight.Bold)
        }
    }
}

// 扫描页的一行设备
@Composable
fun DeviceRow(d: BeaconState, isSelected: Boolean, onMonitor: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CARD_BG)
            .border(1.dp, if (isSelected) ACCENT_CYAN else CARD_BORDER, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, color = Color(0xFFE7EAFF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBars(d.rawRssi)
                Spacer(Modifier.width(6.dp))
                Text("${d.address} · ${d.rawRssi} dBm", color = Color(0xFF8B92C4), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onMonitor,
            colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color(0xFF33C46B) else ACCENT_CYAN),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(if (isSelected) "监控中" else "监控", color = Color(0xFF06121F), fontSize = 12.5f.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ============ 信号强度小图标：4 格信号条 ============
@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    // 按 RSSI 决定点亮的格数（4 格）
    val level = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -88 -> 1
        else -> 0
    }
    val onColor = when (level) {
        4, 3 -> Color(0xFF4CE08A)
        2 -> Color(0xFFFFC46B)
        1 -> Color(0xFFFF8A5B)
        else -> Color(0xFFFF5A6A)
    }
    Canvas(modifier.size(width = 22.dp, height = 16.dp)) {
        val bars = 4
        val gap = size.width * 0.14f
        val bw = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val h = size.height * (0.35f + 0.65f * (i + 1) / bars)
            val left = i * (bw + gap)
            val top = size.height - h
            drawRoundRect(
                color = if (i < level) onColor else Color(0x33FFFFFF),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(bw, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.3f)
            )
        }
    }
}

// ============ 信号强度波形图动画 ============
@Composable
fun SignalWave(history: List<Float>, present: Boolean, modifier: Modifier = Modifier) {
    // 平滑滚动：以列表末尾为最新
    val lineColor = if (present) ACCENT_CYAN else Color(0xFFFF8A5B)
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width; val h = size.height
        // 网格底线
        drawLine(
            color = Color(0x22FFFFFF), start = Offset(0f, h * 0.5f),
            end = Offset(w, h * 0.5f), strokeWidth = 1.dp.toPx()
        )
        if (history.size < 2) return@Canvas
        // RSSI 映射：-100..-40 -> 0..1（越高越靠上）
        fun yOf(v: Float): Float {
            val clamped = v.coerceIn(-105f, -40f)
            val t = (clamped + 105f) / 65f          // 0..1
            return h - t * h
        }
        val n = history.size
        val cap = 60                                   // 与 HISTORY_MAX 对应，固定步长，避免越画越挤
        val step = w / (cap - 1).coerceAtLeast(1)
        val path = androidx.compose.ui.graphics.Path()
        var started = false
        var lastX = 0f; var lastY = 0f
        var hasPoint = false
        for (i in 0 until n) {
            val v = history[i]
            // 最新点固定在右边缘，越旧越靠左（从右往左滚动，步长固定不乱缩放）
            val x = w - (n - 1 - i) * step
            if (v <= -120f) {   // 断点（搜不到）：不连线
                started = false
                continue
            }
            val y = yOf(v)
            if (!started) { path.moveTo(x, y); started = true }
            else path.lineTo(x, y)
            lastX = x; lastY = y; hasPoint = true
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
        // 末端亮点（仅在有有效点时画，避免画到左上角）
        if (hasPoint) drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(lastX, lastY))
    }
}


@Composable
fun MonitorScreen(scanner: BleScanner, alarm: AlarmEngine, onRequestPerms: () -> Unit, onGoScan: () -> Unit) {
    val devices by scanner.devices.collectAsStateWithLifecycle()
    val selectedAddr by scanner.selectedAddress.collectAsStateWithLifecycle()
    val savedName by scanner.savedTargetName.collectAsStateWithLifecycle()
    val error by scanner.error.collectAsStateWithLifecycle()
    val target = selectedAddr?.let { a -> devices.firstOrNull { it.address == a } }
    val present = target != null
    val priceHistory by scanner.targetHistory.collectAsStateWithLifecycle()
    // 目标显示名：扫到时用实时名，扫不到时用记忆名
    val targetName = target?.name ?: savedName ?: "未知设备"

    // 进入监控页自动持续扫描，才能拿到记忆目标的实时 RSSI
    LaunchedEffect(Unit) {
        if (!scanner.hasPermission()) onRequestPerms()
        else if (!scanner.scanning.value) scanner.start()
    }

    val appCtx = LocalContext.current
    // 是否在监听：从持久化恢复（解决切后台被系统重建/冷启动后状态丢失）
    var started by remember { mutableStateOf(SavedMonitorStore.loadActive(appCtx)) }
    // 监听状态变化即持久化
    LaunchedEffect(started) { SavedMonitorStore.saveActive(appCtx, started) }
    var elapsed by remember { mutableIntStateOf(0) }
    // 提醒阈值(dBm)：从持久化恢复（-100 视为"不限"），用户调整后持久化
    var sensitivityDbm by remember { mutableIntStateOf(SavedMonitorStore.loadSensitivity(appCtx)) }

    // 灵敏度(连续 dBm)实时写入提醒引擎阈值，并持久化
    LaunchedEffect(sensitivityDbm) {
        alarm.thresholdRssi = sensitivityDbm
        SavedMonitorStore.saveSensitivity(appCtx, sensitivityDbm)
    }

    // 每 0.5 秒刷新计时（若恢复时无起始时间则从当前重开计时，避免溢出成巨大数字）
    LaunchedEffect(started) {
        if (started && scanner.monitorStartedAtMs <= 0L) scanner.monitorStartedAtMs = System.currentTimeMillis()
        while (started) {
            elapsed = ((System.currentTimeMillis() - scanner.monitorStartedAtMs) / 1000L).toInt().coerceAtLeast(0)
            kotlinx.coroutines.delay(500)
        }
    }
    // 前台状态：切后台即停震，回前台按当前状态自动恢复（修复"切后台回来不震"的 bug）
    var inForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> inForeground = true
                Lifecycle.Event.ON_STOP -> inForeground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 离开监控页（切到扫描页/退出）立即停震，避免残留在后台震
    DisposableEffect(Unit) {
        onDispose { alarm.enabled = false }
    }

    // 震动严格 = 已开始 且 有监控目标 且 目标在线 且 App 在前台
    LaunchedEffect(started, selectedAddr, present, inForeground) {
        alarm.enabled = started && selectedAddr != null && present && inForeground
    }
    // 切走监控页/取消开始都要停震（由 enabled 控制，AlarmEngine 会停）

    // 是否"在提醒范围内"：扫到目标且信号强于阈值
    // 用平滑值(与提醒引擎判定一致)，避免取整造成"按钮显示在范围内但不震动"的边界不一致
    val inRange = present && target != null && target.smoothRssi >= sensitivityDbm
    val btnMode = when {
        selectedAddr == null -> BtnMode.START
        !started -> BtnMode.START
        inRange -> BtnMode.STOP          // 搜到且达阈值：提醒中
        present -> BtnMode.LISTENING      // 搜到但信号未达阈值：监听中（没丢）
        else -> BtnMode.LOST              // 真搜不到：重新搜索
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(26.dp))
            Spacer(Modifier.weight(1f))
            Text("近安星", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(26.dp))
        }
        Spacer(Modifier.height(14.dp))

        if (selectedAddr == null) {
            // 还没有监控目标
            Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有监控目标", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("先到「扫描」页把一个设备加进来", color = Color(0xFF9AA1D0), fontSize = 13.sp)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onGoScan, colors = ButtonDefaults.buttonColors(containerColor = ACCENT_CYAN), shape = RoundedCornerShape(12.dp)) {
                        Text("去扫描添加", color = Color(0xFF06121F), fontWeight = FontWeight.Bold)
                    }
                }
            }
            return@Column
        }

        // 大圆按钮
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BigRoundButton(mode = btnMode, onClick = {
                when (btnMode) {
                    BtnMode.START -> {
                        started = true; elapsed = 0
                        scanner.monitorStartedAtMs = System.currentTimeMillis()
                        if (!scanner.hasPermission()) onRequestPerms()
                        if (!scanner.scanning.value) scanner.start()
                    }
                    BtnMode.LISTENING, BtnMode.STOP, BtnMode.LOST -> {
                        started = false
                        alarm.enabled = false
                    }
                }
            })
        }
        Spacer(Modifier.height(12.dp))

        Text(formatTime(if (started) elapsed else 0), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Light,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(targetName, color = Color(0xFFAFB6E6), fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))

        // 信号强度波形图（替代原来的信息卡）
        SignalCard(present = present, rssi = target?.rawRssi, history = priceHistory)

        if (error != null) {
            Spacer(Modifier.height(8.dp)); Text("⚠ $error", color = Color(0xFFFF8A9B), fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        SensitivityBar(sensitivityDbm) { sensitivityDbm = it }
        Spacer(Modifier.height(10.dp))
    }
}

// ============ 大圆按钮 ============
enum class BtnMode { START, LISTENING, STOP, LOST }

@Composable
fun BigRoundButton(mode: BtnMode, onClick: () -> Unit) {
    val (base, glow) = when (mode) {
        BtnMode.START -> Color(0xFF33C46B) to Color(0xFF7CF0A8)
        BtnMode.LISTENING -> Color(0xFF2E7BE6) to Color(0xFF7FB8FF)   // 蓝：监听中(未达阈值)
        BtnMode.STOP -> Color(0xFFF2405B) to Color(0xFFFF8A9B)
        BtnMode.LOST -> Color(0xFFF2A03C) to Color(0xFFFFCE82)
    }
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "p"
    )
    // 放大镜"搜索"动画相位：0..1 循环
    val scanPhase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "scan"
    )
    // 外圈扩散波纹
    Canvas(Modifier.size(240.dp)) {
        val maxR = size.minDimension / 2f
        if (mode == BtnMode.LISTENING || mode == BtnMode.STOP || mode == BtnMode.LOST) {
            drawCircle(
                color = glow.copy(alpha = (1f - pulse) * 0.35f),
                radius = maxR * (0.62f + 0.38f * pulse),
                style = Stroke(width = 3.dp.toPx())
            )
        }
        // 柔光底
        drawCircle(
            brush = Brush.radialGradient(
                listOf(glow.copy(alpha = 0.45f), Color.Transparent),
                center = center, radius = maxR * 0.9f
            )
        )
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .size(170.dp)
                .shadow(18.dp, CircleShape)
                .background(
                    Brush.verticalGradient(listOf(glow, base)), CircleShape
                )
                .clickableNoRipple(onClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // 图标（Canvas 画）：只有"重新搜索(丢失)"播放动画，其余静止
            Canvas(Modifier.size(46.dp)) {
                drawButtonGlyph(mode, Color.White, if (mode == BtnMode.LOST) scanPhase else -1f)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (mode) {
                    BtnMode.START -> "开始搜索"
                    BtnMode.LISTENING -> "监听中"
                    BtnMode.STOP -> "停止提醒"
                    BtnMode.LOST -> "重新搜索"
                },
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawButtonGlyph(mode: BtnMode, c: Color, phase: Float = 0f) {
    val w = size.width; val h = size.height
    val cx = w / 2; val cy = h / 2
    val sw = (w * 0.10f)
    when (mode) {
        BtnMode.STOP -> { // 停止：实心方块
            val s = w * 0.34f
            drawRoundRect(
                color = c, topLeft = Offset(cx - s, cy - s),
                size = androidx.compose.ui.geometry.Size(s * 2, s * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.22f)
            )
        }
        BtnMode.START, BtnMode.LISTENING, BtnMode.LOST -> { // 放大镜（LISTENING/START 静止，LOST 动画）
            drawSearchLens(c, phase, w, h, sw)
        }
    }
}

// 放大镜绘制：phase < 0 为静止（无动画无波纹）；phase 0..1 为搜索动画
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSearchLens(
    c: Color, phase: Float, w: Float, h: Float, sw: Float
) {
    val cx = w / 2; val cy = h / 2
    val still = phase < 0f

    val r: Float
    val gx: Float
    val gy: Float
    if (still) {
        // 静止：放大镜居中、固定大小、无波纹
        r = w * 0.26f
        gx = cx
        gy = cy - h * 0.02f
    } else {
        val ang = phase * 2f * Math.PI.toFloat()
        val travel = w * 0.22f
        val ox = kotlin.math.sin(ang) * travel
        val oy = kotlin.math.cos(ang * 2f) * (h * 0.05f)
        val scale = 1f + 0.10f * kotlin.math.sin(ang * 2f + 0.6f).coerceAtLeast(0f)
        r = w * 0.26f * scale
        gx = cx + ox
        gy = cy - h * 0.02f + oy
        // 扫描波纹（三圈随时间扩散）
        for (i in 0..2) {
            val t = ((phase + i / 3f) % 1f)
            drawCircle(
                color = c.copy(alpha = (1f - t) * 0.30f),
                radius = r + t * r * 0.9f,
                center = Offset(gx, gy),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
    // 镜片
    drawCircle(color = c, radius = r, center = Offset(gx, gy), style = Stroke(sw))
    // 镜内高光点
    drawCircle(color = c, radius = w * 0.05f, center = Offset(gx - r * 0.15f, gy - r * 0.15f))
    // 手柄
    drawLine(
        color = c, start = Offset(gx + r * 0.70f, gy + r * 0.70f),
        end = Offset(gx + r * 1.25f, gy + r * 1.25f), strokeWidth = sw, cap = StrokeCap.Round
    )
}

// ============ 信号强度卡片（dBm + 波形图）============
@Composable
fun SignalCard(present: Boolean, rssi: Int?, history: List<Float>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CARD_BG)
            .border(1.dp, CARD_BORDER, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor = if (present) Color(0xFF4CE08A) else Color(0xFFFFB74D)
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                if (present) "已发现目标设备" else "目标信号丢失",
                color = Color(0xFFDDE2FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (present && rssi != null) {
                SignalBars(rssi)
                Spacer(Modifier.width(6.dp))
                Text("$rssi dBm", color = Color(0xFF9FE8FF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            } else {
                Text("— dBm", color = Color(0xFFFFB74D), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        // 波形图
        Box(
            Modifier.fillMaxWidth().height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x22000000))
                .padding(vertical = 6.dp)
        ) {
            SignalWave(history = history, present = present, modifier = Modifier.fillMaxSize())
        }
    }
}

// ============ 灵敏度滑杆（对应参考图底部）============
@Composable
fun SensitivityBar(thresholdDbm: Int, onChange: (Int) -> Unit) {
    // 显示：-100 视为"不限"
    val label = if (thresholdDbm <= -100) "不限" else "$thresholdDbm dBm"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CARD_BG)
            .border(1.dp, CARD_BORDER, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("提醒灵敏度", color = Color(0xFFA9B0DD), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(label, color = Color(0xFFDDE2FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        // 无级连续滑杆：范围 -100(不限) ~ -45(很近)，任意取值
        Slider(
            value = thresholdDbm.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(-100, -45)) },
            valueRange = -100f..-45f,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = ACCENT_CYAN,
                inactiveTrackColor = Color(0x55FFFFFF)
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("不限 (-100)", color = Color(0xFF8B92C4), fontSize = 11.sp)
            Text("很近 (-45)", color = Color(0xFF8B92C4), fontSize = 11.sp)
        }
    }
}

// ============ 工具 ============
private fun formatTime(secs: Int): String =
    String.format("%02d:%02d", secs / 60, secs % 60)

// 无涟漪点击
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
