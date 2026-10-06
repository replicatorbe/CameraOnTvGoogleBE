# CameraOnTv

Application Android TV (Google TV) pour afficher et piloter à la télécommande les caméras de surveillance d'un NVR Dahua, sur le réseau local.

## Fonctionnalités

- Découverte automatique des caméras du NVR (noms des canaux).
- Grille adaptée au nombre de caméras (jusqu'à 3×3) :
  - la tuile sélectionnée est en direct (flux secondaire) ;
  - les autres affichent un snapshot rafraîchi toutes les 2 s.
- Plein écran en direct (flux principal, avec le son).
- Navigation entièrement à la télécommande.

Le mode hybride de la grille vient d'une contrainte matérielle : beaucoup de TV n'ont que 2 décodeurs vidéo matériels, et le décodage logiciel de 8 flux sature leur processeur.

| Touche | Grille | Plein écran |
|---|---|---|
| Flèches | Déplacer la sélection | ◀ ▶ caméra précédente / suivante |
| OK | Plein écran | – |
| 1 à 9 | Caméra N en plein écran | Caméra N |
| CH+ / CH- | Sélection suivante / précédente | Caméra suivante / précédente |
| Menu | Configuration | Configuration |
| Retour | Quitter | Retour à la grille |

Mode PTZ (plein écran sur une caméra motorisée, détectée automatiquement) :

| Touche | Action |
|---|---|
| OK | Entrer / sortir du mode PTZ |
| Flèches | Orienter (maintenir pour un mouvement continu, appui bref pour un petit ajustement) |
| CH+ / CH- | Zoom avant / arrière |
| 1 à 9 | Aller au preset N |
| Retour | Sortir du mode PTZ |

Par sécurité, la caméra s'arrête d'elle-même si le relâchement de la touche n'est pas reçu.

## Matériel testé

- NVR Dahua DHI-NVR4108-8P-4KS2, caméras Dahua, portier VTO DHI-VTO2211G-WP.
- TV TCL Google TV (Android 11).

## Architecture (MVC)

```
app/src/main/java/be/cameratv/
├── CameraTvApp   Racine de composition : Modèle et Contrôleurs vivent aussi longtemps que le processus
├── CameraTvService / BootReceiver   Service au premier plan (MQTT permanent), démarré avec la TV
├── model/        État de l'application (AppModel, AppState), configuration, pilotes caméras
│   └── driver/   Interface CameraDriver + implémentation Dahua (API HTTP CGI, authentification Digest)
├── controller/   AppController (machine à états des écrans) et RemoteKeyMapper (touches → commandes)
└── view/         Écrans Compose for TV et lecteur RTSP (Media3 / ExoPlayer)
```

- Le **contrôleur** reçoit les commandes de la télécommande et met à jour le **modèle**.
- La **vue** observe le modèle et se redessine.
- Les caméras sont accessibles via l'interface `CameraDriver`. Un pilote ONVIF pourra s'ajouter à côté du pilote Dahua sans toucher au reste.

Stack : Kotlin, Jetpack Compose for TV, Media3 (RTSP sur TCP), OkHttp, DataStore.

## Compiler et installer

Prérequis : Android SDK (API 35) et JDK 17 ou plus.

```bash
./gradlew :app:testDebugUnitTest      # tests unitaires
./gradlew :app:assembleDebug          # APK : app/build/outputs/apk/debug/app-debug.apk
```

Installation sur la TV :

1. Activer les options développeur sur la TV, puis le débogage réseau.
2. Installer et lancer l'app :

```bash
adb connect <IP_TV>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ensuite, appliquer les réglages de la section [Optimiser la TV](#optimiser-la-tv-adb-sans-root) : au minimum les deux premiers, sans lesquels le pilotage en arrière-plan ne fonctionne pas.

Au premier lancement, l'écran de configuration demande l'adresse du NVR et un utilisateur. Un compte dédié est conseillé, avec seulement les droits de vue en direct et de PTZ.

En build debug, la configuration peut aussi être passée par adb, ce qui évite la saisie au clavier de la TV :

```bash
adb shell am start -n be.cameratv/.view.MainActivity \
  --es nvr_host <IP_NVR> --es nvr_user <UTILISATEUR> --es nvr_password '<MOT_DE_PASSE>'
```

## Conseils côté NVR

- Flux secondaires en **H.264** (pas H.265).
- **Smart Codec désactivé** (H.264+ / H.265+).

## Optimiser la TV (adb, sans root)

Réglages vérifiés sur une TCL Google TV (Android 11, puce Realtek RTD2851A, 2 Go de RAM). Ils sont tous réversibles et survivent à un redémarrage. À lancer depuis une machine du réseau, après `adb connect <IP_TV>:5555`.

### Indispensables au pilotage en arrière-plan

```bash
# Ouvrir l'écran depuis l'arrière-plan (commande show reçue pendant un film)
adb shell appops set be.cameratv SYSTEM_ALERT_WINDOW allow

# TCL : autoriser le démarrage automatique (refusé par défaut, il bloque le service au démarrage)
adb shell appops set be.cameratv APP_AUTO_START allow
```

### Recommandés

```bash
# Exempter l'application de l'économiseur d'énergie et des restrictions d'arrière-plan
adb shell dumpsys deviceidle whitelist +be.cameratv
adb shell appops set be.cameratv RUN_IN_BACKGROUND allow
adb shell appops set be.cameratv RUN_ANY_IN_BACKGROUND allow
```

Vérification :

```bash
adb shell appops get be.cameratv
adb shell dumpsys deviceidle whitelist | grep cameratv
```

### Libérer de la mémoire (optionnel)

Avec 2 Go de RAM, la mémoire libre descend vite sous les 500 Mo. Les gains les plus nets :

```bash
# Économiseur d'écran / mode ambiant Google TV (~200 Mo)
adb shell settings put secure screensaver_enabled 0          # réactiver : 1

# Applications TCL inutilisées (désactivées, pas désinstallées)
adb shell pm disable-user --user 0 com.tcl.usercenter        # compte TCL
adb shell pm disable-user --user 0 com.tcl.miracast          # Miracast (Chromecast reste disponible)
adb shell pm disable-user --user 0 com.tcl.esticker
adb shell pm disable-user --user 0 tv.wuaki.apptv            # Rakuten TV
# réactiver : adb shell pm enable <paquet>
```

`com.tcl.smartalexa` est protégé par le système : il ne peut pas être désactivé sans root.

Pour voir les plus gros consommateurs :

```bash
adb shell dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p'
```

### Interface plus réactive (optionnel)

```bash
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
# valeur d'origine : 1
```

### Bon à savoir

- **En veille, la TV garde son réseau.** L'écran est éteint et Android en veille, mais la connexion MQTT reste ouverte et `cameratv/online` reste à `true`. Mesuré après 5 minutes de veille.
  - Au réveil (rallumage de l'écran), l'application rouvre par sécurité une connexion neuve au broker. Elle était de nouveau en ligne environ 1,5 s après l'allumage.
  - Au réveil, la TV revient sur la dernière application affichée avant la mise en veille.
- **TCL a son propre gestionnaire de mémoire** (`com.tcl.guard`). Il ne peut pas être désactivé sans root, mais il épargne les applications qui ont un service au premier plan, comme celle-ci.
