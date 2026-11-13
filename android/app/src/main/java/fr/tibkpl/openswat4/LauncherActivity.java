package fr.tibkpl.openswat4;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;

/**
 * Ecran de demarrage simplifie.
 *
 * <p>Un seul geste compte : <b>choisir le dossier de la jeu</b>.  Tout le reste
 * devient automatique :
 * <ol>
 *   <li>verification du dossier choisi ;</li>
 *   <li>telechargement du runtime Linux (une seule fois, ~190 Mo) ;</li>
 *   <li>preparation du prefixe Wine ;</li>
 *   <li>lancement du jeu.</li>
 * </ol>
 *
 * <p>Aucun reglage n'est obligatoire : les reglages de graphismes, de langue du
 * jeu et de manette sont dans la roue dentee, en bas de l'ecran.
 */
public class LauncherActivity extends Activity {
    private static final String TAG = "OpenSWAT4";

    static final String ACTION_CHANGE_FOLDER = "fr.tibkpl.openswat4.CHANGE_FOLDER";
    /** Ouvert depuis le rouage du jeu : reglages affiches, pas de lancement automatique. */
    static final String ACTION_SETTINGS = "fr.tibkpl.openswat4.SETTINGS";
    static final String EXTRA_RETURN_TO_GAME = "return_to_game";

    private static final int REQ_PICK_GAME = 1;
    private static final int REQ_PERMISSION = 3;

    private TextView status;
    private TextView runtimeSize;
    private ProgressBar progress;
    private Button playButton;
    private Button restartButton;
    private Button downloadButton;
    private Button pickButton;
    private Button resolutionButton;
    private Button languageButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Vrai quand on vient du raccourci "Changer le dossier" : pas de lancement auto. */
    private boolean forcePick;
    private String pickError;
    /** La detection automatique n'a lieu qu'une fois par session. */
    private boolean detectionDone;

