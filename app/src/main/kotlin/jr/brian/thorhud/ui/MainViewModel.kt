package jr.brian.thorhud.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.net.GameStateRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    repository: GameStateRepository,
) : ViewModel() {
    val hello: StateFlow<GameState?> = repository.hello
    val last: StateFlow<GameState> = repository.last
}
