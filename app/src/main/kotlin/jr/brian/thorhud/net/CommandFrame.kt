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
    return "{\"v\":1,\"type\":\"cmd\",\"id\":$id,\"name\":\"$name\"," +
            "\"args\":{\"${argKey(name)}\":$arg}}\n"
}

/**
 * String variant for the single command that carries a C string arg:
 * set_plate. The plugin validates `[A-Z0-9 ]` and <=8 chars itself and
 * replies with "bad plate text" on anything else; this function strips
 * nothing but refuses obviously bogus input early so the server never
 * sees malformed JSON.
 */
internal fun commandFrameText(id: Int, name: String, text: String): String? {
    // Keep the pre-flight guard narrow: anything with a quote or a
    // backslash in it would need escaping, and no legitimate caller
    // ever asks for one. Reject length early so a 128-char tap on the
    // plate field doesn't travel to the plugin's buffer only to fail.
    if (text.length > 8) return null
    if (text.any { it == '"' || it == '\\' || it.code < 0x20 }) return null
    return "{\"v\":1,\"type\":\"cmd\",\"id\":$id,\"name\":\"$name\"," +
            "\"args\":{\"text\":\"$text\"}}\n"
}

/** The arg key the plugin's parser expects for each named command. */
private fun argKey(name: String): String = when (name) {
    "set_weapon"            -> "weapon"
    "set_radio"             -> "station"
    "route_blip"            -> "blip"
    "cheat"                 -> "cheat"
    // GTA V only. Packed 0x00RRGGBB; the plugin unpacks and paints
    // both custom-colour layers of the current vehicle. The id-based
    // "cheat" command cannot carry the RGB because the id itself
    // occupies arg0, hence the separate name.
    "paint_rgb"             -> "rgb"
    // Customs (GTA V only). Keys match asi-v/src/mods.cpp's parser.
    "set_mod"               -> "mod"           // (slot<<16)|index, 0xFFFF = stock
    "toggle_mod"            -> "slot"
    "set_wheel_type"        -> "type"
    "set_tint"              -> "tint"
    "set_plate_style"       -> "style"
    "set_neon"              -> "rgb"           // 0 = off (all sides)
    "set_smoke"             -> "rgb"
    "set_xenon_colour"      -> "index"         // 0xFFFFFFFF = default blue
    "set_bulletproof_tyres" -> "on"            // 0 / 1
    "mods_list"             -> "v"             // numeric placeholder, ignored
    // Ammu-Nation (GTA V only). armory_list carries the weapon hash;
    // the set_* commands pack (widx<<16)|payload into one u32 slot.
    "armory_list"           -> "weapon"
    "set_component"         -> "arg"
    "set_weapon_tint"       -> "arg"
    "set_ammo"              -> "arg"
    else                    -> "value"         // route_clear / blip_census ignore it
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
