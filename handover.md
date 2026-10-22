# Handover — CleanYourTV launcher + CCTV live preview, personal fork

Status per 23 Oktober 2026. Working tree ada di `/home/rioovs/Project/cleanyourtv1`.

> **Nilai asli (alamat IP, user, password NVR, alamat TV) tidak ada di file ini** — repo ini publik dan
> aturan repo melarang menyimpan identifier dan kredensial. Nilai aslinya ada di file lokal:
> `launcher/local.properties` (host, user, password NVR — di-gitignore, dibaca saat build),
> `tools/restore-this-tv.sh` + `tools/build-and-install-this-tv.sh` (alamat TV),
> `TV-README.md` (alamat TV + NVR). Semua file itu **tidak** ikut di-commit.

## 1. TV dan akses

| | |
|---|---|
| TV | Android TV, 1920x1080 (model, Android version dan firmware ada di `TV-README.md` dan di backup lokal) |
| ADB | `<tv ip>:5555` (network ADB masih **OPEN** — belum ditutup) |
| adb binary | `/home/rioovs/opt/cyt-toolchain/sdk/platform-tools/adb` |
| Build | `JAVA_HOME=/home/rioovs/opt/cyt-toolchain/jdk-17.0.20.1+1 ANDROID_HOME=/home/rioovs/opt/cyt-toolchain/sdk` di folder `launcher/` |
| Tool TV | `TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py '<cmd>'` dan `tools/tv.py --screenshot <file> [KEY ...]` |
| NVR | Dahua di `<nvr ip>`, user + password ada di `launcher/local.properties` |

## 2. Yang sudah selesai (terverifikasi di TV)

- **Debloat 27 paket** (`pm disable-user --user 0` saja, tidak ada uninstall), reboot akhir, `pm list packages -d` byte-identical. Backup: `backup/20261022-202213/`.
- **Launcher `com.gathin.tvhome` jadi Home.** Grid 3 kolom, dock = Stremio / TizenTube / Live TV, 1 baris grid di bawah dock. `config.xml` sudah disesuaikan (`dock_apps`, `columns`, `rows_below_dock`, `app_keys` 190→TizenTube, `hidden_apps`).
- **AdGuard Private DNS** aktif (`dns.adguard-dns.com`).
- **Deliverable**: `TV-README.md`, `tools/restore-this-tv.sh`, `tools/build-and-install-this-tv.sh`, `tools/tv-mirror.sh`.
- **CCTV live preview** 2 kamera di kanan-atas Home, dibungkus komponen Framer "Cinematic Video" (rounded 12dp, picture `centerCrop`, scrim 60% bawah, nama kamera). Lihat `shots/65-final-cctv.png`, `shots/67-final.png`.
- **Switch CCTV** di dropdown Input (baris `CCTV` + status `Aktif`/`Mati`), tersimpan SharedPreferences `cctv`/`on`, bertahan setelah force-stop.
- Tes Python: 429 OK (skipped 2). Lint 0 error. APK **487.255 B** (gate 480 KB → 491.520 B).
- `addons/dahua-cctv/` dan `tools/install-dahua-cctv.sh` **sudah dihapus** (user tidak mau APK terpisah).

## 3. Permintaan user yang belum terpenuhi (PENTING)

User melaporkan dua hal:

1. **"cctv tidak tampil HD, sepertinya itu pakai substream bukan main"** — snapshot `ch1` = 768x432, `ch2` = 512x288. Terlihat seperti substream.
2. **"streamingnya tidak realtime, saya hitung dia hanya menampilkan gambar per 7 detik sekali"** — polling 5 s + jaringan lambat = ~7 s per gambar.

### Temuan investigasi (semua sudah di-probe, lihat `work/`)

