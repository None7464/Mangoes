/*
 * Autoplay.java — Dev_J's Auto Osu Mania
 *
 * Author : Dev_J
 * Helpers: Useless Helpers who cant even test their own shit
 * Log Created by Copilot (Thx bbg)
 * Created: 2024-11-03
 * Updated: 2025-03-20
 *
 * Changelog:
 *   2025-03-20  v2.0  UI redesigned to section-panel layout (image ref). Detection Reworked
 *                     Coord picker now only calls SetForegroundWindow, no ShowWindow — fixes Roblox resize.
 *                     Added Load Config button, Settings section, Credits section.
 *
 * Compile:
 *   javac -cp ".;gson-2.10.1.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar" Autoplay.java
 * Run:
 *   java --enable-native-access=ALL-UNNAMED -cp "Autoplay.jar;gson-2.10.1.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar" Autoplay
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

    // CONFIG
    private static int     BASE_DELAY_MS, RELEASE_DELAY_MS, HIT_DELAY_MS;
    private static int     TOLERANCE, PADDING, HOLD_CHECK_OFFSET;
    private static boolean DEBUG, HOLD_DETECTION_ENABLED;
    private static double  DEBUG_UPDATE_RATE;

    private static final Map<String, Point>   noteCoords      = new LinkedHashMap<>();
    private static final Map<String, Color>   noteColors      = new LinkedHashMap<>();
    private static final Map<String, String>  keyBindingNames = new LinkedHashMap<>();
    private static final Map<String, Integer> keyBindings     = new LinkedHashMap<>();

    private static volatile Rectangle captureBox;
    private static String currentConfigFile = "4key.json";
    private static int    currentKeyMode    = 4;

    // RUNTIME STATE
    private static final AtomicBoolean paused     = new AtomicBoolean(false);
    private static final AtomicInteger keypresses = new AtomicInteger(0);
    private static final AtomicInteger holdNotes  = new AtomicInteger(0);
    private static final AtomicLong    startTime  = new AtomicLong(System.currentTimeMillis());
    private static volatile double fps = 0;

    private static final Map<Integer, Long> currentlyHeldKeys = new ConcurrentHashMap<>();
    private static Robot robot;

    private static final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "autoplay-worker");
            t.setDaemon(true);
            return t;
        });

    // LANE LIST (v1.8: replaces Map<Point,NoteData>, fixes x^y hash collision)
    private static final java.util.List<NoteData> laneList = new ArrayList<>();

    // LOG STATE
    private static final Object        logLock     = new Object();
    private static final Deque<String> logLines    = new ArrayDeque<>();
    private static volatile int        logRunKey   = -1;
    private static volatile int        logRunCount = 0;
    private static volatile String     logRunName  = "";
    private static final int           MAX_LOGS    = 8;
    private static boolean             logsEnabled = false;
    private static final Map<String, Integer> detectAccum = new ConcurrentHashMap<>();

    // UI REFS
    private static JFrame        frame;
    private static JLabel        statusDot, statusText;
    private static JLabel        fpsLabel, kpLabel, holdLabel, holdingLabel, uptimeLabel, memLabel;
    private static JTextArea     logArea;
    private static JToggleButton logToggleBtn;
    private static JScrollPane   configScroll;

    private static final Map<String, JTextField> coordFields   = new LinkedHashMap<>();
    private static final Map<String, JPanel>     colorSwatches = new LinkedHashMap<>();
    private static final Map<String, JTextField> colorFields   = new LinkedHashMap<>();
    private static final Map<String, JTextField> keyFields     = new LinkedHashMap<>();

    private static JTextField    toleranceField, baseDelayField, holdOffsetField;
    private static JToggleButton holdDetectToggle;

    private static final ButtonGroup                 keyModeGroup = new ButtonGroup();
    private static final Map<Integer, JToggleButton> modeBtns     = new HashMap<>();

    // THEME
    private static final Color BG         = new Color(10, 10, 18);
    private static final Color PANEL_BG   = new Color(16, 16, 28);
    private static final Color SECTION_BG = new Color(13, 13, 22);
    private static final Color ACCENT     = new Color(99, 102, 241);
    private static final Color TEAL       = new Color(72, 199, 176);
    private static final Color TEXT_MAIN  = new Color(228, 228, 255);
    private static final Color TEXT_DIM   = new Color(110, 110, 155);
    private static final Color GREEN      = new Color(74, 222, 128);
    private static final Color AMBER      = new Color(251, 191, 36);
    private static final Color ERR_BG     = new Color(80, 22, 22);

    // INNER TYPE
    static class NoteData {
        final String key;
        final Color  targetColor;
        final int    keyCode;
        final Point  originalPoint;
        NoteData(String key, Color color, int kc, Point p) {
            this.key = key; this.targetColor = color; this.keyCode = kc; this.originalPoint = p;
        }
    }


    // =========================================================================
    //  ENTRY POINT
    // =========================================================================
    public static void main(String[] args) {
        try {
            robot = new Robot();
            robot.setAutoDelay(0);
            robot.setAutoWaitForIdle(false);

            loadFromFile("4key.json");
            SwingUtilities.invokeAndWait(Autoplay::buildUI);
            setupKeyboardListener();

            scheduler.scheduleAtFixedRate(
                () -> SwingUtilities.invokeLater(Autoplay::refreshUI),
                0, (long)(DEBUG_UPDATE_RATE * 1000), TimeUnit.MILLISECONDS
            );
            scheduler.execute(Autoplay::scanner);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                scheduler.shutdownNow();
                try { scheduler.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Failed to start:\n" + e.getMessage(),
                "Startup Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }


    // =========================================================================
    //  CONFIG LOAD
    // =========================================================================
    private static synchronized void loadFromFile(String filename) throws IOException {
        currentConfigFile = filename;
        String content = new String(Files.readAllBytes(Paths.get(filename)));
        JsonObject cfg = new Gson().fromJson(content, JsonObject.class);

        BASE_DELAY_MS          = cfg.get("BASE_DELAY_MS").getAsInt();
        RELEASE_DELAY_MS       = cfg.get("RELEASE_DELAY_MS").getAsInt();
        HIT_DELAY_MS           = cfg.get("HIT_DELAY_MS").getAsInt();
        TOLERANCE              = cfg.get("COLOR_TOLERANCE").getAsInt();
        PADDING                = cfg.get("PADDING").getAsInt();
        DEBUG                  = cfg.get("DEBUG").getAsBoolean();
        DEBUG_UPDATE_RATE      = cfg.get("DEBUG_UPDATE_RATE").getAsDouble();
        HOLD_DETECTION_ENABLED = cfg.has("HOLD_DETECTION_ENABLED") ? cfg.get("HOLD_DETECTION_ENABLED").getAsBoolean() : true;
        HOLD_CHECK_OFFSET      = cfg.has("HOLD_CHECK_OFFSET")       ? cfg.get("HOLD_CHECK_OFFSET").getAsInt()        : 50;

        noteCoords.clear();
        for (var e : cfg.getAsJsonObject("NOTE_COORDS").entrySet()) {
            var arr = e.getValue().getAsJsonArray();
            noteCoords.put(e.getKey(), new Point(arr.get(0).getAsInt(), arr.get(1).getAsInt()));
        }

        noteColors.clear();
        for (var e : cfg.getAsJsonObject("NOTE_COLORS").entrySet()) {
            var arr = e.getValue().getAsJsonArray();
            noteColors.put(e.getKey(), new Color(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()));
        }

        keyBindingNames.clear();
        keyBindings.clear();
        for (var e : cfg.getAsJsonObject("KEY_BINDINGS").entrySet()) {
            String keyStr = e.getValue().getAsString();
            keyBindingNames.put(e.getKey(), keyStr);
            keyBindings.put(e.getKey(), getKeyCode(keyStr));
        }

        rebuildCaptureAndKeyMap();
    }

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
            minY - PADDING - HOLD_CHECK_OFFSET,
            maxX - minX + PADDING * 2 + 1,
            maxY - minY + PADDING * 2 + HOLD_CHECK_OFFSET + 1
        );

        laneList.clear();
        for (String lane : noteCoords.keySet()) {
            laneList.add(new NoteData(
                keyBindingNames.getOrDefault(lane, lane.toLowerCase()),
                noteColors.getOrDefault(lane, Color.WHITE),
                keyBindings.getOrDefault(lane, KeyEvent.VK_SPACE),
                noteCoords.get(lane)
            ));
        }
    }


    // =========================================================================
    //  CONFIG SAVE
    // =========================================================================
    private static void saveCurrentFile() {
        try {
            String content = new String(Files.readAllBytes(Paths.get(currentConfigFile)));
            JsonObject cfg = new Gson().fromJson(content, JsonObject.class);

            JsonObject coords = new JsonObject();
            noteCoords.forEach((lane, p) -> {
                JsonArray a = new JsonArray(); a.add(p.x); a.add(p.y); coords.add(lane, a);
            });
            cfg.add("NOTE_COORDS", coords);

            JsonObject colors = new JsonObject();
            noteColors.forEach((lane, c) -> {
                JsonArray a = new JsonArray(); a.add(c.getRed()); a.add(c.getGreen()); a.add(c.getBlue()); colors.add(lane, a);
            });
            cfg.add("NOTE_COLORS", colors);

            JsonObject bindings = new JsonObject();
            keyBindingNames.forEach(bindings::addProperty);
            cfg.add("KEY_BINDINGS", bindings);

            cfg.addProperty("COLOR_TOLERANCE",       TOLERANCE);
            cfg.addProperty("BASE_DELAY_MS",          BASE_DELAY_MS);
            cfg.addProperty("HOLD_DETECTION_ENABLED", HOLD_DETECTION_ENABLED);
            cfg.addProperty("HOLD_CHECK_OFFSET",      HOLD_CHECK_OFFSET);

            Files.write(Paths.get(currentConfigFile),
                new GsonBuilder().setPrettyPrinting().create().toJson(cfg).getBytes());
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }


    // =========================================================================
    //  SCANNER — from FNFTurboBot, adapted for laneList
    // =========================================================================
    private static void scanner() {
        long lastFpsTime = System.currentTimeMillis();
        int  frameCount  = 0;
        BufferedImage img = null;
        NoteData[] snapshot = new NoteData[0];

        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (paused.get() || !isGameFocused()) { Thread.sleep(100); continue; }

                Rectangle box;
                synchronized (Autoplay.class) {
                    box = captureBox;
                    snapshot = laneList.toArray(
                        snapshot.length == laneList.size() ? snapshot : new NoteData[laneList.size()]
                    );
                }

                if (box == null) { Thread.sleep(50); continue; }

                if (img != null) img.flush();
                img = robot.createScreenCapture(box);

                Set<Integer> keysToHold = new HashSet<>();
                for (NoteData nd : snapshot) {
                    if (nd == null) continue;
                    boolean hit = HOLD_DETECTION_ENABLED
                        ? detectHoldTail(img, nd.originalPoint, nd.targetColor)
                        : detectSingleNote(img, nd.originalPoint, nd.targetColor);
                    if (hit) keysToHold.add(nd.keyCode);
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
    //  DETECTION — from FNFTurboBot (flat tolerance), keeping getRGB patch for perf
    // =========================================================================
    private static boolean detectHoldTail(BufferedImage img, Point orig, Color target) {
        int rx = orig.x - captureBox.x;
        int ry = orig.y - captureBox.y;
        int matchCount = 0, checkPoints = 10, step = HOLD_CHECK_OFFSET / checkPoints;

        for (int i = 0; i < checkPoints; i++) {
            int cy = ry - (i * step);
            if (cy < 0) break;
            int px = Math.max(0, rx - 1), py = Math.max(0, cy - 1);
            int pw = Math.min(3, img.getWidth() - px), ph = Math.min(3, img.getHeight() - py);
            if (pw <= 0 || ph <= 0) continue;
            int[] patch = img.getRGB(px, py, pw, ph, null, 0, pw);
            for (int rgb : patch) {
                if (colorMatches(rgb, target)) { matchCount++; break; }
            }
        }
        return matchCount >= 3;
    }

    private static boolean detectSingleNote(BufferedImage img, Point orig, Color target) {
        int rx = orig.x - captureBox.x - 1;
        int ry = orig.y - captureBox.y - 1;
        int px = Math.max(0, rx), py = Math.max(0, ry);
        int pw = Math.min(3, img.getWidth() - px), ph = Math.min(3, img.getHeight() - py);
        if (pw <= 0 || ph <= 0) return false;
        int[] pixels = img.getRGB(px, py, pw, ph, null, 0, pw);
        for (int rgb : pixels) {
            if (colorMatches(rgb, target)) return true;
        }
        return false;
    }

    // v2.0: reverted to flat per-channel tolerance from FNFTurboBot
    private static boolean colorMatches(int rgb, Color t) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return Math.abs(r - t.getRed())   <= TOLERANCE
            && Math.abs(g - t.getGreen()) <= TOLERANCE
            && Math.abs(b - t.getBlue())  <= TOLERANCE;
    }


    // =========================================================================
    //  UPDATE KEY STATES — from FNFTurboBot
    // =========================================================================
    private static void updateKeyStates(Set<Integer> keysToHold) {
        long now = System.currentTimeMillis();

        var it = currentlyHeldKeys.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!keysToHold.contains(e.getKey())) {
                robot.keyRelease(e.getKey());
                it.remove();
            }
        }

        for (int kc : keysToHold) {
            if (!currentlyHeldKeys.containsKey(kc)) {
                if (HIT_DELAY_MS > 0) {
                    try { Thread.sleep(HIT_DELAY_MS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                robot.keyPress(kc);
                currentlyHeldKeys.put(kc, now);
                keypresses.incrementAndGet();
                if (HOLD_DETECTION_ENABLED) holdNotes.incrementAndGet();
                if (logsEnabled) logPress(kc);
            }
        }
    }


    // =========================================================================
    //  KEYBOARD LISTENER
    // =========================================================================
    private static void setupKeyboardListener() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (isAsyncKeyDown(0x50)) {
                    paused.set(!paused.get());
                    SwingUtilities.invokeLater(Autoplay::refreshStatus);
                    Thread.sleep(250);
                }
                if (isAsyncKeyDown(0x1B)) System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    private static boolean isAsyncKeyDown(int vk) {
        try { return (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0; }
        catch (Exception e) { return false; }
    }

    private static boolean isGameFocused() {
        try {
            char[] buf = new char[512];
            User32.INSTANCE.GetWindowText(User32.INSTANCE.GetForegroundWindow(), buf, 512);
            String title = new String(buf).trim();
            return title.contains("osu!") || title.contains("Roblox");
        } catch (Exception e) { return true; }
    }


    // =========================================================================
    //  KEY CODE MAP
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
            case "space"             -> KeyEvent.VK_SPACE;
            case "left"              -> KeyEvent.VK_LEFT;
            case "right"             -> KeyEvent.VK_RIGHT;
            case "up"                -> KeyEvent.VK_UP;
            case "down"              -> KeyEvent.VK_DOWN;
            case "enter"             -> KeyEvent.VK_ENTER;
            case "tab"               -> KeyEvent.VK_TAB;
            case "shift"             -> KeyEvent.VK_SHIFT;
            case "ctrl", "control"   -> KeyEvent.VK_CONTROL;
            case "alt"               -> KeyEvent.VK_ALT;
            case ";", "semicolon"    -> KeyEvent.VK_SEMICOLON;
            case ",", "comma"        -> KeyEvent.VK_COMMA;
            case ".", "period"       -> KeyEvent.VK_PERIOD;
            case "/", "slash"        -> KeyEvent.VK_SLASH;
            case "[", "open_bracket" -> KeyEvent.VK_OPEN_BRACKET;
            case "]","close_bracket" -> KeyEvent.VK_CLOSE_BRACKET;
            case "'", "quote"        -> KeyEvent.VK_QUOTE;
            case "-", "minus"        -> KeyEvent.VK_MINUS;
            case "=", "equals"       -> KeyEvent.VK_EQUALS;
            case "backspace"         -> KeyEvent.VK_BACK_SPACE;
            case "delete"            -> KeyEvent.VK_DELETE;
            case "insert"            -> KeyEvent.VK_INSERT;
            case "home"              -> KeyEvent.VK_HOME;
            case "end"               -> KeyEvent.VK_END;
            case "pageup"            -> KeyEvent.VK_PAGE_UP;
            case "pagedown"          -> KeyEvent.VK_PAGE_DOWN;
            default                  -> KeyEvent.VK_UNDEFINED;
        };
    }


    // =========================================================================
    //  UI — MAIN FRAME
    // =========================================================================
    private static void buildUI() {
        frame = new JFrame("Dev_J's Auto Osu Mania");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setBackground(BG);

        JPanel root = dark(new BorderLayout(0, 5));
        root.setBorder(new EmptyBorder(7, 7, 7, 7));
        root.add(buildHeader(), BorderLayout.NORTH);

        JScrollPane leftScroll = new JScrollPane(buildLeftPanel(),
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        leftScroll.setBorder(null);
        leftScroll.setBackground(BG);
        leftScroll.getViewport().setBackground(BG);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, buildRightPanel());
        split.setBorder(null);
        split.setBackground(BG);
        split.setDividerSize(4);
        split.setDividerLocation(215);

        root.add(split, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setSize(840, 510);
        frame.setMinimumSize(new Dimension(700, 400));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }


    // =========================================================================
    //  UI — HEADER
    // =========================================================================
    private static JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(11, 11, 20));
        p.setBorder(new MatteBorder(0, 0, 1, 0, ACCENT));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        left.setBackground(new Color(11, 11, 20));

        statusDot  = new JLabel("●");
        statusDot.setFont(new Font("Monospaced", Font.PLAIN, 13));
        statusDot.setForeground(GREEN);

        statusText = new JLabel("ONLINE");
        statusText.setFont(new Font("Segoe UI", Font.BOLD, 11));
        statusText.setForeground(GREEN);

        JLabel title = new JLabel("Dev_J's Auto Osu Mania");
        title.setFont(new Font("Segoe UI", Font.BOLD, 12));
        title.setForeground(TEXT_MAIN);

        JLabel hint = new JLabel("   [P] Pause  [ESC] Quit");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        hint.setForeground(TEXT_DIM);

        left.add(statusDot); left.add(statusText); left.add(title); left.add(hint);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 5));
        right.setBackground(new Color(11, 11, 20));

        JLabel modeLabel = new JLabel("Mode:");
        modeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        modeLabel.setForeground(TEXT_DIM);
        right.add(modeLabel);

        for (int k = 4; k <= 9; k++) {
            final int mode = k;
            JToggleButton btn = new JToggleButton(k + "K");
            btn.setFont(new Font("Segoe UI", Font.BOLD, 10));
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

        right.add(Box.createHorizontalStrut(5));

        // v2.0: Load Config — reloads current Xkey.json from disk
        JButton loadCfg = new JButton("Load Config");
        loadCfg.setFont(new Font("Segoe UI", Font.BOLD, 10));
        loadCfg.setBackground(new Color(16, 30, 28));
        loadCfg.setForeground(TEAL);
        loadCfg.setFocusPainted(false);
        loadCfg.setBorder(BorderFactory.createLineBorder(TEAL.darker(), 1));
        loadCfg.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        loadCfg.addActionListener(e -> {
            try {
                loadFromFile(currentConfigFile);
                SwingUtilities.invokeLater(() -> {
                    rebuildConfigFields();
                    syncSettingsFields();
                    frame.revalidate(); frame.repaint();
                });
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame,
                    "Failed to load " + currentConfigFile + ":\n" + ex.getMessage(),
                    "Load Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        right.add(loadCfg);

        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private static void styleMode(JToggleButton btn, boolean on) {
        btn.setBackground(on ? ACCENT : new Color(20, 20, 34));
        btn.setForeground(on ? Color.WHITE : TEXT_DIM);
        btn.setBorder(BorderFactory.createLineBorder(on ? ACCENT.brighter() : new Color(42, 42, 68), 1));
    }


    // =========================================================================
    //  UI — LEFT PANEL  (section layout like image reference)
    //  Teal left-border sections: Time, Status, Performance, Activity Log, Credits
    // =========================================================================
    private static JPanel buildLeftPanel() {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setBackground(BG);
        wrap.setBorder(new EmptyBorder(2, 0, 2, 4));

        // Time
        JPanel timeSection = sec("Time");
        uptimeLabel = new JLabel("0 Hr(s), 0 Min(s), 0 Sec(s)");
        uptimeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        uptimeLabel.setForeground(TEXT_MAIN);
        uptimeLabel.setAlignmentX(0f);
        timeSection.add(uptimeLabel);
        wrap.add(timeSection);
        wrap.add(Box.createVerticalStrut(4));

        // Status
        JPanel statusSection = sec("Status");
        statusSection.add(dimValLabel("Mode",  currentKeyMode + "K"));
        statusSection.add(dimValLabel("State", "ONLINE"));
        wrap.add(statusSection);
        wrap.add(Box.createVerticalStrut(4));

        // Performance
        JPanel perfSection = sec("Performance");
        fpsLabel     = new JLabel("0.0");
        kpLabel      = new JLabel("0");
        holdLabel    = new JLabel("0");
        holdingLabel = new JLabel("0");
        memLabel     = new JLabel("0 MB");
        perfSection.add(statRow("FPS",        fpsLabel));
        perfSection.add(statRow("Keypresses", kpLabel));
        perfSection.add(statRow("Hold Notes", holdLabel));
        perfSection.add(statRow("Keys Held",  holdingLabel));
        perfSection.add(statRow("Memory",     memLabel));
        wrap.add(perfSection);
        wrap.add(Box.createVerticalStrut(4));

        // Activity Log
        JPanel logSection = sec("Activity Log");

        logToggleBtn = new JToggleButton("Logs: OFF");
        logToggleBtn.setBackground(new Color(20, 20, 34));
        logToggleBtn.setForeground(TEXT_DIM);
        logToggleBtn.setFont(new Font("Segoe UI", Font.BOLD, 10));
        logToggleBtn.setFocusPainted(false);
        logToggleBtn.setBorder(BorderFactory.createLineBorder(new Color(42, 42, 68), 1));
        logToggleBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        logToggleBtn.setAlignmentX(0f);
        logToggleBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        logArea = new JTextArea(5, 1);
        logArea.setBackground(SECTION_BG);
        logArea.setForeground(new Color(140, 210, 140));
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setText("-- no logs --");

        JScrollPane logScroll = new JScrollPane(logArea,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        logScroll.setBorder(BorderFactory.createLineBorder(ACCENT.darker(), 1));
        logScroll.setBackground(SECTION_BG);
        logScroll.getViewport().setBackground(SECTION_BG);
        logScroll.setAlignmentX(0f);
        logScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        logScroll.setVisible(false);

        logToggleBtn.addActionListener(e -> {
            logsEnabled = logToggleBtn.isSelected();
            logToggleBtn.setText(logsEnabled ? "Logs: ON" : "Logs: OFF");
            logToggleBtn.setForeground(logsEnabled ? GREEN : TEXT_DIM);
            logToggleBtn.setBackground(logsEnabled ? new Color(14, 30, 18) : new Color(20, 20, 34));
            logToggleBtn.setBorder(BorderFactory.createLineBorder(logsEnabled ? GREEN.darker() : new Color(42, 42, 68), 1));
            logScroll.setVisible(logsEnabled);
            wrap.revalidate(); wrap.repaint();
        });

        logSection.add(logToggleBtn);
        logSection.add(Box.createVerticalStrut(2));
        logSection.add(logScroll);
        wrap.add(logSection);
        wrap.add(Box.createVerticalStrut(4));

        // Credits
        JPanel credSection = sec("Credits");
        JLabel devj = new JLabel("  Dev_J");
        devj.setFont(new Font("Segoe UI", Font.BOLD, 11));
        devj.setForeground(TEAL);
        devj.setAlignmentX(0f);
        credSection.add(devj);
        wrap.add(credSection);

        wrap.add(Box.createVerticalGlue());
        return wrap;
    }

    // Section panel with teal left border + teal header (matches image style)
    private static JPanel sec(String title) {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(PANEL_BG);
        p.setOpaque(true);
        p.setAlignmentX(0f);
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 2, 0, 0, TEAL),
            new EmptyBorder(4, 6, 4, 6)
        ));

        JLabel hdr = new JLabel(title);
        hdr.setFont(new Font("Segoe UI", Font.BOLD, 11));
        hdr.setForeground(TEAL);
        hdr.setAlignmentX(0f);
        p.add(hdr);
        p.add(Box.createVerticalStrut(3));
        return p;
    }

    private static JLabel dimValLabel(String label, String val) {
        JLabel l = new JLabel(label + "  :  " + val);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        l.setForeground(TEXT_DIM);
        l.setAlignmentX(0f);
        return l;
    }

    private static JPanel statRow(String label, JLabel valueLabel) {
        JPanel row = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        row.setBackground(PANEL_BG);
        row.setOpaque(true);
        row.setAlignmentX(0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));

        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lbl.setForeground(TEXT_DIM);

        valueLabel.setFont(new Font("Monospaced", Font.BOLD, 11));
        valueLabel.setForeground(TEXT_MAIN);
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        row.add(lbl, BorderLayout.WEST);
        row.add(valueLabel, BorderLayout.EAST);
        return row;
    }


    // =========================================================================
    //  UI — RIGHT PANEL
    // =========================================================================
    private static JPanel buildRightPanel() {
        JPanel rp = dark(new BorderLayout(0, 4));

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

        rp.add(configScroll, BorderLayout.CENTER);
        rp.add(btnRow, BorderLayout.SOUTH);
        return rp;
    }

    private static JPanel buildFieldsPanel() {
        JPanel panel = dark(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1; gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 5, 0);

        // Note Coords
        JPanel coordCard = card("Note Coords  (x, y)");
        coordCard.setLayout(new GridLayout(0, 3, 3, 3));
        coordFields.clear();
        noteCoords.forEach((lane, pt) -> {
            JTextField tf = field(pt.x + ", " + pt.y);
            coordFields.put(lane, tf);
            JButton pick = new JButton("\uD83D\uDCCD");
            pick.setBackground(new Color(22, 22, 38));
            pick.setForeground(ACCENT);
            pick.setFocusPainted(false);
            pick.setBorderPainted(false);
            pick.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 11));
            pick.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            pick.setToolTipText("Pick coord for lane: " + lane);
            pick.addActionListener(e -> startCoordPicker(lane, tf));
            coordCard.add(dim(lane)); coordCard.add(tf); coordCard.add(pick);
        });
        gbc.gridy = 0; panel.add(coordCard, gbc);

        // Note Colors
        JPanel colorCard = card("Note Colors  (r, g, b)");
        colorCard.setLayout(new GridLayout(0, 3, 3, 3));
        colorFields.clear(); colorSwatches.clear();
        noteColors.forEach((lane, c) -> {
            JPanel swatch = new JPanel();
            swatch.setBackground(c);
            swatch.setPreferredSize(new Dimension(16, 16));
            swatch.setBorder(BorderFactory.createLineBorder(TEXT_DIM, 1));
            colorSwatches.put(lane, swatch);
            JTextField tf = field(c.getRed() + ", " + c.getGreen() + ", " + c.getBlue());
            colorFields.put(lane, tf);
            colorCard.add(dim(lane)); colorCard.add(swatch); colorCard.add(tf);
        });
        gbc.gridy = 1; panel.add(colorCard, gbc);

        // Key Bindings
        JPanel keyCard = card("Key Bindings");
        keyCard.setLayout(new GridLayout(0, 2, 3, 3));
        keyFields.clear();
        keyBindingNames.forEach((lane, keyStr) -> {
            JTextField tf = field(keyStr);
            keyFields.put(lane, tf);
            keyCard.add(dim(lane)); keyCard.add(tf);
        });
        JLabel keyHint = new JLabel("  e.g.  d  f  j  k  space  ;");
        keyHint.setFont(new Font("Segoe UI", Font.ITALIC, 10));
        keyHint.setForeground(TEXT_DIM);
        keyCard.add(keyHint);
        gbc.gridy = 2; panel.add(keyCard, gbc);

        // Settings
        JPanel settingsCard = card("Settings");
        settingsCard.setLayout(new GridLayout(0, 2, 3, 3));

        toleranceField  = field(String.valueOf(TOLERANCE));
        baseDelayField  = field(String.valueOf(BASE_DELAY_MS));
        holdOffsetField = field(String.valueOf(HOLD_CHECK_OFFSET));

        holdDetectToggle = new JToggleButton(HOLD_DETECTION_ENABLED ? "ON" : "OFF");
        holdDetectToggle.setSelected(HOLD_DETECTION_ENABLED);
        holdDetectToggle.setFont(new Font("Segoe UI", Font.BOLD, 10));
        holdDetectToggle.setFocusPainted(false);
        styleHoldToggle(holdDetectToggle);
        holdDetectToggle.addActionListener(e -> styleHoldToggle(holdDetectToggle));

        settingsCard.add(dim("Tolerance"));      settingsCard.add(toleranceField);
        settingsCard.add(dim("Base Delay ms"));  settingsCard.add(baseDelayField);
        settingsCard.add(dim("Hold Detect"));    settingsCard.add(holdDetectToggle);
        settingsCard.add(dim("Hold Offset px")); settingsCard.add(holdOffsetField);
        gbc.gridy = 3; panel.add(settingsCard, gbc);

        gbc.gridy = 4; gbc.weighty = 1;
        panel.add(dark(new BorderLayout()), gbc);

        return panel;
    }

    private static void styleHoldToggle(JToggleButton btn) {
        boolean on = btn.isSelected();
        btn.setText(on ? "ON" : "OFF");
        btn.setBackground(on ? new Color(14, 32, 18) : new Color(20, 20, 34));
        btn.setForeground(on ? GREEN : TEXT_DIM);
        btn.setBorder(BorderFactory.createLineBorder(on ? GREEN.darker() : new Color(42, 42, 68), 1));
    }

    private static void rebuildConfigFields() {
        configScroll.setViewportView(buildFieldsPanel());
    }

    private static void syncSettingsFields() {
        if (toleranceField   != null) toleranceField.setText(String.valueOf(TOLERANCE));
        if (baseDelayField   != null) baseDelayField.setText(String.valueOf(BASE_DELAY_MS));
        if (holdOffsetField  != null) holdOffsetField.setText(String.valueOf(HOLD_CHECK_OFFSET));
        if (holdDetectToggle != null) { holdDetectToggle.setSelected(HOLD_DETECTION_ENABLED); styleHoldToggle(holdDetectToggle); }
    }


    // =========================================================================
    //  APPLY EDITS
    // =========================================================================
    private static void applyEdits(JButton applyBtn) {
        var errors = new StringBuilder();

        coordFields.forEach((lane, tf) -> {
            try {
                String[] pts = tf.getText().trim().split(",");
                noteCoords.put(lane, new Point(Integer.parseInt(pts[0].trim()), Integer.parseInt(pts[1].trim())));
                tf.setBackground(new Color(26, 26, 42));
            } catch (Exception ex) {
                tf.setBackground(ERR_BG);
                errors.append("Bad coord for ").append(lane).append(": ").append(tf.getText().trim()).append("\n");
            }
        });

        colorFields.forEach((lane, tf) -> {
            try {
                String[] pts = tf.getText().trim().split(",");
                Color c = new Color(clamp(Integer.parseInt(pts[0].trim())),
                                    clamp(Integer.parseInt(pts[1].trim())),
                                    clamp(Integer.parseInt(pts[2].trim())));
                noteColors.put(lane, c);
                colorSwatches.get(lane).setBackground(c);
                tf.setBackground(new Color(26, 26, 42));
            } catch (Exception ex) {
                tf.setBackground(ERR_BG);
                errors.append("Bad color for ").append(lane).append(": ").append(tf.getText().trim()).append("\n");
            }
        });

        keyFields.forEach((lane, tf) -> {
            String raw = tf.getText().trim().toLowerCase();
            int kc = getKeyCode(raw);
            if (kc == KeyEvent.VK_UNDEFINED) {
                tf.setBackground(ERR_BG);
                errors.append("Unknown key for ").append(lane).append(": '").append(raw).append("'\n");
            } else {
                keyBindingNames.put(lane, raw);
                keyBindings.put(lane, kc);
                tf.setBackground(new Color(26, 26, 42));
            }
        });

        try { TOLERANCE = Integer.parseInt(toleranceField.getText().trim()); toleranceField.setBackground(new Color(26,26,42)); }
        catch (Exception ex) { toleranceField.setBackground(ERR_BG); errors.append("Bad tolerance\n"); }

        try { BASE_DELAY_MS = Integer.parseInt(baseDelayField.getText().trim()); baseDelayField.setBackground(new Color(26,26,42)); }
        catch (Exception ex) { baseDelayField.setBackground(ERR_BG); errors.append("Bad base delay\n"); }

        try { HOLD_CHECK_OFFSET = Integer.parseInt(holdOffsetField.getText().trim()); holdOffsetField.setBackground(new Color(26,26,42)); }
        catch (Exception ex) { holdOffsetField.setBackground(ERR_BG); errors.append("Bad hold offset\n"); }

        HOLD_DETECTION_ENABLED = holdDetectToggle.isSelected();

        if (errors.length() > 0) {
            JOptionPane.showMessageDialog(frame, "Fix these:\n\n" + errors, "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        synchronized (Autoplay.class) { rebuildCaptureAndKeyMap(); }
        saveCurrentFile();

        applyBtn.setText("Saved \u2713");
        applyBtn.setBackground(new Color(34, 197, 94));
        javax.swing.Timer t = new javax.swing.Timer(1400, e -> {
            applyBtn.setText("Apply & Save");
            applyBtn.setBackground(ACCENT);
        });
        t.setRepeats(false); t.start();
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static void switchKeyMode(int mode, JToggleButton pressed) {
        modeBtns.forEach((k, b) -> styleMode(b, k == mode));
        currentKeyMode = mode;
        String file = mode + "key.json";
        try {
            loadFromFile(file);
            SwingUtilities.invokeLater(() -> {
                rebuildConfigFields();
                syncSettingsFields();
                frame.revalidate(); frame.repaint();
            });
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, "Could not load " + file + ":\n" + ex.getMessage(),
                "Load Error", JOptionPane.ERROR_MESSAGE);
            pressed.setSelected(false);
            styleMode(pressed, false);
        }
    }


    // =========================================================================
    //  UI REFRESH
    // =========================================================================
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
        uptimeLabel.setText(up / 3600 + " Hr(s), " + (up % 3600) / 60 + " Min(s), " + up % 60 + " Sec(s)");

        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        memLabel.setText(String.format("%.1f MB", used / 1_048_576.0));

        if (!logsEnabled || logArea == null) return;

        if (!detectAccum.isEmpty()) {
            synchronized (logLock) {
                detectAccum.forEach((key, count) -> pushLog(key + " det \u00d7" + count));
                detectAccum.clear();
            }
        }

        StringBuilder sb = new StringBuilder();
        synchronized (logLock) {
            if (logRunKey != -1 && logRunCount > 0)
                sb.append(logRunName).append(" \u00d7").append(logRunCount).append(" pressing...\n");
            for (String line : logLines) sb.append(line).append("\n");
        }

        String text = sb.length() > 0 ? sb.toString() : "-- no activity --";
        if (!logArea.getText().equals(text)) {
            logArea.setText(text);
            logArea.setCaretPosition(0);
        }
    }


    // =========================================================================
    //  LOG HELPERS
    // =========================================================================
    private static void logPress(int kc) {
        String name = KeyEvent.getKeyText(kc);
        synchronized (logLock) {
            if (kc == logRunKey) {
                logRunCount++;
            } else {
                if (logRunKey != -1 && logRunCount > 0)
                    pushLog(logRunName + " \u00d7" + logRunCount + " pressed");
                logRunKey = kc; logRunName = name; logRunCount = 1;
            }
        }
    }

    private static void logDetect(String keyName) { detectAccum.merge(keyName, 1, Integer::sum); }

    private static void pushLog(String line) {
        logLines.addFirst(line);
        while (logLines.size() > MAX_LOGS) logLines.removeLast();
    }


    // =========================================================================
    //  UI HELPERS
    // =========================================================================
    private static JPanel dark(LayoutManager lm) {
        JPanel p = new JPanel(lm) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        p.setBackground(BG); p.setOpaque(true); return p;
    }

    private static JPanel card(String title) {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        p.setBackground(PANEL_BG); p.setOpaque(true);
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ACCENT.darker(), 1), " " + title + " ",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 10), ACCENT),
            new EmptyBorder(3, 5, 5, 5)));
        return p;
    }

    private static JLabel dim(String t) {
        JLabel l = new JLabel(t + ":");
        l.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        l.setForeground(TEXT_DIM); return l;
    }

    private static JTextField field(String text) {
        JTextField tf = new JTextField(text);
        tf.setBackground(new Color(26, 26, 42));
        tf.setForeground(TEXT_MAIN);
        tf.setCaretColor(TEXT_MAIN);
        tf.setFont(new Font("Monospaced", Font.PLAIN, 11));
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(48, 48, 76), 1),
            new EmptyBorder(1, 3, 1, 3)));
        return tf;
    }

    private static JButton btn(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg); b.setForeground(Color.WHITE);
        b.setFocusPainted(false); b.setBorderPainted(false);
        b.setFont(new Font("Segoe UI", Font.BOLD, 11));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }


    // =========================================================================
    //  COORD PICKER
    //  v2.0: removed ShowWindow — SetForegroundWindow only, no Roblox resize
    // =========================================================================
    private static WinDef.HWND findGameWindow() {
        for (String t : new String[]{"Roblox", "osu!"}) {
            WinDef.HWND h = User32.INSTANCE.FindWindow(null, t);
            if (h != null) return h;
        }
        return null;
    }

    private static void startCoordPicker(String lane, JTextField targetField) {
        WinDef.HWND hwnd = findGameWindow();
        if (hwnd == null) {
            JOptionPane.showMessageDialog(frame,
                "Game window not found.\nMake sure Roblox or osu! is open.",
                "Not Found", JOptionPane.WARNING_MESSAGE);
            return;
        }
        User32.INSTANCE.SetForegroundWindow(hwnd);
        scheduler.schedule(
            () -> SwingUtilities.invokeLater(() -> showPickerOverlay(lane, targetField)),
            350, TimeUnit.MILLISECONDS
        );
    }

    private static void showPickerOverlay(String lane, JTextField targetField) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        JWindow overlay = new JWindow();
        overlay.setAlwaysOnTop(true);
        overlay.setBounds(0, 0, screen.width, screen.height);

        try { overlay.setBackground(new Color(0, 0, 0, 0)); }
        catch (Exception ignored) { overlay.setOpacity(0.25f); overlay.setBackground(Color.BLACK); }

        JPanel glass = new JPanel(null) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0, 0, 0, 80)); g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(0, 0, 0, 185));
                g2.fillRoundRect(getWidth() / 2 - 255, 16, 510, 62, 10, 10);
                g2.setFont(new Font("Segoe UI", Font.BOLD, 16)); g2.setColor(new Color(255, 230, 60));
                String msg = "Right-click the hit receptor for lane: " + lane;
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, 44);
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 12)); g2.setColor(new Color(175, 175, 175, 210));
                String sub = "ESC to cancel";
                FontMetrics fm2 = g2.getFontMetrics();
                g2.drawString(sub, (getWidth() - fm2.stringWidth(sub)) / 2, 65);
                g2.dispose();
            }
            @Override public boolean isOpaque() { return false; }
        };

        glass.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        glass.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON3) {
                    targetField.setText(e.getXOnScreen() + ", " + e.getYOnScreen());
                    overlay.dispose(); frame.toFront();
                }
            }
        });
        glass.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { overlay.dispose(); frame.toFront(); }
            }
        });

        overlay.setContentPane(glass);
        overlay.setVisible(true);
        glass.requestFocusInWindow();
    }
}