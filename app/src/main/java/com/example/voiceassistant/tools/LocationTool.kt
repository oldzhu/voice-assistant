package com.example.voiceassistant.tools

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.util.Log
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import java.io.IOException

/**
 * Tool: get_location
 *
 * Reads the phone's current location via LocationManager (NETWORK_PROVIDER).
 * Reverse-geocodes to city name via Android Geocoder (Google Play Services)
 * or falls back to an online API on Chinese ROMs.
 *
 * Returns JSON: {"city": "深圳", "country": "中国", "lat": 22.54, "lon": 114.06, "source": "network"}
 */
class LocationTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "LocationTool"
    }

    override val name = "get_location"
    override val description = "获取手机当前所在位置的城市名称、国家和坐标。不需要参数。当用户询问天气但没有说城市时，先调用此工具获取位置。"

    override val parameters: Map<String, ToolParameter> = emptyMap()

    override suspend fun execute(args: Map<String, Any?>): String {
        Log.i(TAG, "Executing get_location")

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return """{"error": "LocationManager不可用"}"""

        // Try providers in order: network (fast, works indoors), then GPS
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )

        var location: android.location.Location? = null
        var usedProvider = "unknown"

        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null && isRecent(loc)) {
                    location = loc
                    usedProvider = provider
                    Log.i(TAG, "Got location from $provider: ${loc.latitude}, ${loc.longitude}")
                    break
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission for $provider: ${e.message}")
            }
        }

        if (location == null) {
            // Try requesting a single fresh location (may take a few seconds in practice,
            // but getLastKnownLocation is instant — if it's null, GPS is likely off)
            return """{"error": "无法获取位置，请确认定位服务已开启并在设置中授予位置权限"}"""
        }

        // Reverse geocode
        val lat = location.latitude
        val lon = location.longitude

        try {
            val address = reverseGeocode(lat, lon)
            if (address != null) {
                val city = address.locality
                    ?: address.subAdminArea
                    ?: address.adminArea
                    ?: "未知城市"
                val country = address.countryName ?: ""
                Log.i(TAG, "Geocoded: $city, $country")
                return """{"city": "$city", "country": "$country", "lat": $lat, "lon": $lon, "source": "$usedProvider"}"""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding failed: ${e.message}")
        }

        // Fallback: return coordinates only
        return """{"city": "未知", "lat": $lat, "lon": $lon, "source": "$usedProvider", "note": "无法解析城市名，请用坐标查询天气"}"""
    }

    // ── Helpers ──────────────────────────────────────────

    /** A location is "recent" if it's less than 30 minutes old */
    private fun isRecent(loc: android.location.Location): Boolean {
        val ageMs = System.currentTimeMillis() - loc.time
        return ageMs < 30 * 60 * 1000  // 30 minutes
    }

    /**
     * Reverse-geocode coordinates to an Address.
     * Tries Android Geocoder first, falls back to an HTTP-based API
     * (needed on Chinese ROMs without Google Play Services).
     */
    private fun reverseGeocode(lat: Double, lon: Double): Address? {
        // Try Android Geocoder (requires Google Play Services)
        if (Geocoder.isPresent()) {
            try {
                val gc = Geocoder(context)
                // Geocoder on Chinese ROMs may return empty list even if isPresent() == true
                val addresses = gc.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    return addresses[0]
                }
            } catch (e: IOException) {
                Log.w(TAG, "Geocoder IO error: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Geocoder arg error: ${e.message}")
            }
        } else {
            Log.i(TAG, "Geocoder not present — using HTTP fallback")
        }

        // HTTP fallback: use nominatim (OpenStreetMap) — works without Play Services
        return try {
            val url = java.net.URL(
                "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=10&accept-language=zh"
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "VoiceAssistant/1.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // Parse JSON response
            val json = org.json.JSONObject(body)
            val addrJson = json.optJSONObject("address") ?: return null

            // Build a synthetic Address-like object
            object : Address(null) {
                override fun getLocality(): String? =
                    addrJson.optString("city", null)
                        ?: addrJson.optString("town", null)
                        ?: addrJson.optString("village", null)
                override fun getSubAdminArea(): String? =
                    addrJson.optString("county", null)
                        ?: addrJson.optString("district", null)
                override fun getAdminArea(): String? =
                    addrJson.optString("state", null)
                        ?: addrJson.optString("province", null)
                override fun getCountryName(): String? =
                    addrJson.optString("country", null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP geocoding failed: ${e.message}")
            null
        }
    }
}
