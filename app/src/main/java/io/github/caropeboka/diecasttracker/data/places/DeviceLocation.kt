package io.github.caropeboka.diecasttracker.data.places

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class LocationException(message: String) : Exception(message)

/** The phone's position from the fused location provider, with errors worded for the collector. */
class DeviceLocation(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    /** Precise or approximate: either is enough to start, approximate just lands a few hundred meters off. */
    fun hasPermission(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun current(): GpsFix {
        if (!hasPermission()) {
            throw LocationException("Izin lokasi ditolak. Izinkan akses lokasi untuk Diecast Tracker di pengaturan HP.")
        }
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager != null && !manager.isLocationEnabled) {
            throw LocationException("Lokasi HP mati. Nyalakan lokasi (GPS) lalu coba lagi.")
        }

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30_000)
            .setDurationMillis(15_000)
            .build()
        val cancel = CancellationTokenSource()

        val location = try {
            suspendCancellableCoroutine<Location?> { continuation ->
                continuation.invokeOnCancellation { cancel.cancel() }
                client.getCurrentLocation(request, cancel.token)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resume(null) }
            }
        } catch (_: SecurityException) {
            throw LocationException("Izin lokasi ditolak. Izinkan akses lokasi untuk Diecast Tracker di pengaturan HP.")
        }

        location ?: throw LocationException("Lokasi tidak ditemukan. Coba lagi di tempat yang sinyalnya lebih baik.")
        return GpsFix(location.latitude, location.longitude, location.accuracy.roundToInt())
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
