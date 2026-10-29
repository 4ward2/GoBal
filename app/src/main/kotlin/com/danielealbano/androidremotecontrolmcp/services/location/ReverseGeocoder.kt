package com.danielealbano.androidremotecontrolmcp.services.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.danielealbano.androidremotecontrolmcp.utils.ApiLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "MCP:ReverseGeocoder"

/** Framework-only (GMS-free) reverse geocoding shared by all LocationProvider implementations. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun reverseGeocode(
    context: Context,
    latitude: Double,
    longitude: Double,
): String? {
    if (!Geocoder.isPresent()) {
        Log.d(TAG, "Geocoder not present on this device")
        return null
    }
    return try {
        val geocoder = Geocoder(context, Locale.getDefault())
        if (ApiLevel.atLeast(Build.VERSION_CODES.TIRAMISU)) {
            geocodeAsync(geocoder, latitude, longitude)
        } else {
            // The only form older phones have blocks on the network, so it goes to the IO pool.
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()?.getAddressLine(0)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "Reverse geocoding failed: ${e.message}")
        null
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private suspend fun geocodeAsync(
    geocoder: Geocoder,
    latitude: Double,
    longitude: Double,
): String? =
    suspendCancellableCoroutine { cont ->
        geocoder.getFromLocation(
            latitude,
            longitude,
            1,
            object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: List<Address>) {
                    cont.resume(addresses.firstOrNull()?.getAddressLine(0))
                }

                override fun onError(errorMessage: String?) {
                    Log.d(TAG, "Geocoder onError: $errorMessage")
                    cont.resume(null)
                }
            },
        )
    }
