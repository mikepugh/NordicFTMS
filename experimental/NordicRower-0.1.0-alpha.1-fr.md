# NordicRower : essai expérimental

Cette version est un prototype de test, pas une solution dont la compatibilité
avec EXR ou RowerTrain est confirmée. Elle se présente en Bluetooth sous le nom
**NordicRower**, quel que soit le modèle du rameur.

Elle remplace temporairement Wolf comme client du contrôleur USB. Elle ne
fonctionne donc pas en parallèle avec iFIT. Aucun logiciel iFIT n'est désinstallé
et NordicRower ne le désactive pas automatiquement. Elle ne commande pas la
résistance à distance. Son profil Bluetooth est un capteur FTMS de rameur en
lecture seule ; les applications exigeant des commandes d'entraînement peuvent
le refuser.

## Avant L'Essai

Conservez les APK Wolf/iFIT déjà sauvegardés. Vérifiez que le rameur est à l'arrêt
et qu'ADB fonctionne toujours. Placez l'APK extrait du ZIP dans le dossier de
`adb.exe`, puis ouvrez PowerShell dans ce dossier.

Ces premières commandes ne modifient rien :

```powershell
.\adb.exe devices
.\adb.exe shell pm path com.ifit.standalone
.\adb.exe shell pm list packages -e com.ifit.standalone
.\adb.exe shell dumpsys usb > NordicRower-usb-avant.txt
```

Si plusieurs appareils sont affichés, ajoutez `-s NUMERO_DE_L_APPAREIL` après
`adb.exe` dans chaque commande. Si Wolf est absent ou qu'ADB ne fonctionne pas
correctement, arrêtez-vous ici. La suite suppose que Wolf était activé au départ.
Ne modifiez pas d'autres services iFIT ni le lanceur Android.

## Installation

Les commandes suivantes arrêtent NordicFTMS, installent NordicRower et
désactivent temporairement Wolf, sans le désinstaller :

```powershell
.\adb.exe shell am force-stop com.nordicftms.app
.\adb.exe install -r NordicRower-0.1.0-alpha.1.apk
.\adb.exe shell pm disable-user --user 0 com.ifit.standalone
.\adb.exe shell am start -n com.nordicrower.app/.MainActivity
```

1. Appuyez sur **Connect Rower**, autorisez l'accès USB si Android le demande,
   puis confirmez l'avertissement.
2. Ramez brièvement et vérifiez les watts, la cadence et le nombre de coups.
   Si une erreur apparaît, ne multipliez pas les tentatives : envoyez les diagnostics.
3. Si les données réelles apparaissent, recherchez **NordicRower** dans EXR ou
   RowerTrain, en testant une seule application à la fois. Indiquez si l'appareil
   est visible, si la connexion réussit et si les watts et les coups sont reçus.
4. Appuyez sur **Save Diagnostics** pour enregistrer un rapport. Envoyez-le avec
   `NordicRower-usb-avant.txt` et, avant tout redémarrage, ce journal :

```powershell
.\adb.exe logcat -d -v threadtime NordicRower:I AndroidRuntime:E '*:S' > NordicRower-logcat.txt
```

Interrompez l'essai si les commandes physiques se comportent de façon inhabituelle.
Cette version ne contient aucune donnée simulée. Le contrôleur doit fournir les
watts et les informations de coups nécessaires au profil FTMS.

## Revenir À Wolf/iFIT

Appuyez d'abord sur **Disconnect** dans NordicRower et attendez la libération USB.
Ensuite :

```powershell
.\adb.exe shell am force-stop com.nordicrower.app
.\adb.exe shell pm enable --user 0 com.ifit.standalone
.\adb.exe shell monkey -p com.ifit.standalone -c android.intent.category.LAUNCHER 1
```

Vérifiez le fonctionnement normal d'iFIT avant de reprendre une séance. Si le
contrôleur ne se reconnecte pas, éteignez puis rallumez la console après avoir
réactivé Wolf. Ne réinitialisez pas la console et ne modifiez pas son firmware.
NordicRower peut être désinstallé séparément avec
`.\adb.exe uninstall com.nordicrower.app`.
