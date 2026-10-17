# FlowSync

SMBSync2-এর আইডিয়ার উপর ভিত্তি করে Kotlin + Jetpack Compose (Material 3) দিয়ে নতুন করে লেখা সিঙ্ক অ্যাপ।

## ফিচার
- **Protocol (root ছাড়া):** Local folder (SAF), SMB 2/3, FTP/FTPS, SFTP (password বা private key), WebDAV, এবং rclone (Google Drive সহ সব ক্লাউড)
- **Source ও Destination যেকোনো কম্বিনেশন** (Local→SMB, SFTP→WebDAV, …)
- **Mode:** Copy / Mirror / Move; subfolder on/off
- **Custom extension filter:** All / Only these / All except these + প্রিসেট (Images, Videos…) + নিজের extension (`jpg, mp4, pdf`), hidden file ও max size skip
- **Destination indicator:** প্রতিটি প্রোফাইলে 🟢 গ্রিন = destination available & active, 🔴 রেড = offline/unreachable, ⚪ গ্রে = checking/paused (অ্যাপ খোলা থাকলে প্রতি ৪৫ সেকেন্ডে, বা Refresh চাপলে)
- **Background:** WorkManager + foreground (dataSync) service, wake/wifi lock, schedule (১৫ মিনিট – ২৪ ঘণ্টা), Wi-Fi only / charging only, reboot-এর পর auto re-arm
- **Permission fix:** Settings-এ Notification + Battery-optimization চেক ও এক ট্যাপে Fix; folder permission persisted URI হিসেবে স্থায়ী
- **JSON config:** সব প্রোফাইল `profiles.json`-এ; Settings থেকে Export / Import (Merge বা Replace, password সহ/ছাড়া)
- Log viewer প্রতি প্রোফাইলে

## Build
Android Studio (Ladybug+) দিয়ে খুলুন → Run। অথবা:
```
./gradlew assembleDebug      # app/build/outputs/apk/debug/
```
GitHub-এ push করলেই `.github/workflows/build.yml` APK বানায় (Actions → Artifacts, আর main/master-এ Releases-এও)। `.github` ফোল্ডারটি রিপোজিটরির root-এ থাকতে হবে।

## জানা সীমাবদ্ধতা
- SMB/WebDAV/Local-এ destination-এ ফাইলের mtime সেট হয় না; তাই "source নতুন বা size আলাদা" নিয়মে তুলনা হয়।
- WebDAV শুধু Basic auth; SFTP/FTPS-এ host key/certificate যাচাই করা হয় না (হোম/NAS ব্যবহারের জন্য)।
- UI ইংরেজিতে।

## স্থায়ী কী দিয়ে signed APK (GitHub Actions)
১. কম্পিউটারে একবার keystore বানান:
```
keytool -genkeypair -v -keystore flowsync.jks -alias flowsync -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 flowsync.jks > flowsync.jks.b64     # macOS: base64 -i flowsync.jks | tr -d '\n' > flowsync.jks.b64
```
২. GitHub → Settings → Secrets and variables → Actions → New repository secret:
`KEYSTORE_BASE64` (b64 ফাইলের লেখা), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (flowsync), `KEY_PASSWORD`
৩. এরপর প্রতিটি বিল্ড একই কী দিয়ে signed হবে, তাই আপডেট ইনস্টলে ডেটা থাকবে।
keystore ফাইল হারাবেন না এবং কাউকে দেবেন না।

## rclone (Google Drive, Dropbox, OneDrive, S3, Mega … ৫০+ ক্লাউড)
অ্যাপে rclone বিল্ট-ইন (root লাগে না)। Settings → **Manage rclone remotes**, অথবা প্রোফাইল এডিটরে প্রোটোকল **rclone** বেছে নিন।
- **Add remote:** ব্যাকএন্ড বেছে নিন। Google Drive/Dropbox/OneDrive/Box/pCloud… এর জন্য **Sign in with browser** – Google Cloud Console লাগে না (rclone-এর নিজস্ব app credentials ব্যবহার হয়; চাইলে নিজের client ID দিন)। অন্যগুলোর (S3, B2, Mega, WebDAV…) জন্য rclone-এর অপশন থেকে ফর্ম তৈরি হয়।
- **Import/Export rclone.conf:** পিসির কনফিগ ইম্পোর্ট করলে সব remote সাথে সাথে চলবে।
- **rclone terminal:** `config` (ইন্টারঅ্যাক্টিভ উইজার্ড), `lsd`, `about`, crypt/union/alias remote — যেকোনো rclone কমান্ড।
- Remote প্রোফাইলে Source বা Destination হিসেবে লোকাল ফোল্ডার/SMB/SFTP… যেকোনোটির সাথে জোড়া যায়। Destination indicator (সবুজ/লাল) rclone remote-এও কাজ করে।

