package be.cameratv.model

/** Écran affiché. Les vues se contentent de dessiner l'écran courant. */
sealed interface Screen {
    data object Setup : Screen
    data object Loading : Screen
    data object Grid : Screen
    data class Fullscreen(val channel: Int) : Screen
}

/** État complet de l'application : la seule chose que les vues observent. */
data class AppState(
    val screen: Screen = Screen.Loading,
    val config: NvrConfig? = null,
    val cameras: List<Camera> = emptyList(),
    /** Index (dans [cameras]) de la tuile qui a le focus dans la grille. */
    val focusedIndex: Int = 0,
    /** Plein écran uniquement : les touches pilotent la caméra motorisée au lieu de changer de caméra. */
    val ptzMode: Boolean = false,
    val error: String? = null,
) {
    /** Colonnes de la grille : 1 caméra = 1, jusqu'à 4 = 2, au-delà = 3 (8 canaux max). */
    val gridColumns: Int
        get() = when {
            cameras.size <= 1 -> 1
            cameras.size <= 4 -> 2
            else -> 3
        }
}
