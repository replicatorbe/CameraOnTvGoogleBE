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

> Version actuelle : la connexion MQTT n'est active que lorsque l'application est au premier plan. Le fonctionnement en arrière-plan (sonnette pendant un film, par exemple) arrivera avec le jalon suivant.

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

- `true` quand l'application est connectée ;
- `false` à la fermeture ou en cas de coupure. C'est aussi le message de dernière volonté MQTT, que le broker publie lui-même si la connexion est perdue.

`cameratv/state` (retenu) :

```json
{
  "screen": "fullscreen",
  "camera": 5,
  "cameraName": "OUESTPTZ",
  "ptzMode": false,
  "cameras": [{"channel": 1, "name": "OUESTTERRASSE", "ptz": true}]
}
```

`screen` vaut `setup`, `loading`, `grid` ou `fullscreen`. `camera` et `cameraName` sont `null` hors plein écran.

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

Si l'application n'est pas au premier plan, elle peut être ouverte, ou réveillée sur une caméra précise, par un lien profond :

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

Scénario type « on sonne » (en attendant le jalon suivant) :

1. Allumer la TV (commande Jeedom de la TV).
2. Ouvrir `cameratv://show?camera=INTERCOM&duration=30`.
