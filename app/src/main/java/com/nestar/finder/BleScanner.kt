package com.nestar.finder

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * BLE 扫描器：
 *  - 扫描所有 BLE 设备，优先显示名字包含"KidBag"的；
 *  - 用指数滑动平均（EMA）平滑 RSSI，抵抗抖动；
 *  - 超过 3 秒没收到广播的设备视为"离开"，从列表移除。
 */
class BleScanner(private val context: Context) {

    private val _devices = MutableStateFlow<List<BeaconState>>(emptyList())
    val devices: StateFlow<List<BeaconState>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 目标设备最近一次平滑 RSSI（给提醒引擎用），null 表示当前没搜到
    private val _targetRssi = MutableStateFlow<Double?>(null)
    val targetRssi: StateFlow<Double?> = _targetRssi.asStateFlow()

    // 当前选中的监听目标（设备地址）；null 表示未选择
    private val _selectedAddress = MutableStateFlow<String?>(null)
    val selectedAddress: StateFlow<String?> = _selectedAddress.asStateFlow()

    // 已保存目标的名称（即使当前没扫到，监控页也能显示名字）
    private val _savedTargetName = MutableStateFlow<String?>(null)
    val savedTargetName: StateFlow<String?> = _savedTargetName.asStateFlow()

    // 目标设备的 RSSI 历史序列（供监控页波形图），保留最近 N 个采样点
    private val _targetHistory = MutableStateFlow<List<Float>>(emptyList())
    val targetHistory: StateFlow<List<Float>> = _targetHistory.asStateFlow()
    private val historyBuf = ArrayDeque<Float>()
    private val HISTORY_MAX = 60   // 60 点 × 500ms = 约 30 秒波形窗口

