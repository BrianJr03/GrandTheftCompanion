package jr.brian.thorhud.ui.map

import jr.brian.thorhud.model.GameState

/**
 * What to call a map blip, by game and sprite id. No Android imports:
 * this is the part of the blip list that can be unit-tested.
 *
 * GTA V names and most of the sprite ids are from the public FiveM
 * blip reference (docs.fivem.net/docs/game-references/blips/). Mission-
 * start flags for the character sprites (M/F/T/strangers/heists) are
 * applied so the UI can tint them with the episode accent. The exact
 * icon the game shows is usually a (sprite, colour) pair rather than
 * the sprite alone: sprite 1 colour 0 is the generic default dot,
 * sprite 1 colour 66 is the yellow mission objective, sprite 66
 * colour 42 is a "?" stranger and sprite 66 colour 43 is a friend.
 * The known pairs live in [V_SPRITE_COLOUR] and win over [V_LABELS].
 * GTA IV has no public table; one entry is seeded from the EFLC MP
 * wiki and the rest will fill in from on-device censuses.
 */
internal data class BlipLabel(
    val name: String,
    /** Walking into this blip starts a mission or a job (story letters,
     *  strangers, heists, mission objectives). The UI tints them with
     *  the accent so the player can find them in a crowded list. */
    val missionStart: Boolean,
)

// Known (sprite, colour) pairs. These win over the sprite-only table.
private val V_SPRITE_COLOUR: Map<Pair<Int, Int>, BlipLabel> = mapOf(
    (1 to 66) to BlipLabel("mission objective", true),
    (66 to 42) to BlipLabel("stranger", true),
    (66 to 43) to BlipLabel("friend", false),
    (66 to 48) to BlipLabel("friend", false),
    (114 to 42) to BlipLabel("stranger (f)", true),
    (115 to 42) to BlipLabel("stranger (m)", true),
)

