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
    /** Pilotage par la domotique ; null = désactivé. */
    val mqtt: MqttConfig? = null,
    val mqttConnected: Boolean = false,
    /** Demande de passage en arrière-plan (commande externe) ; la vue l'exécute puis acquitte. */
    val exitRequested: Boolean = false,
    /** L'écran de l'application est visible (sinon la TV affiche une autre application). */
    val uiVisible: Boolean = false,
    /** Une commande externe veut afficher l'application alors qu'elle est en arrière-plan. */
    val foregroundRequested: Boolean = false,
    /** Écran de la TV allumé (false : TV en veille). */
    val screenOn: Boolean = true,
) {
    /** Colonnes de la grille : 1 caméra = 1, jusqu'à 4 = 2, au-delà = 3 (8 canaux max). */
    val gridColumns: Int
        get() = when {
            cameras.size <= 1 -> 1
            cameras.size <= 4 -> 2
            else -> 3
        }
}
