package jr.brian.thorhud.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HudPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True after the user has dismissed the one-time swipe-hint dialog. */
    var hasSeenSwipeHint: Boolean
        get() = prefs.getBoolean(KEY_SEEN_SWIPE_HINT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SEEN_SWIPE_HINT, value).apply()
        }

    /** Last latestVersion the user dismissed the auto-popup for. The
     *  auto-open on app start skips any version that matches this; the
     *  status-bar chip still opens the dialog on demand. Blank until
     *  the user has dismissed anything. */
    var dismissedUpdateVersion: String
        get() = prefs.getString(KEY_DISMISSED_UPDATE_VERSION, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DISMISSED_UPDATE_VERSION, value).apply()
        }

    /** User zoom factor on top of the preset mirror crop (1.0 = preset). */
    fun mirrorScale(gameKey: String): Float =
        prefs.getFloat("${KEY_MIRROR_SCALE}_$gameKey", 1f)

    /** User pan on top of the preset crop, in source-image fractions. */
    fun mirrorOffsetX(gameKey: String): Float =
        prefs.getFloat("${KEY_MIRROR_OFFSET_X}_$gameKey", 0f)

    fun mirrorOffsetY(gameKey: String): Float =
        prefs.getFloat("${KEY_MIRROR_OFFSET_Y}_$gameKey", 0f)

    fun setMirrorTransform(gameKey: String, scale: Float, offsetX: Float, offsetY: Float) {
        prefs.edit()
            .putFloat("${KEY_MIRROR_SCALE}_$gameKey", scale)
            .putFloat("${KEY_MIRROR_OFFSET_X}_$gameKey", offsetX)
            .putFloat("${KEY_MIRROR_OFFSET_Y}_$gameKey", offsetY)
            .apply()
    }

    fun resetMirrorTransform(gameKey: String) {
        prefs.edit()
            .remove("${KEY_MIRROR_SCALE}_$gameKey")
            .remove("${KEY_MIRROR_OFFSET_X}_$gameKey")
            .remove("${KEY_MIRROR_OFFSET_Y}_$gameKey")
            .apply()
    }

    // StateFlow-backed so Compose redraws immediately when the user
    // flips a switch in the settings sheet. The prefs file is still
    // the source of truth on next launch.
    //
    // Every user-visible toggle below is stored per game ("iv" vs "v")
    // so flipping a switch while playing IV does not change the V
    // experience and vice-versa. One-shot migration from the legacy
    // shared pref seeds both games with the user's previous value;
    // the shared key is then removed.

    private val autoSwipeFlows = perGameBool(KEY_AUTO_SWIPE_ON_DRIVE, default = false)
    fun autoSwipeOnDrive(gameKey: String): StateFlow<Boolean> =
        requirePerGameFlow(autoSwipeFlows, gameKey)
    fun setAutoSwipeOnDrive(gameKey: String, enabled: Boolean) =
        setPerGameBool(autoSwipeFlows, KEY_AUTO_SWIPE_ON_DRIVE, gameKey, enabled)

    private val autoStartMirrorFlows = perGameBool(KEY_AUTO_START_MIRROR, default = false)
    fun autoStartMirror(gameKey: String): StateFlow<Boolean> =
        requirePerGameFlow(autoStartMirrorFlows, gameKey)
    fun setAutoStartMirror(gameKey: String, enabled: Boolean) =
        setPerGameBool(autoStartMirrorFlows, KEY_AUTO_START_MIRROR, gameKey, enabled)

    private val alwaysReturnToHudFlows = perGameBool(KEY_ALWAYS_RETURN_TO_HUD, default = false)
    fun alwaysReturnToHud(gameKey: String): StateFlow<Boolean> =
        requirePerGameFlow(alwaysReturnToHudFlows, gameKey)
    fun setAlwaysReturnToHud(gameKey: String, enabled: Boolean) =
        setPerGameBool(alwaysReturnToHudFlows, KEY_ALWAYS_RETURN_TO_HUD, gameKey, enabled)

    private val mapControlsRightFlows = perGameBool(KEY_MAP_CONTROLS_RIGHT, default = false)
    fun mapControlsRight(gameKey: String): StateFlow<Boolean> =
        requirePerGameFlow(mapControlsRightFlows, gameKey)
    fun setMapControlsRight(gameKey: String, enabled: Boolean) =
        setPerGameBool(mapControlsRightFlows, KEY_MAP_CONTROLS_RIGHT, gameKey, enabled)

    // Weapon wheel slot order per game. Stored as a comma-separated
    // list of VWheelSlot ordinals (0..7); default is the natural
    // 0..7 order the game ships with. A blank or malformed stored
    // value falls back to the default so a corrupt pref cannot hide
    // a slot.
    private val _ivWheelOrder = MutableStateFlow(loadWheelOrder("iv"))
    val ivWheelOrder: StateFlow<List<Int>> = _ivWheelOrder.asStateFlow()
    private val _vWheelOrder = MutableStateFlow(loadWheelOrder("v"))
    val vWheelOrder: StateFlow<List<Int>> = _vWheelOrder.asStateFlow()

    fun setWheelOrder(gameKey: String, order: List<Int>) {
        if (!isValidWheelOrder(order)) return
        prefs.edit().putString(wheelOrderKey(gameKey), order.joinToString(",")).apply()
        wheelFlowFor(gameKey)?.value = order
    }

    fun resetWheelOrder(gameKey: String) {
        prefs.edit().remove(wheelOrderKey(gameKey)).apply()
        wheelFlowFor(gameKey)?.value = DEFAULT_WHEEL_ORDER
    }

    // Cheat section expand/collapse state. We store the set of
    // COLLAPSED category names (CheatCategory.name — "Health",
    // "Wanted", ...) so the empty-set default naturally means every
    // section is expanded, which is what a first-time user wants.
    // Per-game: IV and V have different category shapes (V has
    // Vehicle, IV skips it, etc.), and a collapse layout a user
    // picks for one game is their choice for that game only.
    private val collapsedCheatSectionFlows = perGameStringSet(KEY_COLLAPSED_CHEAT_SECTIONS)
    fun collapsedCheatSections(gameKey: String): StateFlow<Set<String>> =
        requirePerGameFlow(collapsedCheatSectionFlows, gameKey)
    fun setCheatSectionCollapsed(gameKey: String, category: String, collapsed: Boolean) {
        val flow = collapsedCheatSectionFlows[gameKey] ?: return
        val cur = flow.value
        val next = if (collapsed) cur + category else cur - category
        if (next == cur) return
        flow.value = next
        prefs.edit()
            .putStringSet("${KEY_COLLAPSED_CHEAT_SECTIONS}_$gameKey", next)
            .apply()
    }

    // User's PERSISTED intent for which cheat toggles should be on.
    // Updated after a successful tap on a toggle. On reconnect / game
    // relaunch the plugin's mask resets to zero; CheatsViewModel's
    // reconciler sends the matching `cheat` commands so the player's
    // chosen loadout comes back on its own.
    //
    // Per-game. Cheat ids between IV and V are different id spaces,
    // so a bag of ids only makes sense for one game at a time —
    // leaking IV ids into V would re-fire gibberish or get refused.
    private val enabledCheatToggleFlows = perGameIntSet(KEY_ENABLED_CHEAT_TOGGLES)
    fun enabledCheatToggles(gameKey: String): StateFlow<Set<Int>> =
        requirePerGameFlow(enabledCheatToggleFlows, gameKey)
    fun setCheatToggleEnabled(gameKey: String, id: Int, enabled: Boolean) {
        val flow = enabledCheatToggleFlows[gameKey] ?: return
        val cur = flow.value
        val next = if (enabled) cur + id else cur - id
        if (next == cur) return
        flow.value = next
        prefs.edit()
            .putStringSet(
                "${KEY_ENABLED_CHEAT_TOGGLES}_$gameKey",
                next.map { it.toString() }.toSet(),
            )
            .apply()
    }

    // Whether to auto-re-apply the persisted toggles on connect.
    // Default on — a user who turned invincible + police-ignore on in
    // last night's session expects them to still be on tonight.
    private val autoReapplyCheatFlows = perGameBool(KEY_AUTO_REAPPLY_CHEATS, default = true)
    fun autoReapplyCheats(gameKey: String): StateFlow<Boolean> =
        requirePerGameFlow(autoReapplyCheatFlows, gameKey)
    fun setAutoReapplyCheats(gameKey: String, enabled: Boolean) =
        setPerGameBool(autoReapplyCheatFlows, KEY_AUTO_REAPPLY_CHEATS, gameKey, enabled)

    // One-shot cheat ids the user flagged (via long-press) for
    // auto-fire on reconnect. Reconciler fires each one once per
    // safe-state entry (unlike toggles, which also check whether
    // the mask bit is already on). The same auto-reapply master
    // switch gates both. Per-game for the same reason as the
    // enabled-toggles bag above.
    private val autoApplyCheatFlows = perGameIntSet(KEY_AUTO_APPLY_CHEATS)
    fun autoApplyCheats(gameKey: String): StateFlow<Set<Int>> =
        requirePerGameFlow(autoApplyCheatFlows, gameKey)
    fun setCheatAutoApply(gameKey: String, id: Int, enabled: Boolean) {
        val flow = autoApplyCheatFlows[gameKey] ?: return
        val cur = flow.value
        val next = if (enabled) cur + id else cur - id
        if (next == cur) return
        flow.value = next
        prefs.edit()
            .putStringSet(
                "${KEY_AUTO_APPLY_CHEATS}_$gameKey",
                next.map { it.toString() }.toSet(),
            )
            .apply()
    }

    // User-chosen cheat section order (CheatCategory.name values).
    // Empty list = "use default", so the Compose layer can sort by
    // localized section label on first launch without needing a
    // Context here in the data layer. Writes are validated — only
    // known category names survive — and persisted as a comma-
    // separated string, same shape as wheel_order_$game.
    private val cheatSectionOrderFlows: Map<String, MutableStateFlow<List<String>>> =
        GAME_KEYS.associateWith { g ->
            MutableStateFlow(loadCheatSectionOrder(g))
        }
    fun cheatSectionOrder(gameKey: String): StateFlow<List<String>> =
        requirePerGameFlow(cheatSectionOrderFlows, gameKey)
    fun setCheatSectionOrder(gameKey: String, order: List<String>) {
        val flow = cheatSectionOrderFlows[gameKey] ?: return
        if (flow.value == order) return
        flow.value = order
        prefs.edit()
            .putString(cheatSectionOrderKey(gameKey), order.joinToString(","))
            .apply()
    }

    /** True once the user has dismissed the two-slide cheats
     *  onboarding sheet. Shown on first visit to the cheats tab. */
    var hasSeenCheatsOnboarding: Boolean
        get() = prefs.getBoolean(KEY_SEEN_CHEATS_ONBOARDING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SEEN_CHEATS_ONBOARDING, value).apply()
        }

    /** Wipe both persisted cheat sets (toggles the user had on, and
     *  one-shots long-pressed for auto-apply) for one game. Leaves
     *  the master auto-reapply switch and the onboarding-seen flag
     *  alone, and does not touch the other game's bag. */
    fun clearAutoCheats(gameKey: String) {
        enabledCheatToggleFlows[gameKey]?.value = emptySet()
        autoApplyCheatFlows[gameKey]?.value = emptySet()
        prefs.edit()
            .remove("${KEY_ENABLED_CHEAT_TOGGLES}_$gameKey")
            .remove("${KEY_AUTO_APPLY_CHEATS}_$gameKey")
            .apply()
    }

    private fun wheelFlowFor(gameKey: String) = when (gameKey) {
        "iv" -> _ivWheelOrder
        "v"  -> _vWheelOrder
        else -> null
    }

    private fun loadWheelOrder(gameKey: String): List<Int> {
        val raw = prefs.getString(wheelOrderKey(gameKey), null).orEmpty()
        val parsed = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (isValidWheelOrder(parsed)) parsed else DEFAULT_WHEEL_ORDER
    }

    private fun wheelOrderKey(gameKey: String) = "${KEY_WHEEL_ORDER}_$gameKey"

    private fun isValidWheelOrder(order: List<Int>): Boolean =
        order.size == DEFAULT_WHEEL_ORDER.size && order.toSet() == DEFAULT_WHEEL_ORDER.toSet()

    private fun cheatSectionOrderKey(gameKey: String) =
        "${KEY_CHEAT_SECTION_ORDER}_$gameKey"

    // Returns the saved order as a list of category names. Unknown
    // entries (CheatCategory removed in a later build) are filtered
    // out so the Compose layer never sees a stale name. Empty list
    // means "no save" — the Compose layer resolves to its alphabetical
    // default.
    private fun loadCheatSectionOrder(gameKey: String): List<String> {
        val raw = prefs.getString(cheatSectionOrderKey(gameKey), null).orEmpty()
        if (raw.isEmpty()) return emptyList()
        val known = KNOWN_CHEAT_CATEGORIES
        return raw.split(",")
            .map { it.trim() }
            .filter { it in known }
    }

    // Build the (iv, v) pair of flows for a per-game boolean pref.
    // Also runs one-shot migration from the pre-split shared key: if
    // the legacy key is present, seed any still-missing per-game key
    // with that value, then remove the shared key so subsequent
    // launches skip this.
    private fun perGameBool(
        baseKey: String,
        default: Boolean,
    ): Map<String, MutableStateFlow<Boolean>> {
        if (prefs.contains(baseKey)) {
            val shared = prefs.getBoolean(baseKey, default)
            val edit = prefs.edit()
            for (g in GAME_KEYS) {
                val perKey = "${baseKey}_$g"
                if (!prefs.contains(perKey)) edit.putBoolean(perKey, shared)
            }
            edit.remove(baseKey).apply()
        }
        return GAME_KEYS.associateWith { g ->
            MutableStateFlow(prefs.getBoolean("${baseKey}_$g", default))
        }
    }

    // String-set version. The legacy shared value, if present, is
    // migrated into both games on first launch (the shared key is
    // then removed) — category names are the same label space on
    // both HUDs, so a user who had "Vehicle" collapsed before the
    // split probably still wants it collapsed on both.
    private fun perGameStringSet(
        baseKey: String,
    ): Map<String, MutableStateFlow<Set<String>>> {
        if (prefs.contains(baseKey)) {
            val shared = prefs.getStringSet(baseKey, emptySet()).orEmpty().toSet()
            val edit = prefs.edit()
            for (g in GAME_KEYS) {
                val perKey = "${baseKey}_$g"
                if (!prefs.contains(perKey)) edit.putStringSet(perKey, shared)
            }
            edit.remove(baseKey).apply()
        }
        return GAME_KEYS.associateWith { g ->
            MutableStateFlow(
                prefs.getStringSet("${baseKey}_$g", emptySet()).orEmpty().toSet()
            )
        }
    }

    // Int-set version of perGameBool. The legacy shared key, if
    // present, is DROPPED rather than migrated: id spaces between
    // IV and V don't line up and copying the old bag into one game
    // would plant cheats the plugin can't identify.
    private fun perGameIntSet(
        baseKey: String,
    ): Map<String, MutableStateFlow<Set<Int>>> {
        if (prefs.contains(baseKey)) {
            prefs.edit().remove(baseKey).apply()
        }
        return GAME_KEYS.associateWith { g ->
            MutableStateFlow(
                prefs.getStringSet("${baseKey}_$g", emptySet())
                    .orEmpty()
                    .mapNotNull { it.toIntOrNull() }
                    .toSet()
            )
        }
    }

    private fun <T> requirePerGameFlow(
        map: Map<String, MutableStateFlow<T>>,
        gameKey: String,
    ): StateFlow<T> =
        (map[gameKey] ?: error("Unknown game key: $gameKey")).asStateFlow()

    private fun setPerGameBool(
        map: Map<String, MutableStateFlow<Boolean>>,
        baseKey: String,
        gameKey: String,
        enabled: Boolean,
    ) {
        val flow = map[gameKey] ?: return
        if (flow.value == enabled) return
        flow.value = enabled
        prefs.edit().putBoolean("${baseKey}_$gameKey", enabled).apply()
    }

    private companion object {
        const val FILE = "hud_prefs"
        const val KEY_SEEN_SWIPE_HINT = "seen_swipe_hint"
        const val KEY_DISMISSED_UPDATE_VERSION = "dismissed_update_version"
        const val KEY_MIRROR_SCALE = "mirror_scale"
        const val KEY_MIRROR_OFFSET_X = "mirror_offset_x"
        const val KEY_MIRROR_OFFSET_Y = "mirror_offset_y"
        const val KEY_AUTO_SWIPE_ON_DRIVE = "auto_swipe_on_drive"
        const val KEY_AUTO_START_MIRROR = "auto_start_mirror"
        const val KEY_ALWAYS_RETURN_TO_HUD = "always_return_to_hud"
        const val KEY_MAP_CONTROLS_RIGHT = "map_controls_right"
        const val KEY_WHEEL_ORDER = "wheel_order"
        const val KEY_COLLAPSED_CHEAT_SECTIONS = "collapsed_cheat_sections"
        const val KEY_ENABLED_CHEAT_TOGGLES = "enabled_cheat_toggles"
        const val KEY_AUTO_REAPPLY_CHEATS = "auto_reapply_cheats"
        const val KEY_AUTO_APPLY_CHEATS = "auto_apply_cheats"
        const val KEY_SEEN_CHEATS_ONBOARDING = "seen_cheats_onboarding"
        const val KEY_CHEAT_SECTION_ORDER = "cheat_section_order"
        val DEFAULT_WHEEL_ORDER: List<Int> = (0..7).toList()
        val GAME_KEYS = listOf("iv", "v")
        // Mirrors CheatCategory.entries by name. Kept here (instead of
        // referencing the ui.cheats package) so the data layer has no
        // dependency on the UI; CheatCategoryOrderTest asserts the
        // sets stay in sync.
        val KNOWN_CHEAT_CATEGORIES: Set<String> = setOf(
            "Health", "Wanted", "Weapons", "Vehicle",
            "Paint", "World", "Toggles",
        )
    }
}
