package be.cameratv.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Le Modèle du MVC : il détient l'état et notifie ses observateurs (les vues) à chaque changement.
 * Seul le contrôleur le modifie.
 */
class AppModel(initial: AppState = AppState()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun update(transform: (AppState) -> AppState) = _state.update(transform)
}