### rclone-এর কোন ফিচার ব্যবহার করা যায়
- **সব ব্যাকএন্ড/remote** (Add remote, rclone.conf import, `crypt`/`union`/`alias` সহ) এবং **সব কমান্ড** (rclone terminal)।
- **সার্ভার (চালু/বন্ধ সুইচ):** rclone → *Servers*: HTTP, WebDAV, FTP, SFTP, DLNA, S3, NFS এবং **rclone Web GUI** (rclone-এর নিজস্ব সম্পূর্ণ ওয়েব ইন্টারফেস)। যেকোনো remote বা ফোনের ফোল্ডার শেয়ার করা যায়; পোর্ট, ইউজার/পাসওয়ার্ড, read-only, Wi-Fi-তে খোলা/শুধু ফোনে, অতিরিক্ত ফ্ল্যাগ, রিবুটের পর অটো-স্টার্ট। ব্যাকগ্রাউন্ডে নোটিফিকেশন সহ চলে ("Stop all" বাটন আছে)।
- **Saved commands:** যেকোনো rclone কমান্ড (সব ফ্ল্যাগ সহ) সেভ করে এখনই বা শিডিউলে (১৫ মিনিট–২৪ ঘণ্টা) ব্যাকগ্রাউন্ডে চালান; লগ দেখা যায়।
- **প্রোফাইল ইঞ্জিন:** দুই দিকেই rclone remote হলে rclone-এর নিজস্ব ইঞ্জিন: server-side copy, checksum, parallel transfers, bandwidth limit (টাইমটেবিল সহ), dry-run, **Two-way (bisync)**, extension filter, এবং **Extra rclone flags** (যেকোনো ফ্ল্যাগ, যেমন `--fast-list --max-age 7d --backup-dir …`)।
- **ফোনের ফাইল সরাসরি:** "All files access" দিলে rclone যেকোনো ফোন-পাথ (`/storage/emulated/0/...`) ব্যবহার করতে পারে: প্রোফাইলে "This phone (all files)", সার্ভারে ও কমান্ডে।
- **rclone আপডেট:** অ্যাপে "Check for rclone update" নতুন ভার্সন জানায়। Android অ্যাপকে ডাউনলোড করা প্রোগ্রাম চালাতে দেয় না, তাই আপডেট মানে নতুন APK বিল্ড: GitHub → Actions → Build APK → Run workflow (`rclone_version` = latest বা নির্দিষ্ট ভার্সন)। workflow প্রতি সোমবার নিজে থেকেও সর্বশেষ rclone দিয়ে বিল্ড করে। রিপোজিটরি ভ্যারিয়েবল `RCLONE_VERSION` দিয়ে ভার্সন পিন করা যায়।
- **সম্ভব নয়:** `rclone mount` (FUSE/root লাগে)। কিছু ইন্টারঅ্যাক্টিভ কমান্ড (যেমন `ncdu`) টার্মিনাল UI ছাড়া চলে না।
- সার্ভার/Saved commands JSON ব্যাকআপে (Export/Import) অন্তর্ভুক্ত। `rclone.conf` আলাদাভাবে export করতে হয়।

### rclone বাইনারি কীভাবে আসে
`scripts/build-rclone.sh` Android NDK + Go দিয়ে rclone কম্পাইল করে `app/src/main/jniLibs/arm64-v8a/librclone.so` বানায়; GitHub workflow এটা নিজে করে (প্রথমবার ~১০ মিনিট, এরপর cache)। লোকালি বিল্ড করলে আগে স্ক্রিপ্টটি চালান (`ANDROID_NDK_HOME` সেট করে)। বাইনারি না থাকলে অ্যাপ চলে, শুধু rclone অংশে "not included in this build" দেখায়।
৩২-বিট ফোনের জন্য `RCLONE_ABIS="arm64-v8a armeabi-v7a"` দিন এবং `app/build.gradle.kts`-এর `abiFilters`-এ `armeabi-v7a` যোগ করুন। APK-র সাইজ বাড়বে (~৩০–৪০ MB প্রতি ABI)।