// Sprite-only table. Short English labels, episode-accent for ones that
// are actually a mission start. Ids come from the FiveM blips reference.
private val V_LABELS: Map<Int, BlipLabel> = mapOf(
    // --- generic / default dot (special-cased by colour above) --------
    1 to BlipLabel("standard", false),
    2 to BlipLabel("lower", false),
    7 to BlipLabel("north", false),
    8 to BlipLabel("waypoint", false),
    // --- police / enforcement ----------------------------------------
    41 to BlipLabel("police", false),
    42 to BlipLabel("police chase", false),
    43 to BlipLabel("police heli", false),
    47 to BlipLabel("snitch", false),
    56 to BlipLabel("cop patrol", false),
    60 to BlipLabel("police station", false),
    61 to BlipLabel("hospital", false),
    62 to BlipLabel("assassin's mark", true),
    67 to BlipLabel("security van", true),
    68 to BlipLabel("tow truck", false),
    133 to BlipLabel("snitch", false),
    672 to BlipLabel("cop car", false),
    // --- story-mission letters (M/F/T + supporting) ------------------
    59 to BlipLabel("heist", true),
    76 to BlipLabel("Armenian family", true),
    77 to BlipLabel("Lester", true),
    78 to BlipLabel("Michael", true),
    79 to BlipLabel("Trevor", true),
    80 to BlipLabel("jewelry heist", true),
    86 to BlipLabel("Lamar", true),
    88 to BlipLabel("Franklin", true),
    89 to BlipLabel("Chinese", true),
    96 to BlipLabel("biolab heist", true),
    104 to BlipLabel("docks heist", true),
    105 to BlipLabel("FBI heist", true),
    106 to BlipLabel("FBI officers", true),
    107 to BlipLabel("finale bank heist", true),
    108 to BlipLabel("financier", true),
    112 to BlipLabel("Michael (exile)", true),
    113 to BlipLabel("nice house heist", true),
    118 to BlipLabel("rural bank heist", true),
    120 to BlipLabel("Solomon", true),
    123 to BlipLabel("Trevor (exile)", true),
    124 to BlipLabel("Michael / Trevor", true),
    149 to BlipLabel("celebrity theft", true),
    206 to BlipLabel("Epsilon", true),
    207 to BlipLabel("financier (grey)", true),
    208 to BlipLabel("Trevor (grey)", true),
    209 to BlipLabel("Trevor (red)", true),
    210 to BlipLabel("Franklin (grey)", true),
    211 to BlipLabel("Franklin (blue)", true),
    212 to BlipLabel("Franklin A", true),
    213 to BlipLabel("Franklin B", true),
    214 to BlipLabel("Franklin C", true),
    269 to BlipLabel("Altruist", true),
    292 to BlipLabel("Love Fist", true),
    293 to BlipLabel("Simeon", true),
    352 to BlipLabel("Martin Madrazo", true),
    355 to BlipLabel("Devin", true),
    362 to BlipLabel("mask (heist prep)", true),
    363 to BlipLabel("heist prep", true),
    445 to BlipLabel("Lamar (MP)", true),
    // --- strangers & freaks ------------------------------------------
    66 to BlipLabel("random character", true),
    114 to BlipLabel("stranger (f)", true),
    115 to BlipLabel("stranger (m)", true),
    141 to BlipLabel("hunting", true),
    280 to BlipLabel("friend", false),
    294 to BlipLabel("mission", true),
    295 to BlipLabel("mission", true),
    303 to BlipLabel("bounty hit", true),
    304 to BlipLabel("UGC mission", true),
    381 to BlipLabel("Barry", true),
    382 to BlipLabel("Dom", true),
    383 to BlipLabel("Mary Ann", true),
    384 to BlipLabel("Cletus", true),
    385 to BlipLabel("Josh", true),
    386 to BlipLabel("Minute Men", true),
    387 to BlipLabel("Omega", true),
    388 to BlipLabel("Tonya", true),
    389 to BlipLabel("Paparazzo", true),
    400 to BlipLabel("Abigail", true),
    403 to BlipLabel("Rampage", true),
    405 to BlipLabel("Fanatic", true),
    // --- mission objective overlays (also by colour above) -----------
    143 to BlipLabel("objective (blue)", false),
    144 to BlipLabel("objective (green)", false),
    145 to BlipLabel("objective (red)", false),
    146 to BlipLabel("objective (yellow)", false),
    162 to BlipLabel("point of interest", false),
    271 to BlipLabel("on mission", false),
    364 to BlipLabel("incapacitated", false),
    367 to BlipLabel("completed", false),
    // --- shops & services --------------------------------------------
    40 to BlipLabel("safehouse", false),
    71 to BlipLabel("barber", false),
    72 to BlipLabel("LS Customs", false),
    73 to BlipLabel("clothes store", false),
    75 to BlipLabel("tattoo", false),
    85 to BlipLabel("Vinewood tours", false),
    90 to BlipLabel("flight school", false),
    93 to BlipLabel("bar", false),
    99 to BlipLabel("Cabaret Club", false),
    100 to BlipLabel("car wash", false),
    102 to BlipLabel("Comedy Club", false),
    110 to BlipLabel("Ammu-Nation", false),
    111 to BlipLabel("internet cafe", false),
    119 to BlipLabel("shooting range", false),
    121 to BlipLabel("strip club", false),
    135 to BlipLabel("cinema", false),
    136 to BlipLabel("music venue", false),
    138 to BlipLabel("airport", false),
    147 to BlipLabel("arms dealing", true),
    198 to BlipLabel("taxi", false),
    205 to BlipLabel("shrink", false),
    251 to BlipLabel("arms dealing (air)", true),
    266 to BlipLabel("fairground", false),
    267 to BlipLabel("property", false),
    313 to BlipLabel("Ammu-Nation range", false),
    321 to BlipLabel("fire truck", false),
    346 to BlipLabel("gang mission", true),
    356 to BlipLabel("dock", false),
    357 to BlipLabel("garage", false),
    359 to BlipLabel("hangar", false),
    360 to BlipLabel("helipad", false),
    446 to BlipLabel("Benny's", false),
    462 to BlipLabel("yacht", false),
    492 to BlipLabel("biker clubhouse", false),
    614 to BlipLabel("nightclub", false),
    670 to BlipLabel("agency", false),
    671 to BlipLabel("comic store", false),
    679 to BlipLabel("casino", false),
    740 to BlipLabel("arcade", false),
    777 to BlipLabel("LS car meet", false),
    778 to BlipLabel("car test area", false),
    779 to BlipLabel("auto shop", false),
    780 to BlipLabel("docks export", false),
    787 to BlipLabel("LS car meet host", false),
    788 to BlipLabel("Securoserv", false),
    // --- activities (races, sports, free-roam things to do) -----------
    37 to BlipLabel("activities", false),
    38 to BlipLabel("race flag", false),
    82 to BlipLabel("drag race finish", false),
    92 to BlipLabel("air hockey", false),
    94 to BlipLabel("base jump", false),
    95 to BlipLabel("basketball", false),
    103 to BlipLabel("darts", false),
    109 to BlipLabel("golf", false),
    122 to BlipLabel("tennis", false),
    126 to BlipLabel("triathlon", false),
    127 to BlipLabel("off-road race", false),
    142 to BlipLabel("pool", false),
    179 to BlipLabel("triathlon (cycling)", false),
    180 to BlipLabel("triathlon (swimming)", false),
    197 to BlipLabel("yoga", false),
    273 to BlipLabel("Chop", false),
    279 to BlipLabel("hooker", false),
    296 to BlipLabel("friend: darts", false),
    297 to BlipLabel("friend: comedy", false),
    298 to BlipLabel("friend: cinema", false),
    299 to BlipLabel("friend: tennis", false),
    300 to BlipLabel("friend: strip club", false),
    301 to BlipLabel("friend: live music", false),
    302 to BlipLabel("friend: golf", false),
    305 to BlipLabel("horde", false),
    309 to BlipLabel("race", false),
    310 to BlipLabel("deathmatch", false),
    311 to BlipLabel("arm wrestling", false),
    312 to BlipLabel("mission (1-to-2)", true),
    314 to BlipLabel("air race", false),
    315 to BlipLabel("land race", false),
    316 to BlipLabel("sea race", false),
    376 to BlipLabel("bike race", false),
    377 to BlipLabel("parachute", false),
    378 to BlipLabel("team deathmatch", false),
    379 to BlipLabel("foot race", false),
    380 to BlipLabel("vehicle deathmatch", false),
    408 to BlipLabel("capture the flag", false),
    409 to BlipLabel("last team standing", false),
    435 to BlipLabel("race (stunt)", false),
    436 to BlipLabel("hot property", false),
    438 to BlipLabel("king of the castle", false),
    456 to BlipLabel("Finders Keepers", false),
    457 to BlipLabel("Assault Package", false),
    458 to BlipLabel("Hunt the Boss", false),
    459 to BlipLabel("Sightseer", false),
    460 to BlipLabel("Turreted Limo", false),
    461 to BlipLabel("Belly of the Beast", false),
    488 to BlipLabel("stunt race", false),
    490 to BlipLabel("stunt (premium)", false),
    491 to BlipLabel("adversary", false),
    641 to BlipLabel("arena series", false),
    642 to BlipLabel("arena (premium)", false),
    643 to BlipLabel("arena workshop", false),
    644 to BlipLabel("race wars", false),
    673 to BlipLabel("RC time trials", false),
    674 to BlipLabel("king of the hill", false),
    675 to BlipLabel("king of the hill (teams)", false),
    680 to BlipLabel("casino table games", false),
    681 to BlipLabel("casino wheel", false),
    682 to BlipLabel("casino concierge", false),
    683 to BlipLabel("casino chips", false),
    684 to BlipLabel("casino horse racing", false),
    685 to BlipLabel("adversary (featured)", false),
    726 to BlipLabel("open-wheel race", false),
    785 to BlipLabel("street race series", false),
    786 to BlipLabel("pursuit series", false),
    // --- vehicles (type=1 is "your current vehicle" marker) ----------
    36 to BlipLabel("cable car", false),
    225 to BlipLabel("gang vehicle", false),
    229 to BlipLabel("gun car", false),
    262 to BlipLabel("Z-type", false),
    263 to BlipLabel("Stinger", false),
    264 to BlipLabel("Packer", false),
    265 to BlipLabel("Monroe", false),
    308 to BlipLabel("sub", false),
    317 to BlipLabel("tow truck", false),
    318 to BlipLabel("garbage truck", false),
    326 to BlipLabel("getaway car", false),
    410 to BlipLabel("boat", false),
    421 to BlipLabel("tank", false),
    422 to BlipLabel("player heli", false),
    423 to BlipLabel("player plane", false),
    424 to BlipLabel("player jet", false),
    426 to BlipLabel("player gun car", false),
    427 to BlipLabel("player boat", false),
    455 to BlipLabel("yacht", false),
    471 to BlipLabel("Seashark", false),
    477 to BlipLabel("truck", false),
    479 to BlipLabel("trailer", false),
    481 to BlipLabel("Cargobob", false),
    512 to BlipLabel("quad", false),
    513 to BlipLabel("bus", false),
    523 to BlipLabel("sports car", false),
    524 to BlipLabel("warehouse vehicle", false),
    638 to BlipLabel("Blimp", false),
    639 to BlipLabel("Oppressor Mk II", false),
    724 to BlipLabel("limo", false),
    734 to BlipLabel("snow truck", false),
    737 to BlipLabel("Zhaba", false),
    749 to BlipLabel("military quad", false),
    750 to BlipLabel("military truck", false),
    756 to BlipLabel("retro sports car", false),
    // --- properties / for-sale ---------------------------------------
    350 to BlipLabel("property for sale", false),
    369 to BlipLabel("garage for sale", false),
    370 to BlipLabel("helipad for sale", false),
    371 to BlipLabel("dock for sale", false),
    372 to BlipLabel("hangar for sale", false),
    374 to BlipLabel("business", false),
    375 to BlipLabel("business for sale", false),
    473 to BlipLabel("warehouse", false),
    474 to BlipLabel("warehouse for sale", false),
    475 to BlipLabel("office", false),
    476 to BlipLabel("office for sale", false),
    557 to BlipLabel("bunker property", false),
    // --- MP / adversary / internal (short labels; rarely open-world) --
    27 to BlipLabel("crew", false),
    28 to BlipLabel("friendlies", false),
    148 to BlipLabel("MP friend", false),
    412 to BlipLabel("crew", false),
    428 to BlipLabel("MP heist", true),
    442 to BlipLabel("beast", false),
    727 to BlipLabel("rappel", false),
    728 to BlipLabel("swap car", false),
    729 to BlipLabel("scuba gear", false),
    // One-liner for anything else: the UI will show "sprite N" via the
    // fallback in blipLabel below.
)

