package jr.brian.thorhud.ui.v

/**
 * Display names for GTA V weapons, keyed by the game's internal weapon
 * name as sent by ThorBridgeV.asi (`weapon_name`, e.g. "weapon_pistol").
 *
 * GTA IV numbers its ~20 weapons 0..20, so the IV HUD indexes a string
 * array. GTA V identifies ~100 weapons by 32-bit name hash and has no
 * script call that turns a hash back into text, so the plugin sends the
 * internal name and this table supplies the wording. Anything missing
 * here still renders: [vWeaponLabel] falls back to a tidied-up form of
 * the internal name, then to the raw hash.
 *
 * Lower-case to match the GTA IV panel's styling.
 */
private val WEAPON_LABELS: Map<String, String> = mapOf(
    "weapon_unarmed" to "unarmed",
    // melee
    "weapon_knife" to "knife",
    "weapon_nightstick" to "nightstick",
    "weapon_hammer" to "hammer",
    "weapon_bat" to "baseball bat",
    "weapon_golfclub" to "golf club",
    "weapon_crowbar" to "crowbar",
    "weapon_bottle" to "broken bottle",
    "weapon_dagger" to "antique dagger",
    "weapon_hatchet" to "hatchet",
    "weapon_knuckle" to "knuckle dusters",
    "weapon_machete" to "machete",
    "weapon_flashlight" to "flashlight",
    "weapon_switchblade" to "switchblade",
    "weapon_poolcue" to "pool cue",
    "weapon_wrench" to "pipe wrench",
    "weapon_battleaxe" to "battle axe",
    "weapon_stone_hatchet" to "stone hatchet",
    "weapon_candycane" to "candy cane",
    // handguns
    "weapon_pistol" to "pistol",
    "weapon_pistol_mk2" to "pistol mk II",
    "weapon_combatpistol" to "combat pistol",
    "weapon_appistol" to "AP pistol",
    "weapon_pistol50" to "pistol .50",
    "weapon_snspistol" to "SNS pistol",
    "weapon_snspistol_mk2" to "SNS pistol mk II",
    "weapon_heavypistol" to "heavy pistol",
    "weapon_vintagepistol" to "vintage pistol",
    "weapon_marksmanpistol" to "marksman pistol",
    "weapon_revolver" to "heavy revolver",
    "weapon_revolver_mk2" to "heavy revolver mk II",
    "weapon_doubleaction" to "double-action revolver",
    "weapon_stungun" to "stun gun",
    "weapon_flaregun" to "flare gun",
    "weapon_raypistol" to "Up-n-Atomizer",
    "weapon_ceramicpistol" to "ceramic pistol",
    "weapon_navyrevolver" to "navy revolver",
    "weapon_gadgetpistol" to "perico pistol",
    "weapon_pistolxm3" to "WM 29 pistol",
    "weapon_tecpistol" to "tactical SMG",
    // SMGs / MGs
    "weapon_microsmg" to "micro SMG",
    "weapon_smg" to "SMG",
    "weapon_smg_mk2" to "SMG mk II",
    "weapon_assaultsmg" to "assault SMG",
    "weapon_combatpdw" to "combat PDW",
    "weapon_machinepistol" to "machine pistol",
    "weapon_minismg" to "mini SMG",
    "weapon_raycarbine" to "Unholy Hellbringer",
    "weapon_mg" to "MG",
    "weapon_combatmg" to "combat MG",
    "weapon_combatmg_mk2" to "combat MG mk II",
    "weapon_gusenberg" to "Gusenberg sweeper",
    // rifles
    "weapon_assaultrifle" to "assault rifle",
    "weapon_assaultrifle_mk2" to "assault rifle mk II",
    "weapon_carbinerifle" to "carbine rifle",
    "weapon_carbinerifle_mk2" to "carbine rifle mk II",
    "weapon_advancedrifle" to "advanced rifle",
    "weapon_specialcarbine" to "special carbine",
    "weapon_specialcarbine_mk2" to "special carbine mk II",
    "weapon_bullpuprifle" to "bullpup rifle",
    "weapon_bullpuprifle_mk2" to "bullpup rifle mk II",
    "weapon_compactrifle" to "compact rifle",
    "weapon_militaryrifle" to "military rifle",
    "weapon_heavyrifle" to "heavy rifle",
    "weapon_tacticalrifle" to "service carbine",
    "weapon_battlerifle" to "battle rifle",
    // shotguns
    "weapon_pumpshotgun" to "pump shotgun",
    "weapon_pumpshotgun_mk2" to "pump shotgun mk II",
    "weapon_sawnoffshotgun" to "sawed-off shotgun",
    "weapon_assaultshotgun" to "assault shotgun",
    "weapon_bullpupshotgun" to "bullpup shotgun",
    "weapon_musket" to "musket",
    "weapon_heavyshotgun" to "heavy shotgun",
    "weapon_dbshotgun" to "double barrel shotgun",
    "weapon_autoshotgun" to "sweeper shotgun",
    "weapon_combatshotgun" to "combat shotgun",
    // sniper rifles
    "weapon_sniperrifle" to "sniper rifle",
    "weapon_heavysniper" to "heavy sniper",
    "weapon_heavysniper_mk2" to "heavy sniper mk II",
    "weapon_marksmanrifle" to "marksman rifle",
    "weapon_marksmanrifle_mk2" to "marksman rifle mk II",
    "weapon_precisionrifle" to "precision rifle",
    // heavy
    "weapon_rpg" to "RPG",
    "weapon_grenadelauncher" to "grenade launcher",
    "weapon_grenadelauncher_smoke" to "smoke launcher",
    "weapon_minigun" to "minigun",
    "weapon_firework" to "firework launcher",
    "weapon_railgun" to "railgun",
    "weapon_hominglauncher" to "homing launcher",
    "weapon_compactlauncher" to "compact launcher",
    "weapon_rayminigun" to "Widowmaker",
    "weapon_emplauncher" to "compact EMP launcher",
    "weapon_railgunxm3" to "railgun",
    // thrown
    "weapon_grenade" to "grenade",
    "weapon_bzgas" to "tear gas",
    "weapon_molotov" to "molotov",
    "weapon_stickybomb" to "sticky bomb",
    "weapon_proxmine" to "proximity mine",
    "weapon_snowball" to "snowball",
    "weapon_pipebomb" to "pipe bomb",
    "weapon_ball" to "ball",
    "weapon_smokegrenade" to "smoke grenade",
    "weapon_flare" to "flare",
    // misc
    "weapon_petrolcan" to "jerry can",
    "weapon_fireextinguisher" to "fire extinguisher",
    "weapon_hazardcan" to "hazard can",
    "weapon_fertilizercan" to "fertilizer can",
    "gadget_parachute" to "parachute",
)

/** joaat("weapon_unarmed") as an unsigned value. */
internal const val V_WEAPON_UNARMED_HASH = 0xA2719263L

internal fun vIsUnarmed(name: String?, hash: Long?): Boolean =
    name == "weapon_unarmed" || hash == null || hash == 0L ||
        hash == V_WEAPON_UNARMED_HASH

/**
 * Label for the equipped weapon.
 *  1. known internal name -> table wording
 *  2. unknown internal name -> "weapon_foo_bar" becomes "foo bar"
 *  3. no name (weapon not in the plugin's table) -> the hash, so a bug
 *     report can name exactly which weapon was missing
 */
internal fun vWeaponLabel(name: String?, hash: Long?): String {
    if (vIsUnarmed(name, hash)) return WEAPON_LABELS.getValue("weapon_unarmed")
    if (!name.isNullOrBlank()) {
        WEAPON_LABELS[name]?.let { return it }
        return name.removePrefix("weapon_").removePrefix("gadget_")
            .replace('_', ' ')
    }
    return "weapon 0x%08X".format(hash)
}
