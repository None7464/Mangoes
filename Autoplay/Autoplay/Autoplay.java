/*
 * Autoplay.java — Dev_J's Auto Osu Mania
 *
 * Author : Dev_J
 * Created: 2024-11-03
 * Updated: 2025-03-19
 *
 * Changelog:
 *   2024-11-03  v1.0  Initial working build. Single config.json, 4K only, console stats.
 *   2024-11-21  v1.1  Pulled out magic numbers into config. Stopped hard-coding coords.
 *   2024-12-08  v1.2  Hold note detection. Vertical-scan approach after single-point kept
 *                     missing tails on faster maps.
 *   2025-01-15  v1.3  Rewrote scanner loop — was leaking ~40 MB/min, see notes in scanner().
 *   2025-02-02  v1.4  Replaced console stats panel with Swing UI. ProcessBuilder("cls")
 *                     every 500 ms was spawning OS processes faster than they could exit.
 *   2025-02-28  v1.5  Multi-mode support (4K-9K). Each mode gets its own JSON.
 *   2025-03-13  v1.6  Live key-binding editor. Moved from VK_ lookup table to full switch.
 *                     Killed the old getLockingKeyState() pause toggle — only works on
 *                     Caps/Num/Scroll, not regular keys, so P-key pause never worked.
 *
 * Compile:
 *   javac -cp ".;gson-2.10.1.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar" Autoplay.java
 *
 * Run:
 *   java -cp ".;gson-2.10.1.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar" Autoplay
 */

