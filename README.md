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

Pour que la domotique puisse afficher une caméra pendant qu'une autre application tourne, accorder une fois la permission d'affichage depuis l'arrière-plan :

```bash
adb shell appops set be.cameratv SYSTEM_ALERT_WINDOW allow
```

Au premier lancement, l'écran de configuration demande l'adresse du NVR et un utilisateur. Un compte dédié est conseillé, avec seulement les droits de vue en direct et de PTZ.

En build debug, la configuration peut aussi être passée par adb, ce qui évite la saisie au clavier de la TV :

```bash
adb shell am start -n be.cameratv/.view.MainActivity \
  --es nvr_host <IP_NVR> --es nvr_user <UTILISATEUR> --es nvr_password '<MOT_DE_PASSE>'
```

## Conseils côté NVR

- Flux secondaires en **H.264** (pas H.265).
- **Smart Codec désactivé** (H.264+ / H.265+).

## Feuille de route

- [x] MVP 1 : grille, plein écran, navigation à la télécommande
- [x] MVP 2 : PTZ (orientation, zoom, presets) à la télécommande
- [x] MVP 3 : pilotage externe via MQTT (Jeedom), voir [docs/jeedom.md](docs/jeedom.md)
- [ ] MVP 4 : événements du portier (appel → réveil de la TV, incrustation de l'image)
