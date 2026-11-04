package jr.brian.thorhud.ui.cheats

/**
 * Trainer-specific data tables. Phase 2 lands the time slider and
 * weather picker; later phases add vehicle / teleport / object / anim
 * catalogs here (or in sibling files under the same package).
 *
 * Weather names are the game's own identifier strings (NativeDB:
 * SET_WEATHER_TYPE_NOW_PERSIST). They are proper-noun catalog values
 * that stay English in every locale (strings.xml carries them with
 * `translatable="false"` where they surface, and the Kotlin side
 * looks them up by index).
 */
internal object TrainerModel {

    /** One weather preset as it surfaces in the picker. [wire] is the
     *  index sent to the plugin in arg0; [label] is the English name
     *  rendered on the button. The plugin looks up the name server-side
     *  so this list stays the source of truth. */
    internal data class WeatherPreset(val wire: Int, val label: String)

    /** The 14 named GTA V weather presets. Order matches
     *  asi-v/src/cheats.cpp `kTrainerWeatherNames`. "SNOW" is the
     *  seasonal / event-only one; outside the holiday window it tends
     *  to render like CLEAR. */
    internal val WEATHER_PRESETS: List<WeatherPreset> = listOf(
        WeatherPreset(0,  "EXTRASUNNY"),
        WeatherPreset(1,  "CLEAR"),
        WeatherPreset(2,  "CLOUDS"),
        WeatherPreset(3,  "SMOG"),
        WeatherPreset(4,  "FOGGY"),
        WeatherPreset(5,  "OVERCAST"),
        WeatherPreset(6,  "RAIN"),
        WeatherPreset(7,  "THUNDER"),
        WeatherPreset(8,  "CLEARING"),
        WeatherPreset(9,  "NEUTRAL"),
        WeatherPreset(10, "SNOW"),
        WeatherPreset(11, "BLIZZARD"),
        WeatherPreset(12, "SNOWLIGHT"),
        WeatherPreset(13, "XMAS"),
    )

    /** Wire value for "stop overriding, hand weather control back to
     *  the game." Plugin maps this to CLEAR_WEATHER_TYPE_PERSIST. */
    internal const val WEATHER_WIRE_CLEAR = -1

    /** Minimum / maximum for the time-of-day slider. The plugin
     *  rejects values outside this band to keep SET_CLOCK_TIME from
     *  seeing an out-of-range hour. */
    internal const val TIME_MIN_HOUR = 0
    internal const val TIME_MAX_HOUR = 23
}
