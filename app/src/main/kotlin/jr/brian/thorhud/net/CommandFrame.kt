package jr.brian.thorhud.net

/** Largest value a command argument can carry: an unsigned 32-bit int. */
private const val MAX_U32 = 0xFFFFFFFFL

/**
 * One client->server command line, newline included, in the wire format
 * both plugins parse (asi/src/bridge.cpp, asi-v/src/bridge.cpp):
 *
 *     {"v":1,"type":"cmd","id":7,"name":"set_weapon","args":{"weapon":2578778090}}
 *
 * [arg] is a Long because GTA V weapon ids are 32-bit name hashes and
 * about half of them are above Int.MAX_VALUE. Formatted from an Int they
 * would go out negative; the plugins read the first run of digits after
 * "args", so the sign is dropped and a different weapon is requested.
 *
 * Returns null when [arg] does not fit an unsigned 32-bit int, which is
 * all either plugin accepts.
 */
internal fun commandFrame(id: Int, name: String, arg: Long): String? {
    if (arg !in 0..MAX_U32) return null
    val argKey = when (name) {
        "set_weapon" -> "weapon"
        "set_radio"  -> "station"
        "route_blip" -> "blip"
        "cheat"      -> "cheat"
        else         -> "value"   // route_clear / blip_census ignore it
    }
    return "{\"v\":1,\"type\":\"cmd\",\"id\":$id,\"name\":\"$name\"," +
            "\"args\":{\"$argKey\":$arg}}\n"
}

/**
 * A command that carries a pair of world coordinates rather than one int.
 * Used by waypoint_xy; the plugin's parse_cmd walks "x" and "y" keys out
 * of args. Keeps a stable JSON key order so a future field addition does
 * not shift what the (single) regex-free parser on the plugin side reads.
 *
 * Numbers are printed with up to 2 decimals (the plugin reads them with
 * strtof, which is tolerant of either form) to keep the frame short and
 * so a float that happens to be a whole number does not drag a decimal.
 */
internal fun commandFrameXy(id: Int, name: String, x: Float, y: Float): String {
    return "{\"v\":1,\"type\":\"cmd\",\"id\":$id,\"name\":\"$name\"," +
            "\"args\":{\"x\":${fmt(x)},\"y\":${fmt(y)}}}\n"
}

private fun fmt(v: Float): String {
    if (v == 0.0f) return "0"
    val s = "%.2f".format(v)
    // Drop trailing zeros / dot so "123.00" -> "123" and "1.50" -> "1.5".
    return s.trimEnd('0').trimEnd('.')
}