    // 监听蓝牙开关：关闭时停止扫描态，打开时若本应在扫则自动重启（修复开关蓝牙后扫描卡死）
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    try { scanner?.stopScan(callback) } catch (_: Exception) {}
                    _scanning.value = false
                    handler.removeCallbacks(purgeRunnable)
                }
                BluetoothAdapter.STATE_ON -> {
                    if (wantScanning && !_scanning.value) start()
                }
            }
        }
    }
    private var receiverRegistered = false

    init {
        // 启动时恢复上次记忆的监控目标
        SavedMonitorStore.load(context)?.let {
            _selectedAddress.value = it.address
            _savedTargetName.value = it.name
        }
        // 注册蓝牙开关监听（applicationContext + NOT_EXPORTED，避免泄漏与安全告警）
        try {
            ContextCompat.registerReceiver(
                context.applicationContext,
                btStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (_: Exception) {}
    }

    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())

    // 用户是否"希望"在扫描（用于蓝牙关闭后重新打开时自动恢复扫描）
    @Volatile private var wantScanning = false

    // 平滑与状态缓存
    private class Acc(
        var ema: Double,
        var last: Long,
        var n: Int,
        var firstOrder: Long,
        var lastProc: Long = 0L,        // 上次采样处理时间（节流用）
        var latestRaw: Double = ema     // 最近一次原始 RSSI（供节流采样取值）
    )
    private val accMap = HashMap<String, Acc>()
    private val nameMap = HashMap<String, String>()
    private var orderSeq = 0L

    // 扫描页展示用的"冻结快照"：扫描时实时更新，冻结后不再变动，避免列表乱跳
    private val _scanSnapshot = MutableStateFlow<List<BeaconState>>(emptyList())
    val scanSnapshot: StateFlow<List<BeaconState>> = _scanSnapshot.asStateFlow()

    private val _snapshotFrozen = MutableStateFlow(false)
    val snapshotFrozen: StateFlow<Boolean> = _snapshotFrozen.asStateFlow()

    companion object {
        // 多久没收到广播算"离开"。BLE 广播间隔通常几十 ms~数秒，
        // 取 3 秒：既能在目标关闭/远离时快速反应，又容忍短暂丢包。
        const val PRESENCE_TIMEOUT_MS = 3000L
        // 自定义节流：无论设备广播多快，每 500ms（一秒两次）才采样处理一次
        const val SAMPLE_INTERVAL_MS = 500L
    }

    private val purgeRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val it = accMap.entries.iterator()
            var changed = false
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value.last > PRESENCE_TIMEOUT_MS) {
                    it.remove(); changed = true
                    nameMap.remove(e.key)   // 同时清理名字缓存，避免长时间扫描无限累积（内存泄漏）
                }
            }

            // 波形时间基准：每 500ms 给目标追加一个点（有信号用当前平滑值，搜不到用 -127 断点）
            // 这样波形严格按 500ms/点 推进，且与"其它设备出现"完全解耦，不会狂刷
            val sel = _selectedAddress.value
            if (sel != null) {
                if (now - lastHistoryTick >= SAMPLE_INTERVAL_MS) {
                    lastHistoryTick = now
                    val acc = accMap[sel]
                    val v = (if (acc != null && now - acc.last <= PRESENCE_TIMEOUT_MS) acc.ema.toFloat() else -127f)
                    appendTargetHistory(v)
                }
            }

            if (changed) publish()
            // 仅在仍在扫描时自我续命，避免 stop() 后又被重新排上（消除循环叠加/竞态）
            if (_scanning.value) handler.postDelayed(this, 250)   // 每 0.25 秒检查一次
        }
    }

    @Volatile private var lastHistoryTick = 0L
    private var lastPublish = 0L   // 全局发布节流，避免多设备时过度刷新

    // 本次监听的起始时间（Activity 级持有，切页不丢；进程重建后为0则重新计）
    @Volatile var monitorStartedAtMs: Long = 0L

    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 仅需扫描权限；不再需要 BLUETOOTH_CONNECT（最小权限）
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    fun isBluetoothOn(): Boolean = bluetoothAdapter()?.isEnabled == true

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }
        override fun onScanFailed(errorCode: Int) {
            _error.value = "扫描失败，错误码 $errorCode"
            _scanning.value = false
        }
    }

    private fun handleResult(result: ScanResult) {
        try {
            val dev = result.device ?: return
            val now = System.currentTimeMillis()
            val addr = dev.address ?: return   // 扫描途中撤销蓝牙权限时，这里可能抛 SecurityException
            // 仅用扫描广播里的名字，避免调用 dev.name（那需要 BLUETOOTH_CONNECT 权限）
            val name = result.scanRecord?.deviceName
            nameMap[addr] = if (!name.isNullOrBlank()) name else "未知设备 (${addr.takeLast(5)})"

            val acc = accMap[addr]
            if (acc == null) {
                accMap[addr] = Acc(result.rssi.toDouble(), now, 1, orderSeq++, now)
                maybePublish(now, force = true)
            } else {
                acc.latestRaw = result.rssi.toDouble()
                acc.last = now          // 更新时间戳，用于"离开"判定（不受节流影响）
                // 所有设备都按每设备 500ms 节流刷新 EMA（修复：之前只更新目标，导致其他设备信号显示被冻结）
                if (now - acc.lastProc >= SAMPLE_INTERVAL_MS) {
                    acc.ema = acc.ema + 0.35 * (acc.latestRaw - acc.ema)
                    acc.lastProc = now
                    acc.n += 1
                    maybePublish(now)
                }
            }
        } catch (e: Exception) {
            // 兜底：权限被撤销等异常不应让扫描回调崩溃
        }
    }

    /** 全局节流发布：最多每 SAMPLE_INTERVAL_MS(500ms) 刷新一次 UI/StateFlow，避免多设备时过度刷新 */
    private fun maybePublish(now: Long, force: Boolean = false) {
        if (force || now - lastPublish >= SAMPLE_INTERVAL_MS) {
            lastPublish = now
            publish()
        }
    }

    /** 仅由目标设备的采样点调用，追加一点到波形历史 */
    private fun appendTargetHistory(v: Float) {
        historyBuf.addLast(v)
        while (historyBuf.size > HISTORY_MAX) historyBuf.removeFirst()
        _targetHistory.value = historyBuf.toList()
    }

    private fun buildList(): List<BeaconState> = accMap.map { (addr, acc) ->
        BeaconState(
            name = nameMap[addr] ?: "未知设备",
            address = addr,
            rawRssi = acc.ema.roundToInt(),
            smoothRssi = acc.ema,
            lastSeen = acc.last,
            samples = acc.n
        )
    }

    private fun publish() {
        val list = buildList()
        // 实时列表（监控页判断目标在线/取 RSSI）
        _devices.value = list
        // 扫描页快照：按信号强度从强到弱排序；冻结后保持不动
        if (!_snapshotFrozen.value) {
            _scanSnapshot.value = list.sortedByDescending { it.smoothRssi }
        }
        // targetRssi 只反映"已选中的目标设备"
        val sel = _selectedAddress.value
        val cur = if (sel != null) list.firstOrNull { it.address == sel }?.smoothRssi else null
        _targetRssi.value = cur
        // 注意：波形历史不在这里追加（避免被"其他设备出现"高频刷新），
        // 只在目标设备自身的 200ms 节流采样点里追加。
    }

    /** 冻结扫描快照（扫描累积时间到，定住列表不再跳动） */
    fun freezeSnapshot() {
        _snapshotFrozen.value = true
    }

    /** 解冻并清空快照（重新扫描时用） */
    fun resetSnapshot() {
        _snapshotFrozen.value = false
        _scanSnapshot.value = emptyList()
    }

    /** 选择要监听的目标设备并持久化（空串/null 表示取消并清除记忆） */
    fun selectTarget(address: String?, name: String? = null) {
        // 换目标即清空波形历史
        historyBuf.clear(); _targetHistory.value = emptyList()
        if (address.isNullOrBlank()) {
            _selectedAddress.value = null
            _savedTargetName.value = null
            SavedMonitorStore.clear(context)
        } else {
            val nm = name ?: nameMap[address] ?: "未知设备"
            _selectedAddress.value = address
            _savedTargetName.value = nm
            SavedMonitorStore.save(context, address, nm)
        }
        publish()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        // 记录"意图扫描"：即使此刻因蓝牙关闭/权限未授予而失败，之后蓝牙打开也会自动恢复
        wantScanning = true
        // 防重入：已在扫描则直接返回，避免重复 startScan / purgeRunnable 叠加
        if (_scanning.value) return
        _error.value = null
        if (!hasPermission()) { _error.value = "缺少蓝牙权限"; return }
        if (!isBluetoothOn()) { _error.value = "蓝牙未开启"; return }
        val adapter = bluetoothAdapter() ?: run { _error.value = "本机不支持蓝牙"; return }
        scanner = adapter.bluetoothLeScanner ?: run { _error.value = "BLE 扫描器不可用"; return }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)   // 高占空比，RSSI 更新更密
            .build()

        // 低功耗/部分手机用过滤更省电；这里扫全部，兼容没名字的情况
        val filters = emptyList<ScanFilter>()

        try {
            scanner?.startScan(filters, settings, callback)
            _scanning.value = true
            // 先清掉可能残留的回调，避免多次 postDelayed 造成循环叠加
            handler.removeCallbacks(purgeRunnable)
            handler.postDelayed(purgeRunnable, 1000)
        } catch (e: SecurityException) {
            _error.value = "权限被拒绝：${e.message}"
            _scanning.value = false
        } catch (e: Exception) {
            // 兜底：蓝牙在检查与启动之间被关闭等竞态，抛非 Security 异常时不让 App 崩溃
            _error.value = "扫描启动失败：${e.message}"
            _scanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        wantScanning = false
        try { scanner?.stopScan(callback) } catch (_: Exception) {}
        _scanning.value = false
        handler.removeCallbacks(purgeRunnable)
    }

    /** 释放资源：反注册蓝牙状态广播（在 Activity.onDestroy 调用） */
    fun release() {
        handler.removeCallbacks(purgeRunnable)
        if (receiverRegistered) {
            try { context.applicationContext.unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    fun clear() {
        accMap.clear(); nameMap.clear(); orderSeq = 0
        _devices.value = emptyList(); _targetRssi.value = null
        _scanSnapshot.value = emptyList(); _snapshotFrozen.value = false
    }
}
