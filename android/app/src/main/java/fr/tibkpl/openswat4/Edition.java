package fr.tibkpl.openswat4;

/**
 * Edition du lanceur.
 *
 * <p>Le projet produit deux APK a partir du meme code :
 * <ul>
 *   <li><b>free</b> : edition limitee.  La resolution de rendu est plafonnee
 *       (voir {@link #maxRenderHeight()}) et le jeu en reseau local est
 *       desactive.  Les deux APK peuvent coexister sur le meme telephone
 *       (identifiants d'application differents).</li>
 *   <li><b>full</b> : edition complete, sans limitation.</li>
 * </ul>
 *
 * <p>Les valeurs viennent de {@code BuildConfig}, genere par Gradle pour chaque
 * edition (voir {@code android/app/build.gradle.kts}).
 */
final class Edition {

    private Edition() {
    }

    /** Vrai pour l'edition limitee. */
    static boolean isFree() {
        return BuildConfig.EDITION_FREE;
    }

    /** Vrai si le jeu en reseau local est autorise dans cette edition. */
    static boolean networkAllowed() {
        return BuildConfig.EDITION_NETWORK;
    }

    /** Hauteur de rendu maximale (en pixels) pour cette edition. */
    static int maxRenderHeight() {
        return BuildConfig.MAX_RENDER_HEIGHT;
    }

    /** Nom affichable de l'edition. */
    static String label() {
        return isFree() ? "Free" : "Full";
    }
}
