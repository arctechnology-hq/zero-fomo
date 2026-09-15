package com.arctechnology.zerofomo.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class LatLng(val lat: Double, val lng: Double)

/**
 * Approximate device position (ACCESS_COARSE_LOCATION only; product decision
 * 2026-09-15: city-level is all the app needs, and it keeps the Play
 * data-safety form and the privacy policy honest and small). The fix never
 * leaves the device: it is matched against the offline gazetteer and only
 * the resulting place name is kept.
 */
@Singleton
class DeviceLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val permission: String = Manifest.permission.ACCESS_COARSE_LOCATION

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** One-shot balanced-power fix; null when unavailable (no permission,
     *  location off, airplane mode, emulator without a fix). */
    @SuppressLint("MissingPermission")
    suspend fun current(): LatLng? {
        if (!hasPermission()) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cts.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    if (cont.isActive) cont.resume(loc?.let { LatLng(it.latitude, it.longitude) })
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        }
    }

    /** Country without any permission: the cell network's country, then the
     *  SIM's, then the device locale. Good enough to pick the flag. */
    fun networkCountryCode(): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val fromNetwork = tm?.networkCountryIso?.takeIf { it.length == 2 }
        val fromSim = tm?.simCountryIso?.takeIf { it.length == 2 }
        val fromLocale = Locale.getDefault().country.takeIf { it.length == 2 }
        return (fromNetwork ?: fromSim ?: fromLocale)?.uppercase()
    }
}
