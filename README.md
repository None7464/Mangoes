[README.md](https://github.com/user-attachments/files/25966303/README.md)
# 🎵 Dev_J's Auto Osu Mania
> A pixel-based autoplay bot for osu! Mania and other games like osu mania. — supports 4K through 9K with a live config editor UI.

![Java](https://img.shields.io/badge/Java-11%2B-orange?style=flat-square&logo=openjdk)
![Platform](https://img.shields.io/badge/Platform-Windows-blue?style=flat-square&logo=windows)
![Mode](https://img.shields.io/badge/Modes-4K%20→%209K-purple?style=flat-square)
![License](https://img.shields.io/badge/License-Personal%20Use-red?style=flat-square)

---

## 📸 What It Does

- Scans your screen pixels to detect incoming notes
- Automatically presses and holds the correct keys
- Supports **hold notes** via vertical tail detection
- Switches between **4K, 5K, 6K, 7K, 8K, 9K** on the fly
- Live UI to edit coords, colors, and key bindings — no restart needed
- Saves all changes back to the matching `Xkey.json` config file

---

## ⚙️ Requirements

- **Windows** (uses WinAPI for key detection)
- **Java 11 or newer** — [Download here](https://www.java.com/en/download/)

To check if Java is installed, open Command Prompt and run:
```
java -version
```

---

## 🚀 Setup

### 1. Download

Grab the latest release zip from the [Releases](https://github.com/None7464/Mangoes/releases/tag/Autoplay) page and extract it.  
Keep **all files in the same folder** — the bot won't run if the jars are separated.

```
📁 Autoplay/
 ├── Autoplay.jar
 ├── gson-2.10.1.jar
 ├── jna-5.13.0.jar
 ├── jna-platform-5.13.0.jar
 ├── 4key.json
 ├── 5key.json
 ├── 6key.json
 ├── 7key.json
 ├── 8key.json
 ├── 9key.json
 ├── config.json
 ├── run.bat
 └── README.md
```

### 2. Launch

Double-click **`run.bat`** — a terminal window will open, then the bot UI will appear.

> ⚠️ Do **not** close the terminal window — it keeps the bot alive.

---

## 🎯 Calibration (Important!)

The bot reads **your screen pixels** — it doesn't touch osu! memory or files.  
You need to tell it where your note columns are.

**How to find your coordinates:**

1. Open osu! Mania and pause on a frame with notes visible
2. Hover your mouse over the **centre of each hit receptor** (the glow at the bottom of each lane)
3. Read the X, Y coordinates from the bottom-right corner of your screen  
   *(or use [ShareX](https://getsharex.com/) / Windows PowerToys color picker)*

**How to find your note colors:**

1. Take a screenshot at the moment a note is exactly on the hit line
2. Use a color picker to sample the RGB value of that note
3. Enter it into the bot's **Note Colors** panel

**In the bot UI:**

| Panel | What to enter |
|-------|--------------|
| Note Coords | `x, y` for each lane's hit receptor |
| Note Colors | `r, g, b` colour of notes at the hit line |
| Key Bindings | The key for each lane (e.g. `d`, `f`, `j`, `k`) |

Hit **Apply & Save** — changes take effect immediately and save to the active mode's JSON.

---

## 🕹️ Controls

| Key | Action |
|-----|--------|
| `P` | Pause / Resume |
| `ESC` | Quit |

These work even while osu! is focused and the bot window is in the background.

---

## 🔧 JSON Config Reference

Each key mode has its own file (`4key.json` → `9key.json`).  
You can hand-edit these or use the in-app editor.

```json
{
  "BASE_DELAY_MS": 1,
  "HIT_DELAY_MS": 0,
  "COLOR_TOLERANCE": 30,
  "PADDING": 5,
  "HOLD_DETECTION_ENABLED": true,
  "HOLD_CHECK_OFFSET": 50,
  "NOTE_COORDS": {
    "D": [835, 840],
    "F": [885, 840],
    "J": [935, 840],
    "K": [985, 840]
  },
  "NOTE_COLORS": {
    "D": [255, 255, 255],
    "F": [100, 150, 255],
    "J": [100, 150, 255],
    "K": [255, 255, 255]
  },
  "KEY_BINDINGS": {
    "D": "d",
    "F": "f",
    "J": "j",
    "K": "k"
  }
}
```

| Field | Description |
|-------|-------------|
| `BASE_DELAY_MS` | Delay between scan frames — set to `0` for max speed |
| `HIT_DELAY_MS` | Delay before pressing a detected key — usually `0` |
| `COLOR_TOLERANCE` | How close a pixel colour must be to trigger — raise if missing notes |
| `PADDING` | Extra pixels added around the scan capture region |
| `HOLD_DETECTION_ENABLED` | `true` = detect and hold long notes |
| `HOLD_CHECK_OFFSET` | How many pixels upward to scan for hold note tails |

---

## 🐛 Troubleshooting

**Bot window doesn't open**
- Make sure all `.jar` files are in the same folder as `Autoplay.jar`
- Confirm Java is installed: `java -version` in Command Prompt

**Bot runs but nothing happens in-game**
- Your Note Coords might be wrong for your screen resolution
- Make sure the osu! window title contains `osu!`

**Bot misses notes or presses at wrong times**
- Recalibrate Note Coords and Note Colors for your screen
- Raise `COLOR_TOLERANCE` in the JSON (try `40` or `50`)

**Terminal shows WARNING lines about native access**
- These are harmless — JNA needs native access to read your keyboard state
- They're already silenced in `run.bat` with `--enable-native-access=ALL-UNNAMED`

---

## 🏗️ Building from Source

```bash
# Compile
javac -cp ".;gson-2.10.1.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar" Autoplay.java

# Package
jar cfe Autoplay.jar Autoplay Autoplay*.class

# Run
java --enable-native-access=ALL-UNNAMED -cp "Autoplay.jar;gson-2.10.1.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar" Autoplay
```

**Dependencies:**
Notice: No Need To Download this as this is in the .zip already.
- [Gson 2.10.1](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.10.1/)
- [JNA 5.13.0](https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/)
- [JNA PLATFORM 5.13.0](https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.13.0/)

---

## 📋 Changelog

| Version | Date | Changes |
|---------|------|---------|
| v1.6 | 2025-03-13 | Live key binding editor, fixed P-key pause (was broken since v1.0) |
| v1.5 | 2025-02-28 | Multi-mode 4K–9K support, per-mode JSON files |
| v1.4 | 2025-02-02 | Replaced console stats with Swing UI |
| v1.3 | 2025-01-15 | Fixed ~291MB/min memory leak in scanner loop |
| v1.2 | 2024-12-08 | Hold note tail detection |
| v1.1 | 2024-11-21 | Moved all settings to config.json |
| v1.0 | 2024-11-03 | Initial release, 4K only |
---

## ⚠️ Disclaimer

This tool is for **educational and personal use only**.

Using this autoplay tool in **online multiplayer, ranked matches, tournaments, or any official competitive environment** may violate the game's Terms of Service and could result in penalties or account bans.

This tool is intended to be used **only in offline modes or casual games with friends for fun/experimentation**.

Use at your own risk.

---

<div align="center">
  Made by <b>Dev_J</b> &nbsp;•&nbsp; Built with Java + Swing + JNA + Gson
</div>
