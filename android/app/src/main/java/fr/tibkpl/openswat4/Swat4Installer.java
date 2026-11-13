package fr.tibkpl.openswat4;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Installation de SWAT 4 pour le lanceur Android.
 *
 * Le runtime Linux (rootfs Debian arm64 : Box86, Wine 32 bits, Mesa Turnip,
 * Xvfb) est extrait de l'APK.  Les donnees du jeu ne sont pas redistribuees :
 * l'utilisateur designe son installation de SWAT 4 (dossier "SWAT 4 Portable"
 * ou dossier Content d'une installation Windows) et l'application la copie
 * dans son stockage, parce que le jeu doit pouvoir ecrire (sauvegardes,
 * Swat4.ini, User.ini).
 */
final class Swat4Installer {

    private static final String TAG = "OpenSWAT4";
    private static final String KEY_RUNTIME_VERSION = "runtime_version";
    /** Taille (en octets) du runtime installe : sert a detecter les mises a jour. */
    private static final String KEY_RUNTIME_SIZE = "runtime_size";
    private static final String KEY_GAME_PATH = "game_path";

    interface Progress {
        void onStep(String message);
    }

    static final class LaunchConfig {
        final File rootfsDir;
        final File gameRoot;   // /game
        final File homeDir;    // /home/ut (prefixe Wine)
        final File dataDir;

        LaunchConfig(File rootfsDir, File gameRoot, File homeDir, File dataDir) {
            this.rootfsDir = rootfsDir;
            this.gameRoot = gameRoot;
            this.homeDir = homeDir;
            this.dataDir = dataDir;
        }
    }

    // ------------------------------------------------------------------
    // Runtime Linux
    // ------------------------------------------------------------------

    static File rootfsDir(Context context) {
        return new File(context.getFilesDir(), "rootfs");
    }

    static File gameDir(Context context) {
        return new File(context.getFilesDir(), "game");
    }

    static File homeDir(Context context) {
        return new File(context.getFilesDir(), "home");
    }

    /** Dossier du jeu choisi par l'utilisateur (sauvegarde entre les lancements). */
    static File chosenGameDir(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("openswat4", Context.MODE_PRIVATE);
        String path = prefs.getString(KEY_GAME_PATH, null);
        return path != null ? new File(path) : null;
    }

    static void rememberGameDir(Context context, File dir) {
        context.getSharedPreferences("openswat4", Context.MODE_PRIVATE)
                .edit().putString(KEY_GAME_PATH, dir.getAbsolutePath()).apply();
    }

    /**
     * Recupere le runtime Linux.
     *
     * Le runtime (Box86, Wine 32 bits, Mesa Turnip + malisx2, Xvfb : environ
     * 400 Mo decompresse) n'est PAS embarque dans l'APK : il est telecharge une
     * premiere fois, puis conserve dans le stockage de l'application.  L'APK
     * reste ainsi tres petit, et le jeu lui-meme n'est jamais copie.
     *
     * Le runtime n'est retelecharge que si la release "runtime-v1" a change
     * (comparee via la taille du fichier distant) : une simple mise a jour de
     * l'APK ne fait pas re-telecharger 150 Mo.
     */
    static File ensureRuntime(Context context, Progress progress) throws IOException {
        File rootfsDir = rootfsDir(context);
        SharedPreferences prefs = context.getSharedPreferences("openswat4", Context.MODE_PRIVATE);
        String url = context.getString(R.string.runtime_url);
        boolean missing = !new File(rootfsDir, "opt/ut/run-swat4.sh").isFile();
        long installed = prefs.getLong(KEY_RUNTIME_SIZE, -1);
        long remote = remoteSize(url);
        boolean outdated = remote > 0 && installed != remote;
        if (missing || outdated) {
            progress.onStep("Telechargement du runtime Linux...");
            File tmp = new File(context.getCacheDir(), "rootfs.tgz");
            long size = download(context, url, tmp, progress);
            deleteRecursively(rootfsDir);
            try (InputStream in = new java.io.FileInputStream(tmp)) {
                TarExtractor.extract(in, rootfsDir, bytes -> { });
            }
            tmp.delete();
            prefs.edit().putLong(KEY_RUNTIME_SIZE, size > 0 ? size : remote).apply();
        }
        return rootfsDir;
    }

    /** Vrai si le runtime est deja installe (et a jour) : pas de telechargement a faire. */
    static boolean runtimeInstalled(Context context) {
        File rootfsDir = rootfsDir(context);
        if (!new File(rootfsDir, "opt/ut/run-swat4.sh").isFile()) return false;
        SharedPreferences prefs = context.getSharedPreferences("openswat4", Context.MODE_PRIVATE);
        long installed = prefs.getLong(KEY_RUNTIME_SIZE, -1);
        long remote = remoteSize(context.getString(R.string.runtime_url));
        return remote <= 0 || installed == remote;
    }

