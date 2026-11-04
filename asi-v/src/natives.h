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
    IS_VEHICLE_SIREN_ON                = 0x4C9BF537BE2634B2,  // BOOL (Vehicle)
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

    // Trainer cheats. Hashes verified against alloc8or/gta5-nativedb-
    // data (natives.json and natives_gen9.json carry the same original
    // hash). Ragdoll re-fires on every tick while the toggle is on,
    // keeping the ped falling until the player turns it off; invisible
    // / invincible vehicle stay in whatever state the native was last
    // called with, so apply_toggle writes them on every tick to resist
    // game-side resets (character switch, save-load). SET_PED_INFINITE_
    // AMMO is called per weapon group that the ped carries, mirroring
    // NativeTrainer's loop.
    SET_PED_TO_RAGDOLL                     = 0xAE99FB955581844A,  // BOOL (Ped, int time1, int time2, int ragdollType, BOOL p4, BOOL p5, BOOL p6)
    SET_ENTITY_VISIBLE                     = 0xEA1C610A04DB6BBB,  // void (Entity, BOOL toggle, BOOL p2)
    SET_ENTITY_INVINCIBLE                  = 0x3882114BDE571AD4,  // void (Entity, BOOL toggle)
    SET_PED_INFINITE_AMMO                  = 0x9F7794730795E019,  // void (Ped, BOOL toggle, Hash weaponHash)
    CLEAR_AREA                             = 0xA56F01F3765B93A0,  // void (float x, float y, float z, float radius, BOOL p4, BOOL ignoreCopCars, BOOL ignoreObjects, BOOL p7)
    CREATE_PED                             = 0xD49F9B0955C367DE,  // Ped (int pedType, Hash modelHash, float x, float y, float z, float heading, BOOL isNetwork, BOOL bScriptHostPed)
    SET_PED_AS_ENEMY                       = 0x02A0C9720B854BFA,  // void (Ped, BOOL toggle)
    SET_PED_AS_NO_LONGER_NEEDED            = 0x2595DD4236549CE3,  // void (Ped*)
    GET_GAMEPLAY_CAM_COORD                 = 0xA200EB1EE790F448,  // Vector3 ()
    GET_GAMEPLAY_CAM_ROT                   = 0x837765A25378F0BB,  // Vector3 (int rotationOrder)

    // Trainer phase 5: objects + ped animations.
    CREATE_OBJECT                          = 0x509D5878EB39E842,  // Object (Hash modelHash, float x, float y, float z, BOOL isNetwork, BOOL bScriptHostObj, BOOL dynamic)
    SET_OBJECT_AS_NO_LONGER_NEEDED         = 0x3AE22DEB5BA5A3E6,  // void (Object*)
    REQUEST_ANIM_DICT                      = 0xD3BD40951412FEF6,  // void (const char* animDict)
    HAS_ANIM_DICT_LOADED                   = 0xD031A9162D01088C,  // BOOL (const char* animDict)
    REMOVE_ANIM_DICT                       = 0xF66A602F829E2A06,  // void (const char* animDict)
    TASK_PLAY_ANIM                         = 0xEA47FE3719165B94,  // void (Ped, const char* animDict, const char* animName, float speed, float speedMultiplier, int duration, int flag, float playbackRate, BOOL lockX, BOOL lockY, BOOL lockZ)
    CLEAR_PED_TASKS                        = 0xE1EF3C1216AFF2CD,  // void (Ped)

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

    // Weapons - armory (Ammu-Nation on the companion). Hashes verified
    // against alloc8or/gta5-nativedb-data commit
    // 424fb51b089049a9fbcebcc641500b1d44d255b4 on 2026-10-25, both
    // natives.json and natives_gen9.json; name, hash and parameter list
    // identical in both. The DLC_WEAPON_* pair is how the shop's own
    // menu discovers "what parts does this weapon take" — the plugin
    // reuses that list rather than carrying its own.
    GIVE_WEAPON_COMPONENT_TO_PED        = 0xD966D51AA5B28BB9,  // void (Ped, Hash weaponHash, Hash componentHash)
    REMOVE_WEAPON_COMPONENT_FROM_PED    = 0x1E8BE90C74FB4C09,  // void (Ped, Hash weaponHash, Hash componentHash)
    HAS_PED_GOT_WEAPON_COMPONENT        = 0xC593212475FAE340,  // BOOL (Ped, Hash weaponHash, Hash componentHash)
    DOES_WEAPON_TAKE_WEAPON_COMPONENT   = 0x5CEE3DF569CECAB0,  // BOOL (Hash weaponHash, Hash componentHash)
    GET_PED_WEAPON_TINT_INDEX           = 0x2B9EEDC07BD06B9F,  // int (Ped, Hash weaponHash)
    SET_PED_WEAPON_TINT_INDEX           = 0x50969B9B89ED5738,  // void (Ped, Hash weaponHash, int tintIndex)
    GET_WEAPON_TINT_COUNT                = 0x5DCF6C5CAB2E9BF7, // int (Hash weaponHash)
    GET_NUM_DLC_WEAPONS                 = 0xEE47635F352DA367,  // int ()
    GET_DLC_WEAPON_DATA                 = 0x79923CD21BECE14E,  // BOOL (int dlcWeaponIndex, Any* outData, 312-byte struct)
    GET_NUM_DLC_WEAPON_COMPONENTS       = 0x405425358A7D61FE,  // int (int dlcWeaponIndex)
    GET_DLC_WEAPON_COMPONENT_DATA       = 0x6CF598A2957C2BF8,  // BOOL (int dlcWeaponIndex, int dlcWeapCompIndex, Any* componentDataPtr, 176-byte struct)
    GET_WEAPON_CLIP_SIZE                = 0x583BE370B1EC6EB4,  // int (Hash weaponHash)
    ADD_AMMO_TO_PED                     = 0x78F0424C34306220,  // void (Ped, Hash weaponHash, int ammo)

    // Ped components + props (Wardrobe on the companion). Hashes
    // verified against alloc8or/gta5-nativedb-data commit
    // 424fb51b089049a9fbcebcc641500b1d44d255b4 on 2026-11-03, both
    // natives.json and natives_gen9.json; name, hash and parameter list
    // identical in both. v1 drives clothing on the current player ped
    // only — a walker reads the per-slot/drawable counts (gated on
    // non-zero so a slot the model doesn't use costs no frame budget)
    // and set_piece writes one component or prop at a time.
    //
    // Component slots are 0..11; props are 0, 1, 2, 6, 7 (hat, glasses,
    // ears, watch, bracelet). SP protagonists hide most props when
    // nothing is fitted (prop index -1).
    SET_PED_COMPONENT_VARIATION             = 0x262B14F48D29DE80,  // void (Ped, int componentId, int drawableId, int textureId, int paletteId)
    GET_PED_DRAWABLE_VARIATION              = 0x67F3780DD425D4FC,  // int (Ped, int componentId)
    GET_PED_TEXTURE_VARIATION               = 0x04A355E041E004E6,  // int (Ped, int componentId)
    GET_PED_PALETTE_VARIATION               = 0xE3DD5F2A84B42281,  // int (Ped, int componentId)
    GET_NUMBER_OF_PED_DRAWABLE_VARIATIONS   = 0x27561561732A7842,  // int (Ped, int componentId)
    GET_NUMBER_OF_PED_TEXTURE_VARIATIONS    = 0x8F7156A3142A6BAD,  // int (Ped, int componentId, int drawableId)
    IS_PED_COMPONENT_VARIATION_VALID        = 0xE825F6B6CEA7671D,  // BOOL (Ped, int componentId, int drawableId, int textureId)
    SET_PED_DEFAULT_COMPONENT_VARIATION     = 0x45EEE61580806D63,  // void (Ped)
    SET_PED_PROP_INDEX                      = 0x93376B65A266EB5F,  // void (Ped, int componentId, int drawableId, int textureId, BOOL attach, Any p5)
    GET_PED_PROP_INDEX                      = 0x898CC20EA75BACD8,  // int (Ped, int componentId, Any p2)
    GET_PED_PROP_TEXTURE_INDEX              = 0xE131A28626F81AB2,  // int (Ped, int componentId)
    CLEAR_PED_PROP                          = 0x0943E5B8E078E76E,  // void (Ped, int propId, Any p2)
    CLEAR_ALL_PED_PROPS                     = 0xCD8A7537A9B52F06,  // void (Ped, Any p1)
    GET_NUMBER_OF_PED_PROP_DRAWABLE_VARIATIONS = 0x5FAF9754E789FB47,  // int (Ped, int propId)
    GET_NUMBER_OF_PED_PROP_TEXTURE_VARIATIONS  = 0xA6E7F1CEB523E171,  // int (Ped, int propId, int drawableId)
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
