# NordicRower 0.1.0-alpha.3 : essai et diagnostics

Cette version expérimentale s'affiche sous le nom Bluetooth **NordicRower**.
Sa compatibilité avec votre rameur, EXR et RowerTrain n'est pas encore confirmée.
Elle communique directement avec le contrôleur USB. Wolf/iFIT peut rester
activé pendant l'essai ; gardez NordicRower au premier plan pour transmettre
les mesures aux applications tierces. Ce n'est pas une connexion à l'API Wolf.

Le ZIP contient l'APK, ce guide, le script de collecte PowerShell, la licence
Hyperborea et les empreintes SHA-256. Il ne contient aucun APK de test simulant
des watts, aucune application iFIT, aucune clé et aucun code source fournisseur.

## Ce qui change depuis alpha.2

Wolf/iFIT activé ne bloque plus la connexion ou la recherche des capacités.
Son état est affiché et enregistré dans les diagnostics. NordicRower essaie
l'accès USB normalement ; si un autre client occupe réellement l'interface,
la connexion peut encore échouer. Le fonctionnement en parallèle sur votre
rameur reste à valider.

Les journaux de l'essai du 10 octobre montraient le blocage logiciel d'alpha.2 :
le contrôleur USB était visible et autorisé, mais aucune ouverture USB ni
émission Bluetooth NordicRower n'avait été tentée. Ils ne prouvent donc pas
que Wolf occupait l'interface. Alpha.3 permet de tester cette hypothèse.

Le collecteur ajoute l'état activé/désactivé et les services Wolf. Il reste
en lecture seule et ne modifie aucune application.
Si la connexion USB ne peut pas être obtenue, une aide s'affiche avec un bouton
vers les réglages iFIT/Wolf. Vous pouvez y forcer son arrêt, revenir ici et
réessayer la même opération. NordicRower n'arrête pas Wolf automatiquement.

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
.\adb.exe install -r NordicRower-0.1.0-alpha.3.apk
.\adb.exe shell am start -n com.nordicrower.app/.MainActivity
```

Ces commandes installent NordicRower et l'ouvrent sans arrêter ni désactiver
Wolf. L'écran indique si Wolf/iFIT est activé ou désactivé ; les deux états
permettent l'essai. Aucun autre service iFIT ni le lanceur Android ne doit être
modifié. Si Wolf est encore désactivé à la suite d'un ancien essai, réactivez-le
avec la section ci-dessous avant de tester le fonctionnement en parallèle.

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
2. Gardez NordicRower au premier plan et ramez brièvement. Notez si les watts,
   la cadence et le nombre de coups apparaissent dans NordicRower. Si l'accès
   USB échoue, suivez la procédure ci-dessous.
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

## Si l'accès au rameur échoue

Après plusieurs tentatives USB infructueuses, NordicRower affiche **Libérer
l'accès au rameur**. iFIT/Wolf utilise peut-être encore le contrôleur ; ce
message ne prouve pas que Wolf est la cause.

Laissez le rameur à l'arrêt, puis :

1. Appuyez sur **Réglages iFIT** dans NordicRower. La fiche Android d'iFIT/Wolf
   s'ouvre ; aucun arrêt n'est effectué automatiquement.
2. Appuyez sur **Forcer l'arrêt** (ou **Force stop**) et confirmez.
   Cette action ferme iFIT/Wolf temporairement, sans le désactiver ni le désinstaller.
3. Utilisez la touche **Retour** pour revenir à NordicRower. N'ouvrez pas iFIT
   entre-temps.
4. Appuyez sur **Réessayer** et confirmez. NordicRower retente l'opération qui
   a échoué : connexion ou recherche des capacités.

Les instructions restent sur l'écran après fermeture du message et après un
redémarrage de NordicRower. Si les réglages ne s'ouvrent pas, allez manuellement
dans **Paramètres > Applications > iFIT/Wolf > Forcer l'arrêt**. Si le bouton est
grisé ou si l'essai échoue encore, exportez les diagnostics pour le support.

Cette aide concerne uniquement l'échec de prise de l'interface USB. Une erreur
de permission, de protocole ou de Bluetooth conserve son propre message.

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

Wolf reste activé pendant cet essai. Les commandes `pm enable` sont utiles
seulement si vous l'aviez désactivé lors d'un essai précédent. Elles ne sont
pas nécessaires pour une installation normale d'alpha.3.

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
