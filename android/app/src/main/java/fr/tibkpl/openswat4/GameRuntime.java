package fr.tibkpl.openswat4;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Execute SWAT 4 dans le runtime Linux (rootfs Debian arm64) via proot.
 *
 * SWAT 4 est un binaire Windows 32 bits : il tourne sous Wine 32 bits, lui-meme
 * execute par Box86 (traduction x86 -> ARM64).  Le rendu passe par wined3d
 * (OpenGL) puis Mesa Zink -> Vulkan Turnip -> GPU Adreno, comme le moteur
 * natif de l'UT2004.  L'entree tactile est injectee dans l'ecran X virtuel par
 * xbridge (XTest) et recuperee par Wine comme des evuenements souris/clavier.
 */
final class GameRuntime {

    private static final String TAG = "OpenSWAT4";

    private final Context context;
    private final Swat4Installer.LaunchConfig config;
    private java.lang.Process process;

    GameRuntime(Context context, Swat4Installer.LaunchConfig config) {
        this.context = context;
        this.config = config;
    }

    /** Fichier de l'image affichee par l'ecran X virtuel (voir /opt/ut/run-swat4.sh). */
    File framebufferFile() {
        return new File(config.rootfsDir, "tmp/fb/Xvfb_screen0");
    }

    File audioFifo() {
        return new File(config.rootfsDir, "tmp/audio.fifo");
    }

    /** Journal du jeu, dans Android/data/<appli>/files (lisible pour les rapports de bug). */
    File logFile() {
        File dir = context.getExternalFilesDir(null);
        return new File(dir != null ? dir : context.getFilesDir(), "swat4.log");
    }

    /**
     * Rendu GPU possible : le pilote Vulkan du mode demande est present dans le
     * runtime et le noeud du pilote noyau est accessible.
     *
     * @param mode "turnip" (Adreno), "mali" (Arm Mali) ou "software"
     */
    boolean gpuAvailable(String mode) {
        if (DeviceProfile.RENDERER_TURNIP.equals(mode)) {
            return new File("/dev/kgsl-3d0").canRead()
                    && new File(config.rootfsDir, "opt/turnip/lib").isDirectory();
        }
        if (DeviceProfile.RENDERER_MALI.equals(mode)) {
            return new File("/dev/mali0").canRead()
                    && new File(config.rootfsDir, "opt/mali/libvulkan_malisx2.so").isFile();
        }
        return false;
    }

    java.lang.Process start(int width, int height, int inputPort, boolean gpu,
                             String renderer, int targetFps) throws IOException {
        List<String> binds = new ArrayList<>();
        binds.add(config.gameRoot.getAbsolutePath() + ":/game");
        binds.add(config.homeDir.getAbsolutePath() + ":/home/ut");
        binds.add(logFile().getParentFile().getAbsolutePath() + ":/logs");

        List<String> env = new ArrayList<>();
        env.add("HOME=/home/ut");
        env.add("SWAT4_WIDTH=" + width);
        env.add("SWAT4_HEIGHT=" + height);
        env.add("SWAT4_INPUT_PORT=" + inputPort);
        env.add("SWAT4_RENDERER=" + (gpu ? renderer : "software"));
        env.add("SWAT4_FPS=" + targetFps);
        env.add("SWAT4_LOG_DIR=/logs");
        env.add("SWAT4_GAME_DIR=/game");
        env.add("SDL_JOYSTICK_HIDAPI=0");

        LanNetwork.prepare(context, config.rootfsDir);
        ProcessBuilder pb = proot(context, config.rootfsDir, binds, env, "/game",
                "/opt/ut/run-swat4.sh");
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile());
        process = pb.start();
        return process;
    }

    /**
     * Prepare le prefixe Wine et les reglages du jeu (une fois, au premier
     * lancement).  Tourne dans le rootfs : le prefixe doit etre cree par Wine.
     */
    void prepareWine(int width, int height, int targetFps) throws IOException {
        List<String> binds = new ArrayList<>();
        binds.add(config.gameRoot.getAbsolutePath() + ":/game");
        binds.add(config.homeDir.getAbsolutePath() + ":/home/ut");
        List<String> env = new ArrayList<>();
        env.add("HOME=/home/ut");
        env.add("SWAT4_FPS=" + targetFps);
        ProcessBuilder pb = proot(context, config.rootfsDir, binds, env, "/game",
                "/opt/ut/setup-wine.sh", "/game", String.valueOf(width), String.valueOf(height),
                String.valueOf(targetFps));
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(config.rootfsDir, "tmp/setup-wine.log"));
        process = pb.start();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Commande proot dans le rootfs : /dev, /proc, /sys et /storage (memes
     * chemins qu'Android), plus les montages donnes.
     */
    static ProcessBuilder proot(Context context, File rootfs, List<String> binds, List<String> env,
                                String workDir, String... command) throws IOException {
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        File prootTmp = new File(context.getCacheDir(), "proot");
        if (!prootTmp.isDirectory() && !prootTmp.mkdirs()) {
            throw new IOException("Creation impossible : " + prootTmp);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(libDir + "/libproot.so");
        cmd.add("--kill-on-exit");
        cmd.add("-0");
        cmd.add("--link2symlink");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        for (String b : new String[]{"/dev", "/proc", "/sys", "/storage",
                new File(rootfs, "tmp").getAbsolutePath() + ":/dev/shm"}) {
            cmd.add("-b");
            cmd.add(b);
        }
        // Android forwards controller input; do not also open physical evdev devices.
        File inputDevices = new File(context.getFilesDir(), "linux-input");
        if (!inputDevices.isDirectory() && !inputDevices.mkdirs()) {
            throw new IOException("Creation du dossier des entrees impossible");
        }
        cmd.add("-b");
        cmd.add(inputDevices.getAbsolutePath() + ":/dev/input");
        for (String b : binds) {
            cmd.add("-b");
            cmd.add(b);
        }
        cmd.add("-w");
        cmd.add(workDir);
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("LANG=C.UTF-8");
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.addAll(env);
        cmd.add("/bin/sh");
        java.util.Collections.addAll(cmd, command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> penv = pb.environment();
        penv.remove("LD_PRELOAD");
        penv.put("LD_LIBRARY_PATH", libDir);
        penv.put("PROOT_LOADER", libDir + "/libproot-loader.so");
        penv.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        Log.i(TAG, "proot : " + String.join(" ", cmd));
        return pb;
    }

    /** Arrete le jeu et ses processus traces. */
    void stop() {
        if (process != null) process.destroy();
        killAppProcesses();
    }

    static void killAppProcesses() {
        int self = Process.myPid();
        int uid = Process.myUid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        for (File p : procs) {
            int pid;
            try {
                pid = Integer.parseInt(p.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == self || uidOf(p) != uid) continue;
            try {
                Os.kill(pid, android.system.OsConstants.SIGKILL);
            } catch (Exception ignored) {
                // processus deja termine
            }
        }
    }

    private static int uidOf(File procDir) {
        try {
            for (String line : Files.readAllLines(new File(procDir, "status").toPath(),
                    StandardCharsets.UTF_8)) {
                if (line.startsWith("Uid:")) return Integer.parseInt(line.split("\\s+")[1]);
            }
        } catch (Exception ignored) {
            // processus disparu ou illisible
        }
        return -1;
    }

    /** Dernieres lignes du journal, pour afficher une erreur a l'utilisateur. */
    String logTail(int lines) {
        try {
            List<String> all = Files.readAllLines(logFile().toPath(), StandardCharsets.ISO_8859_1);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "";
        }
    }
}