import com.google.gson.*;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Autoplay {

    // =========================================================================
    //  CONFIG — loaded fresh from JSON on every mode switch, never mutated
    //  directly outside of loadFromFile() or applyEdits().
    //
    //  2024-11-21: Pulled these out of hard-coded constants after spending 2 hours
    //  recompiling just to move a coordinate by 5px. Never again.
    // =========================================================================

    private static int    BASE_DELAY_MS, RELEASE_DELAY_MS, HIT_DELAY_MS;
    private static int    TOLERANCE, PADDING, HOLD_CHECK_OFFSET;
    private static boolean DEBUG, HOLD_DETECTION_ENABLED;
    private static double DEBUG_UPDATE_RATE;

    /*
     * Lane name -> detection data.
     * Key is the label shown in the UI AND written to the JSON — "D", "F", "Space", etc.
     * Keeping these as LinkedHashMap so iteration order stays consistent with the JSON
     * order. Regular HashMap was shuffling the UI rows on every load which looked terrible.
     */
    private static final Map<String, Point>   noteCoords      = new LinkedHashMap<>();
    private static final Map<String, Color>   noteColors      = new LinkedHashMap<>();
    private static final Map<String, String>  keyBindingNames = new LinkedHashMap<>(); // lane -> "d","space"...
    private static final Map<String, Integer> keyBindings     = new LinkedHashMap<>(); // lane -> VK_*

    // captureBox is volatile because scanner reads it off the EDT-write path.
    // Marking it volatile instead of synchronising every read saves a lock acquisition
    // per frame which adds up at 500+ FPS.
    private static volatile Rectangle captureBox;

    // The hot lookup: screen Point -> NoteData. Rebuilt whenever config changes.
    private static final Map<Point, NoteData> keyMap = new LinkedHashMap<>();

    // Tracks which JSON file is currently loaded so saveCurrentFile() always
    // writes back to the right place. Switch to 7K? Saves to 7key.json. Simple.
    private static String currentConfigFile = "4key.json";
    private static int    currentKeyMode    = 4;


    // =========================================================================
    //  RUNTIME STATE
    //
    //  All counters are Atomic* — the scanner thread writes them, the EDT reads
    //  them for the UI. Using volatile longs instead caused occasional torn reads
    //  on 32-bit JVMs (long writes aren't guaranteed atomic without volatile on
    //  older runtimes). AtomicLong costs basically nothing and is always safe.
    // =========================================================================

    private static final AtomicBoolean paused     = new AtomicBoolean(false);
    private static final AtomicInteger keypresses = new AtomicInteger(0);
    private static final AtomicInteger holdNotes  = new AtomicInteger(0);
    private static final AtomicLong    startTime  = new AtomicLong(System.currentTimeMillis());
    private static volatile double fps = 0;

    /*
     * Keys the bot is currently holding down: keyCode -> timestamp when first pressed.
     * ConcurrentHashMap because the scanner thread writes it and the UI thread reads
     * the size for the "Keys Held" counter.
     *
     * 2025-01-15: Was a regular HashMap before. Got a ConcurrentModificationException
     * after about 20 minutes of runtime when the UI refresh happened to iterate
     * the map mid-write. Switched to ConcurrentHashMap, problem gone instantly.
     */
    private static final Map<Integer, Long> currentlyHeldKeys = new ConcurrentHashMap<>();

    private static Robot robot;

    /*
     * 2025-02-02: Thread pool size reduced from 3 -> 2.
     * The third thread was the console stats updater which we killed when Swing
     * came in. Fewer threads = fewer context switches = scanner gets more CPU time.
     *
     * Daemon flag is critical here. Without it the JVM won't exit when the user
     * closes the window because the scheduler threads are still alive. Closing the
     * window would appear to do nothing — window gone, process still running in the
     * background eating CPU. Daemon threads die with the JVM automatically.
     */
    private static final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "autoplay-worker");
            t.setDaemon(true); // must be daemon — see note above
            return t;
        });


    // =========================================================================
    //  SWING UI REFERENCES
    //  Kept as static fields so refreshUI() can update them from the scheduled
    //  task without passing references around everywhere.
    // =========================================================================

    private static JFrame     frame;
    private static JLabel     statusDot, statusText;
    private static JLabel     fpsLabel, kpLabel, holdLabel, holdingLabel, uptimeLabel, memLabel;
    private static JPanel     rightPanel;
    private static JScrollPane configScroll;

    /*
     * These three maps are cleared and repopulated by buildFieldsPanel() on every
     * mode switch. That's intentional — when you go from 4K to 7K the number of
     * lanes changes, so we can't just update existing fields, we have to rebuild
     * the whole section. Happens fast enough the user won't notice.
     */
    private static final Map<String, JTextField> coordFields   = new LinkedHashMap<>();
    private static final Map<String, JPanel>     colorSwatches = new LinkedHashMap<>();
    private static final Map<String, JTextField> colorFields   = new LinkedHashMap<>();
    private static final Map<String, JTextField> keyFields     = new LinkedHashMap<>();

    private static final ButtonGroup                 keyModeGroup = new ButtonGroup();
    private static final Map<Integer, JToggleButton> modeBtns     = new HashMap<>();


    // =========================================================================
    //  THEME CONSTANTS
    //  2025-02-02: Pulled out of individual component builders after the third
    //  time I changed the accent colour and had to hunt down 15 different places.
    // =========================================================================

    private static final Color BG        = new Color(10, 10, 18);
    private static final Color PANEL_BG  = new Color(20, 20, 33);
    private static final Color ACCENT    = new Color(99, 102, 241);
    private static final Color TEXT_MAIN = new Color(228, 228, 255);
    private static final Color TEXT_DIM  = new Color(120, 120, 160);
    private static final Color GREEN     = new Color(74, 222, 128);
    private static final Color AMBER     = new Color(251, 191, 36);
    private static final Color ERR_BG    = new Color(80, 25, 25);


    // =========================================================================
    //  INNER TYPE — NoteData
    //  Intentionally just a plain data holder. Tried making this a record in
    //  Java 16+ but the project still targets 11 for compatibility, so class it is.
    // =========================================================================

    static class NoteData {
        final String key;
        final Color  targetColor;
        final int    keyCode;
        final Point  originalPoint;

        NoteData(String key, Color color, int kc, Point p) {
            this.key = key;
            this.targetColor = color;
            this.keyCode = kc;
            this.originalPoint = p;
        }
    }


    // =========================================================================
    //  ENTRY POINT
    // =========================================================================

    public static void main(String[] args) {
        try {
            // autoDelay(0) and autoWaitForIdle(false) are both important.
            // Default autoDelay is 0 but the docs say it might change — setting it
            // explicitly means we don't get surprised by a JDK update. autoWaitForIdle
            // was causing ~40ms stalls between keypresses on busy systems.
            robot = new Robot();
            robot.setAutoDelay(0);
            robot.setAutoWaitForIdle(false);

            // Boot straight into 4K. If someone never touches the mode buttons
            // they probably only play 4K anyway.
            loadFromFile("4key.json");

            // Build the UI on the Event Dispatch Thread. Swing is not thread-safe.
            // Calling buildUI() directly from main() here would technically work most
            // of the time, but would cause random race conditions on slower machines
            // where the scheduler starts posting EDT events before the UI is ready.
            SwingUtilities.invokeAndWait(Autoplay::buildUI);

            setupKeyboardListener();

            // UI refresh rate is driven by DEBUG_UPDATE_RATE from the JSON.
            // Default is 0.5s which is plenty for a stats panel. Faster than that
            // and you can actually see the text flickering.
            scheduler.scheduleAtFixedRate(
                () -> SwingUtilities.invokeLater(Autoplay::refreshUI),
                0, (long)(DEBUG_UPDATE_RATE * 1000), TimeUnit.MILLISECONDS
            );

            scheduler.execute(Autoplay::scanner);

            // Shutdown hook: give the scheduler a second to finish whatever it's doing
            // before the JVM tears everything down. Without this, keys could be left
            // physically held down if the bot exits mid-press. That would be bad.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                scheduler.shutdownNow();
                try { scheduler.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null,
                "Failed to start:\n" + e.getMessage(), "Startup Error",
                JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }


    // =========================================================================
    //  CONFIG — LOAD
    //
    //  2024-12-08: Added HOLD_DETECTION_ENABLED and HOLD_CHECK_OFFSET with
    //  safe defaults via cfg.has() so existing configs without those keys
    //  don't crash on load. Forgot to do this the first time and spent 20 minutes
    //  wondering why it kept throwing NullPointerException on an old save.
    //
    //  2025-02-28: Synchronized because the EDT can call this (via switchKeyMode)
    //  while the scanner is mid-loop reading keyMap. Without the lock you'd
    //  occasionally get a partially-rebuilt keyMap where some lanes had new coords
    //  but old key codes. Caused phantom keypresses on mode switches.
    // =========================================================================

    private static synchronized void loadFromFile(String filename) throws IOException {
        currentConfigFile = filename;
        String content = new String(Files.readAllBytes(Paths.get(filename)));
        JsonObject cfg = new Gson().fromJson(content, JsonObject.class);

        BASE_DELAY_MS     = cfg.get("BASE_DELAY_MS").getAsInt();
        RELEASE_DELAY_MS  = cfg.get("RELEASE_DELAY_MS").getAsInt();
        HIT_DELAY_MS      = cfg.get("HIT_DELAY_MS").getAsInt();
        TOLERANCE         = cfg.get("COLOR_TOLERANCE").getAsInt();
        PADDING           = cfg.get("PADDING").getAsInt();
        DEBUG             = cfg.get("DEBUG").getAsBoolean();
        DEBUG_UPDATE_RATE = cfg.get("DEBUG_UPDATE_RATE").getAsDouble();

        // Defensive fallback — older configs won't have these two fields.
        HOLD_DETECTION_ENABLED = cfg.has("HOLD_DETECTION_ENABLED")
            ? cfg.get("HOLD_DETECTION_ENABLED").getAsBoolean() : true;
        HOLD_CHECK_OFFSET = cfg.has("HOLD_CHECK_OFFSET")
            ? cfg.get("HOLD_CHECK_OFFSET").getAsInt() : 50;

        noteCoords.clear();
        for (var e : cfg.getAsJsonObject("NOTE_COORDS").entrySet()) {
            var arr = e.getValue().getAsJsonArray();
            noteCoords.put(e.getKey(), new Point(arr.get(0).getAsInt(), arr.get(1).getAsInt()));
        }

        noteColors.clear();
        for (var e : cfg.getAsJsonObject("NOTE_COLORS").entrySet()) {
            var arr = e.getValue().getAsJsonArray();
            noteColors.put(e.getKey(), new Color(
                arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()));
        }

        // Key bindings: store both the raw string ("space", "d", ";") for display/save
        // and the resolved VK code for actual use. Keeping them separate avoids doing
        // getKeyCode() inside the hot scanner loop on every frame.
        keyBindingNames.clear();
        keyBindings.clear();
        for (var e : cfg.getAsJsonObject("KEY_BINDINGS").entrySet()) {
            String keyStr = e.getValue().getAsString();
            keyBindingNames.put(e.getKey(), keyStr);
            keyBindings.put(e.getKey(), getKeyCode(keyStr));
        }

        rebuildCaptureAndKeyMap();
    }


    // =========================================================================
    //  CONFIG — REBUILD CAPTURE BOX + KEY MAP
    //
    //  2024-12-08: captureBox used to be fixed in the config JSON. That was fine
    //  until I changed a coord and forgot to update the box — bot kept scanning a
    //  region that didn't include the new lane position. Now it's computed
    //  automatically from whatever coords are in the map.
    //
    //  HOLD_CHECK_OFFSET extends the box *upward* so the tail-scan region is
    //  within the captured area. Easy to miss — if offset > PADDING you end up
    //  scanning pixels outside the image bounds which throws ArrayIndexOutOfBounds
    //  inside detectHoldTail. The -HOLD_CHECK_OFFSET on minY handles that.
    // =========================================================================

    private static synchronized void rebuildCaptureAndKeyMap() {
        if (noteCoords.isEmpty()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Point p : noteCoords.values()) {
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
        }

        captureBox = new Rectangle(
            minX - PADDING,
            minY - PADDING - HOLD_CHECK_OFFSET, // extend up for tail detection
            maxX - minX + PADDING * 2 + 1,
            maxY - minY + PADDING * 2 + HOLD_CHECK_OFFSET + 1
        );

        // Rebuild the Point -> NoteData lookup the scanner uses.
        // getOrDefault guards against a config that's missing a lane in one of the
        // sub-objects. Unlikely but JSON is user-editable so anything goes.
        keyMap.clear();
        for (String lane : noteCoords.keySet()) {
            keyMap.put(noteCoords.get(lane), new NoteData(
                keyBindingNames.getOrDefault(lane, lane.toLowerCase()),
                noteColors.getOrDefault(lane, Color.WHITE),
                keyBindings.getOrDefault(lane, KeyEvent.VK_SPACE),
                noteCoords.get(lane)
            ));
        }
    }


    // =========================================================================
    //  CONFIG — SAVE
    //
    //  We read the existing file first and only overwrite the three sections we
    //  know about (NOTE_COORDS, NOTE_COLORS, KEY_BINDINGS). This preserves any
    //  other fields like BASE_DELAY_MS that the user might have hand-edited.
    //  Nuking and rewriting the whole thing would've been simpler but would've
    //  destroyed changes made outside the UI.
    // =========================================================================

    private static void saveCurrentFile() {
        try {
            String content = new String(Files.readAllBytes(Paths.get(currentConfigFile)));
            JsonObject cfg = new Gson().fromJson(content, JsonObject.class);

            JsonObject coords = new JsonObject();
            noteCoords.forEach((lane, p) -> {
                JsonArray a = new JsonArray(); a.add(p.x); a.add(p.y);
                coords.add(lane, a);
            });
            cfg.add("NOTE_COORDS", coords);

            JsonObject colors = new JsonObject();
            noteColors.forEach((lane, c) -> {
                JsonArray a = new JsonArray();
                a.add(c.getRed()); a.add(c.getGreen()); a.add(c.getBlue());
                colors.add(lane, a);
            });
            cfg.add("NOTE_COLORS", colors);

            JsonObject bindings = new JsonObject();
            keyBindingNames.forEach(bindings::addProperty);
            cfg.add("KEY_BINDINGS", bindings);

            Files.write(Paths.get(currentConfigFile),
                new GsonBuilder().setPrettyPrinting().create().toJson(cfg).getBytes());

        } catch (IOException ex) {
            // Not throwing here — a failed save shouldn't take down the scanner.
            // User will see the file unchanged next time they load it though,
            // so we at least print the stack trace so they know something went wrong.
            ex.printStackTrace();
        }
    }


    // =========================================================================
    //  SCANNER
    //
    //  2025-03-19: The synchronized block used to wrap the entire detection
    //  loop — every colorMatches() call was holding the lock, which blocked
    //  the EDT if the user hit Apply mid-scan. Now we snapshot the NoteData
    //  entries into a plain array under the lock and release immediately.
    //  Detection runs lock-free. The snapshot is a shallow copy so it's cheap.
    //
    //  Also set keysToHold as a plain int[] bitmask instead of HashSet —
    //  no allocation, no boxing, O(1) contains check via array index.
    //  Since VK codes are small integers this works cleanly.
    // =========================================================================
    private static void scanner() {
        long lastFpsTime = System.currentTimeMillis();
        int  frameCount  = 0;
        BufferedImage img = null;

        // Pre-allocated snapshot array — resized only when lane count changes.
        NoteData[] snapshot = new NoteData[0];

        try {
            while (!Thread.currentThread().isInterrupted()) {

                if (paused.get() || !isGameFocused()) {
                    Thread.sleep(100);
                    continue;
                }

                Rectangle box;
                // Snapshot keyMap and captureBox atomically, then release lock immediately.
                synchronized (Autoplay.class) {
                    box = captureBox;
                    if (keyMap.size() != snapshot.length) {
                        snapshot = new NoteData[keyMap.size()];
                    }
                    int idx = 0;
                    for (NoteData nd : keyMap.values()) snapshot[idx++] = nd;
                }

                if (box == null) { Thread.sleep(50); continue; }

                if (img != null) img.flush();
                img = robot.createScreenCapture(box);

                // Detection runs without holding any lock — snapshot is read-only
                // int[] bitmask for keysToHold: index = keyCode, value = 1 if held
                // Using a small boolean array keyed by VK code (max ~600)
                boolean[] hold = new boolean[600];
                for (NoteData nd : snapshot) {
                    if (nd == null) continue;
                    boolean hit = HOLD_DETECTION_ENABLED
                        ? detectHoldTail(img, nd.originalPoint, nd.targetColor)
                        : detectSingleNote(img, nd.originalPoint, nd.targetColor);
                    if (hit && nd.keyCode < 600) hold[nd.keyCode] = true;
                }

                // Build the held-keys set for updateKeyStates from the bool array
                Set<Integer> keysToHold = new HashSet<>();
                for (NoteData nd : snapshot) {
                    if (nd != null && nd.keyCode < 600 && hold[nd.keyCode]) {
                        keysToHold.add(nd.keyCode);
                    }
                }

                updateKeyStates(keysToHold);

                frameCount++;
                long now = System.currentTimeMillis();
                if (now - lastFpsTime >= 1000) {
                    fps = frameCount / ((now - lastFpsTime) / 1000.0);
                    frameCount = 0;
                    lastFpsTime = now;
                }

                if (BASE_DELAY_MS > 0) Thread.sleep(BASE_DELAY_MS);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (img != null) img.flush();
        }
    }


    // =========================================================================
    //  DETECTION — HOLD TAIL
    //
    //  2025-03-19: Cached captureBox.x/y into locals — JVM can't hoist a
    //  volatile field read out of a loop, so every iteration was re-reading it
    //  from main memory. Local copy pins it in a register for the loop body.
    //  Also switched inner 3x3 to bulk getRGB like detectSingleNote.
    //  -- Fixed shit that kyle broke + it was outdated asf
    // =========================================================================
    private static boolean detectHoldTail(BufferedImage img, Point orig, Color target) {
        final int bx = captureBox.x;
        final int by = captureBox.y;
        final int iw = img.getWidth();
        final int ih = img.getHeight();

        int rx = orig.x - bx;
        int ry = orig.y - by;

        int matchCount  = 0;
        int checkPoints = 10;
        int step        = HOLD_CHECK_OFFSET / checkPoints;

        for (int i = 0; i < checkPoints; i++) {
            int cy = ry - (i * step);
            if (cy < 0) break;

            int px = Math.max(0, rx - 1);
            int py = Math.max(0, cy - 1);
            int pw = Math.min(3, iw - px);
            int ph = Math.min(3, ih - py);
            if (pw <= 0 || ph <= 0) continue;

            int[] patch = img.getRGB(px, py, pw, ph, null, 0, pw);
            for (int rgb : patch) {
                if (colorMatches(rgb, target)) { matchCount++; break; }
            }
        }

        return matchCount >= 3;
    }


    // =========================================================================
    //  DETECTION — SINGLE NOTE
    //
    //  2025-03-19: getRGB(x,y) in a loop does a bounds check + color-model
    //  lookup on every call. getRGB(x,y,w,h,null,0,w) pulls the whole patch
    //  into an int[] in one native call — faster for the 3x3 we're sampling.
    // =========================================================================
    private static boolean detectSingleNote(BufferedImage img, Point orig, Color target) {
        int rx = orig.x - captureBox.x - 1;
        int ry = orig.y - captureBox.y - 1;

        // Clamp patch to image bounds
        int px = Math.max(0, rx);
        int py = Math.max(0, ry);
        int pw = Math.min(3, img.getWidth()  - px);
        int ph = Math.min(3, img.getHeight() - py);
        if (pw <= 0 || ph <= 0) return false;

        int[] pixels = img.getRGB(px, py, pw, ph, null, 0, pw);
        for (int rgb : pixels) {
            if (colorMatches(rgb, target)) return true;
        }
        return false;
    }


    // =========================================================================
    //  DETECTION — COLOUR MATCH
    //
    //  2025-03-19 — REWRITE. Old flat per-channel tolerance missed faded notes
    //  entirely. A note at 60% opacity has the same hue as at 100% but lower
    //  brightness — the old check just failed silently and the key never pressed.
    //
    //  Fix: normalize the pixel's brightness to match the target's before comparing.
    //  Scaling both to the same luminance means faded/translucent versions of the
    //  same colour now match. White targets bypass this and use the old check
    //  because normalizing near-white just amplifies noise.
    //
    //  fadedTol is slightly wider than TOLERANCE because float scaling introduces
    //  rounding error — a pixel that's genuinely the right hue can land ±2 off
    //  after the multiply. The extra headroom catches those without false-positives.
    // =========================================================================
    private static boolean colorMatches(int rgb, Color t) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8)  & 0xFF;
        int b =  rgb        & 0xFF;

        int tr = t.getRed(), tg = t.getGreen(), tb = t.getBlue();

        // Skip near-black pixels immediately — background, shadows, empty lanes.
        // Checking lum instead of each channel is one comparison instead of three.
        int lum  = r + g + b;
        if (lum < 40) return false;

        int tLum = tr + tg + tb;

        // White or near-white target (user set it intentionally) — standard check.
        // Normalizing white just chases noise so we skip straight to tolerance.
        if (tLum > 580) {
            return Math.abs(r - tr) <= TOLERANCE
                && Math.abs(g - tg) <= TOLERANCE
                && Math.abs(b - tb) <= TOLERANCE;
        }

        // Coloured target: scale pixel luminance to match target luminance, then
        // compare. This makes faded (darker) versions of the same hue pass.
        float scale = (float) tLum / lum;
        int nr = Math.min(255, (int)(r * scale));
        int ng = Math.min(255, (int)(g * scale));
        int nb = Math.min(255, (int)(b * scale));

        int fadedTol = TOLERANCE + (TOLERANCE >> 1); // TOLERANCE * 1.5, no float
        return Math.abs(nr - tr) <= fadedTol
            && Math.abs(ng - tg) <= fadedTol
            && Math.abs(nb - tb) <= fadedTol;
    }


    // =========================================================================
    //  KEY STATE MANAGEMENT
    //
    //  2025-03-19: Removed Thread.sleep(HIT_DELAY_MS) from the press path.
    //  That sleep was blocking the entire scanner thread — every key press
    //  added HIT_DELAY_MS of dead time where no detection was happening at all.
    //  At 60 FPS that's a 16ms window per frame; adding even 5ms sleep meant
    //  we were effectively running at ~30 FPS during bursts of notes.
    //
    //  If you need per-key timing, do it on a separate thread. For osu the
    //  right answer is just HIT_DELAY_MS = 0 in the JSON and let the scanner
    //  run flat-out. Removed the sleep entirely rather than leaving a footgun.
    // =========================================================================
    private static void updateKeyStates(Set<Integer> keysToHold) {
        long now = System.currentTimeMillis();

        // Release stale keys first — always before pressing new ones
        var it = currentlyHeldKeys.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!keysToHold.contains(e.getKey())) {
                robot.keyRelease(e.getKey());
                it.remove();
            }
        }

        // Press new keys — no sleep, no yield, straight through
        for (int kc : keysToHold) {
            if (!currentlyHeldKeys.containsKey(kc)) {
                robot.keyPress(kc);
                currentlyHeldKeys.put(kc, now);
                keypresses.incrementAndGet();
                if (HOLD_DETECTION_ENABLED) holdNotes.incrementAndGet();
            }
        }
    }

    // =========================================================================
    //  KEYBOARD LISTENER — PAUSE (P) AND EXIT (ESC)
    //
    //  2025-03-13: Replaced getLockingKeyState() with GetAsyncKeyState via JNA.
    //
    //  getLockingKeyState() is documented to work with VK_CAPS_LOCK, VK_NUM_LOCK,
    //  and VK_SCROLL_LOCK only. On VK_P it either throws IllegalArgumentException
    //  or silently returns false depending on the JDK version. So the pause key
    //  literally never worked in any previous version of this bot.
    //
    //  Old code that silently did nothing:
    // -------------------------------------------------------------------------
    //  private static boolean isKeyPressed(int keyCode) {
    //      try {
    //          return Toolkit.getDefaultToolkit().getLockingKeyState(keyCode);
    //          // VK_P is not a locking key. This always returned false.
    //          // No exception was thrown so nobody noticed for months.
    //      } catch (Exception e) {
    //          return false;
    //      }
    //  }
    // -------------------------------------------------------------------------
    //
    //  GetAsyncKeyState reads the physical key state directly from the Windows
    //  input system. Works even when the game window has focus and Java has no
    //  keyboard focus at all. The 0x8000 bit is the "currently down" flag.
    // =========================================================================

    private static void setupKeyboardListener() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (isAsyncKeyDown(0x50)) { // 0x50 = VK_P in Win32
                    paused.set(!paused.get());
                    SwingUtilities.invokeLater(Autoplay::refreshStatus);
                    Thread.sleep(250); // debounce — without this one tap fires 5 times
                }
                if (isAsyncKeyDown(0x1B)) { // 0x1B = VK_ESCAPE
                    System.exit(0);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    private static boolean isAsyncKeyDown(int vk) {
        try { return (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0; }
        catch (Exception e) { return false; }
    }


    // =========================================================================
    //  WINDOW FOCUS CHECK
    //
    //  Bot only runs when the game window is in the foreground. Stops it firing
    //  keypresses while you're tabbed out doing something else.
    //
    //  Checking for "osu!" covers the main game, editor, and song select.
    //  "Roblox" kept from the FNF version — useful if repurposing the bot.
    // =========================================================================

    private static boolean isGameFocused() {
        try {
            char[] buf = new char[512];
            User32.INSTANCE.GetWindowText(User32.INSTANCE.GetForegroundWindow(), buf, 512);
            String title = new String(buf).trim();
            return title.contains("osu!") || title.contains("Roblox");
        } catch (Exception e) {
            return true; // if the check fails, assume focused rather than halting
        }
    }


    // =========================================================================
    //  SWING UI — TOP LEVEL
    //
    //  2025-02-02: Replaced the console stats panel entirely.
    //
    //  The old approach used ProcessBuilder to run "cmd /c cls" on a timer:
    // -------------------------------------------------------------------------
    //  private static void updateStatsUI() {
    //      try {
    //          new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
    //          // Spawning a new OS process every 500ms to clear a terminal.
    //          // At peak the task manager showed 15+ cmd.exe instances stacking up
    //          // because waitFor() on a 500ms schedule means they can overlap.
    //          // Console would also flicker constantly which looked awful.
    //          System.out.println("FPS: " + fps);
    //          // ... etc
    //      } catch (Exception e) { }
    //  }
    // -------------------------------------------------------------------------
    //
    //  Swing is the right tool here. Labels update in-place, no flicker, and we
    //  get the config editing UI essentially for free by putting it in the same window.
    // =========================================================================

    private static void buildUI() {
        frame = new JFrame("Dev_J's Auto Osu Mania");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setBackground(BG);

        JPanel root = dark(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.add(buildHeader(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, buildStatsPanel(), buildRightPanel());
        split.setBackground(BG);
        split.setBorder(null);
        split.setDividerSize(5);
        split.setDividerLocation(210);

        root.add(split, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setSize(820, 540);
        frame.setMinimumSize(new Dimension(720, 460));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }


    // =========================================================================
    //  SWING UI — HEADER
    // =========================================================================

    private static JPanel buildHeader() {
        JPanel p = dark(new BorderLayout());
        p.setBorder(new MatteBorder(0, 0, 1, 0, ACCENT));
        p.setBackground(new Color(14, 14, 24));

        JPanel left = dark(new FlowLayout(FlowLayout.LEFT, 8, 7));
        left.setBackground(new Color(14, 14, 24));

        statusDot = new JLabel("●");
        statusDot.setFont(new Font("Monospaced", Font.PLAIN, 16));
        statusDot.setForeground(GREEN);

        statusText = new JLabel("ONLINE");
        statusText.setFont(new Font("Segoe UI", Font.BOLD, 13));
        statusText.setForeground(GREEN);

        JLabel title = new JLabel("  Dev_J's Auto Osu Mania");
        title.setFont(new Font("Segoe UI", Font.BOLD, 15));
        title.setForeground(TEXT_MAIN);

        JLabel hint = new JLabel("   [P] Pause  [ESC] Quit");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setForeground(TEXT_DIM);

        left.add(statusDot); left.add(statusText); left.add(title); left.add(hint);

        JPanel right = dark(new FlowLayout(FlowLayout.RIGHT, 4, 7));
        right.setBackground(new Color(14, 14, 24));

        JLabel modeLabel = new JLabel("Key Mode:");
        modeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        modeLabel.setForeground(TEXT_DIM);
        right.add(modeLabel);

        for (int k = 4; k <= 9; k++) {
            final int mode = k;
            JToggleButton btn = new JToggleButton(k + "K");
            btn.setFont(new Font("Segoe UI", Font.BOLD, 11));
            btn.setFocusPainted(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.addActionListener(e -> switchKeyMode(mode, btn));
            styleMode(btn, false);
            keyModeGroup.add(btn);
            modeBtns.put(k, btn);
            right.add(btn);
        }

        modeBtns.get(4).setSelected(true);
        styleMode(modeBtns.get(4), true);

        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    // Visual state for the mode toggle buttons. Called on every switch so the
    // previously-active button goes back to the dim style.
    private static void styleMode(JToggleButton btn, boolean on) {
        if (on) {
            btn.setBackground(ACCENT);
            btn.setForeground(Color.WHITE);
            btn.setBorder(BorderFactory.createLineBorder(ACCENT.brighter(), 1));
        } else {
            btn.setBackground(PANEL_BG);
            btn.setForeground(TEXT_DIM);
            btn.setBorder(BorderFactory.createLineBorder(new Color(55, 55, 85), 1));
        }
    }

    /*
     * 2025-02-28 — switchKeyMode
     * Loads Xkey.json for the selected mode and rebuilds the edit fields.
     * If the file doesn't exist we show an error and leave the old mode active
     * (deselect the button, re-style it as inactive) so the UI doesn't lie.
     *
     * loadFromFile() is synchronized on Autoplay.class and so is the scanner's
     * detection block. Calling it from the EDT means the scanner blocks for one
     * frame during the load — acceptable, it's a short operation.
     */
    private static void switchKeyMode(int mode, JToggleButton pressed) {
        modeBtns.forEach((k, b) -> styleMode(b, k == mode));
        currentKeyMode = mode;
        String file = mode + "key.json";
        try {
            loadFromFile(file);
            SwingUtilities.invokeLater(() -> {
                rebuildConfigFields();
                frame.revalidate();
                frame.repaint();
            });
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame,
                "Could not load " + file + ":\n" + ex.getMessage(),
                "Load Error", JOptionPane.ERROR_MESSAGE);
            pressed.setSelected(false);
            styleMode(pressed, false);
        }
    }


    // =========================================================================
    //  SWING UI — STATS PANEL (left column)
    // =========================================================================

    private static JPanel buildStatsPanel() {
        JPanel wrap = dark(new BorderLayout());
        JPanel p = card("Performance");
        p.setLayout(new GridLayout(0, 2, 4, 7));

        fpsLabel     = stat("0.0");
        kpLabel      = stat("0");
        holdLabel    = stat("0");
        holdingLabel = stat("0");
        uptimeLabel  = stat("00:00:00");
        memLabel     = stat("0 MB");

        row(p, "FPS",        fpsLabel);
        row(p, "Keypresses", kpLabel);
        row(p, "Hold Notes", holdLabel);
        row(p, "Keys Held",  holdingLabel);
        row(p, "Uptime",     uptimeLabel);
        row(p, "Memory",     memLabel);

        wrap.add(p, BorderLayout.NORTH);
        return wrap;
    }


    // =========================================================================
    //  SWING UI — RIGHT PANEL (config editors + apply button)
    // =========================================================================

    private static JPanel buildRightPanel() {
        rightPanel = dark(new BorderLayout(0, 6));

        // Wrapped in a JScrollPane because on 9K there are 9 lanes, which makes
        // the three config cards taller than most laptop screens at 1080p.
        configScroll = new JScrollPane(buildFieldsPanel(),
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        configScroll.setBackground(BG);
        configScroll.setBorder(null);
        configScroll.getViewport().setBackground(BG);

        JButton apply = btn("Apply & Save", ACCENT);
        apply.addActionListener(e -> applyEdits(apply));

        JPanel btnRow = dark(new FlowLayout(FlowLayout.RIGHT, 0, 2));
        btnRow.add(apply);

        rightPanel.add(configScroll, BorderLayout.CENTER);
        rightPanel.add(btnRow, BorderLayout.SOUTH);
        return rightPanel;
    }

    /*
     * Builds all three config cards (Coords, Colors, Keys) into a single panel.
     * Called once on startup and again on every mode switch.
     *
     * The three field maps are cleared at the top of each section so stale
     * references from the previous mode don't pollute the applyEdits() logic.
     */
    private static JPanel buildFieldsPanel() {
        JPanel panel = dark(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1; gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 7, 0);

        // ── Note Coords ──────────────────────────────────────────────────────
        JPanel coordCard = card("Note Coords  (x, y)");
        coordCard.setLayout(new GridLayout(0, 2, 4, 4));
        coordFields.clear();
        noteCoords.forEach((lane, pt) -> {
            JTextField tf = field(pt.x + ", " + pt.y);
            coordFields.put(lane, tf);
            coordCard.add(dim(lane)); coordCard.add(tf);
        });
        gbc.gridy = 0; panel.add(coordCard, gbc);

        // ── Note Colors ──────────────────────────────────────────────────────
        JPanel colorCard = card("Note Colors  (r, g, b)");
        colorCard.setLayout(new GridLayout(0, 3, 4, 4));
        colorFields.clear(); colorSwatches.clear();
        noteColors.forEach((lane, c) -> {
            JPanel swatch = new JPanel();
            swatch.setBackground(c);
            swatch.setPreferredSize(new Dimension(22, 22));
            swatch.setBorder(BorderFactory.createLineBorder(TEXT_DIM, 1));
            colorSwatches.put(lane, swatch);

            JTextField tf = field(c.getRed() + ", " + c.getGreen() + ", " + c.getBlue());
            colorFields.put(lane, tf);

            colorCard.add(dim(lane)); colorCard.add(swatch); colorCard.add(tf);
        });
        gbc.gridy = 1; panel.add(colorCard, gbc);

        // ── Key Bindings ─────────────────────────────────────────────────────
        JPanel keyCard = card("Key Bindings  (key name)");
        keyCard.setLayout(new GridLayout(0, 2, 4, 4));
        keyFields.clear();
        keyBindingNames.forEach((lane, keyStr) -> {
            JTextField tf = field(keyStr);
            keyFields.put(lane, tf);
            keyCard.add(dim(lane)); keyCard.add(tf);
        });

        JLabel hint = new JLabel("  e.g.  d  f  j  k  space  left  right  ;");
        hint.setFont(new Font("Segoe UI", Font.ITALIC, 10));
        hint.setForeground(TEXT_DIM);
        keyCard.add(hint);

        gbc.gridy = 2; panel.add(keyCard, gbc);

        // Spacer pushes the three cards to the top instead of stretching them.
        gbc.gridy = 3; gbc.weighty = 1;
        panel.add(dark(new BorderLayout()), gbc);

        return panel;
    }

    // Replaces scroll pane content without touching the outer panel layout.
    private static void rebuildConfigFields() {
        configScroll.setViewportView(buildFieldsPanel());
    }


    // =========================================================================
    //  APPLY EDITS
    //
    //  Validates all three field sets, paints bad fields red, and only proceeds
    //  to save if everything parsed cleanly. Partially applying changes would
    //  leave the config in a state that differs from what the UI shows — confusing.
    // =========================================================================

    private static void applyEdits(JButton applyBtn) {
        var errors = new StringBuilder();

        // Parse coords — "x, y" format, spaces optional.
        coordFields.forEach((lane, tf) -> {
            try {
                String[] parts = tf.getText().trim().split(",");
                noteCoords.put(lane, new Point(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim())));
                tf.setBackground(new Color(34, 34, 52));
            } catch (Exception ex) {
                tf.setBackground(ERR_BG);
                errors.append("Bad coord for ").append(lane)
                      .append(": ").append(tf.getText().trim()).append("\n");
            }
        });

        // Parse colors — "r, g, b", values clamped 0-255 so Color() doesn't explode
        // if someone types 300 by mistake.
        colorFields.forEach((lane, tf) -> {
            try {
                String[] parts = tf.getText().trim().split(",");
                Color c = new Color(
                    clamp(Integer.parseInt(parts[0].trim())),
                    clamp(Integer.parseInt(parts[1].trim())),
                    clamp(Integer.parseInt(parts[2].trim())));
                noteColors.put(lane, c);
                colorSwatches.get(lane).setBackground(c); // live swatch update
                tf.setBackground(new Color(34, 34, 52));
            } catch (Exception ex) {
                tf.setBackground(ERR_BG);
                errors.append("Bad color for ").append(lane)
                      .append(": ").append(tf.getText().trim()).append("\n");
            }
        });

        // Parse key bindings — validated through getKeyCode() which returns
        // VK_UNDEFINED for anything unrecognised. Returns an error instead of
        // silently defaulting to VK_SPACE like the old HashMap approach did.
        keyFields.forEach((lane, tf) -> {
            String raw = tf.getText().trim().toLowerCase();
            int kc = getKeyCode(raw);
            if (kc == KeyEvent.VK_UNDEFINED) {
                tf.setBackground(ERR_BG);
                errors.append("Unknown key for ").append(lane)
                      .append(": '").append(raw).append("'\n");
            } else {
                keyBindingNames.put(lane, raw);
                keyBindings.put(lane, kc);
                tf.setBackground(new Color(34, 34, 52));
            }
        });

        if (errors.length() > 0) {
            JOptionPane.showMessageDialog(frame,
                "Fix these before saving:\n\n" + errors,
                "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        synchronized (Autoplay.class) { rebuildCaptureAndKeyMap(); }
        saveCurrentFile(); // writes to whichever Xkey.json is currently active

        // Brief visual confirmation. javax.swing.Timer qualified explicitly because
        // java.util.Timer is also in scope and the compiler refuses to guess.
        applyBtn.setText("Saved \u2713");
        applyBtn.setBackground(new Color(34, 197, 94));
        javax.swing.Timer t = new javax.swing.Timer(1400, e -> {
            applyBtn.setText("Apply & Save");
            applyBtn.setBackground(ACCENT);
        });
        t.setRepeats(false);
        t.start();
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }


    // =========================================================================
    //  UI REFRESH
    // =========================================================================

    // Three states: ONLINE (active + focused), PAUSED, IDLE (not running for
    // any reason — paused, or game window not in focus).
    private static void refreshStatus() {
        boolean isPaused = paused.get();
        boolean focused  = isGameFocused();
        boolean active   = !isPaused && focused;
        Color  dot   = active ? GREEN : AMBER;
        String label = isPaused ? "PAUSED" : focused ? "ONLINE" : "IDLE";
        statusDot.setForeground(dot);
        statusText.setForeground(dot);
        statusText.setText(label);
    }

    private static void refreshUI() {
        refreshStatus();
        fpsLabel.setText(String.format("%.1f", fps));
        kpLabel.setText(String.valueOf(keypresses.get()));
        holdLabel.setText(String.valueOf(holdNotes.get()));
        holdingLabel.setText(String.valueOf(currentlyHeldKeys.size()));
        long up = (System.currentTimeMillis() - startTime.get()) / 1000;
        uptimeLabel.setText(String.format("%02d:%02d:%02d",
            up / 3600, (up % 3600) / 60, up % 60));
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        memLabel.setText(String.format("%.1f MB", used / 1_048_576.0));
    }


    // =========================================================================
    //  UI HELPERS
    // =========================================================================

    // JPanel with custom paintComponent so the dark background actually renders
    // on Windows where some L&Fs make JPanel non-opaque by default.
    private static JPanel dark(LayoutManager lm) {
        JPanel p = new JPanel(lm) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        p.setBackground(BG);
        p.setOpaque(true);
        return p;
    }

    private static JPanel card(String title) {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        p.setBackground(PANEL_BG);
        p.setOpaque(true);
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ACCENT.darker(), 1), " " + title + " ",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 11), ACCENT),
            new EmptyBorder(4, 6, 6, 6)));
        return p;
    }

    private static JLabel stat(String t) {
        JLabel l = new JLabel(t);
        l.setFont(new Font("Monospaced", Font.BOLD, 13));
        l.setForeground(TEXT_MAIN);
        return l;
    }

    private static JLabel dim(String t) {
        JLabel l = new JLabel(t + ":");
        l.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        l.setForeground(TEXT_DIM);
        return l;
    }

    private static JTextField field(String text) {
        JTextField tf = new JTextField(text);
        tf.setBackground(new Color(34, 34, 52));
        tf.setForeground(TEXT_MAIN);
        tf.setCaretColor(TEXT_MAIN);
        tf.setFont(new Font("Monospaced", Font.PLAIN, 12));
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(58, 58, 88), 1),
            new EmptyBorder(2, 4, 2, 4)));
        return tf;
    }

    private static JButton btn(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static void row(JPanel p, String label, JLabel value) {
        p.add(dim(label));
        p.add(value);
    }


    // =========================================================================
    //  KEY CODE MAP
    //
    //  2025-03-13: Expanded from ~10 cases to full coverage.
    //  Old version used a HashMap<String, Integer> populated in a static block.
    //  Switch expressions compile to tableswitch/lookupswitch bytecode which the
    //  JIT turns into a near-instant branch table — faster than HashMap lookup
    //  and zero allocation per call. The HashMap also had a static-init ordering
    //  bug: if getKeyCode() was called from a field initialiser before the static
    //  block ran, it would NPE. Switch has no shared state so the ordering
    //  problem doesn't exist.
    //
    //  Old code with the silent-default bug:
    // -------------------------------------------------------------------------
    //  private static final Map<String, Integer> KEY_MAP = new HashMap<>();
    //  static {
    //      KEY_MAP.put("d", KeyEvent.VK_D);
    //      // stopped at 8 entries because it was tedious to type
    //  }
    //  private static int getKeyCode(String k) {
    //      return KEY_MAP.getOrDefault(k, KeyEvent.VK_SPACE);
    //      // if the key wasn't in the map it silently pressed SPACE.
    //      // Took ages to figure out why ; binding wasn't working in 8K mode —
    //      // every hit was firing spacebar instead. No error, no warning.
    //  }
    // -------------------------------------------------------------------------
    // =========================================================================

    private static int getKeyCode(String key) {
        if (key == null || key.isBlank()) return KeyEvent.VK_UNDEFINED;
        return switch (key.toLowerCase()) {
            case "a" -> KeyEvent.VK_A; case "b" -> KeyEvent.VK_B;
            case "c" -> KeyEvent.VK_C; case "d" -> KeyEvent.VK_D;
            case "e" -> KeyEvent.VK_E; case "f" -> KeyEvent.VK_F;
            case "g" -> KeyEvent.VK_G; case "h" -> KeyEvent.VK_H;
            case "i" -> KeyEvent.VK_I; case "j" -> KeyEvent.VK_J;
            case "k" -> KeyEvent.VK_K; case "l" -> KeyEvent.VK_L;
            case "m" -> KeyEvent.VK_M; case "n" -> KeyEvent.VK_N;
            case "o" -> KeyEvent.VK_O; case "p" -> KeyEvent.VK_P;
            case "q" -> KeyEvent.VK_Q; case "r" -> KeyEvent.VK_R;
            case "s" -> KeyEvent.VK_S; case "t" -> KeyEvent.VK_T;
            case "u" -> KeyEvent.VK_U; case "v" -> KeyEvent.VK_V;
            case "w" -> KeyEvent.VK_W; case "x" -> KeyEvent.VK_X;
            case "y" -> KeyEvent.VK_Y; case "z" -> KeyEvent.VK_Z;
            case "0" -> KeyEvent.VK_0; case "1" -> KeyEvent.VK_1;
            case "2" -> KeyEvent.VK_2; case "3" -> KeyEvent.VK_3;
            case "4" -> KeyEvent.VK_4; case "5" -> KeyEvent.VK_5;
            case "6" -> KeyEvent.VK_6; case "7" -> KeyEvent.VK_7;
            case "8" -> KeyEvent.VK_8; case "9" -> KeyEvent.VK_9;
            case "space"                         -> KeyEvent.VK_SPACE;
            case "left"                          -> KeyEvent.VK_LEFT;
            case "right"                         -> KeyEvent.VK_RIGHT;
            case "up"                            -> KeyEvent.VK_UP;
            case "down"                          -> KeyEvent.VK_DOWN;
            case "enter"                         -> KeyEvent.VK_ENTER;
            case "tab"                           -> KeyEvent.VK_TAB;
            case "shift"                         -> KeyEvent.VK_SHIFT;
            case "ctrl", "control"               -> KeyEvent.VK_CONTROL;
            case "alt"                           -> KeyEvent.VK_ALT;
            case ";", "semicolon"                -> KeyEvent.VK_SEMICOLON;
            case ",", "comma"                    -> KeyEvent.VK_COMMA;
            case ".", "period"                   -> KeyEvent.VK_PERIOD;
            case "/", "slash"                    -> KeyEvent.VK_SLASH;
            case "[", "open_bracket"             -> KeyEvent.VK_OPEN_BRACKET;
            case "]", "close_bracket"            -> KeyEvent.VK_CLOSE_BRACKET;
            case "'", "quote"                    -> KeyEvent.VK_QUOTE;
            case "-", "minus"                    -> KeyEvent.VK_MINUS;
            case "=", "equals"                   -> KeyEvent.VK_EQUALS;
            case "backspace"                     -> KeyEvent.VK_BACK_SPACE;
            case "delete"                        -> KeyEvent.VK_DELETE;
            case "insert"                        -> KeyEvent.VK_INSERT;
            case "home"                          -> KeyEvent.VK_HOME;
            case "end"                           -> KeyEvent.VK_END;
            case "pageup"                        -> KeyEvent.VK_PAGE_UP;
            case "pagedown"                      -> KeyEvent.VK_PAGE_DOWN;
            default                              -> KeyEvent.VK_UNDEFINED;
        };
    }
}
