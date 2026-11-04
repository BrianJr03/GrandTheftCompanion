package jr.brian.thorhud.ui.cheats

/**
 * Curated ped animation library for the Trainer animation picker.
 * Each entry is (label, dict, name) where the plugin runs
 * REQUEST_ANIM_DICT(dict) -> HAS_ANIM_DICT_LOADED -> TASK_PLAY_ANIM.
 *
 * Wire format mirrors TeleportLocations: the plugin has an index-
 * matched mirror table (asi-v/src/cheats.cpp kAnimationList) so the
 * client only sends the index in arg0. Keeps the dict + name strings
 * off the wire and bounds how much the plugin has to trust.
 *
 * Coverage: classic NativeTrainer "play anim" catalog, trimmed to
 * things the player is likely to want to see on themselves. The
 * plugin always plays animations on the current player ped.
 */
internal object AnimationLibrary {

    internal enum class Category(val label: String) {
        Gestures("gestures"),
        Dances("dances"),
        Reactions("reactions"),
        Idles("idles"),
    }

    internal data class Anim(
        val wire: Int,
        val label: String,
        val category: Category,
    )

    /** 30 curated animations. Order matches
     *  asi-v/src/cheats.cpp kAnimationList by wire index. */
    internal val ALL: List<Anim> = listOf(
        // -- Gestures -------------------------------------------------
        Anim(0,  "Wave",            Category.Gestures),
        Anim(1,  "Salute",          Category.Gestures),
        Anim(2,  "Point",           Category.Gestures),
        Anim(3,  "Thumbs Up",       Category.Gestures),
        Anim(4,  "Nod Yes",         Category.Gestures),
        Anim(5,  "Shake Head No",   Category.Gestures),
        Anim(6,  "Shrug",           Category.Gestures),
        Anim(7,  "Beckon",          Category.Gestures),

        // -- Dances --------------------------------------------------
        Anim(8,  "Dance (solo)",    Category.Dances),
        Anim(9,  "Dance Low 1",     Category.Dances),
        Anim(10, "Dance Med 1",     Category.Dances),
        Anim(11, "Dance High 1",    Category.Dances),
        Anim(12, "Dance Facial",    Category.Dances),

        // -- Reactions ----------------------------------------------
        Anim(13, "Laugh",           Category.Reactions),
        Anim(14, "Cry",             Category.Reactions),
        Anim(15, "Angry",           Category.Reactions),
        Anim(16, "Scared",          Category.Reactions),
        Anim(17, "Happy",           Category.Reactions),
        Anim(18, "Surprised",       Category.Reactions),
        Anim(19, "Flex",            Category.Reactions),
        Anim(20, "Push Up Start",   Category.Reactions),
        Anim(21, "Finger Wag",      Category.Reactions),

        // -- Idles ---------------------------------------------------
        Anim(22, "Phone Call",      Category.Idles),
        Anim(23, "Smoke",           Category.Idles),
        Anim(24, "Drink Beer",      Category.Idles),
        Anim(25, "Hands on Hips",   Category.Idles),
        Anim(26, "Cross Arms",      Category.Idles),
        Anim(27, "Lean on Wall",    Category.Idles),
        Anim(28, "Sit on Ground",   Category.Idles),
        Anim(29, "Stretch",         Category.Idles),
    )

    /** Pre-grouped by category, order-preserving. */
    internal val BY_CATEGORY: Map<Category, List<Anim>> =
        ALL.groupBy { it.category }
}
