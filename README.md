# Benyoriki — APK Android & Smart TV

Aplikasi Android (HP + Android TV / Google TV) yang membungkus https://benyoriki.com.

## Fitur
- **Offline**: situs sudah punya service worker (`sw.js`) — setelah dibuka sekali online, halaman tersimpan. Jika pertama kali dibuka tanpa internet, app menampilkan salinan situs yang ikut dikemas di APK (`app/src/main/assets/site`).
- **Konten update otomatis**: app memuat situs live, jadi perubahan di benyoriki.com langsung tampil.
- **APK update otomatis**: app cek GitHub Releases saat dibuka dan tiap 6 jam; jika ada versi baru muncul dialog "Perbarui" → unduh → installer Android. (Android tidak mengizinkan instal 100% senyap; pengguna menekan "Instal" sekali.)
- **Remote TV**: panah = pindah fokus (sorotan emas), OK = klik, Kembali = tutup modal / halaman sebelumnya / tekan 2x keluar, tombol Menu/Info = menu (muat ulang, cek update, hapus cache, keluar). Satu APK untuk HP dan TV.

## Cara pakai GitHub (sekali saja)
1. Buat repo **publik** baru di GitHub (repo privat membuat cek update gagal).
2. Upload seluruh isi folder ini (termasuk folder `.github` dan `keystore`):
   ```
   git init && git add . && git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/USERNAME/REPO.git
   git push -u origin main
   ```
3. Buka tab **Actions** → workflow **Build APK** berjalan otomatis (atau *Run workflow*).
4. APK ada di tab **Releases** (`benyoriki-<nomor>.apk`) dan di Artifacts.
5. Setiap push ke `main` = rilis baru; app yang sudah terpasang akan menawarkan update.

## Pasang di Smart TV
- Salin APK lewat flashdisk, atau buka link Release di browser TV / aplikasi "Downloader" (kode/URL link rilis).
- Aktifkan *Instal aplikasi tidak dikenal* untuk aplikasi pemasang bila diminta.
- Update berikutnya: app menawarkan sendiri.

## Keystore
`keystore/release.jks` (sandi `benyoriki123`) disertakan agar update langsung berjalan. Itu kunci publik di repo — cukup untuk sideload pribadi. Untuk kunci rahasia: buat keystore sendiri dan isi Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. **Jangan ganti kunci setelah app tersebar**, atau update tidak bisa menimpa versi lama.

## Menyegarkan salinan offline
Ganti isi `app/src/main/assets/site` dengan versi terbaru situs (tanpa `sw.js`) lalu push.

## Catatan
- Proyek ini belum dikompilasi di lingkungan pembuatnya (tanpa Android SDK/internet); build pertama di GitHub Actions yang membuktikannya. Jika gagal, kirim log error-nya.
- Preview portofolio memakai iframe ke situs lain; situs yang memblokir iframe tampil lewat tombol "Buka Website".
