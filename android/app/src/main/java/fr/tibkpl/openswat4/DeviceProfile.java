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
 * <p>Le runtime embarque deux pilotes GPU :
 * <ul>
 *   <li><b>Turnip</b> (freedreno) pour les GPU Qualcomm Adreno ; il parle au
 *       pilote noyau KGSL d'Android, expose par <code>/dev/kgsl-3d0</code>.</li>
 *   <li><b>malisx2</b> pour les GPU Arm Mali ; il parle au pilote noyau
 *       <code>mali_kbase</code> d'Android, expose par <code>/dev/mali0</code>.</li>
 * </ul>
 * Sans pilote compatible, le rendu se fait en logiciel (Mesa llvmpipe) :
 * fonctionnel, mais lent.
 *
 * <p>Les valeurs par defaut sont choisies pour que le jeu reste fluide et
 * stable (debit fixe grace a MinDesiredFrameRate du moteur, cf.
 * setup-wine.sh) sur des telephones d'entree de gamme.
 *
 * <p>Les deux telephones de reference :
 * <ul>
 *   <li><b>Realme C25</b> (Helio G70, MT6769) : Mali-G52 2EEMC2 -> 540p.</li>
 *   <li><b>Galaxy A9 2018</b> (SDM660) : Adreno 512 -> 720p.</li>
 * </ul>
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
        String socLower = soc.toLowerCase(Locale.ROOT);

        DeviceProfile p;
        if (adreno && new File(rootfs, "opt/turnip/lib").isDirectory()) {
            p = new DeviceProfile(RENDERER_TURNIP, adrenoHeight(socLower), 30, "Qualcomm Adreno (" + soc + ")");
        } else if (mali && new File(rootfs, "opt/mali/libvulkan_malisx2.so").isFile()) {
            p = new DeviceProfile(RENDERER_MALI, maliHeight(socLower), 30, "Arm Mali (" + soc + ")");
        } else if (adreno) {
            // GPU Adreno mais pilote absent du runtime : on peut encore utiliser
            // le rendu logiciel, ou le pilote Mesa livre avec le runtime.
            Log.w(TAG, "GPU Adreno detecte mais Turnip absent du runtime");
            p = new DeviceProfile(RENDERER_SOFTWARE, 540, 30, "Adreno (logiciel)");
        } else if (mali) {
            Log.w(TAG, "GPU Mali detecte mais malisx2 absent du runtime");
            p = new DeviceProfile(RENDERER_SOFTWARE, 540, 30, "Mali (logiciel)");
        } else {
            p = new DeviceProfile(RENDERER_SOFTWARE, 540, 30, "logiciel (" + soc + ")");
        }
        return p.cap(Edition.maxRenderHeight());
    }

    /**
     * Plafonne la hauteur de rendu (edition gratuite : 720 pixels maximum).
     * Le debit d'images cible est conserve : on prefere une image un peu moins
     * grande mais toujours fluide.
     */
    private DeviceProfile cap(int maxHeight) {
        if (renderHeight <= maxHeight) return this;
        return new DeviceProfile(renderer, maxHeight, targetFps, gpuName);
    }

    /**
     * Hauteur de rendu pour un GPU Adreno.
     *
     * <p>Adreno 6xx/7xx recents (SD845, SD855, SD865, SD888...) : 1080p.
     * Adreno 5xx (SD660 du Galaxy A9 2018, SD665, SD675, SD632...) : 720p.
     * Adreno 4xx (SD430, SD505, SD508) : 540p.
     */
    private static int adrenoHeight(String soc) {
        if (any(soc, "855", "865", "888", "870", "860", "845", "840", "835",
                "732", "730", "740", "780", "8gen", "8 gen", "gen 8")) {
            return 1080;
        }
        if (any(soc, "660", "662", "665", "675", "680", "685", "690",
                "636", "632", "630", "712")) {
            return 720;
        }
        if (any(soc, "508", "506", "405", "306")) {
            return 540;
        }
        return 720;
    }

    /**
     * Hauteur de rendu pour un GPU Mali.
     *
     * <p>Mali-G7x (Helio G90/G95, Dimensity 900/1200) : 1080p.
     * Mali-G57/G61 (Helio G80, G70) : 540p a 720p selon le nombre de coeurs.
     * Mali-G52 (Helio G70/G80/G81) : 540p sur les versions 2 coeurs
     * (Realme C25), 720p sur les versions plus recentes.
     */
    private static int maliHeight(String soc) {
        // Mali-G78 / G710 (Dimensity 1200, D800, D9000)
        if (any(soc, "mt6883", "mt6885", "mt6893", "mt6895", "mt6983", "dimensity", "g78", "g710")) {
            return 1080;
        }
        // Mali-G76 (Helio G90, G95, MT6781/6782/6785)
        if (any(soc, "mt6781", "mt6782", "mt6785", "mt6873", "mt6877", "g76", "g90", "g95")) {
            return 720;
        }
        // Mali-G57 (Helio G80 / G81, MT6765 / MT6768 / MT6769) : 2 coeurs -> 540p
        if (any(soc, "mt6769", "mt6768", "mt6765", "mt6853", "mt6833", "g57", "g70", "g80", "g81")) {
            return 540;
        }
        // Mali-G52 (Helio P60/P70/P90, MT6762)
        if (any(soc, "mt6762", "mt6767", "g52")) {
            return 540;
        }
        // Mali-G51 / G31 (entree de gamme)
        if (any(soc, "mt6737", "mt6761", "g51", "g31")) {
            return 540;
        }
        // Mali-T (Mali-T720/T880 et suivants) : powerful, 1080p
        if (any(soc, "mt6795", "mt6797", "t720", "t760", "t880")) {
            return 1080;
        }
        return 540;
    }

    /** Vrai si le SoC correspond a un GPU PowerVR (IMG) : rendu logiciel uniquement. */
    static boolean isPowerVr(String soc) {
        String s = soc.toLowerCase(Locale.ROOT);
        return any(s, "mt673", "sc986", "unisoc", "spreadtrum", "powervr", "ge8300", "ge8320");
    }

    /** Vrai si la chaine contient l'un des motifs donnes. */
    private static boolean any(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    /**
     * Identification du systeme sur pave, lisible dans les reglages.
     * On rassemble le fabricant et le modele : c'est ce qui permet de
     * reconnaitre un SoC ("SDM660", "MT6769"...).
     */
    static String socName() {
        StringBuilder sb = new StringBuilder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            append(sb, Build.SOC_MANUFACTURER);
            append(sb, Build.SOC_MODEL);
        }
        append(sb, Build.HARDWARE);
        append(sb, Build.BOARD);
        String soc = sb.toString().trim();
        return soc.isEmpty() ? "inconnu" : soc;
    }

    private static void append(StringBuilder sb, String value) {
        if (value == null || value.isEmpty()) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(value);
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
