# LocalNet Hub — Android App (Target SDK 35)

An Android app that turns your phone into a local network hub.
Other devices connect via a browser — **no internet required**.

---

## Features

- **Embedded HTTP Server** (port 8080) — serves a full chat web app to any connected device
- **Wi-Fi Direct (P2P)** — create or join an ad-hoc device group without a router
- **Real-time messaging** — all connected devices chat via the browser
- **Connected device tracker** — see who's on the network
- **Foreground service** — keeps the server alive when the app is in the background
- **Offline SSH tunnel relay** — host device can expose a local tunnel port (e.g. `:2222`) and forward traffic to any connected device SSH endpoint (`:22`)
- **Phase 1 QR SSH key pairing** — challenge-response payloads with SAS verification for offline device-to-device pairing

---

## How it works

1. **On the host Android phone:**
   - Open the app → server starts automatically on port 8080
   - Enable your phone's Wi-Fi hotspot (Settings → Hotspot) OR use Wi-Fi Direct tab to create a group
   - Note your IP address shown in the app

2. **On other devices (phones, laptops, tablets):**
   - Connect to the host's hotspot/Wi-Fi Direct group
   - Open a browser and go to: `http://<HOST_IP>:8080`
   - Start chatting — no internet, no accounts, no cloud

---

## Offline SSH Tunnel (No Internet Required)

- Open `http://<HOST_IP>:8080` and use the **Offline SSH Tunnel** section.
- Set **Target host/IP** to the SSH destination device, keep target port `22`, and set a listening port (default `2222`).
- Start the tunnel, then from any connected device run: `ssh -p 2222 <user>@<HOST_IP>`.

## Phase 1 QR SSH Key Pairing

- In the **Phase 1: QR SSH Key Pairing** section, enter your device name + SSH public key and create an **Init payload**.
- Encode that payload as a QR code on device A, scan it on device B, then generate a **Response payload** on B.
- Scan the response back on device A and run **Finalize**, then verify the same SAS appears on both devices.
- Endpoints added for this flow:
  - `POST /api/pairing/qr/init`
  - `POST /api/pairing/qr/respond`
  - `POST /api/pairing/qr/finalize`

## Build Instructions

### Requirements
- Android Studio Hedgehog or newer
- JDK 17+
- Android SDK 35 installed

### Steps
1. Open Android Studio
2. **File → Open** → select this folder (`LocalNetHub`)
3. Wait for Gradle sync to complete
4. Connect an Android device (API 26+) or start an emulator
5. Click **Run ▶**

### Build APK manually
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Build release APK
```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## Permissions Required

| Permission | Purpose |
|---|---|
| ACCESS_FINE_LOCATION | Required for Wi-Fi Direct on Android 10+ |
| NEARBY_WIFI_DEVICES | Required for Wi-Fi Direct on Android 13+ |
| ACCESS/CHANGE_WIFI_STATE | Wi-Fi Direct control |
| FOREGROUND_SERVICE | Keep server alive in background |
| INTERNET | Local socket server |
| WAKE_LOCK | Prevent sleep during server operation |

---

## Architecture

```
LocalNetHub/
├── server/
│   ├── LocalHttpServer.kt   — Pure Java HTTP server (no libraries needed)
│   └── NetworkService.kt    — Foreground service hosting the server
├── wifi/
│   └── WifiDirectManager.kt — Wi-Fi P2P group management
└── ui/
    ├── MainActivity.kt      — Two-tab UI (Server + Wi-Fi Direct)
    ├── MainViewModel.kt     — State management
    └── MessageAdapter.kt   — RecyclerView adapter
```

---

## The Web App (Served to Clients)

The server hosts a dark-themed web app at `http://<IP>:8080` that includes:
- Live chat with auto-refresh every 1.5 seconds
- Connected device list
- Custom display name
- Works on any browser — no app install needed on client devices

---

## Target SDK Details

- **compileSdk:** 35 (Android 15)
- **targetSdk:** 35
- **minSdk:** 26 (Android 8.0 Oreo)
- **Language:** Kotlin
- **Build system:** Gradle 8.7 with KTS