- `snapshot.cgi` **mengabaikan parameter `subtype`**: ch1 subtype=0 dan subtype=1 sama-sama 768x432; ch2 subtype=0 dan 1 sama-sama 512x288. Jadi tidak ada cara memilih main stream lewat `snapshot.cgi`.
- Endpoint `/cgi-bin/mjpg/video.cgi?channel=N&subtype=0` **ada dan jalan**: `Content-Type: multipart/x-mixed-replace; boundary=myboundary`, ~16 fps, 2.688 KB/frame rata-rata, 2.688 MB/10 s.
- **Isi frame mjpg itu BUKAN JPEG.** Body tiap part dimulai `00 00 FF FE 00 0F 0A 01 31 DD F7 D7 ...` (marker COM, bukan SOI `FF D8`), tidak ada `FFD8`/`FFD9` di sebagian besar part, dan tidak bisa didekode PIL baik raw maupun dengan `FFD8` prefix. Kemungkinan besar ini **bitstream H.264/H.265 mentah** yang dibungkus multipart (bukan JPEG). Perlu konfirmasi.
- Dahua punya `width`/`height` pada `mjpg/video.cgi` — belum sempat diuji (terkunci lockout).
- TV **HEVC-capable**: `dumpsys media.player` menunjukkan `OMX.MS.HEVC.Decoder`, `c2.android.hevc.decoder`, `video/hevc` terdaftar. `OMX.MS.AVC.Decoder` juga ada.
- RTSP: challenge dari NVR **tidak punya `qop`** (RFC 2069 style: `response = MD5(HA1:nonce:HA2)`). Probe sebelumnya salah karena mengirim `qop=auth`. `probe_sdp3.py` sudah diperbaiki tapi belum diuji karena lockout.
- NVR H.265-only dari probe awal: `a=rtpmap:98 H265/90000` di semua channel/subtype.

### Blocker saat ini: NVR account lockout

NVR membalas **HTTP 401 `Error\r\nInvalid Authority!`** ke semua digest (HTTP dan RTSP), juga `/cgi-bin/api.cgi?cmd=Login`. Penyebabnya: terlalu banyak percobaan auth saat probing. Root `/` (200) dan halaman login 404 normal. Lockout berlangsung > 15 menit.

**Status 23 Okt 06:05: lockout sudah habis.** NVR kembali menjawab challenge digest yang normal
(`WWW-Authenticate: Digest realm="...", qop="auth", nonce="..."`, tanpa "Invalid Authority").

**Rekomendasi:** jangan probe auth NVR berulang-ulang. Kalau lockout muncul lagi, tunggu 30 menit
lalu pakai **satu** probe saja. Jangan menulis ulang `probe_digest_variants.py` dengan 6 request
sekaligus.

## 4. Opsi solusi (urut dari paling jelas)

**Opsi A — RTSP + HEVC lewat media3 (paling benar, tapi berat)**
- `launcher/app/build.gradle` pernah punya media3, lalu dihapus (APK naik, gate 480 KB). RTSP client media3 = `DefaultRtspClient` (RFC 2326, tanpa digest!) → perlu RTSP client sendiri dengan digest.
- Perlu `SurfaceView`/`TextureView` (bukan `ImageView`), H.265 software decode di TV 1 GB RAM = berat.
- Risiko: gate APK 480 KB mungkin tidak bisa dengan media3.
- NVR H.265 → butuh HEVC decoder; `OMX.MS.HEVC.Decoder` ada, tapi preview di launcher mungkin berat.

**Opsi B — MJPEG dari `mjpg/video.cgi` (kalau ternyata JPEG)**
- Kalau body-nya JPEG asli dengan header aneh, parser bisa diperbaiki. Tapi evidence sekarang bilang **bukan JPEG**.

**Opsi C — snapshot cepat + ukuran benar**
- `snapshot.cgi` tidak bisa pilih main stream. Tapi mungkin ada CGI lain (mis. `/cgi-bin/Encode/...`, `snapshot.cgi` dengan `extra=1`/`watermark=0`) atau parameter `width`/`height` yang mengembalikan resolusi native. `work/probe_paths.py` sudah ditulis untuk menguji 10 path — **belum dijalankan** (terkunci lockout).
- Real-time: turunkan interval ke ~1,5–2 s, ambil frame **dari cache lokal** (satu request per kamera per tick, pakai thread bersama), dan hanya decode ulang kalau byte-nya beda (hash cepat) supaya tidak flicker.

**Opsi D — tanyakan user untuk mengubah setting NVR**
- NVR bisa diubah lewat web UI: `http://<nvr ip>`, cek "Encode" → main stream resolution + FPS, dan pastikan user NVR punya hak akses video.

## 5. Berkas penting

