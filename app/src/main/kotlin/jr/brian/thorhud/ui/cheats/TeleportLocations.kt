package jr.brian.thorhud.ui.cheats

/**
 * Curated map points for the Trainer teleport picker. Each entry is
 * (label, category, x, y, z) in GTA V world units. The plugin owns an
 * index-matched table (asi-v/src/cheats.cpp kTeleportPresets) so the
 * wire only carries the preset index in arg0 and the plugin reads the
 * coords from its own copy; keeping the source of truth on both sides
 * means the Kotlin list can show the user-facing labels while the
 * plugin never has to trust coords sent from the app.
 *
 * Labels are proper nouns and GTA V landmark names, kept English
 * everywhere (per CLAUDE.md's skip-list for brand / in-game
 * terminology).
 *
 * Z coords are approximate ground + a small lift so a teleport lands
 * the player on top of geometry rather than inside it; the game's own
 * physics resolves them to the ground on the next frame.
 */
internal object TeleportLocations {

    internal enum class Category(val label: String) {
        LosSantos("los santos"),
        Blaine("blaine county"),
        Landmark("landmark"),
        Mission("mission"),
    }

    internal data class Location(
        val wire: Int,
        val label: String,
        val category: Category,
        val x: Float,
        val y: Float,
        val z: Float,
    )

    /** 30 named points. Order matches the plugin's kTeleportPresets
     *  by [wire] index so a mismatch between the two tables shows up
     *  as a wrong-location teleport rather than a silent miss.
     *  Coords are well-known GTA V coordinates; a few are rounded. */
    internal val ALL: List<Location> = listOf(
        // -- Los Santos -----------------------------------------------
        Location(0,  "Michael's House",      Category.LosSantos, -816f,  177f,  72f),
        Location(1,  "Franklin's Aunt",      Category.LosSantos, -17f,  -1444f, 31f),
        Location(2,  "Franklin's Mansion",   Category.LosSantos, 7f,     536f,  175f),
        Location(3,  "Del Perro Pier",       Category.LosSantos, -1850f, -1248f, 8f),
        Location(4,  "Vespucci Beach",       Category.LosSantos, -1600f, -1050f, 13f),
        Location(5,  "Legion Square",        Category.LosSantos, 193f,  -934f,  30f),
        Location(6,  "Downtown LS",          Category.LosSantos, 215f,   219f,  106f),
        Location(7,  "Maze Bank Rooftop",    Category.LosSantos, -75f,  -818f,  326f),
        Location(8,  "Union Depository",     Category.LosSantos, 7f,    -680f,  32f),
        Location(9,  "Pacific Standard",     Category.LosSantos, 240f,   220f,  106f),
        Location(10, "LS Airport",           Category.LosSantos, -1086f, -2708f, 13f),
        Location(11, "Del Perro Plaza",      Category.LosSantos, -1281f, -706f, 24f),
        Location(12, "Rockford Plaza",       Category.LosSantos, -716f, -158f,  36f),
        Location(13, "Mission Row PD",       Category.LosSantos, 426f,  -980f,  30f),

        // -- Blaine County --------------------------------------------
        Location(14, "Trevor's Trailer",     Category.Blaine, 1972f, 3817f, 33f),
        Location(15, "Sandy Shores",         Category.Blaine, 1961f, 3740f, 32f),
        Location(16, "Sandy Shores Airstrip", Category.Blaine, 1748f, 3273f, 41f),
        Location(17, "Grapeseed",            Category.Blaine, 1700f, 4647f, 42f),
        Location(18, "Paleto Bay",           Category.Blaine, -275f, 6227f, 31f),
        Location(19, "Paleto Sheriff",       Category.Blaine, -443f, 6013f, 31f),
        Location(20, "Alamo Sea Pier",       Category.Blaine, 920f,  3600f, 30f),
        Location(21, "Mt Gordo Lighthouse",  Category.Blaine, 3444f, 5172f, 20f),
        Location(22, "Yellow Jack Inn",      Category.Blaine, 1984f, 3053f, 47f),

        // -- Landmarks ------------------------------------------------
        Location(23, "Vinewood Sign",        Category.Landmark, 712f,  1197f, 353f),
        Location(24, "Mt Chiliad Peak",      Category.Landmark, 501f,  5604f, 797f),
        Location(25, "Galileo Observatory",  Category.Landmark, -438f, 1081f, 352f),
        Location(26, "Fort Zancudo Gate",    Category.Landmark, -1587f, 2751f, 17f),
        Location(27, "Humane Labs",          Category.Landmark, 3611f, 3733f, 28f),

        // -- Missions -------------------------------------------------
        Location(28, "Diamond Casino",       Category.Mission, 972f,  44f,   81f),
        Location(29, "Carrier (SP)",         Category.Mission, 3023f, -4650f, 15f),
    )

    /** Pre-grouped by category, order-preserving. */
    internal val BY_CATEGORY: Map<Category, List<Location>> =
        ALL.groupBy { it.category }
}
