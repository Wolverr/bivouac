package io.github.wolverr.bivouac.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/** What the phone can do: which cores are fast and how much memory there is. */
data class DeviceProfile(
    val model: String,
    val soc: String,
    val totalRamBytes: Long,
    /** CPU ids of every core outside the slowest cluster. */
    val bigCores: IntArray,
) {
    val allCores: IntArray get() = (0 until Runtime.getRuntime().availableProcessors()).toList().toIntArray()

    val totalRamGb: Double get() = totalRamBytes / 1e9

    companion object {
        fun detect(context: Context): DeviceProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
            return DeviceProfile(
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                soc = soc,
                totalRamBytes = mem.totalMem,
                bigCores = detectBigCores(),
            )
        }

        /**
         * Phones pair a few fast cores with slow efficiency cores. Inference threads
         * that land on the slow cluster stall every matmul, so we only use cores whose
         * max frequency is above the slowest cluster's.
         */
        private fun detectBigCores(): IntArray {
            val n = Runtime.getRuntime().availableProcessors()
            val freqs = (0 until n).map { cpu ->
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .runCatching { readText().trim().toLong() }
                    .getOrDefault(0L)
            }
            val slowest = freqs.filter { it > 0 }.minOrNull() ?: return (0 until n).toList().toIntArray()
            val big = (0 until n).filter { freqs[it] > slowest }
            return if (big.isEmpty()) (0 until n).toList().toIntArray() else big.toIntArray()
        }
    }
}
