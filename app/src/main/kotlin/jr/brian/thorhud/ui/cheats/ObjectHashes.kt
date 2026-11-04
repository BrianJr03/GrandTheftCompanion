package jr.brian.thorhud.ui.cheats

import jr.brian.thorhud.ui.v.vJoaat

/**
 * Curated GTA V prop / world-object catalog for the Trainer object
 * spawner. Short on purpose; the full NativeTrainer object table has
 * hundreds of entries but most are scenery that never surfaces
 * meaningfully in freeroam. This list focuses on the "player wants
 * to drop a thing in front of themselves" set: ramps, barriers,
 * barrels, boxes, vending machines, a few animals.
 *
 * Wire format mirrors the vehicle library: Kotlin joaats the name,
 * sends the hash in a spawn_object command, and the plugin arms the
 * existing spawn state machine with a new kSpawnObject kind that
 * routes through CREATE_OBJECT instead of CREATE_VEHICLE.
 */
internal object ObjectHashes {

    internal enum class Category(val label: String) {
        Ramp("ramp"),
        Barrier("barrier"),
        Container("container"),
        Prop("prop"),
        Vending("vending"),
        Animal("animal"),
    }

    internal data class Entry(
        val name: String,
        val label: String,
        val category: Category,
    ) {
        val hash: Long = vJoaat(name)
    }

    internal val ALL: List<Entry> = listOf(
        // -- Ramps ---------------------------------------------------
        Entry("stt_prop_stunt_soccer_ball", "Soccer Ball",
              Category.Ramp),
        Entry("stt_prop_ramp_jump_m",       "Ramp (medium)", Category.Ramp),
        Entry("stt_prop_ramp_jump_l",       "Ramp (large)",  Category.Ramp),
        Entry("stt_prop_ramp_jump_xl",      "Ramp (XL)",     Category.Ramp),
        Entry("stt_prop_ramp_jump_xxl",     "Ramp (XXL)",    Category.Ramp),
        Entry("stt_prop_ramp_adj_hump",     "Speed Bump",    Category.Ramp),
        Entry("stt_prop_ramp_adj_m",        "Adj Ramp M",    Category.Ramp),
        Entry("stt_prop_ramp_adj_l",        "Adj Ramp L",    Category.Ramp),
        Entry("stt_prop_ramp_adj_xl",       "Adj Ramp XL",   Category.Ramp),
        Entry("stt_prop_ramp_adj_xxl",      "Adj Ramp XXL",  Category.Ramp),

        // -- Barriers -----------------------------------------------
        Entry("prop_barrier_work01a",       "Jersey Barrier",
              Category.Barrier),
        Entry("prop_barrier_work02a",       "Concrete Barrier",
              Category.Barrier),
        Entry("prop_barrier_work04a",       "Water Barrier", Category.Barrier),
        Entry("prop_barrier_work05",        "Road Barrier",  Category.Barrier),
        Entry("prop_barrier_wat_03a",       "Water Barrier 3",
              Category.Barrier),
        Entry("prop_mp_barrier_01",         "MP Barrier",    Category.Barrier),
        Entry("prop_mp_barrier_02",         "MP Barrier 2",  Category.Barrier),
        Entry("prop_fnc_metal_02",          "Metal Fence",   Category.Barrier),

        // -- Containers / boxes --------------------------------------
        Entry("prop_barrel_01a",            "Oil Barrel",    Category.Container),
        Entry("prop_barrel_02a",            "Blue Barrel",   Category.Container),
        Entry("prop_barrel_exp_01a",        "Explosive Barrel",
              Category.Container),
        Entry("prop_box_wood01a",           "Wooden Crate",  Category.Container),
        Entry("prop_box_wood02a",           "Wooden Box",    Category.Container),
        Entry("prop_boxpile_07d",           "Box Pile",      Category.Container),
        Entry("prop_container_01a",         "Shipping Container",
              Category.Container),
        Entry("prop_cs_dumpster_01a",       "Dumpster",      Category.Container),
        Entry("prop_bin_01a",               "Trash Can",     Category.Container),
        Entry("prop_mb_crate_01a",          "Military Crate",
              Category.Container),

        // -- Random props --------------------------------------------
        Entry("prop_beach_chair_01",        "Beach Chair",   Category.Prop),
        Entry("prop_chair_01a",             "Office Chair",  Category.Prop),
        Entry("prop_table_03",              "Plain Table",   Category.Prop),
        Entry("prop_para_bag_01",           "Parachute Bag", Category.Prop),
        Entry("prop_tyre01",                "Tyre",          Category.Prop),
        Entry("prop_cone_01a",              "Traffic Cone",  Category.Prop),
        Entry("prop_boombox_01",            "Boombox",       Category.Prop),

        // -- Vending -------------------------------------------------
        Entry("prop_vend_soda_01",          "Sprunk Vending",
              Category.Vending),
        Entry("prop_vend_soda_02",          "eCola Vending", Category.Vending),
        Entry("prop_vend_snak_01",          "Snack Vending", Category.Vending),
        Entry("prop_vend_water_01",         "Water Vending", Category.Vending),
        Entry("prop_atm_01",                "ATM",           Category.Vending),
        Entry("prop_atm_02",                "Fleeca ATM",    Category.Vending),

        // -- Animals (as objects; no AI, just the model) -------------
        // Note: many animal "props" are actually peds; the hashes
        // below are the object variants safe for CREATE_OBJECT.
        Entry("prop_rub_cardpile_04",       "Cardboard Pile",
              Category.Animal),
        Entry("prop_rub_rubbish_04",        "Rubbish Pile",  Category.Animal),
    )

    /** Pre-grouped by category, order-preserving. */
    internal val BY_CATEGORY: Map<Category, List<Entry>> =
        ALL.groupBy { it.category }
}
