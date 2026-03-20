============================================================
   Dev_J's Auto Osu Mania — Setup & User Guide
   Version 1.6 | Made by Dev_J
============================================================

------------------------------------------------------------
 WHAT'S IN THE ZIP
------------------------------------------------------------

  Autoplay.jar              — The bot itself
  gson-2.10.1.jar           — Required library (JSON reading)
  jna-5.13.0.jar            — Required library (Windows input)
  jna-platform-5.13.0.jar   — Required library (Windows input)
  4key.json                 — Config for 4K mode
  5key.json                 — Config for 5K mode
  6key.json                 — Config for 6K mode
  7key.json                 — Config for 7K mode
  8key.json                 — Config for 8K mode
  9key.json                 — Config for 9K mode
  config.json               — Master default config
  run.bat                   — Double-click to launch
  README.txt                — This file


------------------------------------------------------------
 STEP 1 — INSTALL JAVA
------------------------------------------------------------

You need Java 11 or newer installed to run this.

  1. Go to: https://www.java.com/en/download/
  2. Download and install Java
  3. To check if Java is already installed, open Command Prompt
     and type:   java -version
     If you see a version number, you're good.


------------------------------------------------------------
 STEP 2 — EXTRACT THE ZIP
------------------------------------------------------------

  1. Right-click the zip file
  2. Click "Extract All"
  3. Keep ALL files in the SAME folder — do not move
     individual files out. The bot won't work if the
     jar files are separated from each other.

  Your folder should look like this:

  [Autoplay Folder]
    Autoplay.jar
    gson-2.10.1.jar
    jna-5.13.0.jar
    jna-platform-5.13.0.jar
    4key.json
    5key.json
    6key.json
    7key.json
    8key.json
    9key.json
    config.json
    run.bat
    README.txt


------------------------------------------------------------
 STEP 3 — LAUNCH THE BOT
------------------------------------------------------------

  Double-click  run.bat  to start.

  A terminal window will open first (this is normal),
  then the main bot window will appear.

  DO NOT close the terminal — it keeps the bot running.


------------------------------------------------------------
 STEP 4 — CONFIGURE FOR YOUR SCREEN
------------------------------------------------------------

The bot works by scanning specific pixel positions on your
screen to detect notes. You MUST set the correct coordinates
for your setup before using it.

  1. Open osu! and go into a Mania map
  2. Pause or use the editor to get a still frame
  3. Hover your mouse over the centre of each hit receptor
     (the glowing zone at the bottom of each column)
  4. Note the X and Y position shown in the bottom-right
     of your screen (or use a tool like ShareX or Greenshot
     to find pixel coordinates)

  In the bot window:
    - Select your key mode (4K, 5K, 6K... up to 9K)
    - In "Note Coords (x, y)" enter the X, Y of each
      column's hit receptor
    - In "Note Colors (r, g, b)" enter the RGB colour of
      each note at the moment it should be pressed
      (use a colour picker like PowerToys or ShareX)
    - In "Key Bindings" enter the key for each column
      to match your osu! Mania keybinds

  Click "Apply & Save" when done.
  Settings save automatically to the matching JSON file
  (e.g. 7K saves to 7key.json).


------------------------------------------------------------
 CONTROLS
------------------------------------------------------------

  P          Pause / Resume the bot
  ESC        Quit the bot entirely

  These work even while osu! is in focus.


------------------------------------------------------------
 HOW THE BOT WORKS
------------------------------------------------------------

The bot does NOT read any osu! game data or memory.
It works purely by looking at your screen pixels:

  1. It captures a small region of your screen every frame
     around the hit receptor area.

  2. For each column it checks if the pixel colour matches
     the note colour within a tolerance range.

  3. If a note is detected it presses and holds the key.

  4. For hold notes it scans upward along the column to
     check if the tail is still on screen. It keeps the
     key held until the tail disappears.

  5. It releases the key the moment detection goes false.

The bot only activates when the osu! window is in focus.
Tabbing out pauses input automatically.


------------------------------------------------------------
 TIPS FOR BEST ACCURACY
------------------------------------------------------------

  - Run osu! in Windowed or Borderless Windowed mode.
    Fullscreen can shift pixel positions on some setups.

  - Set COLOR_TOLERANCE in your JSON higher (40-50) if the
    bot is missing notes. Lower it (15-20) if it presses
    on empty columns.

  - Set BASE_DELAY_MS to 0 in the JSON for maximum speed.
    Increase to 1-2 if your CPU is running hot.

  - Coordinates need recalibrating if you:
      Change your osu! resolution
      Move the osu! window
      Change your scroll speed


------------------------------------------------------------
 TROUBLESHOOTING
------------------------------------------------------------

  Bot window does not open
    Make sure ALL jar files are in the same folder.
    Make sure Java is installed (run: java -version in cmd).

  Bot opens but nothing happens in-game
    Check that your Note Coords match your screen resolution.
    Make sure the osu! window title contains "osu!".

  Bot presses wrong keys or misses notes
    Recalibrate Note Coords and Note Colors.
    Increase COLOR_TOLERANCE in the JSON file.

  Warning messages appear in the terminal
    Lines starting with WARNING: about native access are
    harmless. The bot works normally with them present.


------------------------------------------------------------
 WHAT YOU CAN EDIT IN THE JSON FILES
------------------------------------------------------------

  BASE_DELAY_MS       Delay between scan frames (0 = fastest)
  HIT_DELAY_MS        Delay before pressing a key (usually 0)
  COLOR_TOLERANCE     How close the colour must match (0-255)
  PADDING             Extra pixels around the scan region
  HOLD_DETECTION      true = detect hold note tails
  HOLD_CHECK_OFFSET   Pixels upward to scan for hold tails
  NOTE_COORDS         X, Y of each column's hit zone
  NOTE_COLORS         R, G, B colour of notes at the hit line
  KEY_BINDINGS        Key to press for each column


------------------------------------------------------------
 CREDITS
------------------------------------------------------------

  Made by Dev_J
  Built with Java + Swing + JNA + Gson

============================================================
