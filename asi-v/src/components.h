#pragma once

#include <cstddef>
#include <cstdint>

#include "joaat.h"

// components:: — Ammu-Nation fallback table for base-game weapons
// GET_DLC_WEAPON_DATA does not cover.
//
// GET_NUM_DLC_WEAPONS lists DLC-added weapons plus a specific shop
// subset; vanilla Pistol / Micro SMG / Carbine Rifle / Pump Shotgun /
// Sniper Rifle / etc. are intentionally outside it, even though they
// take real components in-game. For those, the plugin walks this
// table and filters each entry by DOES_WEAPON_TAKE_WEAPON_COMPONENT
// so a wrong-build entry simply drops out.
//
// Names are the game-internal component ids (joaat'd at compile time
// via joaat.h), so new DLC additions only need adding here, not in
// the plugin's apply path. Display names and category are ours.
// Categories map to the same bone ids the DLC catalogue uses so the
// client's kBoneSlotNames table labels both sources the same way.

namespace components {

enum Category : uint8_t {
    kCatClip       = 0,
    kCatFlashlight = 1,
    kCatScope      = 2,
    kCatMuzzle     = 3,
    kCatGrip       = 4,
    kCatFinish     = 5,
};

// Bone ids chosen to match a representative DLC-catalogue bone for
// each category so the sheet shows "Clip" / "Flashlight" / etc.
// without a parallel app-side lookup.
constexpr uint32_t kBoneForCategory[6] = {
    3723347892u,   // Clip
    679107254u,    // Flashlight
    1684637069u,   // Scope
    1863181664u,   // Muzzle
    2972950469u,   // Grip
    962500902u,    // Finish
};

struct Entry {
    uint32_t    hash;        // joaat of the game-internal component name
    const char* label;       // our display label
    uint8_t     category;
    bool        is_default;  // true for CLIP_01 variants (never removable)
};

#define C(gname, label, cat, def) \
    {joaat(gname), label, static_cast<uint8_t>(cat), def}

// Order matters only for display: default clip first, then non-default
// clips, then attachments by category.

constexpr Entry kPistol[] = {
    C("COMPONENT_PISTOL_CLIP_01",     "Default Clip",       kCatClip,       true),
    C("COMPONENT_PISTOL_CLIP_02",     "Extended Clip",      kCatClip,       false),
    C("COMPONENT_AT_PI_FLSH",         "Flashlight",         kCatFlashlight, false),
    C("COMPONENT_AT_PI_SUPP_02",      "Suppressor",         kCatMuzzle,     false),
    C("COMPONENT_PISTOL_VARMOD_LUXE", "Yusuf Amir Finish",  kCatFinish,     false),
};

constexpr Entry kCombatPistol[] = {
    C("COMPONENT_COMBATPISTOL_CLIP_01",       "Default Clip",      kCatClip,       true),
    C("COMPONENT_COMBATPISTOL_CLIP_02",       "Extended Clip",     kCatClip,       false),
    C("COMPONENT_AT_PI_FLSH",                 "Flashlight",        kCatFlashlight, false),
    C("COMPONENT_AT_PI_SUPP",                 "Suppressor",        kCatMuzzle,     false),
    C("COMPONENT_COMBATPISTOL_VARMOD_LOWRIDER","Yusuf Amir Finish",kCatFinish,     false),
};

constexpr Entry kApPistol[] = {
    C("COMPONENT_APPISTOL_CLIP_01",     "Default Clip",      kCatClip,       true),
    C("COMPONENT_APPISTOL_CLIP_02",     "Extended Clip",     kCatClip,       false),
    C("COMPONENT_AT_PI_FLSH",           "Flashlight",        kCatFlashlight, false),
    C("COMPONENT_AT_PI_SUPP",           "Suppressor",        kCatMuzzle,     false),
    C("COMPONENT_APPISTOL_VARMOD_LUXE", "Yusuf Amir Finish", kCatFinish,     false),
};

constexpr Entry kPistol50[] = {
    C("COMPONENT_PISTOL50_CLIP_01",     "Default Clip",      kCatClip,       true),
    C("COMPONENT_PISTOL50_CLIP_02",     "Extended Clip",     kCatClip,       false),
    C("COMPONENT_AT_PI_FLSH",           "Flashlight",        kCatFlashlight, false),
    C("COMPONENT_AT_AR_SUPP_02",        "Suppressor",        kCatMuzzle,     false),
    C("COMPONENT_PISTOL50_VARMOD_LUXE", "Platinum Finish",   kCatFinish,     false),
};

constexpr Entry kMicroSmg[] = {
    C("COMPONENT_MICROSMG_CLIP_01",     "Default Clip",     kCatClip,       true),
    C("COMPONENT_MICROSMG_CLIP_02",     "Extended Clip",    kCatClip,       false),
    C("COMPONENT_AT_PI_FLSH",           "Flashlight",       kCatFlashlight, false),
    C("COMPONENT_AT_SCOPE_MACRO",       "Scope",            kCatScope,      false),
    C("COMPONENT_AT_AR_SUPP_02",        "Suppressor",       kCatMuzzle,     false),
    C("COMPONENT_MICROSMG_VARMOD_LUXE", "Yusuf Amir Finish",kCatFinish,     false),
};

constexpr Entry kSmg[] = {
    C("COMPONENT_SMG_CLIP_01",     "Default Clip",     kCatClip,       true),
    C("COMPONENT_SMG_CLIP_02",     "Extended Clip",    kCatClip,       false),
    C("COMPONENT_SMG_CLIP_03",     "Drum Magazine",    kCatClip,       false),
    C("COMPONENT_AT_AR_FLSH",      "Flashlight",       kCatFlashlight, false),
    C("COMPONENT_AT_SCOPE_MACRO_02","Scope",           kCatScope,      false),
    C("COMPONENT_AT_PI_SUPP",      "Suppressor",       kCatMuzzle,     false),
    C("COMPONENT_SMG_VARMOD_LUXE", "Yusuf Amir Finish",kCatFinish,     false),
};

constexpr Entry kAssaultSmg[] = {
    C("COMPONENT_ASSAULTSMG_CLIP_01",       "Default Clip",      kCatClip,       true),
    C("COMPONENT_ASSAULTSMG_CLIP_02",       "Extended Clip",     kCatClip,       false),
    C("COMPONENT_AT_AR_FLSH",               "Flashlight",        kCatFlashlight, false),
    C("COMPONENT_AT_SCOPE_MACRO",           "Scope",             kCatScope,      false),
    C("COMPONENT_AT_AR_SUPP",               "Suppressor",        kCatMuzzle,     false),
    C("COMPONENT_ASSAULTSMG_VARMOD_LOWRIDER","Yusuf Amir Finish",kCatFinish,     false),
};

constexpr Entry kAssaultRifle[] = {
    C("COMPONENT_ASSAULTRIFLE_CLIP_01",     "Default Clip",      kCatClip,       true),
    C("COMPONENT_ASSAULTRIFLE_CLIP_02",     "Extended Clip",     kCatClip,       false),
    C("COMPONENT_ASSAULTRIFLE_CLIP_03",     "Box Magazine",      kCatClip,       false),
    C("COMPONENT_AT_AR_FLSH",               "Flashlight",        kCatFlashlight, false),
    C("COMPONENT_AT_AR_AFGRIP",             "Grip",              kCatGrip,       false),
    C("COMPONENT_AT_SCOPE_MACRO",           "Scope",             kCatScope,      false),
    C("COMPONENT_AT_AR_SUPP_02",            "Suppressor",        kCatMuzzle,     false),
    C("COMPONENT_ASSAULTRIFLE_VARMOD_LUXE", "Yusuf Amir Finish", kCatFinish,     false),
};

constexpr Entry kCarbineRifle[] = {
    C("COMPONENT_CARBINERIFLE_CLIP_01",     "Default Clip",      kCatClip,       true),
    C("COMPONENT_CARBINERIFLE_CLIP_02",     "Extended Clip",     kCatClip,       false),
    C("COMPONENT_CARBINERIFLE_CLIP_03",     "Box Magazine",      kCatClip,       false),
    C("COMPONENT_AT_AR_FLSH",               "Flashlight",        kCatFlashlight, false),
    C("COMPONENT_AT_AR_AFGRIP",             "Grip",              kCatGrip,       false),
    C("COMPONENT_AT_SCOPE_MEDIUM",          "Scope",             kCatScope,      false),
    C("COMPONENT_AT_AR_SUPP",               "Suppressor",        kCatMuzzle,     false),
    C("COMPONENT_CARBINERIFLE_VARMOD_LUXE", "Yusuf Amir Finish", kCatFinish,     false),
};

constexpr Entry kAdvancedRifle[] = {
    C("COMPONENT_ADVANCEDRIFLE_CLIP_01",     "Default Clip",      kCatClip,       true),
    C("COMPONENT_ADVANCEDRIFLE_CLIP_02",     "Extended Clip",     kCatClip,       false),
    C("COMPONENT_AT_AR_FLSH",                "Flashlight",        kCatFlashlight, false),
    C("COMPONENT_AT_SCOPE_SMALL",            "Scope",             kCatScope,      false),
    C("COMPONENT_AT_AR_SUPP",                "Suppressor",        kCatMuzzle,     false),
    C("COMPONENT_ADVANCEDRIFLE_VARMOD_LUXE", "Yusuf Amir Finish", kCatFinish,     false),
};

constexpr Entry kCompactRifle[] = {
    C("COMPONENT_COMPACTRIFLE_CLIP_01", "Default Clip",   kCatClip, true),
    C("COMPONENT_COMPACTRIFLE_CLIP_02", "Extended Clip",  kCatClip, false),
    C("COMPONENT_COMPACTRIFLE_CLIP_03", "Drum Magazine",  kCatClip, false),
};

constexpr Entry kMg[] = {
    C("COMPONENT_MG_CLIP_01",         "Default Clip",      kCatClip,       true),
    C("COMPONENT_MG_CLIP_02",         "Extended Clip",     kCatClip,       false),
    C("COMPONENT_AT_SCOPE_SMALL_02",  "Scope",             kCatScope,      false),
    C("COMPONENT_MG_VARMOD_LOWRIDER", "Yusuf Amir Finish", kCatFinish,     false),
};

constexpr Entry kCombatMg[] = {
    C("COMPONENT_COMBATMG_CLIP_01",     "Default Clip",      kCatClip,  true),
    C("COMPONENT_COMBATMG_CLIP_02",     "Extended Clip",     kCatClip,  false),
    C("COMPONENT_AT_AR_AFGRIP",         "Grip",              kCatGrip,  false),
    C("COMPONENT_AT_SCOPE_MEDIUM",      "Scope",             kCatScope, false),
    C("COMPONENT_COMBATMG_VARMOD_LOWRIDER","Yusuf Amir Finish",kCatFinish,false),
};

constexpr Entry kPumpShotgun[] = {
    C("COMPONENT_AT_AR_FLSH",                "Flashlight",        kCatFlashlight, false),
    C("COMPONENT_AT_AR_SUPP",                "Suppressor",        kCatMuzzle,     false),
    C("COMPONENT_PUMPSHOTGUN_VARMOD_LOWRIDER","Yusuf Amir Finish",kCatFinish,     false),
};

constexpr Entry kSawnoffShotgun[] = {
    C("COMPONENT_SAWNOFFSHOTGUN_VARMOD_LUXE","Yusuf Amir Finish",kCatFinish,false),
};

constexpr Entry kAssaultShotgun[] = {
    C("COMPONENT_ASSAULTSHOTGUN_CLIP_01","Default Clip", kCatClip,       true),
    C("COMPONENT_ASSAULTSHOTGUN_CLIP_02","Extended Clip",kCatClip,       false),
    C("COMPONENT_AT_AR_FLSH",            "Flashlight",   kCatFlashlight, false),
    C("COMPONENT_AT_AR_SUPP",            "Suppressor",   kCatMuzzle,     false),
};

constexpr Entry kBullpupShotgun[] = {
    C("COMPONENT_AT_AR_FLSH", "Flashlight", kCatFlashlight, false),
    C("COMPONENT_AT_AR_SUPP", "Suppressor", kCatMuzzle,     false),
};

constexpr Entry kSniperRifle[] = {
    C("COMPONENT_AT_SCOPE_LARGE",         "Scope",             kCatScope,  false),
    C("COMPONENT_AT_AR_SUPP_02",          "Suppressor",        kCatMuzzle, false),
    C("COMPONENT_SNIPERRIFLE_VARMOD_LUXE","Yusuf Amir Finish", kCatFinish, false),
};

constexpr Entry kHeavySniper[] = {
    C("COMPONENT_AT_SCOPE_LARGE",   "Large Scope", kCatScope, false),
    C("COMPONENT_AT_SCOPE_MAX",     "Advanced Scope", kCatScope, false),
};

#undef C

struct WeaponTable {
    uint32_t     weapon_hash;
    size_t       count;
    const Entry* entries;
};

constexpr WeaponTable kTable[] = {
    {joaat("weapon_pistol"),         sizeof(kPistol)/sizeof(Entry),         kPistol},
    {joaat("weapon_combatpistol"),   sizeof(kCombatPistol)/sizeof(Entry),   kCombatPistol},
    {joaat("weapon_appistol"),       sizeof(kApPistol)/sizeof(Entry),       kApPistol},
    {joaat("weapon_pistol50"),       sizeof(kPistol50)/sizeof(Entry),       kPistol50},
    {joaat("weapon_microsmg"),       sizeof(kMicroSmg)/sizeof(Entry),       kMicroSmg},
    {joaat("weapon_smg"),            sizeof(kSmg)/sizeof(Entry),            kSmg},
    {joaat("weapon_assaultsmg"),     sizeof(kAssaultSmg)/sizeof(Entry),     kAssaultSmg},
    {joaat("weapon_assaultrifle"),   sizeof(kAssaultRifle)/sizeof(Entry),   kAssaultRifle},
    {joaat("weapon_carbinerifle"),   sizeof(kCarbineRifle)/sizeof(Entry),   kCarbineRifle},
    {joaat("weapon_advancedrifle"),  sizeof(kAdvancedRifle)/sizeof(Entry),  kAdvancedRifle},
    {joaat("weapon_compactrifle"),   sizeof(kCompactRifle)/sizeof(Entry),   kCompactRifle},
    {joaat("weapon_mg"),             sizeof(kMg)/sizeof(Entry),             kMg},
    {joaat("weapon_combatmg"),       sizeof(kCombatMg)/sizeof(Entry),       kCombatMg},
    {joaat("weapon_pumpshotgun"),    sizeof(kPumpShotgun)/sizeof(Entry),    kPumpShotgun},
    {joaat("weapon_sawnoffshotgun"), sizeof(kSawnoffShotgun)/sizeof(Entry), kSawnoffShotgun},
    {joaat("weapon_assaultshotgun"), sizeof(kAssaultShotgun)/sizeof(Entry), kAssaultShotgun},
    {joaat("weapon_bullpupshotgun"), sizeof(kBullpupShotgun)/sizeof(Entry), kBullpupShotgun},
    {joaat("weapon_sniperrifle"),    sizeof(kSniperRifle)/sizeof(Entry),    kSniperRifle},
    {joaat("weapon_heavysniper"),    sizeof(kHeavySniper)/sizeof(Entry),    kHeavySniper},
};

constexpr size_t kTableCount = sizeof(kTable) / sizeof(kTable[0]);

inline const WeaponTable* lookup(uint32_t weapon_hash) {
    for (size_t i = 0; i < kTableCount; ++i) {
        if (kTable[i].weapon_hash == weapon_hash) return &kTable[i];
    }
    return nullptr;
}

}  // namespace components
