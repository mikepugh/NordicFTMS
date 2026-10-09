# NordicRower 0.1.0-alpha.2 : essai et diagnostics

Cette version expérimentale s'affiche sous le nom Bluetooth **NordicRower**.
Sa compatibilité avec votre rameur, EXR et RowerTrain n'est pas encore confirmée.
Elle communique directement avec le contrôleur USB et remplace temporairement
Wolf comme application de contrôle. Ce n'est pas une connexion à l'API Wolf.

Le ZIP contient l'APK, ce guide, le script de collecte PowerShell, la licence
Hyperborea et les empreintes SHA-256. Il ne contient aucun APK de test simulant
des watts, aucune application iFIT, aucune clé et aucun code source fournisseur.

## Précautions et installation

Gardez vos sauvegardes des APK iFIT/Wolf et laissez le rameur à l'arrêt.
Décompressez le ZIP et placez l'APK et le script dans le dossier de `adb.exe`.
Ouvrez PowerShell dans ce dossier. Vérifiez d'abord la connexion :

```powershell
.\adb.exe devices
.\adb.exe shell pm path com.ifit.standalone
.\adb.exe shell pm list packages -e com.ifit.standalone
```

Si plusieurs appareils sont connectés, ajoutez `-s NUMERO_DE_L_APPAREIL` après
`adb.exe` dans chaque commande. N'utilisez ce prototype que sur le rameur Wolf
prévu, pas sur un tapis roulant ou un vélo GlassOS. Si Wolf est absent ou si ADB
ne fonctionne pas, arrêtez-vous et contactez le support.

Pour installer ou mettre à jour NordicRower sans effacer ses anciens journaux :

```powershell
.\adb.exe shell am force-stop com.nordicrower.app
.\adb.exe shell am force-stop com.nordicftms.app
.\adb.exe install -r NordicRower-0.1.0-alpha.2.apk
.\adb.exe shell pm disable-user --user 0 com.ifit.standalone
.\adb.exe shell am start -n com.nordicrower.app/.MainActivity
```

Ces commandes désactivent temporairement Wolf, mais ne le désinstallent pas.
Si Wolf était déjà désactivé pour le précédent essai, laissez-le désactivé.
Ne désactivez aucun autre service iFIT et ne changez pas le lanceur Android.
NordicRower ne désactive aucune application automatiquement.

## 1. Rechercher les capacités

Laissez le rameur à l'arrêt. Si NordicRower est déjà connecté, appuyez sur
**Déconnecter** et attendez la libération USB. Ensuite :

1. Appuyez sur **Rechercher les capacités** et autorisez l'accès USB si Android
   le demande. Confirmez **Lancer le diagnostic**.
2. Le diagnostic suit six étapes : vérification du matériel et de Wolf,
   ouverture USB, identité et fonctions déclarées, lecture des valeurs
   disponibles, synthèse, puis enregistrement du rapport. Une lecture absente
   ou refusée est indiquée comme inconnue. La recherche est limitée dans le temps.
3. Attendez la fin, ou le message d'erreur. Le rapport partiel et les erreurs
   restent utiles : n'effacez pas les données de l'application.

Le diagnostic ne change **ni la résistance, ni les watts cibles, ni l'état de
la séance**. Il ne déverrouille pas le contrôleur, ne calibre rien et ne modifie
aucun firmware. Des abonnements de lecture peuvent être utilisés en FitPro V2.
Une fonction déclarée ou une cible lisible ne prouve pas qu'une commande serait
acceptée ni que le mode ERG fonctionne réellement. Cela nécessitera un autre essai.

## 2. Essayer les données Bluetooth

1. Appuyez sur **Connecter le rameur**, autorisez l'accès USB/Bluetooth si
   nécessaire et confirmez. Contrairement au diagnostic, cette connexion
   initialise une séance sur le contrôleur.
2. Ramez brièvement. Notez si les watts, la cadence et le nombre de coups
   apparaissent dans NordicRower.
3. Si les données réelles apparaissent, recherchez **NordicRower** dans EXR
   puis RowerTrain, une seule application à la fois. Notez si l'appareil est
   visible, si la connexion réussit et si l'application affiche des watts.
4. Appuyez sur **Déconnecter** à la fin. Interrompez l'essai si les commandes
   physiques ou la résistance se comportent de façon inhabituelle.

Cette version transmet uniquement les mesures FTMS de rameur en lecture seule.
Elle ne permet pas encore de régler la résistance ou la puissance par Bluetooth.
Les watts et les coups doivent venir réellement du contrôleur : aucune donnée
n'est inventée. Une application exigeant un point de contrôle FTMS peut refuser
la connexion. Le compteur de notifications ne prouve pas que l'application
réceptrice a compris les mesures.

## 3. Récupérer et envoyer les journaux

Les journaux sont enregistrés automatiquement, sans activer un mode debug.
Ils sont limités à environ 3 Mio et conservés après fermeture ou redémarrage
de NordicRower, mais une désinstallation ou un effacement des données les détruit.
Collectez-les dès que possible, même si rien n'a fonctionné.

Dans PowerShell, depuis le dossier de `adb.exe` et du script :

```powershell
.\collect-nordicrower-diagnostics.ps1
```

Si Windows bloque le script, examinez-le puis exécutez cette seule invocation :

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\collect-nordicrower-diagnostics.ps1
```

Cela ne change pas la stratégie d'exécution enregistrée. N'exécutez pas le
script en administrateur. Avec plusieurs appareils, ajoutez
`-Serial NUMERO_DE_L_APPAREIL`. Le script n'établit aucune nouvelle connexion ADB.

Il crée un fichier **NordicRower-diagnostics-DATE-IDENTIFIANT.zip** dans son
dossier. Il lit les journaux NordicRower, les erreurs récentes et l'état
USB/Bluetooth. Il ne lance ni n'arrête aucune application, ne change aucun
réglage et n'efface aucun journal. Il n'accède pas aux fichiers privés iFIT.

Examinez le contenu et envoyez le ZIP en privé, même en cas d'erreur, avec :

- l'heure de l'essai et le nom/version de l'application testée ;
- ce que NordicRower affichait pendant que vous ramiez ;
- si EXR/RowerTrain voyait NordicRower, se connectait et affichait des watts ;
- une photo du message en cas d'erreur.

Les dumps système peuvent contenir des identifiants d'appareils ou des détails
d'autres applications. Ne publiez pas ce rapport dans une issue GitHub publique.
Le script conserve les erreurs et fichiers manquants : un ancien journal de
rotation peut ne pas exister, ce qui est normal. Une erreur `run-as` est un
problème de collecte, pas une preuve de limitation du rameur. Le bouton
**Exporter les diagnostics** permet aussi de sauvegarder un rapport texte.

## Revenir à Wolf/iFIT

Appuyez sur **Déconnecter** et attendez la libération USB, puis :

```powershell
.\adb.exe shell am force-stop com.nordicrower.app
.\adb.exe shell pm enable --user 0 com.ifit.standalone
.\adb.exe shell monkey -p com.ifit.standalone -c android.intent.category.LAUNCHER 1
```

Vérifiez le fonctionnement habituel d'iFIT avant de reprendre une séance. Si le
contrôleur ne se reconnecte pas, éteignez puis rallumez la console après avoir
réactivé Wolf. Ne réinitialisez pas la console et ne modifiez pas son firmware.
Collectez les journaux avant de désinstaller NordicRower.
