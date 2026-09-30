package io.github.wolverr.bivouac.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The phone's position from Android's own LocationManager: GPS works with no
 * network and no Google Play Services (so it works on GrapheneOS too).
 */
object DeviceLocation {
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, maxAgeMs: Long = 10 * 60_000L, timeoutMs: Long = 20_000L): GeoPoint? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < maxAgeMs }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent.toPoint()
        if (Build.VERSION.SDK_INT < 30) return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in providers }
            ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                lm.getCurrentLocation(provider, cancel, context.mainExecutor) { loc -> cont.resume(loc?.toPoint()) }
            }
        }
    }

    private fun Location.toPoint() = GeoPoint(latitude, longitude, "your location")
}