    /** Preparation automatique en cours (runtime / prefixe Wine). */
    private boolean preparing;
    private final Runnable watcher = new Runnable() {
        @Override
        public void run() {
            if (preparing) {
                handler.postDelayed(this, 400);
                return;
            }
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        status = findViewById(R.id.status);
        runtimeSize = findViewById(R.id.runtime_size);
        progress = findViewById(R.id.progress);
        playButton = findViewById(R.id.play);
        restartButton = findViewById(R.id.restart);
        downloadButton = findViewById(R.id.download_runtime);
        pickButton = findViewById(R.id.pick);
        resolutionButton = findViewById(R.id.resolution);
        languageButton = findViewById(R.id.language);

        restartButton.setOnClickListener(v -> { setResult(Activity.RESULT_FIRST_USER); finish(); });
        downloadButton.setOnClickListener(v -> downloadRuntime());
        pickButton.setOnClickListener(v -> {
            if (!hasStorageAccess()) requestStorageAccess();
            else openPicker(REQ_PICK_GAME);
        });
        resolutionButton.setOnClickListener(v -> openGraphicsSettings());
        findViewById(R.id.controller).setOnClickListener(v -> ControllerSettings.show(this));
        updateResolutionButton();

        forcePick = ACTION_CHANGE_FOLDER.equals(getIntent().getAction())
                || ACTION_SETTINGS.equals(getIntent().getAction());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(watcher);
    }

    // ------------------------------------------------------------------
    // Affichage
    // ------------------------------------------------------------------

    private void refresh() {
        if (preparing) return;

        File gameDir = Swat4Installer.gameRoot(this);
        boolean returnToGame = getIntent().getBooleanExtra(EXTRA_RETURN_TO_GAME, false);

        // Detection automatique : le jeu est souvent deja sur le telephone, on
        // le cherche pour eviter d'avoir a parcourir l'arborescence a la main.
        if (gameDir == null && !forcePick && !detectionDone) {
            detectionDone = true;
            gameDir = Swat4Installer.detectGame();
            if (gameDir != null) {
                Swat4Installer.rememberGameDir(this, gameDir);
                status.setText(getString(R.string.status_detected, gameDir.getAbsolutePath()));
                return;
            }
        }

        progress.setVisibility(View.GONE);
        playButton.setVisibility(View.GONE);
        restartButton.setVisibility(View.GONE);
        downloadButton.setVisibility(View.GONE);
        runtimeSize.setVisibility(View.GONE);
        pickButton.setVisibility(View.VISIBLE);
        pickButton.setEnabled(true);
        pickButton.setText(R.string.btn_pick);

        if (pickError != null) {
            status.setText(pickError);
            return;
        }

        if (gameDir == null) {
            // Pas encore de jeu : il faut d'abord le moteur, puis le dossier.
            if (!Swat4Installer.runtimeInstalled(this)) {
                downloadButton.setVisibility(View.VISIBLE);
                downloadButton.setEnabled(true);
                downloadButton.setText(R.string.btn_download_runtime);
                status.setText(R.string.status_runtime_missing);
                long size = Swat4Installer.runtimeRemoteSize(this);
                runtimeSize.setVisibility(size > 0 ? View.VISIBLE : View.GONE);
                if (size > 0) {
                    runtimeSize.setText(getString(R.string.runtime_size, size / 1048576));
                }
                return;
            }
            // Moteur pret : on demande le dossier de la jeu.
            status.setText(R.string.status_choose_folder);
            return;
        }

        // Le jeu est la : on propose de jouer directement.
        final File found = gameDir;
        if (GameData.sessionCrashed(this)) {
            // La session precedente s'est interrompue brutalement : on previent
            // et le rendu logiciel sera impose (il ne touche pas au GPU).
            status.setText(R.string.status_previous_crash);
        } else {
            status.setText(getString(R.string.status_found, found.getAbsolutePath()));
        }
        playButton.setVisibility(View.VISIBLE);
        playButton.setText(returnToGame ? R.string.btn_return_game : R.string.btn_play);
        playButton.setOnClickListener(v -> {
            if (returnToGame) finish();
            else launchGame(found);
        });
        if (returnToGame) {
            restartButton.setVisibility(View.VISIBLE);
        }
        updateLanguageButton(found);
    }

    // ------------------------------------------------------------------
    // Telechargement du moteur (runtime Linux)
    // ------------------------------------------------------------------

    /**
     * Telecharge le moteur Linux (Box86 + Wine 32 bits + Mesa).  La barre de
     * progression montre ce qui est deja telecharge, en Mo.  Apres le
     * telechargement, l'ecran passe a « choisis le dossier de la jeu ».
     */
    private void downloadRuntime() {
        if (preparing) return;
        preparing = true;
        downloadButton.setEnabled(false);
        downloadButton.setVisibility(View.GONE);
        runtimeSize.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        status.setText(R.string.status_downloading_runtime);

        new Thread(() -> {
            String error = null;
            try {
                Swat4Installer.ensureRuntime(this, (step) -> runOnUiThread(() -> {
                    status.setText(step);
                    progress.setIndeterminate(true);
                }));
            } catch (Exception e) {
                error = e.getMessage();
                Log.w(TAG, "telechargement du runtime impossible", e);
            }
            final String failure = error;
            runOnUiThread(() -> {
                preparing = false;
                progress.setVisibility(View.GONE);
                if (failure != null) {
                    status.setText(getString(R.string.status_runtime_failed, failure));
                    downloadButton.setVisibility(View.VISIBLE);
                    downloadButton.setEnabled(true);
                    return;
                }
                status.setText(R.string.status_runtime_ready);
                refresh();
            });
        }).start();
    }

    // ------------------------------------------------------------------
    // Parcours automatique
    // ------------------------------------------------------------------

    /**
     * Lance la partie : le runtime est telecharge s'il manque, le prefixe Wine
     * est prepare, puis le jeu demarre.  Tout se fait ici, sans action de
     * l'utilisateur.
     */
    private void launchGame(File gameDir) {
        if (gameDir == null) return;
        status.setText(R.string.status_preparing);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        preparing = true;
        handler.removeCallbacks(watcher);
        handler.postDelayed(watcher, 400);

        new Thread(() -> {
            String error = null;
            try {
                Swat4Installer.ensureRuntime(this, (step) -> runOnUiThread(() -> {
                    status.setText(step);
                    progress.setIndeterminate(true);
                }));
            } catch (Exception e) {
                error = e.getMessage();
                Log.w(TAG, "runtime indisponible", e);
            }
            final String failure = error;
            runOnUiThread(() -> {
                preparing = false;
                handler.removeCallbacks(watcher);
                if (failure != null) {
                    progress.setVisibility(View.GONE);
                    status.setText(getString(R.string.status_runtime_failed, failure));
                    return;
                }
                progress.setVisibility(View.GONE);
                Intent intent = new Intent(this, GameActivity.class);
                intent.putExtra(GameActivity.EXTRA_GAME_DIR, gameDir.getAbsolutePath());
                startActivity(intent);
                finish();
            });
        }).start();
    }

    // ------------------------------------------------------------------
    // Choix du dossier
    // ------------------------------------------------------------------

    private void openPicker(int request) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), request);
        } catch (Exception e) {
            pickError = getString(R.string.status_pick_unsupported);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        File picked = GameData.treeUriToFile(data.getData());
        if (picked == null) {
            pickError = getString(R.string.status_pick_unsupported);
            refresh();
            return;
        }

        // Verification du dossier choisi.  Aucune copie : le jeu reste la ou
        // il est, l'application monte son dossier dans le runtime.
        try {
            File root = Swat4Installer.verifyGame(picked);
            Swat4Installer.rememberGameDir(this, root);
            pickError = null;
            forcePick = false;
        } catch (Exception e) {
            pickError = e.getMessage();
            refresh();
            return;
        }
        // Le jeu est choisi : on demarre tout de suite, automatiquement.
        launchGame(Swat4Installer.gameRoot(this));
    }


    // ------------------------------------------------------------------
    // Reglages
    // ------------------------------------------------------------------

    /** Langues detectees dans les fichiers du jeu ; bouton masque sans jeu. */
    private void updateLanguageButton(File gameDir) {
        if (gameDir == null) {
            languageButton.setVisibility(View.GONE);
            return;
        }
        java.util.List<String> languages = GameData.detectLanguages(gameDir, gameDir);
        String current = GameData.getLanguage(this, languages);
        languageButton.setVisibility(View.VISIBLE);
        languageButton.setText(getString(R.string.btn_language, GameData.languageName(current)));
        languageButton.setOnClickListener(v -> {
            int i = languages.indexOf(current);
            GameData.saveLanguage(this, languages.get((i + 1) % languages.size()));
            updateLanguageButton(gameDir);
        });
    }

    private void openGraphicsSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, 0);
        TextView driverLabel = new TextView(this);
        driverLabel.setText(R.string.graphics_driver);
        content.addView(driverLabel);
        Spinner driver = new Spinner(this);
        String[] rendererIds = {"auto", "turnip", "mali", "software"};
        String[] rendererLabels = getResources().getStringArray(R.array.graphics_drivers);
        driver.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, rendererLabels));
        for (int i = 0; i < rendererIds.length; i++) if (rendererIds[i].equals(GameData.getRenderer(this))) driver.setSelection(i);
        content.addView(driver);
        TextView resolutionLabel = new TextView(this);
        resolutionLabel.setText(R.string.graphics_resolution);
        content.addView(resolutionLabel);
        Spinner resolution = new Spinner(this);
        resolution.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.graphics_resolutions)));
        for (int i = 0; i < GameData.RENDER_HEIGHTS.length; i++)
            if (GameData.RENDER_HEIGHTS[i] == GameData.getRenderHeight(this)) resolution.setSelection(i);
        content.addView(resolution);
        CheckBox fps = new CheckBox(this);
        fps.setText(R.string.graphics_fps);
        fps.setChecked(GameData.showFps(this));
        content.addView(fps);
        CheckBox frameGen = new CheckBox(this);
        frameGen.setText(R.string.graphics_frame_generation);
        frameGen.setChecked(GameData.frameGeneration(this));
        content.addView(frameGen);
        TextView help = new TextView(this);
        help.setText(R.string.graphics_help);
        help.setTextSize(12);
        content.addView(help);
        new AlertDialog.Builder(this).setTitle(R.string.graphics_title).setView(content)
                .setPositiveButton(R.string.graphics_apply, (d, which) -> {
                    GameData.saveGraphics(this, GameData.RENDER_HEIGHTS[resolution.getSelectedItemPosition()],
                            rendererIds[driver.getSelectedItemPosition()], fps.isChecked(),
                            frameGen.isChecked());
                    updateResolutionButton();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void updateResolutionButton() {
        resolutionButton.setText(getString(R.string.graphics_summary, GameData.getRenderHeight(this),
                getResources().getStringArray(R.array.graphics_drivers)[rendererIndex()]));
    }

    /** Index de l'entree de pilote correspondant au reglage enregistre. */
    private int rendererIndex() {
        String asked = GameData.getRenderer(this);
        String[] ids = {"auto", "turnip", "mali", "software"};
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(asked)) return i;
        return 0;
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }
}
