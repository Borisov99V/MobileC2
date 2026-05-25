# MobileC2

An educational Command & Control framework for Android devices.

---

## Server Setup

### Option 1 — Local (venv)

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

### Option 2 — Docker

```bash
docker build -t mobilec2 .
docker run -p 8000:8000 mobilec2
```

Run in background:
```bash
docker run -d -p 8000:8000 mobilec2
```

With persistent data (survives container restarts):
```bash
docker run -d -p 8000:8000 \
  -v $(pwd)/exfil:/app/exfil \
  -v $(pwd)/data:/app \
  mobilec2
```

### Dashboard

Once the server is running, open `dashboard.html` in your browser or navigate to:
```
http://localhost:8000
```

---

## Building the APK

Give the script execute permission first (only needed once):
```bash
chmod +x build_apk.sh
```

Then build with your server IP and Android SDK path:
```bash
./build_apk.sh  <SERVER_IP> <SDK_PATH>
```

Example:
```bash
./build_apk.sh 192.168.1.100 /home/user/Android/Sdk
```

### Finding your SDK path

**Linux / Mac:** Usually located at `~/Android/Sdk`

**Windows:** Usually located at `C:\Users\<user>\AppData\Local\Android\Sdk`

### Installing Android SDK (if not already installed)

Download and install [Android Studio](https://developer.android.com/studio) — the SDK is included automatically. After installation the SDK will be at the default path above.

---

## How It Works

The agent sends a beacon to the server **every time the app is opened or brought to the foreground** (`onResume`). On each beacon:

1. Device info is sent to the server (model, OS version, battery)
2. Server responds with a pending command (if any)
3. Agent executes the command and sends the result back

> **Note:** The app must be open or reopened for the beacon to trigger. Force close and reopen the app to trigger a new beacon cycle.

---

## Available Commands

| Command | Description |
|---|---|
| `GET_DEVICE_INFO` | Model, OS version, battery level |
| `GEOIP_INFO` | Local IP address + GPS coordinates + accuracy |
| `GET_CONTACTS` | List of contacts (name + phone number) |
| `LAST_PHOTO` | Exfiltrates the most recent photo from the gallery |

Commands can be issued directly from the dashboard. Up to 3 commands can be queued per agent at a time.

---

## Known Limitations

- Beaconing requires the app to be in the foreground
- Camera capture not implemented in current version

---

## Team

- Borisov Victor Mihai
- Andrei Robert Dobre

