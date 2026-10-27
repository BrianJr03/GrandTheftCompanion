package jr.brian.thorhud.ui.map.custom

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-game "drop-in folder" the user picked through the Storage Access
 * Framework. One picked folder per game, stored as the string form of a
 * tree Uri. CustomMapStore reads these on refresh to decide whether to
 * sync a user-visible folder (e.g. /sdcard/Download/GTC-IV) into the
 * app-private location its decoders read from.
 *
 * Writing / reading persists synchronously via SharedPreferences — the
 * values are tiny (one Uri string each) and the picker fires only on an
 * explicit user tap so there is no hot-path pressure.
 */
@Singleton
class AtlasFolderPrefs @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _iv = MutableStateFlow(read(CustomMapStore.Game.IV))
    val iv: StateFlow<Uri?> = _iv.asStateFlow()
    private val _v = MutableStateFlow(read(CustomMapStore.Game.V))
    val v: StateFlow<Uri?> = _v.asStateFlow()

    fun uriFor(game: CustomMapStore.Game): Uri? = when (game) {
        CustomMapStore.Game.IV -> _iv.value
        CustomMapStore.Game.V  -> _v.value
    }

    fun set(game: CustomMapStore.Game, uri: Uri?) {
        val key = keyFor(game)
        if (uri == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, uri.toString()).apply()
        }
        when (game) {
            CustomMapStore.Game.IV -> _iv.value = uri
            CustomMapStore.Game.V  -> _v.value  = uri
        }
    }

    private fun read(game: CustomMapStore.Game): Uri? =
        prefs.getString(keyFor(game), null)?.let(Uri::parse)

    private fun keyFor(game: CustomMapStore.Game): String = when (game) {
        CustomMapStore.Game.IV -> KEY_IV
        CustomMapStore.Game.V  -> KEY_V
    }

    companion object {
        private const val FILE = "atlas_folder_prefs"
        private const val KEY_IV = "folder_iv"
        private const val KEY_V  = "folder_v"
    }
}
