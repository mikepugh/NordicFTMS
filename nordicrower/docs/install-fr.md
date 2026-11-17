# NordicRower — installation et utilisation

NordicRower transmet la puissance, la cadence et le nombre de coups de rame
de certains rameurs NordicTrack/ProForm à une application Bluetooth sur un
autre appareil. Son nom Bluetooth est **NordicRower**. Laissez NordicRower au
premier plan sur la console pendant la séance.

Cette application est distincte de NordicFTMS. Les mesures locales sont
confirmées sur un rameur Wolf/iFIT. Une capture RowerTrain montre également
NordicRower avec le statut **Connecté**. L'affichage des mesures et leur
enregistrement dans RowerTrain, ainsi que la compatibilité avec d'autres
applications réceptrices, restent à valider. Les vélos (dont le S15i) et les consoles GlassOS
ne sont pas pris en charge. Cette version ne propose ni commande à distance
de la résistance, ni ERG, vitesse, distance ou allure. Les applications qui
exigent un point de contrôle FTMS ne sont pas compatibles.

## Installer ou mettre à jour

1. Ouvrez la [page des versions GitHub](https://github.com/mikepugh/NordicFTMS/releases)
   et choisissez une version intitulée **NordicRower**. Dans **Assets**, téléchargez
   **NordicRower.apk**, ou **NordicRower-version.zip** puis extrayez le ZIP.
   Ne choisissez pas NordicFTMS ni les archives « Source code ».
2. Utilisez votre connexion ADB déjà autorisée à la console. Si nécessaire,
   téléchargez les [outils ADB officiels](https://developer.android.com/tools/releases/platform-tools).
3. Dans un terminal ouvert dans le dossier de l'APK, lancez :
   `adb install -r NordicRower.apk`.
   Si ADB est dans ce dossier, remplacez `adb` par `.\adb.exe` sous Windows
   ou `./adb` sous macOS/Linux. Avec plusieurs appareils, ajoutez `-s NUMERO`
   après `adb`. La mise à jour conserve les données. En cas d'erreur de
   signature, conservez l'application installée et récupérez les diagnostics
   avant d'envisager une désinstallation.
4. Ouvrez NordicRower dans la liste des applications de la console.

## Faire un essai

1. Rameur à l'arrêt, fermez/forcez l'arrêt de **QZ Fitness et de son Companion**
   s'ils sont installés. Laissez d'abord Wolf/iFIT activé.
2. Appuyez sur **Connecter le rameur**, confirmez et acceptez les autorisations
   USB/Bluetooth demandées. La connexion initialise une séance sur le contrôleur.
3. Ramez et vérifiez la puissance, la cadence et les coups affichés.
4. Sur votre téléphone, tablette ou ordinateur, recherchez **NordicRower** comme
   rameur Bluetooth dans l'application réceptrice. L'écran distingue l'attente
   d'une application, sa connexion et son abonnement aux mesures. Vérifiez aussi
   les valeurs dans cette application ; une connexion seule ne suffit pas.
5. Si l'accès USB échoue, appuyez sur **Réglages iFIT**, puis sur **Forcer l'arrêt**
   dans les réglages Android. Revenez à NordicRower et appuyez sur **Réessayer**.
   NordicRower ne ferme pas Wolf automatiquement.
6. Gardez NordicRower visible pendant la séance, puis appuyez sur **Déconnecter**.
   Les anciennes valeurs sont masquées après cinq secondes sans mesures complètes.

## Récupérer les diagnostics

Après l'essai, appuyez sur **Partager les diagnostics** pour choisir une
application d'envoi, ou sur **Enregistrer les diagnostics** pour sauvegarder le
rapport. Le nom commence par `NordicRower-`, contient la version, la date et un
identifiant, et se termine par `.txt`. Vérifiez que sa première ligne indique
NordicRower, puis transmettez ce fichier au support. Rien n'est envoyé
automatiquement. Certaines consoles n'ont pas de sélecteur de fichiers ou
d'application de partage. **Enregistrer les diagnostics** crée aussi une copie
pour ADB, même sans sélecteur de fichiers. Pour la récupérer depuis un ordinateur :

```sh
adb pull /sdcard/Android/data/com.nordicrower.app/files/diagnostics/latest-report.txt NordicRower-report.txt
```

Pour rechercher les capacités : déconnectez le rameur, ouvrez **Afficher /
masquer les détails techniques**, choisissez **Rechercher les capacités** et
laissez le rameur immobile jusqu'à la fin. Cette lecture ne teste pas les
commandes de résistance/ERG. Exportez ensuite un nouveau rapport.

Le script Windows facultatif, placé près de `adb.exe`, permet de recueillir
des informations système supplémentaires :

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\collect-nordicrower-diagnostics.ps1
```

Appuyez sur **Enregistrer les diagnostics** juste avant de lancer le script :
la copie sera incluse dans le ZIP sous `exported-report.txt`. Vérifiez sa date
`Exported` : cette copie ne se met pas à jour automatiquement. L'APK public
n'autorise pas `run-as` ; les erreurs de lecture des fichiers privés sont normales.
Le script ne modifie pas le rameur et n'envoie rien.
Transmettez les diagnostics en privé et récupérez-les avant de désinstaller
l'application ou d'effacer ses données.
