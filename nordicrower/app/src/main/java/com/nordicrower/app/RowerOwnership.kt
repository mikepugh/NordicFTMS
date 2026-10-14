package com.nordicrower.app

/** Wolf package state is diagnostic evidence; only an actual USB claim tests access. */
data class RowerOwnership(val glassConsoleInstalled: Boolean, val wolfEnabled: Boolean?) {
    val block: RowerOwnershipBlock?
        get() = when {
            glassConsoleInstalled -> RowerOwnershipBlock.GLASS_CONSOLE
            else -> null
        }

    val summary: String
        get() = block?.message ?: when (wolfEnabled) {
            true -> "Wolf/iFIT activé ; essai en parallèle, accès USB à vérifier lors de la connexion"
            false -> "Wolf/iFIT désactivé ; accès USB à vérifier lors de la connexion"
            else -> "Wolf/iFIT absent ; accès USB à vérifier lors de la connexion"
        }
}

enum class RowerOwnershipBlock(val message: String, val guidance: String) {
    GLASS_CONSOLE(
        "Console GlassOS détectée : NordicRower ne convient pas à cette console",
        "Ce prototype est prévu pour le rameur Wolf. Utilisez NordicFTMS pour une console GlassOS compatible.",
    ),
}

class RowerOwnershipException(val block: RowerOwnershipBlock) : IllegalStateException(block.message)
