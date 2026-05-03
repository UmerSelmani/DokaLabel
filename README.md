# Doka Label Studio — Android App

Native Android wrapper for the Doka Label Studio label designer.
Prints TSPL directly to the Xprinter XP-470B via USB OTG — **no RawBT, no NokoPrint, no "Choose an app" dialog.**

---

## 📱 Build from your phone (no PC needed) — GitHub Actions

GitHub will build the APK for free in the cloud. You just upload the code and download the APK.

### Step 1 — Create a GitHub account
Go to **github.com** → Sign up (free)

### Step 2 — Create a new repository
1. Tap the **+** button → **New repository**
2. Name it `DokaLabelStudio`
3. Set to **Private** (recommended)
4. Tap **Create repository**

### Step 3 — Upload the project files
1. On your new repo page, tap **uploading an existing file**
2. Upload all the files from this zip (keep the folder structure)
   - Easiest: use a file manager app that supports zip extraction, then upload folder by folder
   - Or use the GitHub mobile app
3. Tap **Commit changes**

### Step 4 — Watch it build
1. Tap the **Actions** tab on your repo
2. You'll see **Build APK** running — takes about 3–5 minutes
3. When it shows a green ✅, tap on it
4. Scroll down to **Artifacts** → tap **DokaLabelStudio-v1** → download the zip

### Step 5 — Install on your phone
1. Unzip the downloaded file → you'll have `app-debug.apk`
2. Tap the APK file to install
3. Android will warn "Install from unknown source" — tap **Install anyway**
4. Done!

### Trigger a new build anytime
Go to **Actions → Build APK → Run workflow** — no code changes needed.

---



The label designer UI runs in a WebView (same HTML/JS as the web app).  
A Kotlin `JavascriptInterface` (`window.AndroidBridge`) bridges JS → native USB.

When you tap **⎙ PRINT**:
1. JS renders the label to canvas, builds the TSPL byte buffer
2. JS calls `AndroidBridge.printTSPL(base64)` — goes straight to Kotlin
3. Kotlin opens the USB device, claims the bulk-OUT endpoint, streams the data
4. Done. No other app involved.

---

## Building

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Kotlin 1.9+

### Steps

1. Open Android Studio → **File → Open** → select this folder
2. Let Gradle sync finish
3. Create `local.properties` in the root with your SDK path:
   ```
   sdk.dir=/Users/yourname/Library/Android/sdk
   ```
4. Connect your phone (USB debugging on) or use an emulator
5. **Run → Run 'app'**

To build a release APK:
- **Build → Generate Signed Bundle/APK → APK**
- Or: `./gradlew assembleRelease`

---

## Using the app

1. Install the APK on your Android phone
2. Connect your Xprinter XP-470B via a **USB OTG cable**
3. Android will ask *"Open Doka Label Studio?"* — tap **Always** (only asked once)
4. Design your label in the app
5. Tap **⎙ PRINT** — the green dot next to the button means the printer is connected
6. Android will ask for USB permission once — tap **Allow**
7. Label prints immediately

The PRINT button now has a status dot:
- 🟢 Green = printer connected and ready
- 🔴 Red = no printer detected

---

## Settings (USB modal)

The **🖨** button or **⎙N** batch button still let you change:
- Label size (58×40, 100×150, etc.)
- Media type (pre-cut / continuous roll)
- Number of copies

These settings are saved and used automatically by the PRINT button.

---

## Offline use

The app loads the label designer from `assets/index.html`.  
The JS libraries (React, JsBarcode, QRCode) are loaded from CDN and require internet on first use.

**To make it fully offline**, download these files and place them in `app/src/main/assets/`:
- `react.production.min.js` — https://cdnjs.cloudflare.com/ajax/libs/react/18.2.0/umd/react.production.min.js
- `react-dom.production.min.js` — https://cdnjs.cloudflare.com/ajax/libs/react-dom/18.2.0/umd/react-dom.production.min.js
- `babel.min.js` — https://cdnjs.cloudflare.com/ajax/libs/babel-standalone/7.23.2/babel.min.js
- `jsbarcode.min.js` — https://cdnjs.cloudflare.com/ajax/libs/jsbarcode/3.11.5/JsBarcode.all.min.js
- `qrcode.min.js` — https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js

Then replace the CDN `<script src="https://...">` lines in `assets/index.html` with local paths:
```html
<script src="file:///android_asset/react.production.min.js"></script>
```

---

## Project structure

```
app/src/main/
├── assets/index.html              ← Label designer UI (WebView)
├── java/com/dokalabel/studio/
│   ├── MainActivity.kt            ← WebView host + USB print logic
│   └── UsbPrintBridge.kt          ← JS ↔ Kotlin bridge
├── res/
│   └── xml/device_filter.xml      ← Xprinter USB vendor IDs
└── AndroidManifest.xml
```
