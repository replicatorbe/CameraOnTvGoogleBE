# Pilotage depuis Jeedom (MQTT)

L'application se connecte à un broker MQTT (Mosquitto, par exemple celui du plugin MQTT Manager de Jeedom). Une fois connectée :

- elle **reçoit des commandes** sur `cameratv/cmd/...` ;
- elle **publie son état** sur `cameratv/state` et `cameratv/online`.

`cameratv` est le préfixe par défaut. Il se change dans l'écran de configuration de l'application.

## Configuration dans l'application

Écran de configuration (touche **Menu** de la télécommande), section « Domotique (MQTT) » :

- **Broker** : adresse IP du broker. Laisser vide pour désactiver le pilotage.
- **Port** : `1883` par défaut.
- **Préfixe** : `cameratv` par défaut.

### Fonctionnement en arrière-plan

Un service garde la connexion MQTT ouverte en permanence. Il démarre avec la TV, même si l'application n'est pas ouverte.

Quand une commande `show` ou `grid` arrive pendant qu'une autre application est affichée (un film, par exemple) :

1. l'application passe au premier plan ;
2. avec `duration`, elle rend ensuite la main à l'application précédente, et le film reprend.

Depuis Android 10, ouvrir un écran depuis l'arrière-plan exige la permission « afficher par-dessus les autres applications ». Sur Google TV, elle s'accorde une seule fois par adb :

```bash
adb shell appops set be.cameratv SYSTEM_ALERT_WINDOW allow
```

TV en veille : elle garde son réseau, la connexion MQTT reste ouverte et `cameratv/online` reste à `true`. L'effet d'une commande `show` reçue pendant la veille n'a pas été testé : allumer d'abord la TV depuis Jeedom. Au rallumage, l'application rouvre une connexion neuve au broker en environ 1,5 s.

## Commandes

| Topic | Payload | Effet |
|---|---|---|
| `cameratv/cmd/show` | `{"camera": 3, "duration": 30}` | Caméra 3 en plein écran, retour à l'écran précédent après 30 s |
| `cameratv/cmd/show` | `{"camera": "OUESTPTZ"}` | Caméra désignée par son nom (insensible à la casse), sans retour automatique |
| `cameratv/cmd/show` | `3` ou `OUESTPTZ` | Forme courte, sans JSON |
| `cameratv/cmd/grid` | (vide) | Affiche la grille |
| `cameratv/cmd/ptz` | `{"camera": 5, "action": "preset", "value": 1}` | Caméra 5 vers son preset 1 |
| `cameratv/cmd/ptz` | `{"camera": 5, "action": "left", "value": 800}` | Mouvement vers la gauche pendant 800 ms |
| `cameratv/cmd/ptz` | `{"camera": 5, "action": "stop"}` | Arrêt du mouvement |
| `cameratv/cmd/exit` | (vide) | Met l'application en arrière-plan (retour au programme TV) |

Pour `cameratv/cmd/ptz` :

- **Actions** : `left`, `right`, `up`, `down`, `zoom_in`, `zoom_out`, `preset`, `stop`.
- **Durée d'un mouvement** : 500 ms par défaut, entre 100 et 10 000 ms.

Si l'utilisateur touche la télécommande pendant un affichage temporaire (`duration`), le retour automatique est annulé.

## État publié

`cameratv/online` (retenu) vaut :

- `true` quand l'application est connectée, y compris TV en veille ;
- `false` à l'arrêt du service ou en cas de coupure réseau. C'est aussi le message de dernière volonté MQTT, que le broker publie lui-même si la connexion est perdue.

`online` n'indique donc pas si la TV est allumée : utiliser pour cela l'état de l'équipement Jeedom de la TV.

`cameratv/state` (retenu) :

```json
{
  "screen": "fullscreen",
  "camera": 5,
  "cameraName": "OUESTPTZ",
  "ptzMode": false,
  "visible": true,
  "cameras": [{"channel": 1, "name": "OUESTTERRASSE", "ptz": true}]
}
```

Champs :

- `screen` : `setup`, `loading`, `grid` ou `fullscreen` ;
- `camera` et `cameraName` : `null` hors plein écran ;
- `visible` : `false` quand la TV affiche une autre application.

## Équipement Jeedom (plugin jMQTT ou MQTT Manager)

Créer un équipement « Caméras TV » rattaché au broker, avec :

- des commandes **action** :

| Nom | Topic | Payload |
|---|---|---|
| Porte d'entrée | `cameratv/cmd/show` | `{"camera": "INTERCOM", "duration": 30}` |
| Grille | `cameratv/cmd/grid` | |
| Garage | `cameratv/cmd/show` | `{"camera": "garage"}` |
| Quitter | `cameratv/cmd/exit` | |

- des commandes **info** :

| Nom | Topic | Remarque |
|---|---|---|
| En ligne | `cameratv/online` | binaire |
| Écran | `cameratv/state` | champ JSON `screen` |
| Caméra affichée | `cameratv/state` | champ JSON `cameraName` |

## Ouvrir l'application depuis Jeedom

En complément de MQTT, un lien profond ouvre l'application sur une caméra précise :

```
cameratv://show?camera=INTERCOM&duration=30
cameratv://show?camera=3
cameratv://grid
```

Ces liens peuvent être lancés de deux façons :

- **par le plugin Jeedom qui pilote la TV**, s'il sait ouvrir une application ou un lien ;
- **par adb**, depuis une machine du réseau :

```bash
adb shell am start -a android.intent.action.VIEW -d "cameratv://show?camera=INTERCOM&duration=30"
```

Scénario type « on sonne » :

1. Si la TV est éteinte, d'après l'état de son équipement Jeedom : l'allumer, puis attendre 3 secondes.
2. Publier `{"camera": "INTERCOM", "duration": 30}` sur `cameratv/cmd/show`.
