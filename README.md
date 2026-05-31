# APK Cloner Starter

Project Android Studio ini adalah **starter APK cloner**: pilih file `.apk`, ubah `package name`, hapus signature lama, lalu sign ulang APK hasil clone memakai debug key bawaan.

## Cara pakai

1. Buka folder ini di Android Studio.
2. Tunggu Gradle sync selesai.
3. Build dan install app `APK Cloner Starter` ke HP.
4. Buka app, tekan **Pilih APK**.
5. Isi:
   - **Nama clone / label**: contoh `myXL2`
   - **Package clone baru**: contoh `com.clone.myxl2`
6. Tekan **Build APK Clone**.
7. Output APK ada di folder app external files, lalu bisa ditekan **Install APK Hasil Clone**.

## Contoh clone banyak

Untuk clone ke-2, ke-3, dan seterusnya, ubah package agar selalu unik:

- `com.clone.myxl2`
- `com.clone.myxl3`
- `com.clone.myxl4`

Jangan pakai package yang sama dua kali, karena Android akan menganggapnya aplikasi yang sama.

## Batasan penting

Tidak semua APK bisa berhasil diclone. Yang sering gagal:

- APK split / `.apks` / `.xapk`
- Aplikasi yang mengecek signature asli
- Aplikasi bank, e-wallet, game online, operator, atau aplikasi dengan Play Integrity / SafetyNet
- APK dengan proteksi anti-tamper
- APK yang butuh provider authority / permission khusus yang tidak bisa diganti otomatis

Label app hanya bisa dipatch jika label di manifest berbentuk teks langsung. Kalau label memakai resource seperti `@string/app_name`, clone tetap bisa jadi tetapi nama di launcher mungkin tetap nama lama.

Gunakan hanya untuk APK milik sendiri atau yang memang kamu punya izin untuk modifikasi.

## File penting

- `MainActivity.java` — UI pilih APK, input package, build, install.
- `CloneEngine.java` — ekstrak APK, patch manifest, zip ulang, sign ulang.
- `BinaryXmlPackagePatcher.java` — patch `AndroidManifest.xml` binary AXML.
- `DebugKeyProvider.java` — debug private key dan certificate untuk signing.
