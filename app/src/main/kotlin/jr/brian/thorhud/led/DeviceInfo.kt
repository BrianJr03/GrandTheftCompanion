package jr.brian.thorhud.led

import android.os.Build

/**
 * The direct LED path is specific to the AYN Thor's SN3112 drivers and
 * its [PServer binder][DirectLedDriver]. Everything else should keep
 * going through [BifrostClient] (or do nothing at all).
 */
internal object DeviceInfo {
    val isAynThor: Boolean by lazy {
        Build.MANUFACTURER.lowercase().contains("ayn") &&
            Build.MODEL.lowercase().contains("thor")
    }
}