| File | Isi |
|---|---|
| `launcher/app/src/main/java/com/gathin/tvhome/CctvPanel.java` | snapshot poller, digest auth manual (RFC 2617, `qop=auth`), thread pool bersama, offset 2.500 ms antar panel. `INTERVAL_MS = 5000`, `TIMEOUT_MS = 6000`. Host/user/pass masuk lewat constructor; guard `isLocalHost()` menolak host non-LAN privat. |
| `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java` | `cctvGudang`/`cctvGarasi`, `cctvOn`, `applyCctvVisibility()`, `toggleCctv()`, `addCctvRow()`, `roundCctvCards()`, prefs `CCTV_PREFS = "cctv"`. |
| `launcher/app/src/main/res/layout/activity_main.xml` | blok `cctvPanels` (2 FrameLayout card, `cctvGudangCard`/`cctvGarasiCard`, 271dp x 207dp, container 554dp, `layout_gravity="top|end"`, `layout_marginTop="106dp"`, `layout_marginEnd="56dp"`). |
| `launcher/app/src/main/res/layout-iw/activity_main.xml` | versi Hebrew (card di `top|start`); **wajib** punya view id yang sama → test `tests/test_hebrew_resources.py` gagal kalau tidak. |
| `launcher/app/src/main/res/drawable/cctv_frame.xml` | kartu rounded 12dp. |
| `launcher/app/src/main/res/drawable/cctv_scrim.xml` | gradien scrim `angle=90`, `#80000000` → `#00000000`. |
| `launcher/app/src/main/res/drawable/ic_cctv.xml` | ikon kamera. |
| `launcher/app/src/main/java/com/gathin/tvhome/Config.java` | field `cctvHost`/`cctvUser`/`cctvPass` dibaca dari `R.string.cctv_*` (build-time, dari `local.properties`). |
| `launcher/app/build.gradle` | baca `cctv.host`/`cctv.user`/`cctv.pass` dari `local.properties` → `resValue 'string', 'cctv_host' …`. |
| `launcher/local.properties` | **di-gitignore**: host, user, password NVR. |
| `launcher/app/src/main/res/values/config.xml` | `cctv_enabled` (bool), dock, columns, `app_keys`, `hidden_apps`. |
| `launcher/app/src/main/AndroidManifest.xml` | `INTERNET` + `usesCleartextTraffic="true"` (host dicek runtime oleh `CctvPanel.isLocalHost()`; `network_security_config.xml` tidak bisa dipakai karena `<domain>` cuma string-match, tanpa CIDR — AOSP `Domain.java`). |
| `work/probe_paths.py` | 10 path CGI + ukuran → **belum dijalankan**. |
| `work/probe_mjpeg_rate.py` | parse multipart + ukur fps. |
| `work/probe_mjpeg_markers.py` | scan marker JPEG di stream (bukti body bukan JPEG). |
| `work/probe_sdp3.py` | RTSP DESCRIBE dengan digest tanpa `qop` → **belum dijalankan** (lockout). |
| `work/probe_lockout.py` | retry tiap 30 s, dipakai memantau lockout. |
| `work/probe_digest_why.py` | uji 5 varian digest (semua gagal karena lockout). |

## 6. Aturan repo yang tetap berlaku

- Tidak uninstall. Tidak ada root. Tidak ada flash.
- Tidak ada `INTERNET` di launcher **upstream** — fork ini pribadi untuk TV ini, tidak di-PR. Kalau mau dikirim ke fork publik, filmedari sumber dan jangan ikutkan `INTERNET`/kredensial.
- Tidak ada secret/identifier di commit: `backup/`, `shots/`, `work/`, `launcher/local.properties`, `TV-README.md` dan script `*-this-tv.sh` sudah di-gitignore.
- Test: `tools/venv/bin/python -m unittest discover -s tests` (429 test, harus OK).
- Build wajib: `./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease`.
- Gate APK: `stat -c %s launcher/app/build/outputs/apk/release/app-release.apk` < 491.520 B.
- `tools/disable.sh`, `restore.sh`, `check.py` menolak list yang disable paket inti Android.

## 7. Sisa langkah

1. Tunggu lockout NVR habis → jalankan **satu** `work/probe_paths.py` untuk cari path yang bisa full-res + cek `work/probe_sdp3.py` untuk resolusi & codec main stream via SDP.
2. Kalau main stream ternyata H.265-only dan tidak ada JPEG full-res → tanyakan user: boleh ubah setting NVR (main stream ke H.264, atau substream ke 1080p)?
3. Perbaiki real-time: interval ~1,5–2 s, satu request per kamera per tick, decode hanya kalau byte berubah.
4. Update `TV-README.md` (bagian CCTV) setelah hasilnya final.
5. **Tutup network ADB** di akhir: USB debugging off, Network/ADB debugging off, revoke authorisations, reboot, lalu `tools/adb-closed.py <tv ip>` harus melaporkan port tertutup.
6. Minta user tekan tombol asli remot (YouTube / Input / Settings) — injected key tidak pernah sampai ke accessibility helper.
