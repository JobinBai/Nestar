package com.nestar.finder

import android.content.Context

/**
 * 监控目标的持久化存储（SharedPreferences）。
 * 记住你添加进监控的设备（地址+名称+加入时间），
 * 下次打开 App 直接恢复，无需重新扫描添加。
 */
object SavedMonitorStore {
    private const val PREF = "nestar_monitor"
    private const val KEY_ADDR = "target_address"
    private const val KEY_NAME = "target_name"
    private const val KEY_AT   = "target_added_at"
    private const val KEY_ACTIVE = "monitoring_active"   // 是否正在监听（跨重建/冷启动保留）
    private const val KEY_SENS = "sensitivity_dbm"       // 提醒灵敏度阈值(dBm)，持久化

    data class SavedTarget(val address: String, val name: String, val addedAt: Long)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(context: Context, address: String, name: String) {
        prefs(context).edit()
            .putString(KEY_ADDR, address)
            .putString(KEY_NAME, name)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): SavedTarget? {
        val p = prefs(context)
        val addr = p.getString(KEY_ADDR, null) ?: return null
        if (addr.isBlank()) return null
        return SavedTarget(
            address = addr,
            name = p.getString(KEY_NAME, "") ?: "",
            addedAt = p.getLong(KEY_AT, 0L)
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ADDR).remove(KEY_NAME).remove(KEY_AT).remove(KEY_ACTIVE).apply()
    }

    /** 保存"是否正在监听"状态（切后台/重建/冷启动后恢复用） */
    fun saveActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    fun loadActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    /** 提醒灵敏度阈值(dBm)持久化 */
    fun saveSensitivity(context: Context, dbm: Int) {
        prefs(context).edit().putInt(KEY_SENS, dbm).apply()
    }

    fun loadSensitivity(context: Context): Int = prefs(context).getInt(KEY_SENS, -100)
}