// The EFLC multiplayer wiki's one documented example is sprite 44 =
// Playboy X; nothing else is public. Fill in from the census.
private val IV_LABELS: Map<Int, BlipLabel> = mapOf(
    44 to BlipLabel("Playboy X", true),
)

internal fun blipLabel(isGtaV: Boolean, sprite: Int, colour: Int = 0): BlipLabel {
    if (isGtaV) {
        V_SPRITE_COLOUR[sprite to colour]?.let { return it }
        V_LABELS[sprite]?.let { return it }
    } else {
        IV_LABELS[sprite]?.let { return it }
    }
    return BlipLabel("sprite $sprite", false)
}

/**
 * Imperial units to match the games' own HUDs (both GTA IV and GTA V
 * show feet / miles on the mini-map). Feet below a mile, miles above.
 * 1 m = 3.280839895 ft; 1 mi = 1609.344 m.
 */
internal fun blipDistance(distM: Float): String =
    if (distM < 1609.344f) "${(distM * 3.280839895f).toInt()} ft"
    else String.format(java.util.Locale.US, "%.1f mi", distM / 1609.344f)

/** One row of the companion's blip list. */
internal data class BlipRow(
    val blip: GameState.Blip,
    val label: BlipLabel,
    val distance: String,
    val routed: Boolean,
)

