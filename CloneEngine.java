package com.digitalizha.apkcloner;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import com.android.apksig.ApkSigner;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class CloneEngine {
    private CloneEngine() {}

    static final class CloneResult {
        final File signedApk;
        final String oldPackageName;
        final String newPackageName;
        final boolean labelPatched;

        CloneResult(File signedApk, String oldPackageName, String newPackageName, boolean labelPatched) {
            this.signedApk = signedApk;
            this.oldPackageName = oldPackageName;
            this.newPackageName = newPackageName;
            this.labelPatched = labelPatched;
        }
    }

    static CloneResult cloneApk(Context context, Uri sourceUri, String newPackageName, String cloneLabel) throws Exception {
        File workDir = new File(context.getCacheDir(), "clone-work");
        deleteRecursively(workDir);
        if (!workDir.mkdirs() && !workDir.exists()) {
            throw new IOException("Gagal membuat folder kerja.");
        }

        File inputApk = new File(workDir, "input.apk");
        copyUriToFile(context, sourceUri, inputApk);

        File unsignedApk = new File(workDir, "unsigned-clone.apk");
        BinaryXmlPackagePatcher.PatchResult patchResult = repackWithPatchedManifest(inputApk, unsignedApk, newPackageName, cloneLabel);

        File outDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "clones");
        if (!outDir.mkdirs() && !outDir.exists()) {
            throw new IOException("Gagal membuat folder output.");
        }

        String safeName = sanitizeFileName(cloneLabel == null || cloneLabel.trim().isEmpty() ? newPackageName : cloneLabel.trim());
        File signedApk = new File(outDir, safeName + "-" + System.currentTimeMillis() + ".apk");
        signApk(unsignedApk, signedApk);

        return new CloneResult(signedApk, patchResult.oldPackageName, newPackageName, patchResult.labelPatched);
    }

    static String guessPackageName(Context context, Uri sourceUri) throws Exception {
        File workDir = new File(context.getCacheDir(), "read-manifest");
        deleteRecursively(workDir);
        if (!workDir.mkdirs() && !workDir.exists()) throw new IOException("Gagal membuat folder kerja.");
        File inputApk = new File(workDir, "input.apk");
        copyUriToFile(context, sourceUri, inputApk);
        try (ZipFile zip = new ZipFile(inputApk)) {
            ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
            if (manifest == null) return null;
            byte[] bytes = readAll(zip.getInputStream(manifest));
            return BinaryXmlPackagePatcher.readPackageName(bytes);
        }
    }

    private static BinaryXmlPackagePatcher.PatchResult repackWithPatchedManifest(
            File inputApk,
            File unsignedOutput,
            String newPackageName,
            String cloneLabel
    ) throws Exception {
        try (ZipFile zip = new ZipFile(inputApk);
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(unsignedOutput))) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            BinaryXmlPackagePatcher.PatchResult patchResult = null;

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.startsWith("META-INF/")) {
                    continue; // buang signature lama
                }

                byte[] data = readAll(zip.getInputStream(entry));

                if ("AndroidManifest.xml".equals(name)) {
                    patchResult = BinaryXmlPackagePatcher.patch(data, newPackageName, cloneLabel);
                    data = patchResult.manifestBytes;
                }

                writeZipEntry(out, entry, name, data, "AndroidManifest.xml".equals(name));
            }

            if (patchResult == null) {
                throw new IOException("AndroidManifest.xml tidak ditemukan di APK.");
            }
            return patchResult;
        }
    }

    private static void writeZipEntry(ZipOutputStream out, ZipEntry original, String name, byte[] data, boolean modified) throws IOException {
        ZipEntry newEntry = new ZipEntry(name);
        newEntry.setTime(original.getTime());

        if (!modified && original.getMethod() == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            newEntry.setMethod(ZipEntry.STORED);
            newEntry.setSize(data.length);
            newEntry.setCompressedSize(data.length);
            newEntry.setCrc(crc.getValue());
        } else {
            newEntry.setMethod(ZipEntry.DEFLATED);
        }

        out.putNextEntry(newEntry);
        out.write(data);
        out.closeEntry();
    }

    private static void signApk(File inputUnsignedApk, File outputSignedApk) throws Exception {
        PrivateKey privateKey = DebugKeyProvider.privateKey();
        X509Certificate cert = DebugKeyProvider.certificate();

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "debug",
                privateKey,
                Collections.singletonList(cert)
        ).build();

        List<ApkSigner.SignerConfig> signerConfigs = new ArrayList<>();
        signerConfigs.add(signerConfig);

        ApkSigner signer = new ApkSigner.Builder(signerConfigs)
                .setInputApk(inputUnsignedApk)
                .setOutputApk(outputSignedApk)
                .setMinSdkVersion(23)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build();
        signer.sign();
    }

    private static void copyUriToFile(Context context, Uri uri, File out) throws IOException {
        try (InputStream input = new BufferedInputStream(context.getContentResolver().openInputStream(uri));
             FileOutputStream output = new FileOutputStream(out)) {
            if (input == null) throw new IOException("File APK tidak bisa dibuka.");
            byte[] buffer = new byte[1024 * 64];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024 * 64];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static String sanitizeFileName(String name) {
        String safe = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9._-]", "-");
        safe = safe.replaceAll("-+", "-");
        if (safe.length() < 1) safe = "clone";
        if (safe.length() > 60) safe = safe.substring(0, 60);
        return safe;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
