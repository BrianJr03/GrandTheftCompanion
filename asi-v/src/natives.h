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

    // Cheat writes. Hashes verified against NativeDB (alloc8or/gta5-
    // nativedb-data; natives.json and natives_gen9.json carry the same
    // original hash). Reads of GET_ENTITY_MAX_HEALTH / GET_PLAYER_MAX_
    // ARMOUR / GET_PLAYER_WANTED_LEVEL / GET_VEHICLE_PED_IS_IN / HAS_
    // PED_GOT_WEAPON / GET_ENTITY_COORDS reused from above.
    SET_ENTITY_HEALTH                      = 0x6B76DC1F3AE6E6A3,  // void (Entity, int, Entity, Hash)
    SET_PED_ARMOUR                         = 0xCEA04D83135264CC,  // void (Ped, int)
    SET_PLAYER_WANTED_LEVEL                = 0x39FF19C64EF7DA5B,  // void (Player, int, BOOL)
    SET_PLAYER_WANTED_LEVEL_NOW            = 0xE0A7D1E497FFCD6F,  // void (Player, BOOL)
    CLEAR_PLAYER_WANTED_LEVEL              = 0xB302540597885499,  // void (Player)
    GIVE_WEAPON_TO_PED                     = 0xBF0FD6E56C964FCB,  // void (Ped, Hash, int, BOOL, BOOL)
    SET_PED_AMMO                           = 0x14E56BC5B5DB6A19,  // void (Ped, Hash, int, BOOL)
    GET_MAX_AMMO                           = 0xDC16122C7A20C933,  // BOOL (Ped, Hash, int*)
    SET_PED_INFINITE_AMMO_CLIP             = 0x183DADC6AA953186,  // void (Ped, BOOL)
    SET_VEHICLE_FIXED                      = 0x115722B1B9C14C1C,  // void (Vehicle)
    SET_VEHICLE_ENGINE_HEALTH              = 0x45F6D8EEF34ABEF1,  // void (Vehicle, float)
    SET_VEHICLE_BODY_HEALTH                = 0xB77D05AC8C78AADB,  // void (Vehicle, float)
    SET_VEHICLE_DIRT_LEVEL                 = 0x79D3B596FE44EE8B,  // void (Vehicle, float)
    SET_WEATHER_TYPE_NOW_PERSIST           = 0xED712CA327900C8A,  // void (const char*)
    CLEAR_WEATHER_TYPE_PERSIST             = 0xCCC39339BEF76CF5,  // void ()
    GET_CLOCK_HOURS                        = 0x25223CA6B4D20B7F,  // int ()
    SET_CLOCK_TIME                         = 0x47C3B5848C3E45D8,  // void (int, int, int)
    SPECIAL_ABILITY_FILL_METER             = 0x3DACA8DDC6FD4980,  // void (Player, BOOL, Any)
    IS_MODEL_IN_CDIMAGE                    = 0x35B9E0803292B641,  // BOOL (Hash)
    IS_MODEL_A_VEHICLE                     = 0x19AAC8F07BFEC53E,  // BOOL (Hash)
    REQUEST_MODEL                          = 0x963D27A58DF860AC,  // void (Hash)
    HAS_MODEL_LOADED                       = 0x98A4EB5D89A0C952,  // BOOL (Hash)
    SET_MODEL_AS_NO_LONGER_NEEDED          = 0xE532F5D78798DAAB,  // void (Hash)
    GET_ENTITY_HEADING                     = 0xE83D4F9BA2A38914,  // float (Entity)
    CREATE_VEHICLE                         = 0xAF35D0D2583051B0,  // Vehicle (Hash, float, float, float, float, BOOL, BOOL, BOOL)
    SET_VEHICLE_ON_GROUND_PROPERLY         = 0x49733E92263139D1,  // BOOL (Vehicle, float)
    SET_PED_INTO_VEHICLE                   = 0xF75B0D629E1C063D,  // void (Ped, Vehicle, int)
    SET_VEHICLE_AS_NO_LONGER_NEEDED        = 0x629BFA74418D6239,  // void (Vehicle*)
    SET_PLAYER_INVINCIBLE_BUT_HAS_REACTIONS = 0x6BC97F4F4BB3C04B, // void (Player, BOOL)
    SET_RUN_SPRINT_MULTIPLIER_FOR_PLAYER   = 0x6DB47AA77FD94E09,  // void (Player, float)
    SET_GRAVITY_LEVEL                      = 0x740E14FAD5842351,  // void (int 0..3)
    SET_TIME_SCALE                         = 0x1D408577D440E81E,  // void (float)
    SET_POLICE_IGNORE_PLAYER               = 0x32C62AA929C2DA6A,  // void (Player, BOOL)
    STAT_SET_CHEAT_IS_ACTIVE               = 0x047CBED6F6F8B63C,  // void ()

    // Phase 2 cheats (phone-code equivalents the companion exposes
    // as taps). The "this frame" group needs its native called every
    // game frame to feel smooth; cheats::needs_frame_wait() flips the
    // script fiber from script_wait(40 ms) to script_wait(0) while
    // any of their mask bits is set.
    SET_PED_IS_DRUNK                       = 0x95D2D383D5396B8A,  // void (Ped, BOOL)
    SET_SWIM_MULTIPLIER_FOR_PLAYER         = 0xA91C6F0FF7D16A13,  // void (Player, float)
    SET_SUPER_JUMP_THIS_FRAME              = 0x57FFF03E423A4C0B,  // void (Player)
    SET_EXPLOSIVE_AMMO_THIS_FRAME          = 0xA66C71C98D5F2CFB,  // void (Player)
    SET_EXPLOSIVE_MELEE_THIS_FRAME         = 0xFF1BED81BFDC0FE0,  // void (Player)
    SET_FIRE_AMMO_THIS_FRAME               = 0x11879CDD803D30F4,  // void (Player)
    SET_VEHICLE_REDUCE_GRIP                = 0x222FF6A823D122E2,  // void (Vehicle, BOOL)
    // Four-arg y/n flags; passing BOOLs is enough for a teleport.
    SET_ENTITY_COORDS                      = 0x621873ECE1178967,  // void (Entity, float, float, float, BOOL, BOOL, BOOL, BOOL)

    // Paint. Palette-index pair + two RGB override layers. Hashes
    // verified against alloc8or/gta5-nativedb-data (natives.json and
    // natives_gen9.json carry the same original hash). CLEAR_ natives
    // run before every index write because an RGB override on a layer
    // hides SET_VEHICLE_COLOURS for that layer; the clear is cheap and
    // harmless when there was no override.
    GET_VEHICLE_COLOURS                       = 0xA19435F193E081AC,  // void (Vehicle, int* c1, int* c2)
    SET_VEHICLE_COLOURS                       = 0x4F1D4BE3A7F24601,  // void (Vehicle, int c1, int c2)
    SET_VEHICLE_CUSTOM_PRIMARY_COLOUR         = 0x7141766F91D15BEA,  // void (Vehicle, int r, int g, int b)
    SET_VEHICLE_CUSTOM_SECONDARY_COLOUR       = 0x36CED73BFED89754,  // void (Vehicle, int r, int g, int b)
    CLEAR_VEHICLE_CUSTOM_PRIMARY_COLOUR       = 0x55E1D2758F34E437,  // void (Vehicle)
    CLEAR_VEHICLE_CUSTOM_SECONDARY_COLOUR     = 0x5FFBDEEC3E8E2009,  // void (Vehicle)
    GET_IS_VEHICLE_PRIMARY_COLOUR_CUSTOM      = 0xF095C0405307B21B,  // BOOL (Vehicle)
    GET_IS_VEHICLE_SECONDARY_COLOUR_CUSTOM    = 0x910A32E7AAD2656C,  // BOOL (Vehicle)

    // Vehicle mods (Los Santos Customs). Hashes verified against
    // alloc8or/gta5-nativedb-data (natives.json / natives_gen9.json
    // carry the same original hash). SET_VEHICLE_MOD_KIT must run
    // before any SET_VEHICLE_MOD, GET_NUM_VEHICLE_MODS or
    // GET_VEHICLE_MOD on a vehicle or every mod call silently fails;
    // mods::request() calls it when the catalogue walk starts.
    SET_VEHICLE_MOD_KIT                 = 0x1F2AA07F00B3217A,  // void (Vehicle, int modKit)
    GET_VEHICLE_MOD_KIT                 = 0x6325D1A044AE510D,  // int (Vehicle)
    GET_NUM_VEHICLE_MODS                = 0xE38E9162A2500646,  // int (Vehicle, int modType)
    GET_VEHICLE_MOD                     = 0x772960298DA26FDB,  // int (Vehicle, int modType)
    SET_VEHICLE_MOD                     = 0x6AF0636DDEDCB6DD,  // void (Vehicle, int modType, int modIndex, BOOL customTires)
    REMOVE_VEHICLE_MOD                  = 0x92D619E420858204,  // void (Vehicle, int modType)
    TOGGLE_VEHICLE_MOD                  = 0x2A1F4F37F95BAD08,  // void (Vehicle, int modType, BOOL toggle)
    IS_TOGGLE_MOD_ON                    = 0x84B233A8C8FC8AE7,  // BOOL (Vehicle, int modType)
    GET_MOD_TEXT_LABEL                  = 0x8935624F8C5592CC,  // const char* (Vehicle, int modType, int modValue)
    GET_MOD_SLOT_NAME                   = 0x51F0FEB9F6AE98C0,  // const char* (Vehicle, int modType)
    DOES_TEXT_LABEL_EXIST               = 0xAC09CA973C564252,  // BOOL (const char* gxt)
    SET_VEHICLE_WHEEL_TYPE              = 0x487EB21CC7295BA1,  // void (Vehicle, int wheelType)
    GET_VEHICLE_WHEEL_TYPE              = 0xB3ED1BFB4BE636DC,  // int (Vehicle)
    SET_VEHICLE_WINDOW_TINT             = 0x57C51E6BAD752696,  // void (Vehicle, int tint)
    GET_VEHICLE_WINDOW_TINT             = 0x0EE21293DAD47C95,  // int (Vehicle)
    GET_NUM_VEHICLE_WINDOW_TINTS        = 0x9D1224004B3A6707,  // int ()
    SET_VEHICLE_NUMBER_PLATE_TEXT       = 0x95A88F0B409CDA47,  // void (Vehicle, const char* plateText)
    GET_VEHICLE_NUMBER_PLATE_TEXT       = 0x7CE1CCB9B293020E,  // const char* (Vehicle)
    SET_VEHICLE_NUMBER_PLATE_TEXT_INDEX = 0x9088EB5A43FFB0A1,  // void (Vehicle, int plateIndex)
    SET_VEHICLE_NEON_ENABLED            = 0x2AA720E4287BF269,  // void (Vehicle, int index, BOOL toggle)
    GET_VEHICLE_NEON_ENABLED            = 0x8C4B92553E4766A5,  // BOOL (Vehicle, int index)
    SET_VEHICLE_NEON_COLOUR             = 0x8E0A582209A62695,  // void (Vehicle, int r, int g, int b)
    SET_VEHICLE_TYRE_SMOKE_COLOR        = 0xB5BA80F839791C0F,  // void (Vehicle, int r, int g, int b)
    SET_VEHICLE_XENON_LIGHT_COLOR_INDEX = 0xE41033B25D003A07,  // void (Vehicle, int colorIndex)
    SET_VEHICLE_TYRES_CAN_BURST         = 0xEB9DC3C7D8596C46,  // void (Vehicle, BOOL toggle)
    GET_VEHICLE_TYRES_CAN_BURST         = 0x678B9BB8C3F58FEB,  // BOOL (Vehicle)
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
