#pragma once

#include <cstdint>

// GTA V script-native identifiers used by this plugin.
//
// These are the ORIGINAL (launch-build) hashes that Script Hook V
// accepts and translates to whatever the running game build uses, for
// both the Legacy and Enhanced editions. Every value below was checked
// against the community NativeDB (alloc8or/gta5-nativedb-data,
// natives.json AND natives_gen9.json) — names, hashes and parameter
// lists. Parameter lists are repeated in the trailing comments because
// passing the wrong arity to a native is the classic way to crash.

namespace natives {

enum : uint64_t {
    // PLAYER
    PLAYER_ID                          = 0x4F8644AF03D0E0D6,  // Player ()
    PLAYER_PED_ID                      = 0xD80958FC74E988A6,  // Ped ()
    GET_PLAYER_WANTED_LEVEL            = 0xE28E54788CE8F12D,  // int (Player)
    GET_PLAYER_MAX_ARMOUR              = 0x92659B4CE1863CB3,  // int (Player)
    IS_SPECIAL_ABILITY_UNLOCKED        = 0xC6017F6A6CDFA694,  // BOOL (Hash playerModel)
    IS_SPECIAL_ABILITY_ENABLED         = 0xB1D200FE26AEF3CB,  // BOOL (Player, Any p1)
    IS_SPECIAL_ABILITY_ACTIVE          = 0x3E5F7FC85D854E15,  // BOOL (Player, Any p1)
    IS_SPECIAL_ABILITY_METER_FULL      = 0x05A1FE504B7F2587,  // BOOL (Player, Any p1)

    // ENTITY
    DOES_ENTITY_EXIST                  = 0x7239B21A38F536BA,  // BOOL (Entity)
    GET_ENTITY_HEALTH                  = 0xEEF059FAD016D209,  // int (Entity)
    GET_ENTITY_MAX_HEALTH              = 0x15D757606D170C3C,  // int (Entity)
    GET_ENTITY_MODEL                   = 0x9F47B058362C84B5,  // Hash (Entity)
    GET_ENTITY_SPEED                   = 0xD5037BA82E12416F,  // float (Entity), m/s
    GET_ENTITY_HEIGHT_ABOVE_GROUND     = 0x1DD55701034110E5,  // float (Entity), m
    GET_ENTITY_COORDS                  = 0x3FEF770D40960D5A,  // Vector3 (Entity, BOOL alive)

    // PED
    GET_PED_ARMOUR                     = 0x9483AF821605B1D8,  // int (Ped)
    IS_PED_IN_ANY_VEHICLE              = 0x997ABD671D25CA0B,  // BOOL (Ped, BOOL atGetIn)
    GET_VEHICLE_PED_IS_IN              = 0x9A9112A0FE9A4713,  // Vehicle (Ped, BOOL includeEntering)

    // WEAPON
    GET_SELECTED_PED_WEAPON            = 0x0A6DB4965674D243,  // Hash (Ped)
    GET_AMMO_IN_PED_WEAPON             = 0x015A522136D7F951,  // int (Ped, Hash)
    GET_AMMO_IN_CLIP                   = 0x2E1202248937775C,  // BOOL (Ped, Hash, int* ammo)
    GET_MAX_AMMO_IN_CLIP               = 0xA38DCFFCEA8962FA,  // int (Ped, Hash, BOOL p2)
    HAS_PED_GOT_WEAPON                 = 0x8DECB02F88F428BC,  // BOOL (Ped, Hash, BOOL p2)
    GET_WEAPONTYPE_GROUP               = 0xC3287EE3050FB74C,  // Hash (Hash weapon)
    SET_CURRENT_PED_WEAPON             = 0xADF692B254977C0C,  // void (Ped, Hash, BOOL forceInHand)

    // VEHICLE
    GET_VEHICLE_CLASS                  = 0x29439776AAA00A62,  // int (Vehicle)
    GET_VEHICLE_ENGINE_HEALTH          = 0xC45D23BAF168AAB8,  // float (Vehicle), -4000..1000
    GET_VEHICLE_BODY_HEALTH            = 0xF271147EB7B40F12,  // float (Vehicle), 0..1000
    GET_DISPLAY_NAME_FROM_VEHICLE_MODEL = 0xB215AAC32D25D019, // const char* (Hash model) -> GXT key

    // PAD
    // Answers false while the game itself has the control disabled
    // (cutscenes, scripted sequences), which is what the weapon-wheel
    // flag wants: it should only light up when the game would react.
    IS_CONTROL_PRESSED                 = 0xF3A21BCD95725A4A,  // BOOL (int control, int action)
    IS_CONTROL_JUST_PRESSED            = 0x580417101DDB492F,  // BOOL (int control, int action)
    IS_DISABLED_CONTROL_PRESSED        = 0xE2587F8CBBD87B1D,  // BOOL (int control, int action)

    // HUD
    // NativeDB's current name for what the SDK calls _GET_LABEL_TEXT:
    // resolves a GXT key ("ADDER", "RADIO_01_CLASS_ROCK") to the
    // localised display string; returns "NULL" for unknown keys.
    GET_LABEL_TEXT                     = 0x7B5280EBA9840C72,  // const char* (const char* key)
    IS_PAUSE_MENU_ACTIVE               = 0xB0034A223497FFCB,  // BOOL ()

