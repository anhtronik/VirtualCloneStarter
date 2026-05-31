package com.digitalizha.apkcloner;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PICK_APK = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Uri selectedApkUri;
    private File lastOutputApk;

    private TextView selectedFileText;
    private EditText labelEdit;
    private EditText packageEdit;
    private TextView logText;
    private Button cloneButton;
    private Button installButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(30));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("APK Cloner Starter");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        TextView desc = new TextView(this);
        desc.setText("Pilih APK, ubah package name, lalu build clone. Cocok untuk APK sederhana / APK milik sendiri.");
        desc.setTextSize(14);
        desc.setPadding(0, dp(8), 0, dp(16));
        root.addView(desc, matchWrap());

        Button pickButton = new Button(this);
        pickButton.setText("Pilih APK");
        pickButton.setOnClickListener(v -> pickApk());
        root.addView(pickButton, matchWrap());

        selectedFileText = new TextView(this);
        selectedFileText.setText("Belum ada APK dipilih");
        selectedFileText.setPadding(0, dp(8), 0, dp(12));
        root.addView(selectedFileText, matchWrap());

        TextView labelTitle = label("Nama clone / label");
        root.addView(labelTitle, matchWrap());

        labelEdit = new EditText(this);
        labelEdit.setHint("Contoh: myXL2");
        labelEdit.setSingleLine(true);
        labelEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        root.addView(labelEdit, matchWrap());

        TextView packageTitle = label("Package clone baru");
        root.addView(packageTitle, matchWrap());

        packageEdit = new EditText(this);
        packageEdit.setHint("Contoh: com.clone.myxl2");
        packageEdit.setSingleLine(true);
        packageEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(packageEdit, matchWrap());

        cloneButton = new Button(this);
        cloneButton.setText("Build APK Clone");
        cloneButton.setEnabled(false);
        cloneButton.setOnClickListener(v -> buildClone());
        root.addView(cloneButton, matchWrapWithTop(dp(12)));

        installButton = new Button(this);
        installButton.setText("Install APK Hasil Clone");
        installButton.setEnabled(false);
        installButton.setOnClickListener(v -> installLastApk());
        root.addView(installButton, matchWrap());

        TextView logTitle = label("Log");
        root.addView(logTitle, matchWrapWithTop(dp(16)));

        logText = new TextView(this);
        logText.setText("Siap.\n");
        logText.setTextSize(13);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        logText.setBackgroundColor(0xffeeeeee);
        root.addView(logText, matchWrap());

        return scroll;
    }

    private TextView label(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.START);
        tv.setPadding(0, dp(10), 0, 0);
        return tv;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int top) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive",
                "application/octet-stream"
        });
        startActivityForResult(intent, REQ_PICK_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_APK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedApkUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        selectedApkUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
                // Beberapa file picker tidak memberikan persistable permission.
            }

            selectedFileText.setText(selectedApkUri.toString());
            cloneButton.setEnabled(true);
            appendLog("APK dipilih: " + selectedApkUri);
            readPackageNameAsync();
        }
    }

    private void readPackageNameAsync() {
        executor.execute(() -> {
            try {
                String oldPackage = CloneEngine.guessPackageName(this, selectedApkUri);
                runOnUiThread(() -> {
                    if (oldPackage != null) {
                        packageEdit.setText(oldPackage + ".clone1");
                        appendLog("Package lama: " + oldPackage);
                    } else {
                        appendLog("Package lama tidak terbaca. Isi package clone manual.");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("Gagal membaca package: " + e.getMessage()));
            }
        });
    }

    private void buildClone() {
        if (selectedApkUri == null) {
            appendLog("Pilih APK dulu.");
            return;
        }

        String cloneLabel = labelEdit.getText().toString().trim();
        String newPackage = packageEdit.getText().toString().trim();
        if (cloneLabel.isEmpty()) {
            appendLog("Isi nama clone dulu, contoh myXL2.");
            return;
        }
        if (!newPackage.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")) {
            appendLog("Package tidak valid. Contoh benar: com.clone.myxl2");
            return;
        }

        setWorking(true);
        appendLog("Mulai build clone...");
        executor.execute(() -> {
            try {
                CloneEngine.CloneResult result = CloneEngine.cloneApk(this, selectedApkUri, newPackage, cloneLabel);
                lastOutputApk = result.signedApk;
                runOnUiThread(() -> {
                    appendLog("Selesai.");
                    appendLog("Package lama: " + result.oldPackageName);
                    appendLog("Package baru: " + result.newPackageName);
                    appendLog("Label dipatch: " + (result.labelPatched ? "ya" : "tidak / label pakai resource"));
                    appendLog("Output: " + result.signedApk.getAbsolutePath());
                    installButton.setEnabled(true);
                    setWorking(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendLog("ERROR: " + e.getMessage());
                    setWorking(false);
                });
            }
        });
    }

    private void setWorking(boolean working) {
        cloneButton.setEnabled(!working && selectedApkUri != null);
        cloneButton.setText(working ? "Memproses..." : "Build APK Clone");
    }

    private void installLastApk() {
        if (lastOutputApk == null || !lastOutputApk.exists()) {
            appendLog("APK output belum ada.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            appendLog("Aktifkan izin install unknown apps untuk APK Cloner Starter.");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            settings.setData(Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }

        Uri uri = FileProvider.getUriForFile(
                this,
                BuildConfig.APPLICATION_ID + ".provider",
                lastOutputApk
        );
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(install);
    }

    private void appendLog(String message) {
        logText.append(message + "\n");
    }
}
