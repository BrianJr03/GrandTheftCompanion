package jr.brian.thorhud.ui.map.custom

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Pure-Kotlin persistence for a [Calibration].
 *
 * Split out of [CustomMapStore] so it can be unit tested against a
 * `java.io.File` from a tmp dir without any Android dependency.
 */
object CalibrationIo {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Read a calibration from a JSON file. Returns null when the
     *  file does not exist or is corrupt. */
    fun read(file: File): Calibration? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(Calibration.serializer(), file.readText())
        }.getOrNull()
    }

    /** Write a calibration JSON. Creates parent directories. Returns
     *  false (and leaves the file untouched) on IOException. */
    fun write(file: File, cal: Calibration): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Calibration.serializer(), cal))
            true
        }.getOrDefault(false)
    }
}
