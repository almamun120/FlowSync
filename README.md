# FlowSync

SMBSync2-এর আইডিয়ার উপর ভিত্তি করে Kotlin + Jetpack Compose (Material 3) দিয়ে নতুন করে লেখা সিঙ্ক অ্যাপ।

## ফিচার
- **Protocol (root ছাড়া):** Local folder (SAF), SMB 2/3, FTP/FTPS, SFTP (password বা private key), WebDAV
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

## Google Drive সেটআপ (একবার)
Drive ব্যবহার করতে Google-এর OAuth অনুমতি লাগে, যা আপনার নিজের Google Cloud প্রজেক্ট থেকে নিতে হয় (বিনামূল্যে):
1. console.cloud.google.com → নতুন প্রজেক্ট → **Google Drive API** Enable করুন।
2. OAuth consent screen: User type *External*, নিজের ইমেইল **Test users**-এ যোগ করুন (Testing মোডেই ব্যক্তিগত ব্যবহার চলে)।
3. Credentials → Create credentials → **OAuth client ID** → Application type **Android**:
   Package name = `app.flowsync`, SHA-1 = আপনার **release keystore**-এর SHA-1:
   `keytool -list -v -keystore flowsync.jks -alias flowsync` (SHA1 লাইনটি)
4. অ্যাপে প্রোফাইল → Google Drive → **Sign in with Google**।
⚠️ SHA-1 স্থায়ী keystore-এর সাথে বাঁধা, তাই Drive ব্যবহারের আগে উপরের "স্থায়ী কী" সেটআপ করে নিন।
