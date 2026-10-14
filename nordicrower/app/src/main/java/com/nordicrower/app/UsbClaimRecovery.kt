package com.nordicrower.app

/** Distinguishes an exhausted interface claim from permission, handshake and BLE failures. */
class UsbClaimException(cause: Throwable) : IllegalStateException("USB interface claim failed", cause)

data class UsbClaimRecovery(val action: String) {
    val status = "Accès au rameur impossible ; essayez de fermer iFIT/Wolf"
    val title = "Libérer l'accès au rameur"
    val instructions: String
        get() = "NordicRower n'a pas pu accéder au rameur. iFIT/Wolf l'utilise peut-être encore.\n\n" +
            "Laissez le rameur à l'arrêt, puis :\n\n" +
            "1. Appuyez sur « Réglages iFIT » ci-dessous.\n\n" +
            "2. Dans la fiche iFIT/Wolf, appuyez sur « Forcer l'arrêt » (Force stop), puis confirmez. " +
            "Cela ferme iFIT/Wolf temporairement.\n\n" +
            "3. Utilisez la touche Retour pour revenir à NordicRower. N'ouvrez pas iFIT entre-temps.\n\n" +
            "4. Appuyez sur « Réessayer » et confirmez " +
            (if (action == "discover") "la recherche des capacités." else "la connexion au rameur.") +
            "\n\nSi les réglages ne s'ouvrent pas : Paramètres > Applications > iFIT/Wolf > Forcer l'arrêt. " +
            "Si le bouton est grisé ou si l'essai échoue encore, exportez les diagnostics pour le support."

    companion object {
        const val WOLF_PACKAGE = "com.ifit.standalone"
        fun fromFailure(error: Throwable, action: String): UsbClaimRecovery? =
            if (generateSequence(error) { it.cause }.any { it is UsbClaimException }) fromAction(action) else null

        fun fromAction(action: String?): UsbClaimRecovery? =
            action?.takeIf { it == "connect" || it == "discover" }?.let(::UsbClaimRecovery)
    }
}
