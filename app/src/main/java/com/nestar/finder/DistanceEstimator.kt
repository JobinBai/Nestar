package com.nestar.finder

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 距离推算工具：基于蓝牙 RSSI 的对数距离路径损耗模型
 *
 *   RSSI = A - 10 * n * log10(d)
 *   =>  d = 10 ^ ((A - RSSI) / (10 * n))
 *
 * A  : 距离 1 米处的参考 RSSI（dBm），典型 -50 ~ -60
 * n  : 路径损耗指数，自由空间约 2.0，室内/书包遮挡取 2.2 ~ 2.8
 *
 * 参数可由【校准】功能用实测点自动拟合。
 */
object DistanceEstimator {
    @Volatile var REF_RSSI_1M = -59.0   // A：1 米处参考值，可校准
    @Volatile var PATH_LOSS_N = 2.2     // n：环境衰减指数，可校准

    fun reset() { REF_RSSI_1M = -59.0; PATH_LOSS_N = 2.2 }

    fun rssiToMeters(rssi: Double): Double =
        10.0.pow((REF_RSSI_1M - rssi) / (10.0 * PATH_LOSS_N))

    fun proximity(rssi: Int): Proximity = when {
        rssi >= -55 -> Proximity.VERY_NEAR
        rssi >= -65 -> Proximity.NEAR
        rssi >= -75 -> Proximity.MID
        rssi >= -85 -> Proximity.FAR
        else -> Proximity.VERY_FAR
    }

    fun closeness(rssi: Int): Float {
        val clamped = rssi.coerceIn(-95, -45)
        return ((clamped + 95) / 50.0).toFloat()
    }

    fun format(rssi: Int): String {
        val m = rssiToMeters(rssi.toDouble())
        return when {
            m < 1.0 -> "< 1 m"
            m < 10.0 -> String.format("%.1f m", m)
            else -> "≈ ${m.roundToInt()} m"
        }
    }

    /**
     * 用校准点（距离 d_i 米，测得 RSSI_i）做最小二乘拟合，求 A 和 n。
     *
     * 模型：RSSI_i = A - 10 n * log10(d_i)
     * 令 x_i = log10(d_i)，则 RSSI_i = A - 10n * x_i，是关于 (A, 10n) 的线性回归。
     *
     * @param points 至少 2 个 (米, dBm) 点
     * @return 是否拟合成功
     */
    fun calibrate(points: List<Pair<Double, Double>>): Boolean {
        val pts = points.filter { it.first > 0.05 }
        if (pts.size < 2) return false
        val xs = pts.map { log10(it.first) }
        val ys = pts.map { it.second }
        val meanX = xs.average()
        val meanY = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - meanX) * (ys[i] - meanY)
            den += (xs[i] - meanX) * (xs[i] - meanX)
        }
        if (den < 1e-9) return false
        val slope = num / den          // 斜率 = -10n
        val intercept = meanY - slope * meanX  // A
        val nn = -slope / 10.0
        // 合理性检查：A 在 -30~-80，n 在 1.2~4.0
        if (nn < 1.0 || nn > 4.5) return false
        if (intercept > -30 || intercept < -90) return false
        REF_RSSI_1M = intercept
        PATH_LOSS_N = nn
        return true
    }
}

enum class Proximity(val label: String) {
    VERY_NEAR("就在附近"),
    NEAR("较近了"),
    MID("中距离"),
    FAR("有点远"),
    VERY_FAR("很远");
}

/** 一个设备的实时状态（含 RSSI 平滑） */
data class BeaconState(
    val name: String,
    val address: String,
    val rawRssi: Int,
    val smoothRssi: Double,
    val lastSeen: Long,
    val samples: Int
) {
    val distanceLabel: String get() = DistanceEstimator.format(smoothRssi.roundToInt())
    val proximity: Proximity get() = DistanceEstimator.proximity(smoothRssi.roundToInt())
    val closeness: Float get() = DistanceEstimator.closeness(smoothRssi.roundToInt())
}