    // HUD / blips. The map's icons are blips; the pair of FIRST/NEXT
    // natives walks every blip carrying one sprite id, so a census of
    // the whole map is one walk per sprite id. A blip handle is only
    // meaningful while DOES_BLIP_EXIST.
    GET_FIRST_BLIP_INFO_ID             = 0x1BEDE233E6CD2A1F,  // Blip (int blipSprite)
    GET_NEXT_BLIP_INFO_ID              = 0x14F96AA50D6FBEA7,  // Blip (int blipSprite)
    DOES_BLIP_EXIST                    = 0xA6DB27D19ECBB7DA,  // BOOL (Blip)
    GET_BLIP_INFO_ID_COORD             = 0xFA7C7F0AADF25D09,  // Vector3 (Blip)
    GET_BLIP_INFO_ID_DISPLAY           = 0x1E314167F701DC3B,  // int (Blip)
    GET_BLIP_INFO_ID_TYPE              = 0xBE9B0959FFD0779B,  // int (Blip): 1 veh 2 ped 3 obj 4 coord 6 pickup 7 radius
    GET_BLIP_SPRITE                    = 0x1FC877464A04FC4F,  // int (Blip)
    GET_BLIP_COLOUR                    = 0xDF729E8D20CF7327,  // int (Blip)
    // The player's own map waypoint. SET_NEW_WAYPOINT is what the pause
    // map does on "set waypoint": the GPS route follows at once.
    GET_WAYPOINT_BLIP_ENUM_ID          = 0x186E5D252FA50E7D,  // int ()  sprite id of the waypoint blip
    IS_WAYPOINT_ACTIVE                 = 0x1DD1F58F493F1DA5,  // BOOL ()
    SET_NEW_WAYPOINT                   = 0xFE43368D2AA4F2FC,  // void (float x, float y)
    SET_WAYPOINT_OFF                   = 0xA7E4E2D361C2627F,  // void ()

    // AUDIO
    GET_PLAYER_RADIO_STATION_INDEX     = 0xE8AF77C4C06ADC93,  // int (), 255 = off
    GET_PLAYER_RADIO_STATION_NAME      = 0xF6D733C32076AD03,  // const char* ()
    GET_RADIO_STATION_NAME             = 0xB28ECA15046CA8B9,  // const char* (int index)
    GET_NUM_UNLOCKED_RADIO_STATIONS    = 0xF1620ECB50E01DE7,  // int ()
    DOES_PLAYER_VEH_HAVE_RADIO         = 0x109697E2FFBAC8A1,  // BOOL ()
    SET_RADIO_TO_STATION_NAME          = 0xC69EDA28699D5107,  // void (const char* name)

    // STATS
    STAT_GET_INT                       = 0x767FBC2AC802EF3D,  // BOOL (Hash stat, int* out, int p2)

    // STREAMING / CUTSCENE / DLC / MISC / NETWORK
    IS_PLAYER_SWITCH_IN_PROGRESS       = 0xD9D2CFFF49FAB35F,  // BOOL ()
    IS_CUTSCENE_PLAYING                = 0xD3C2E180A40F031E,  // BOOL ()
    GET_IS_LOADING_SCREEN_ACTIVE       = 0x10D0A8F259E93EC9,  // BOOL ()
    GET_IS_PLAYER_IN_ANIMAL_FORM       = 0x9689123E3F213AA5,  // BOOL ()
    GET_MISSION_FLAG                   = 0xA33CDCCDA663159E,  // BOOL ()
    NETWORK_IS_SESSION_STARTED         = 0x9DE624D2FC4B603F,  // BOOL ()
};

// IS_CONTROL_PRESSED arguments. `control` is the input group, `action`
// the control index. INPUT_SELECT_WEAPON is the hold-for-weapon-wheel
// control: LB / L1 on a pad, Tab on a keyboard, and it follows whatever
// the player rebound it to.
enum Control : int32_t {
    kPlayerControl      = 0,
    kInputSelectWeapon  = 37,
    // Dpad arrows via the cellphone-nav input group. On-foot the four
    // controls map 1:1 to the physical dpad arrows. In a vehicle V
    // aliases CELLPHONE_DOWN (174) to the physical dpad-LEFT button
    // (because dpad-LEFT is "previous radio station"), so the dpad
    // read in read_dpad_down() is gated to on-foot only. The script-
    // pad group (213..216) looks like the right fit but on this device
    // the game consumes the input before any script sees it.
    kInputCellphoneUp    = 173,
    kInputCellphoneDown  = 174,
    kInputCellphoneLeft  = 175,
    kInputCellphoneRight = 176,
};

// GET_VEHICLE_CLASS values the HUD cares about.
enum VehicleClass : int32_t {
    kClassMotorcycles = 8,
    kClassCycles      = 13,
    kClassBoats       = 14,
    kClassHelicopters = 15,
    kClassPlanes      = 16,
    kClassTrains      = 21,
};

}  // namespace natives
