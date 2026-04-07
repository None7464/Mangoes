# Sailor Mango V 0.5

Desktop JavaFX automation tool with Roblox embed support, pathing modules, recording tools, and macro utilities.

## Date

April 7, 2026

## Developer

- Dev_Still In Love (Won't Answer Dms)
- Discord ID: `890484073485717535`
- Notes For Ccs: You can post this mango anywhere you like
- Ty Copilot for this readme.md im lazy
## Requirements

- Windows 10/11
- JDK 21+ [recommended](https://download.oracle.com/java/26/latest/jdk-26_windows-x64_bin.exe)
- JavaFX SDK folder in project root:
  - `javafx-sdk-26/lib`
- Libraries in project root:
  - `libs/jna-5.13.0.jar`
  - `libs/jna-platform-5.13.0.jar`
  - `libs/gson-2.10.1.jar`

## Project Layout

- `SailorPieceMango.java` - main app
- `sailor.bat` - compile, jar, cleanup, launch script
- `UI/UI.html` - dashboard markup
- `UI/UI.css` - dashboard styles
- `config.txt` - profile/config data (auto-created if missing)
- `Pathings/` - pathing profiles (boss/npc/tower)
- `Bosses.txt` - boss list for UI dropdown

## Quick Start

1. Place JavaFX and libs exactly as listed in Requirements.
2. Double-click `sailor.bat`.
3. The script will:
   - compile Java
   - build `SailorPieceMango.jar`
   - remove loose `.class` files
   - launch the app from jar

## Manual Run (optional)

```bat
javac --module-path "javafx-sdk-26\lib" --add-modules javafx.web,javafx.controls -cp ".;libs\jna-5.13.0.jar;libs\jna-platform-5.13.0.jar;libs\gson-2.10.1.jar" -Xlint:-removal SailorPieceMango.java
jar cfe SailorPieceMango.jar SailorPieceMango *.class
del /q "*.class"
java --enable-native-access=javafx.graphics,javafx.web,ALL-UNNAMED --module-path "javafx-sdk-26\lib" --add-modules javafx.web,javafx.controls -cp "SailorPieceMango.jar;libs\jna-5.13.0.jar;libs\jna-platform-5.13.0.jar;libs\gson-2.10.1.jar" SailorPieceMango
```

## Features

### Roblox Embed

- Auto-detects `RobloxPlayerBeta.exe` every 5 seconds.
- Auto-normalizes/attaches Roblox window when detected.
- Status indicator updates in UI.

### Combo System

- Toggleable auto combo.
- Supports combo string parsing (`Z > X > C > ...`).
- Nuke mode option.
- Key-press counter-style logs (repeated keys summarized with counts).

### Automation Modes

- Auto Boss
- Auto Kill NPCs
- Auto Tower
- Optional `Add AutoCombo After Pathing` toggle.
- Pathing uses timed key-action entries from text files.

### Pathing Profile Creator

- Create NPC pathing profile files.
- Create Boss pathing profile files:
  - file format: `Pathings/Boss<name>.txt`
- Legacy cleanup on boss profile creation:
  - deletes `Pathings/bosspathing.txt` if found.

### WASD Recorder

- Records keys: `w`, `a`, `s`, `d`, `ctrl`, `esc`, `r`, `enter`
- Output format:
  - `<milliseconds_since_record_start>,<key>,<down|up>`
- Starting a new recording resets target file and records fresh.

### Melee Swap Module (F1)

- Global F1 toggles melee swap on/off.
- Set `Coord A` and `Coord B` by left-click capture.
- Loop sequence:
  - click A -> `1` -> `F`
  - click B -> `1` -> `F`
- Includes configurable buffer in Java (`meleeSwapBufferMs`).

### Logs Window

- Floating resizable logs window.
- Hidden on launch by default.
- Open/close from `Logs` button in sidebar.
- Streams runtime info and errors.

## Pathing File Format

Each line:

```text
<timestamp_ms>,<key>,<action>
```

Example:

```text
828,s,down
3125,s,up
```

Notes:

- `timestamp_ms` is elapsed milliseconds from start of recording.
- `action` should be `down` or `up`.

## Troubleshooting

- Roblox not attaching:
  - Ensure Roblox is open.
  - Run app normally (admin generally not required).
  - Check Logs window for attach/pathing errors.

- Automation toggle turns on but nothing happens:
  - Confirm the expected pathing `.txt` exists and has valid lines.
  - Confirm selected boss name has matching file.

- JavaFX launch/module errors:
  - Verify `javafx-sdk-26/lib` path is correct.
  - Verify all jars in `libs/` exist.

- JNA errors:
  - Ensure both JNA jars are present and readable.

## Safety / Usage Notes

- This project sends keyboard/mouse input to active windows.
- Use responsibly and at your own risk.
- Keep profile/pathing files backed up if you edit frequently.

## License

This project source currently has no explicit standalone project license file.

Third-party libraries are licensed by their respective authors:

- JNA (`jna`, `jna-platform`)
- Gson
- JavaFX

You must follow each dependency's license terms when redistributing binaries or source bundles.
