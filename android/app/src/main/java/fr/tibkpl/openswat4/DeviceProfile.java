package fr.tibkpl.openswat4;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * Profil de l'appareil : nature du GPU (donc pilote Vulkan utilisable dans le
 * runtime Linux), resolution de rendu interne et debit d'images cible.
 *
 * Le runtime embarque deux pilotes GPU :
 * <ul>
 *   <li><b>Turnip</b> (freedreno) pour les GPU Qualcomm Adreno ; il parle au
 *       pilote noyau KGSL d'Android, expose par <code>/dev/kgsl-3d0</code>.</li>
 *   <li><b>malisx2</b> pour les GPU Arm Mali ; il parle au pilote noyau
 *       <code>mali_kbase</code> d'Android, expose par <code>/dev/mali0</code>.</li>
 * </ul>
 * Sans pilote compatible, le rendu se fait en logiciel (Mesa llvmpipe) :
 * fonctionnel, mais lent.
 *
 * Les valeurs par defaut sont choisies pour que le jeu reste fluide et
 * stable (debit fixe grace a MinDesiredFrameRate du moteur, cf.
 * setup-wine.sh) sur des telephones d'entree de gamme.
 */
final class DeviceProfile {

    private static final String TAG = "OpenSWAT4";

    /** Rendu par le pilote Vulkan embarque (Adreno). */
    static final String RENDERER_TURNIP = "turnip";
    /** Rendu par le pilote Vulkan embarque (Mali). */
    static final String RENDERER_MALI = "mali";
    /** Rendu logiciel (llvmpipe). */
    static final String RENDERER_SOFTWARE = "software";

    /** Mode de rendu retenu. */
    final String renderer;
    /** Hauteur de l'ecran virtuel interne (la largeur suit le format de l'ecran). */
    final int renderHeight;
    /** Debit d'images impose au moteur (MinDesiredFrameRate). */
    final int targetFps;
    /** Nom lisible du GPU, pour l'ecran de reglages. */
    final String gpuName;

    private DeviceProfile(String renderer, int renderHeight, int targetFps, String gpuName) {
        this.renderer = renderer;
        this.renderHeight = renderHeight;
        this.targetFps = targetFps;
        this.gpuName = gpuName;
    }

    /**
     * Detecte le GPU et les capacites du telephone.
     *
     * @param rootfs dossier du runtime Linux deja extrait (on verifie que le
     *               pilote est bien present avant de l'annoncer)
     */
    static DeviceProfile detect(Context context, File rootfs) {
        boolean adreno = new File("/dev/kgsl-3d0").exists();
        boolean mali = new File("/dev/mali0").exists();
        String soc = socName();

        if (adreno && new File(rootfs, "opt/turnip/lib").isDirectory()) {
            return new DeviceProfile(RENDERER_TURNIP, adrenoHeight(soc), 30, "Qualcomm Adreno (" + soc + ")");
        }
        if (mali && new File(rootfs, "opt/mali/libvulkan_malisx2.so").isFile()) {
            return new DeviceProfile(RENDERER_MALI, maliHeight(soc), 30, "Arm Mali (" + soc + ")");
        }
        if (adreno) {
            // GPU Adreno mais pilote absent du runtime : on peut encore utiliser
            // le rendu logiciel, ou le pilote Mesa livre avec le runtime.
            Log.w(TAG, "GPU Adreno detecte mais Turnip absent du runtime");
            return new DeviceProfile(RENDERER_SOFTWARE, 540, 30, "Adreno (logiciel)");
        }
        if (mali) {
            Log.w(TAG, "GPU Mali detecte mais malisx2 absent du runtime");
            return new DeviceProfile(RENDERER_SOFTWARE, 540, 30, "Mali (logiciel)");
        }
        return new DeviceProfile(RENDERER_SOFTWARE, 540, 30, "logiciel (" + soc + ")");
    }

    /**
     * Hauteur de rendu pour un GPU Adreno. Les Adreno 5xx/6xx (SD660 = 512,
     * SD845 = 630) sont de milieu de gamme : 720p tient la route, on evite le
     * 1080p qui fait chuter le debit d'images de facon irreguliere.
     */
    private static int adrenoHeight(String soc) {
        String s = soc.toLowerCase(Locale.ROOT);
        if (s.contains("855") || s.contains("865") || s.contains("8gen")
                || s.contains("qcom") && s.contains("8")) {
            return 1080;
        }
        if (s.contains("730") || s.contains("732") || s.contains("740")
                || s.contains("835") || s.contains("845") || s.contains("860")
                || s.contains("870") || s.contains("888")) {
            return 1080;
        }
        return 720;
    }

    /**
     * Hauteur de rendu pour un GPU Mali. Les Mali-G52 (Helio G70, 2 coeurs)
     * sont en dessous de la moyenne : 540p pour garder un debit stable.
     */
    private static int maliHeight(String soc) {
        String s = soc.toLowerCase(Locale.ROOT);
        if (s.contains("g57") || s.contains("g68") || s.contains("g77") || s.contains("g78")
                || s.contains("g710") || s.contains("g715") || s.contains("g610")) {
            return 720;
        }
        return 540;
    }

    /** Identification du systeme sur puce, lisible dans les reglages. */
    static String socName() {
        String soc = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            soc = Build.SOC_MANUFACTURER;
        }
        if (soc == null || soc.isEmpty()) {
            soc = Build.HARDWARE;
        }
        return soc == null ? "" : soc;
    }

    /** Le mode de rendu demande par l'utilisateur prime sur la detection. */
    static String rendererFor(Context context, DeviceProfile profile, boolean gpuFailed) {
        if (gpuFailed) return RENDERER_SOFTWARE;
        String asked = GameData.getRenderer(context);
        if (asked == null || "auto".equals(asked)) return profile.renderer;
        if (RENDERER_SOFTWARE.equals(asked)) return RENDERER_SOFTWARE;
        // Un pilote exige explicitement n'est utilise que s'il est present.
        if (RENDERER_MALI.equals(asked) && RENDERER_MALI.equals(profile.renderer)) {
            return RENDERER_MALI;
        }
        if (RENDERER_TURNIP.equals(asked) && RENDERER_TURNIP.equals(profile.renderer)) {
            return RENDERER_TURNIP;
        }
        return profile.renderer;
    }
}