    /** Taille du runtime distant en octets, -1 si inconnue. */
    static long runtimeRemoteSize(Context context) {
        return remoteSize(context.getString(R.string.runtime_url));
    }

    /** Taille du fichier distant, -1 si elle est inconnue (HEAD). */
    private static long remoteSize(String url) {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            try {
                if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return -1;
                return conn.getContentLengthLong();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** Telecharge un fichier avec avancement (le runtime : environ 190 Mo). */
    private static long download(Context context, String url, File dest, Progress progress)
            throws IOException {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.connect();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Telechargement du runtime impossible (HTTP " + code + ")");
        }
        long total = conn.getContentLengthLong();
        long done = 0;
        long lastReport = 0;
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                long step = total > 0 ? Math.max(total / 100, 512 * 1024) : 2L * 1024 * 1024;
                if (done - lastReport > step) {
                    lastReport = done;
                    progress.onStep(String.format(java.util.Locale.US,
                            "Runtime Linux : %d / %d Mo (%d%%)",
                            done / 1048576, total / 1048576,
                            total > 0 ? (int) (done * 100 / total) : 0));
                }
            }
        } finally {
            conn.disconnect();
        }
        return total;
    }

    private static int appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 1;
        }
    }


    // ------------------------------------------------------------------
    // Donnees du jeu
    // ------------------------------------------------------------------

    /**
     * Verifie l'installation de SWAT 4 choisie par l'utilisateur et en
     * memorise le chemin.
     *
     * Le jeu n'est PAS copie : l'application monte le dossier tel quel dans le
     * runtime.  L'APK reste donc minuscule et la place sur le telephone est
     * celle du dossier du jeu (carte SD).
     */
    static File verifyGame(File source) throws IOException {
        File content = new File(source, "Content").isDirectory() ? new File(source, "Content") : source;
        if (!new File(content, "System/Swat4.exe").isFile()) {
            // L'utilisateur a peut-etre choisi le dossier System lui-meme.
            if (new File(content, "Swat4.exe").isFile()) {
                return content.getParentFile().getParentFile();   // racine du jeu
            }
            throw new IOException(source.getAbsolutePath()
                    + " : Swat4.exe introuvable (dossier attendu : Content/System/Swat4.exe)");
        }
        return content.getParentFile();   // la racine du jeu (contient Content/)
    }

    /**
     * Recherche une installation de SWAT 4 dans le dossier donne : la racine du
     * jeu (qui contient Content/), le dossier Content, ou le dossier
     * Content/System.  Renvoie null si rien n'est trouve.
     */
    static File findSwat4(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        if (new File(dir, "Content/System/Swat4.exe").isFile()) return dir;
        File content = new File(dir, "Content");
        if (new File(content, "System/Swat4.exe").isFile()) return dir;
        File system = new File(content, "System");
        if (new File(system, "Swat4.exe").isFile()) return dir.getParentFile();
        return null;
    }

    /**
     * Cherche SWAT 4 dans les emplacements habituels du telephone (carte SD,
     * dossier de stockage interne, dossier "Jeux").  Renvoie la racine du jeu
     * trouvee, ou null.
     */
    static File detectGame() {
        File external = android.os.Environment.getExternalStorageDirectory();
        if (external == null) return null;
        List<File> roots = new ArrayList<>();
        roots.add(external);
        File[] children = external.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !child.getName().equals("Android")) roots.add(child);
            }
        }
        for (File root : roots) {
            File found = findSwat4(root);
            if (found != null) return found;
            // Un niveau plus bas : /sdcard/Jeux/SWAT 4 ...
            File[] sub = root.listFiles();
            if (sub == null) continue;
            for (File child : sub) {
                if (!child.isDirectory()) continue;
                File deep = findSwat4(child);
                if (deep != null) return deep;
            }
        }
        return null;
    }

    /** Racine du jeu choisi (dossier contenant Content/), ou null. */
    static File gameRoot(Context context) {
        File dir = chosenGameDir(context);
        return dir != null ? dir : null;
    }

    // ------------------------------------------------------------------
    // Utilitaires fichier
    // ------------------------------------------------------------------

    static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Creation impossible : " + dir);
        }
    }

    static void copyTree(File src, File dest) throws IOException {
        mkdirs(dest);
        File[] children = src.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = new File(dest, child.getName());
            if (Files.isSymbolicLink(child.toPath())) {
                Files.createSymbolicLink(target.toPath(),
                        Files.readSymbolicLink(child.toPath()));
            } else if (child.isDirectory()) {
                copyTree(child, target);
            } else {
                try (InputStream in = Files.newInputStream(child.toPath());
                     OutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
    }

    static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.exists()) return;
        if (!file.delete()) Log.w(TAG, "Suppression impossible : " + file);
    }
}