/**
 * Rows for the list, in the plugin's order (nearest first), with the
 * routed one flagged. [routedId] is [GameState.routedBlipId].
 */
internal fun blipRows(isGtaV: Boolean, blips: List<GameState.Blip>, routedId: Int?): List<BlipRow> =
    blips.map { b ->
        BlipRow(
            blip = b,
            label = blipLabel(isGtaV, b.sprite, b.colour),
            distance = blipDistance(b.distM),
            routed = routedId != null && routedId == b.id,
        )
    }

/**
 * A section of the list: every row whose label resolved to the same
 * [name], in nearest-first order. Groups themselves are returned
 * nearest-first (the first group is the one holding the player's
 * closest blip).
 */
internal data class BlipGroup(
    val name: String,
    val missionStart: Boolean,
    val rows: List<BlipRow>,
) {
    val count: Int get() = rows.size
    val nearest: String get() = rows.first().distance
    val routed: Boolean get() = rows.any { it.routed }
}

internal fun blipGroups(
    isGtaV: Boolean,
    blips: List<GameState.Blip>,
    routedId: Int?,
): List<BlipGroup> {
    val rows = blipRows(isGtaV, blips, routedId)
    // LinkedHashMap preserves first-seen order, which mirrors
    // nearest-first because [rows] is already sorted that way.
    val grouped = LinkedHashMap<String, MutableList<BlipRow>>()
    for (r in rows) grouped.getOrPut(r.label.name) { mutableListOf() }.add(r)
    val built = grouped.map { (name, rs) ->
        BlipGroup(
            name = name,
            missionStart = rs.first().label.missionStart,
            rows = rs,
        )
    }
    // Single-item groups first, multis after — each bucket keeps the
    // nearest-first order it already has. partition() is stable.
    val (singles, multis) = built.partition { it.count == 1 }
    return singles + multis
}

/**
 * Title-case the first letter of every space-separated word, leaving
 * any letter that is already uppercase alone. "ammu-Nation" stays
 * "Ammu-Nation", "car wash" becomes "Car Wash", "sprite 123" becomes
 * "Sprite 123".
 */
internal fun String.titleCase(): String =
    split(' ').joinToString(" ") { w ->
        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
