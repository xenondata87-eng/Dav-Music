# Pemutar Musik – Aplikasi Android

Aplikasi Android untuk situs **music-pjj2** Anda. Situs PHP tidak diubah sama sekali; aplikasi ini
membuka situs tersebut dan menambahkan pemutaran **latar belakang** seperti Spotify:

- Musik tetap jalan saat layar mati, pindah aplikasi, atau tombol Back ditekan.
- Notifikasi kontrol media (Sebelumnya, Putar/Jeda, Berikutnya, Tutup) + kontrol di layar kunci.
- Tombol headset / Bluetooth berfungsi; musik otomatis jeda saat headset dicabut.
- Lagu berikutnya jalan otomatis di latar belakang.
- Unduh lagu masuk ke folder Download; upload lagu (admin) memakai pemilih file Android.
- Tetap login (cookie sesi disimpan).

## Cara membuat APK

### Opsi A – GitHub (tanpa install apa pun)
1. Buat repositori baru di GitHub, unggah seluruh isi folder ini.
2. Buka tab **Actions** → workflow **Build APK** → tunggu selesai (±3–5 menit).
3. Unduh artifact **musik-pjj-apk** → di dalamnya ada `app-debug.apk`. Pasang di HP
   (izinkan "pasang dari sumber tidak dikenal").

### Opsi B – Android Studio
1. Buka folder ini di Android Studio (Hedgehog atau lebih baru), tunggu sync Gradle.
2. **Build → Build APK(s)**, atau tekan Run dengan HP tersambung.
   Lewat terminal: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Pemakaian
1. Saat pertama dibuka, isi **alamat server** (folder situs yang berisi `index.php`),
   mis. `https://situs-anda.com/musik/`. Wajib `https://`.
2. Login seperti biasa, putar lagu, lalu izinkan **notifikasi** dan **tanpa pembatasan baterai**
   saat diminta (penting agar HP tidak mematikan musik di latar belakang; beberapa merek seperti
   Xiaomi/Oppo/Vivo/Samsung juga punya pengaturan "Autostart"/"Hemat daya aplikasi" sendiri).
3. Ubah alamat server: tekan lama ikon aplikasi → **Ubah alamat server**
   (atau tombol di layar error).

## Batasan
- Situs harus online; aplikasi hanyalah pembungkus situs Anda (database & Backblaze tetap di server).
- Menggeser aplikasi dari daftar *Recent apps* menghentikan musik (tombol Back/Home tidak).
  Gunakan Home atau Back untuk menyembunyikan aplikasi.
- APK debug ditandatangani kunci debug; untuk Play Store buat APK/AAB release dengan keystore sendiri.

## Struktur
- `MainActivity` – WebView, alamat server, unduh, upload, izin.
- `PlaybackService` – foreground service `mediaPlayback`, MediaSession, notifikasi, wake lock.
- `PlayerBridge` – skrip yang memantau `<audio id="audio">` di situs dan mengirim status ke layanan.
