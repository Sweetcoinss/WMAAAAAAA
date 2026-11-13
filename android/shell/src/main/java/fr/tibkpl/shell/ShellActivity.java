package fr.tibkpl.shell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Terminal de diagnostic pour le telephone.
 *
 * <p>Fonctionnement : on ecrit une commande, on appuie sur « تنفيذ », le
 * resultat s'affiche, puis on appuie sur « نسخ » ou « إرسال » pour le mettre
 * dans la conversation.
 *
 * <p>Sur un telephone rooté, la case « root » utilise <code>su</code>, ce qui
 * donne acces a <code>dmesg</code> (le journal du noyau, ou apparaissent les
 * panique qui font redemarrer l'appareil), aux journaux systeme et aux
 * fichiers de l'application.
 *
 * <p>Les commandes sont lancees par l'utilisateur, une par une : rien ne
 * s'execute seul.  Aucune donnee ne sort du telephone sans une action
 * explicite de partage.
 */
public class ShellActivity extends android.app.Activity {

    private static final String TAG = "S4Shell";
    /** Au-dela, on arrete la lecture : un <code>cat</code> sur un fichier
     *  immense ferait saturer la memoire du telephone. */
    private static final int MAX_OUTPUT = 300_000;
    /** Duree maximale d'une commande. */
    private static final long TIMEOUT_MS = 120_000;

    private AutoCompleteTextView input;
    private TextView output;
    private Button runButton;
    private Button stopButton;
    private Button copyButton;
    private Button shareButton;
    private Button diagButton;
    private Button clearButton;
    private CheckBox rootBox;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private Thread current;
    private volatile Process running;
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    /** Copie automatiquement la sortie a la fin de chaque commande. */
    private boolean autoCopy = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shell);

        input = findViewById(R.id.input);
        output = findViewById(R.id.output);
        runButton = findViewById(R.id.run);
        stopButton = findViewById(R.id.stop);
        copyButton = findViewById(R.id.copy);
        shareButton = findViewById(R.id.share);
        diagButton = findViewById(R.id.diag);
        clearButton = findViewById(R.id.clear);
        rootBox = findViewById(R.id.root);

        runButton.setOnClickListener(v -> run(input.getText().toString()));
        stopButton.setOnClickListener(v -> stop());
        copyButton.setOnClickListener(v -> copyOutput());
        shareButton.setOnClickListener(v -> shareOutput());
        diagButton.setOnClickListener(v -> runDiagnostics());
        clearButton.setOnClickListener(v -> clear());

        input.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, history));
        // Les fleches haut/bas parcourent l'historique, comme dans un terminal.
        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                browseHistory(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                browseHistory(1);
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { historyIndex = -1; }
        });

        // Une commande peut etre envoyee depuis une autre application
        // (partage, lien, ...) : on la place dans le champ de saisie.
        String incoming = getIntent().getStringExtra("command");
        if (incoming != null && !incoming.isEmpty()) {
            input.setText(incoming);
            input.setSelection(incoming.length());
        }

        append("== Terminal SWAT 4 ==\n");
        append("اكتب أمراً ثم «تنفيذ». afterwards «نسخ» أو «إرسال» للنتيجة.\n".replace("afterwards ", "ثم "));
        append("root: " + (hasRoot() ? "متاح ✓" : "غير متاح") + "\n");
        append("قائمة الأوامر السابقة:Fleches haut/bas\n\n");
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    private void run(String command) {
        if (command == null || command.trim().isEmpty()) return;
        if (current != null && current.isAlive()) {
            toast("There is already a running command.");
            return;
        }
        remember(command.trim());
        append("$ " + command.trim() + "\n");
        runButton.setEnabled(false);
        stopButton.setEnabled(true);

        final boolean useRoot = rootBox.isChecked();
        current = new Thread(() -> {
            String text = execute(command.trim(), useRoot);
            handler.post(() -> {
                append(text);
                append("\n");
                runButton.setEnabled(true);
                stopButton.setEnabled(false);
                if (autoCopy) copyOutput(true);
            });
        }, "shell");
        current.start();
    }

    /** Execute la commande et renvoie sa sortie (sortie et erreurs confondues). */
    private String execute(String command, boolean useRoot) {
        List<String> cmd = new ArrayList<>();
        if (useRoot) {
            // Magisk : "su -c <cmd>".  Certains ROMs exigent "su 0 <cmd>".
            cmd.add("su");
            cmd.add("-c");
            cmd.add(command);
        } else {
            cmd.add("sh");
            cmd.add("-c");
            cmd.add(command);
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File("/"));
            pb.redirectErrorStream(true);
            pb.environment().put("PATH",
                    "/system/bin:/system/xbin:/vendor/bin:/data/data/com.termux/files/usr/bin");
            p = pb.start();
            running = p;
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    out.append(buf, 0, n);
                    if (out.length() > MAX_OUTPUT) {
                        out.append("\n... [output truncated]\n");
                        break;
                    }
                }
            }
            boolean finished = p.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                out.append("\n[timeout: the command was stopped after "
                        + (TIMEOUT_MS / 1000) + " s]\n");
            }
            int code = p.exitValue();
            if (code != 0) {
                out.append("\n[exit code ").append(code).append("]\n");
            }
            return out.toString();
        } catch (IOException e) {
            return "[error] " + e.getMessage() + "\n";
        } catch (Exception e) {
            return "[error] " + e + "\n";
        } finally {
            running = null;
        }
    }

    private void stop() {
        Process p = running;
        if (p != null) {
            p.destroyForcibly();
            append("\n[stopped by the user]\n");
        }
    }

    private void remember(String command) {
        if (!history.contains(command)) {
            history.add(0, command);
            while (history.size() > 40) history.remove(history.size() - 1);
            input.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, history));
        }
        historyIndex = -1;
    }

    /** Parcourt l'historique (delta = -1 vers l'arriere, +1 vers l'avant). */
    private void browseHistory(int delta) {
        if (history.isEmpty()) return;
        if (historyIndex < 0) {
            if (delta > 0) return;
            historyIndex = 0;
        } else {
            int next = historyIndex + delta;
            if (next < 0) next = 0;
            if (next >= history.size()) {
                input.setText("");
                historyIndex = -1;
                return;
            }
            historyIndex = next;
        }
        input.setText(history.get(historyIndex));
        input.setSelection(input.getText().length());
    }

    // ------------------------------------------------------------------
    // Sortie
    // ------------------------------------------------------------------

    private void append(String text) {
        if (text == null) return;
        log.append(text);
        if (log.length() > MAX_OUTPUT * 2) {
            log.delete(0, log.length() / 2);
        }
        output.setText(log.toString());
    }

    private void clear() {
        log.setLength(0);
        output.setText("");
    }

    private void copyOutput() {
        copyOutput(false);
    }

    private void copyOutput(boolean silent) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            if (!silent) toast("Clipboard unavailable.");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("shell", log.toString()));
        if (!silent) toast("Copied. Paste it in the conversation.");
    }

    private void shareOutput() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Sortie du terminal");
        send.putExtra(Intent.EXTRA_TEXT, log.toString());
        startActivity(Intent.createChooser(send, "Envoyer la sortie"));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // Diagnostic SWAT 4
    // ------------------------------------------------------------------

    /**
     * Execute une serie de commandes de diagnostic : c'est exactement ce qu'il
     * faut pour comprendre un redemarrage du telephone.
     */
    private void runDiagnostics() {
        clear();
        String[] commands = {
            "echo '--- appareil ---'",
            "getprop ro.product.manufacturer",
            "getprop ro.product.model",
            "getprop ro.build.version.release",
            "getprop ro.build.version.sdk",
            "getprop ro.product.cpu.abi",
            "getprop ro.hardware",
            "getprop ro.board.platform",
            "echo '--- noyau ---'",
            "uname -a",
            "cat /proc/version",
            "echo '--- peripheriques GPU ---'",
            "ls -l /dev/kgsl-3d0 /dev/mali0 /dev/dri 2>&1",
            "echo '--- memoire ---'",
            "cat /proc/meminfo | head -3",
            "echo '--- stockage ---'",
            "df -h /data | tail -2",
            "echo '--- journal noyau (panic / reboot) ---'",
            "dmesg 2>/dev/null | tail -80 || echo 'dmesg inaccessible (root needed)'",
            "echo '--- applications ---'",
            "pm list packages | grep -i -E 'swat|tibkpl|shell'",
            "echo '--- processus ---'",
            "ps -A | grep -i -E 'proot|wine|swat' || true",
            "echo '--- journal du jeu ---'",
            "ls -la /sdcard/Android/data/fr.tibkpl.openswat4/files/ 2>&1",
            "tail -60 /sdcard/Android/data/fr.tibkpl.openswat4/files/swat4.log 2>&1",
            "echo '--- preferences ---'",
            "cat /data/data/fr.tibkpl.openswat4/shared_prefs/openswat4.xml 2>&1",
            "echo '--- journal Android ---'",
            "logcat -d -v threadtime -s OpenSWAT4:V AndroidRuntime:E libc:E DEBUG:E '*:S' | tail -100",
        };
        for (String c : commands) {
            run(c);
        }
    }

    /** Vrai si la commande <code>su</code> est disponible (telephone rooté). */
    private boolean hasRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            String text = read(p);
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return text.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    private String read(Process p) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            Log.w(TAG, "lecture", e);
        }
        return sb.toString();
    }
}
