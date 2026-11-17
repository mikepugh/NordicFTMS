# NordicRower — installation et utilisation

NordicRower transmet la puissance, la cadence et le nombre de coups de rame des
rameurs NordicTrack/ProForm compatibles avec une console USB Wolf/iFIT.
Installez NordicRower sur la console et utilisez votre application de fitness
sur un autre appareil.

## Installer ou mettre à jour

1. Dans une [version NordicRower sur GitHub](https://github.com/mikepugh/NordicFTMS/releases),
   téléchargez **NordicRower.apk**, ou téléchargez puis extrayez **NordicRower-version.zip**.
2. Utilisez votre connexion ADB autorisée à la console. Si nécessaire,
   téléchargez les [outils Android officiels](https://developer.android.com/tools/releases/platform-tools).
3. Lancez `adb install -r NordicRower.apk`, puis ouvrez NordicRower sur la console.
   La mise à jour conserve les données. Si ADB est dans le dossier courant,
   utilisez `.\adb.exe` sous Windows ou `./adb` sous macOS/Linux.
   Avec plusieurs appareils, ajoutez `-s NUMERO` après `adb`.

## Utiliser NordicRower

1. Fermez les autres applications de fitness sur la console. Vous pouvez
   d'abord laisser Wolf/iFIT activé.
2. Appuyez sur **Connecter le rameur**, confirmez et acceptez les autorisations demandées.
3. Commencez à ramer. Dans votre application sur l'autre appareil, connectez
   **NordicRower** comme rameur Bluetooth.
4. Gardez NordicRower au premier plan, puis appuyez sur **Déconnecter** en fin de séance.

Si l'accès USB échoue, appuyez sur **Réglages iFIT**, puis sur **Forcer l'arrêt**
dans les réglages Android. Revenez à NordicRower et appuyez sur **Réessayer**.

Cette application transmet uniquement des mesures. La commande à distance de la
résistance, l'ERG, la vitesse, la distance et l'allure ne sont pas pris en charge.
Les applications exigeant un point de contrôle FTMS sont incompatibles.
Pour les consoles GlassOS compatibles, utilisez NordicFTMS.

## Diagnostics

Appuyez sur **Partager les diagnostics** pour envoyer un rapport, ou sur
**Enregistrer les diagnostics** pour le sauvegarder. Rien n'est envoyé
automatiquement. Transmettez les rapports au support en privé.

Si la console n'a pas de sélecteur de fichiers, appuyez sur **Enregistrer les diagnostics**,
puis récupérez le rapport depuis votre ordinateur :

```sh
adb pull /sdcard/Android/data/com.nordicrower.app/files/diagnostics/latest-report.txt NordicRower-report.txt
```

Le collecteur Windows facultatif recueille des informations système supplémentaires.
Placez-le près de `adb.exe`, enregistrez un nouveau rapport dans l'application, puis lancez :

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\collect-nordicrower-diagnostics.ps1
```

Le ZIP créé inclut le rapport enregistré. Les erreurs de lecture des fichiers
privés avec `run-as` sont normales avec l'APK public. Récupérez les rapports
avant de désinstaller l'application ou d'effacer ses données.
