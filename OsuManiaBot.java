// Dev_J's Auto Osu Mania
// Compile: javac OsuManiaBot.java
// Run:     java OsuManiaBot

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class OsuManiaBot {

    // ── PALETTE ──────────────────────────────────────────────────────────
    static final Color C_BG        = new Color(0x1C1C1E);
    static final Color C_SURFACE   = new Color(0x2C2C2E);
    static final Color C_SURFACE2  = new Color(0x3A3A3C);
    static final Color C_SIDEBAR   = new Color(0x232325);
    static final Color C_ACCENT    = new Color(0x0A84FF);
    static final Color C_PINK      = new Color(0xFF375F);
    static final Color C_GREEN     = new Color(0x30D158);
    static final Color C_YELLOW    = new Color(0xFFD60A);
    static final Color C_TEXT      = new Color(0xF2F2F7);
    static final Color C_TEXT2     = new Color(0x8E8E93);
    static final Color C_SEP       = new Color(0x38383A);
    static final Color C_FIELD     = new Color(0x18181A);
    static final Color C_TOGGLE_OFF= new Color(0x39393B);

    // ── STATE ────────────────────────────────────────────────────────────
    static volatile boolean    running   = false;
    static final AtomicBoolean paused    = new AtomicBoolean(false);
    static final AtomicInteger keypresses= new AtomicInteger(0);
    static final AtomicInteger holdNotes = new AtomicInteger(0);
    static final AtomicLong    startTime = new AtomicLong(0);
    static volatile double     fps       = 0;
    static volatile int        selectedKeyMode = 4;

    // ── ATTACH ───────────────────────────────────────────────────────────
    static volatile String attachedTitle  = null;
    static volatile Point  windowOrigin   = new Point(0, 0);
    static volatile long   lastOriginTime = 0;

    // ── CONFIG FIELDS ────────────────────────────────────────────────────
    static int     BASE_DELAY_MS          = 1;
    static int     HIT_DELAY_MS           = 0;
    static int     TOLERANCE              = 40;
    static int     PADDING                = 2;
    static boolean HOLD_DETECTION_ENABLED = true;
    static int     HOLD_CHECK_OFFSET      = 50;

    static final Map<String, Point>   noteCoords  = new LinkedHashMap<>();
    static final Map<String, Color>   noteColors  = new LinkedHashMap<>();
    static final Map<String, Integer> keyBindings = new LinkedHashMap<>();
    static Rectangle captureBox;
    static final Map<Point, NoteData> keyMap   = new LinkedHashMap<>();
    static final Map<Integer, Long>   heldKeys = new ConcurrentHashMap<>();

    static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    static Robot robot;

    // ── UI REFS ──────────────────────────────────────────────────────────
    static JFrame     frame;
    static JLabel     statusDot, statusLabel;
    static JLabel     fpsLabel, kpsLabel, holdLabel, uptimeLabel, heldKeysLabel;
    static JTextPane  logPane;
    static StyledDocument logDoc;
    static JButton    btnStart, btnPause, btnStop;
    static JSpinner   spnTolerance, spnBaseDelay, spnHitDelay, spnHoldOffset;
    static JCheckBox  chkHold;
    static JPanel     columnsListPanel;
    static JLabel     attachLabel;
    static JButton    btnAttach, btnDetach;
    static final ButtonGroup keyModeGroup = new ButtonGroup();

    static final String[]  NAV_ITEMS = {"Attach", "Key Mode", "Timing", "Detection", "Columns"};
    // key · keyboard · clock · ! · 123
    static final String[]  NAV_ICONS = {"\uD83D\uDD11", "\u2328", "\uD83D\uDD50", "\u2757", "\uD83D\uDD22"};
    static JPanel      mainContent;
    static CardLayout  cardLayout;
    static JButton[]   navBtns;

    // defs format: { internalKey, x, y, hexR, hexG, hexB, keyBinding }
    static final Map<Integer, String[][]> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put(4, new String[][]{
            {"col1","640","728","FF","37","5F","d"},
            {"col2","756","728","0A","84","FF","f"},
            {"col3","872","728","FF","37","5F","j"},
            {"col4","988","728","0A","84","FF","k"},
        });
        DEFAULTS.put(5, new String[][]{
            {"col1","583","728","FF","37","5F","a"},
            {"col2","699","728","0A","84","FF","s"},
            {"col3","815","728","FF","D6","0A","space"},
            {"col4","931","728","0A","84","FF","k"},
            {"col5","1047","728","FF","37","5F","l"},
        });
        DEFAULTS.put(6, new String[][]{
            {"col1","555","728","FF","37","5F","s"},
            {"col2","651","728","0A","84","FF","d"},
            {"col3","747","728","FF","D6","0A","f"},
            {"col4","843","728","FF","D6","0A","j"},
            {"col5","939","728","0A","84","FF","k"},
            {"col6","1035","728","FF","37","5F","l"},
        });
        DEFAULTS.put(7, new String[][]{
            {"col1","530","728","FF","37","5F","s"},
            {"col2","614","728","0A","84","FF","d"},
            {"col3","698","728","FF","D6","0A","f"},
            {"col4","782","728","FF","D6","0A","space"},
            {"col5","866","728","FF","D6","0A","j"},
            {"col6","950","728","0A","84","FF","k"},
            {"col7","1034","728","FF","37","5F","l"},
        });
        DEFAULTS.put(8, new String[][]{
            {"col1","500","728","FF","37","5F","a"},
            {"col2","578","728","0A","84","FF","s"},
            {"col3","656","728","FF","D6","0A","d"},
            {"col4","734","728","FF","D6","0A","f"},
            {"col5","812","728","FF","D6","0A","j"},
            {"col6","890","728","FF","D6","0A","k"},
            {"col7","968","728","0A","84","FF","l"},
            {"col8","1046","728","FF","37","5F","semicolon"},
        });
        DEFAULTS.put(9, new String[][]{
            {"col1","476","728","FF","37","5F","a"},
            {"col2","554","728","0A","84","FF","s"},
            {"col3","632","728","FF","D6","0A","d"},
            {"col4","710","728","FF","D6","0A","f"},
            {"col5","788","728","FF","D6","0A","space"},
            {"col6","866","728","FF","D6","0A","j"},
            {"col7","944","728","FF","D6","0A","k"},
            {"col8","1022","728","0A","84","FF","l"},
            {"col9","1100","728","FF","37","5F","semicolon"},
        });
    }

    static class NoteData {
        Color targetColor; int keyCode; Point originalPoint;
        NoteData(Color c, int kc, Point p){ targetColor=c; keyCode=kc; originalPoint=p; }
    }

    // ════════════════════════════════════════════════════════════════════
    // CUSTOM COMPONENTS
    // ════════════════════════════════════════════════════════════════════

    static class RoundPanel extends JPanel {
        int radius; Color bg;
        RoundPanel(int r, Color bg){ this.radius=r; this.bg=bg; setOpaque(false); }
        @Override protected void paintComponent(Graphics g){
            Graphics2D g2=(Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),radius,radius));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static class ToggleSwitch extends JComponent {
        boolean on;
        java.util.function.Consumer<Boolean> onChange;
        ToggleSwitch(boolean init){ this.on=init; setPreferredSize(new Dimension(40,22));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter(){ public void mouseClicked(MouseEvent e){ on=!on; repaint(); if(onChange!=null)onChange.accept(on); }});
        }
        boolean isSelected(){ return on; }
        void setSelected(boolean v){ on=v; repaint(); }
        @Override protected void paintComponent(Graphics g){
            Graphics2D g2=(Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(),h=getHeight();
            g2.setColor(on?C_ACCENT:C_TOGGLE_OFF);
            g2.fill(new RoundRectangle2D.Float(0,0,w,h,h,h));
            int tx=on?w-h+2:2;
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Float(tx,2,h-4,h-4));
            g2.dispose();
        }
    }

    static class RoundField extends JTextField {
        RoundField(String v, int w){
            super(v); setPreferredSize(new Dimension(w,26));
            setFont(new Font("Consolas",Font.PLAIN,12));
            setForeground(C_TEXT); setCaretColor(C_TEXT);
            setOpaque(false); setBorder(BorderFactory.createEmptyBorder(3,7,3,7));
        }
        @Override protected void paintComponent(Graphics g){
            Graphics2D g2=(Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(C_FIELD);
            g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),7,7));
            g2.setColor(C_SEP);
            g2.draw(new RoundRectangle2D.Float(0,0,getWidth()-1,getHeight()-1,7,7));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static JSpinner mkSpinner(int v, int min, int max, int step){
        JSpinner s=new JSpinner(new SpinnerNumberModel(v,min,max,step));
        s.setPreferredSize(new Dimension(68,26));
        JComponent ed=s.getEditor();
        if(ed instanceof JSpinner.DefaultEditor){
            JTextField tf=((JSpinner.DefaultEditor)ed).getTextField();
            tf.setBackground(C_SURFACE2); tf.setForeground(C_TEXT);
            tf.setCaretColor(C_TEXT); tf.setFont(new Font("Consolas",Font.PLAIN,12));
            tf.setBorder(BorderFactory.createEmptyBorder(0,5,0,0));
        }
        s.setBorder(BorderFactory.createLineBorder(C_SEP,1));
        s.setBackground(C_SURFACE2);
        return s;
    }

    // ════════════════════════════════════════════════════════════════════
    // MAIN
    // ════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {
        robot=new Robot(); robot.setAutoDelay(0); robot.setAutoWaitForIdle(false);
        SwingUtilities.invokeLater(OsuManiaBot::buildGUI);
    }

    // ════════════════════════════════════════════════════════════════════
    // POWERSHELL WIN32 (no external JARs needed)
    // ════════════════════════════════════════════════════════════════════
    static String ps(String cmd){
        try{
            ProcessBuilder pb=new ProcessBuilder("powershell","-NoProfile","-NonInteractive","-Command",cmd);
            pb.redirectErrorStream(true);
            Process p=pb.start();
            String out=new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(3,TimeUnit.SECONDS);
            return out;
        }catch(Exception e){ return ""; }
    }
    static List<String> listWindowTitles(){
        String raw=ps("Get-Process|Where-Object{$_.MainWindowTitle -ne ''}|ForEach-Object{$_.MainWindowTitle}");
        List<String> t=new ArrayList<>();
        for(String l:raw.split("\\r?\\n")){ String s=l.trim(); if(!s.isEmpty())t.add(s); }
        return t;
    }
    static Point getClientOrigin(String title){
        String safe=title.replace("'","''");
        String sc="$code=@'\nusing System;\nusing System.Runtime.InteropServices;\n"+
            "public class W32{\n[DllImport(\"user32.dll\")] public static extern IntPtr FindWindow(string c,string t);\n"+
            "[DllImport(\"user32.dll\")] public static extern bool ClientToScreen(IntPtr h,ref POINT p);\n"+
            "public struct POINT{public int X,Y;}}\n'@\n"+
            "Add-Type -TypeDefinition $code -Language CSharp\n"+
            "$h=[W32]::FindWindow($null,'"+safe+"')\n"+
            "if($h -eq [IntPtr]::Zero){Write-Output '0,0';exit}\n"+
            "$p=New-Object W32+POINT\n[W32]::ClientToScreen($h,[ref]$p)|Out-Null\nWrite-Output \"$($p.X),$($p.Y)\"\n";
        try{ String[] pts=ps(sc).split(","); return new Point(Integer.parseInt(pts[0].trim()),Integer.parseInt(pts[1].trim())); }
        catch(Exception e){ return new Point(0,0); }
    }
    static boolean isWindowFocused(String title){
        String safe=title.replace("'","''");
        String sc="$code=@'\nusing System.Runtime.InteropServices;\n"+
            "public class W32F{\n[DllImport(\"user32.dll\")] public static extern IntPtr GetForegroundWindow();\n"+
            "[DllImport(\"user32.dll\")] public static extern int GetWindowText(IntPtr h,System.Text.StringBuilder s,int m);\n}\n'@\n"+
            "Add-Type -TypeDefinition $code -Language CSharp\n"+
            "$h=[W32F]::GetForegroundWindow()\n$sb=New-Object System.Text.StringBuilder 512\n"+
            "[W32F]::GetWindowText($h,$sb,512)|Out-Null\nWrite-Output $sb.ToString()\n";
        String fg=ps(sc).trim();
        return fg.equalsIgnoreCase(title)||fg.toLowerCase().contains(title.toLowerCase());
    }
    static void updateWindowOriginIfNeeded(){
        if(attachedTitle==null)return;
        long now=System.currentTimeMillis(); if(now-lastOriginTime<500)return;
        lastOriginTime=now;
        scheduler.execute(()->{ windowOrigin=getClientOrigin(attachedTitle); rebuildKeyMap(); });
    }

    // ════════════════════════════════════════════════════════════════════
    // CONFIG FILE SAVER
    // ════════════════════════════════════════════════════════════════════
    static void saveConfigFile(){
        JFileChooser fc=new JFileChooser();
        fc.setDialogTitle("Save Config File");
        // Only .json — no shortcuts/links
        javax.swing.filechooser.FileNameExtensionFilter jsonOnly =
            new javax.swing.filechooser.FileNameExtensionFilter("JSON config (*.json)","json");
        fc.setFileFilter(jsonOnly);
        fc.setAcceptAllFileFilterUsed(false);          // hide "All files" option
        fc.setFileHidingEnabled(true);                 // hide hidden files
        fc.setSelectedFile(new File("my_config.json")); // default name suggestion

        if(fc.showSaveDialog(frame)!=JFileChooser.APPROVE_OPTION)return;
        File f=fc.getSelectedFile();

        // Reject symlinks / junctions — real files only
        try{
            if(!f.getCanonicalPath().equals(f.getAbsolutePath().replace('/',File.separatorChar))){
                // getCanonicalPath resolved a symlink — paths differ
                JOptionPane.showMessageDialog(frame,
                    "Cannot save to a symlink or shortcut.\nPlease choose a real folder.",
                    "Save Config",JOptionPane.ERROR_MESSAGE);
                return;
            }
            // Also check via NIO
            java.nio.file.Path nioPath=f.toPath();
            if(java.nio.file.Files.isSymbolicLink(nioPath)){
                JOptionPane.showMessageDialog(frame,
                    "Cannot save to a symbolic link.",
                    "Save Config",JOptionPane.ERROR_MESSAGE);
                return;
            }
        }catch(Exception ex){
            log("Path check error: "+ex.getMessage(),C_PINK);
        }

        // Enforce .json extension
        if(!f.getName().toLowerCase().endsWith(".json"))
            f=new File(f.getParentFile(), f.getName()+".json");

        // Build JSON
        StringBuilder sb=new StringBuilder();
        sb.append("{\n");
        sb.append("  \"osumania\": {\n");
        sb.append("    \"key_mode\": ").append(selectedKeyMode).append(",\n");
        sb.append("    \"settings\": {\n");
        sb.append("      \"base_delay_ms\": ").append(spnBaseDelay!=null?(int)spnBaseDelay.getValue():BASE_DELAY_MS).append(",\n");
        sb.append("      \"hit_delay_ms\": ").append(spnHitDelay!=null?(int)spnHitDelay.getValue():HIT_DELAY_MS).append(",\n");
        sb.append("      \"tolerance\": ").append(spnTolerance!=null?(int)spnTolerance.getValue():TOLERANCE).append(",\n");
        sb.append("      \"hold_detection\": ").append(chkHold!=null?chkHold.isSelected():HOLD_DETECTION_ENABLED).append(",\n");
        sb.append("      \"hold_check_height\": ").append(spnHoldOffset!=null?(int)spnHoldOffset.getValue():HOLD_CHECK_OFFSET).append("\n");
        sb.append("    },\n");
        sb.append("    \"columns\": [\n");

        String[][] defs=DEFAULTS.get(selectedKeyMode);
        if(defs!=null){
            for(int i=0;i<defs.length;i++){
                String lane=defs[i][0];
                Point  pt  =noteCoords .getOrDefault(lane,new Point(Integer.parseInt(defs[i][1]),Integer.parseInt(defs[i][2])));
                Color  col =noteColors .getOrDefault(lane,new Color(Integer.parseInt(defs[i][3],16),Integer.parseInt(defs[i][4],16),Integer.parseInt(defs[i][5],16)));
                String key =defs[i][6];
                // look up current key binding text
                for(Map.Entry<String,Integer> e:keyBindings.entrySet()){
                    if(e.getKey().equals(lane)){ key=keyCodeToName(e.getValue()); break; }
                }
                String hex=String.format("#%02X%02X%02X",col.getRed(),col.getGreen(),col.getBlue());
                sb.append("      { \"x\": ").append(pt.x)
                  .append(", \"y\": ").append(pt.y)
                  .append(", \"key\": \"").append(key).append("\"")
                  .append(", \"color\": \"").append(hex).append("\" }");
                if(i<defs.length-1)sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("    ]\n");
        sb.append("  }\n");
        sb.append("}\n");

        try{
            java.nio.file.Files.writeString(f.toPath(),sb.toString());
            // Display just the filename (no extension) in the log
            String displayName=f.getName().replaceAll("(?i)\\.json$","");
            log("Config saved as: "+displayName,C_GREEN);
            log("Location: "+f.getAbsolutePath(),C_TEXT2);
        }catch(Exception ex){
            log("Failed to save config: "+ex.getMessage(),C_PINK);
            JOptionPane.showMessageDialog(frame,"Error saving config:\n"+ex.getMessage(),"Save Config",JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Reverse of keyCode() — returns the string name for a VK_ constant. */
    static String keyCodeToName(int kc){
        switch(kc){
            case KeyEvent.VK_A:return"a";case KeyEvent.VK_B:return"b";case KeyEvent.VK_C:return"c";
            case KeyEvent.VK_D:return"d";case KeyEvent.VK_E:return"e";case KeyEvent.VK_F:return"f";
            case KeyEvent.VK_G:return"g";case KeyEvent.VK_H:return"h";case KeyEvent.VK_I:return"i";
            case KeyEvent.VK_J:return"j";case KeyEvent.VK_K:return"k";case KeyEvent.VK_L:return"l";
            case KeyEvent.VK_M:return"m";case KeyEvent.VK_N:return"n";case KeyEvent.VK_O:return"o";
            case KeyEvent.VK_P:return"p";case KeyEvent.VK_Q:return"q";case KeyEvent.VK_R:return"r";
            case KeyEvent.VK_S:return"s";case KeyEvent.VK_T:return"t";case KeyEvent.VK_U:return"u";
            case KeyEvent.VK_V:return"v";case KeyEvent.VK_W:return"w";case KeyEvent.VK_X:return"x";
            case KeyEvent.VK_Y:return"y";case KeyEvent.VK_Z:return"z";
            case KeyEvent.VK_SPACE:    return"space";
            case KeyEvent.VK_SEMICOLON:return"semicolon";
            case KeyEvent.VK_LEFT:     return"left";
            case KeyEvent.VK_RIGHT:    return"right";
            case KeyEvent.VK_UP:       return"up";
            case KeyEvent.VK_DOWN:     return"down";
            default:return"space";
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // CONFIG FILE LOADER  (.json — custom format)
    //
    // Expected format:
    // {
    //   "osumania": {
    //     "key_mode": 4,
    //     "settings": {
    //       "base_delay_ms": 1,
    //       "hit_delay_ms": 0,
    //       "tolerance": 40,
    //       "hold_detection": true,
    //       "hold_check_height": 50
    //     },
    //     "columns": [
    //       { "x": 640, "y": 728, "key": "d", "color": "#FF375F" },
    //       { "x": 756, "y": 728, "key": "f", "color": "#0A84FF" }
    //     ]
    //   }
    // }
    // ════════════════════════════════════════════════════════════════════
    static void loadConfigFile(){
        JFileChooser fc=new JFileChooser();
        fc.setDialogTitle("Load Config File");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON config (*.json)","json"));
        fc.setAcceptAllFileFilterUsed(false);   // no "All files"
        fc.setFileHidingEnabled(true);
        if(fc.showOpenDialog(frame)!=JFileChooser.APPROVE_OPTION)return;
        File f=fc.getSelectedFile();

        // Reject non-.json
        if(!f.getName().toLowerCase().endsWith(".json")){
            JOptionPane.showMessageDialog(frame,"Please select a .json file.","Load Config",JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Reject symlinks / shortcuts
        try{
            if(java.nio.file.Files.isSymbolicLink(f.toPath())){
                JOptionPane.showMessageDialog(frame,"Cannot load from a symbolic link or shortcut.","Load Config",JOptionPane.ERROR_MESSAGE);
                return;
            }
        }catch(Exception ex){ log("Path check error: "+ex.getMessage(),C_PINK); }
        try{
            String json=new String(Files.readAllBytes(f.toPath())).replaceAll("\\s+","");

            // --- settings ---
            int bDelay  = jsonInt(json,"base_delay_ms",  BASE_DELAY_MS);
            int hDelay  = jsonInt(json,"hit_delay_ms",   HIT_DELAY_MS);
            int tol     = jsonInt(json,"tolerance",      TOLERANCE);
            boolean hold= jsonBool(json,"hold_detection",HOLD_DETECTION_ENABLED);
            int hHeight = jsonInt(json,"hold_check_height",HOLD_CHECK_OFFSET);
            int km      = jsonInt(json,"key_mode",       selectedKeyMode);

            // --- columns ---
            List<String[]> cols=parseColumns(json);

            // Apply
            if(spnBaseDelay!=null) spnBaseDelay.setValue(bDelay);
            if(spnHitDelay!=null)  spnHitDelay .setValue(hDelay);
            if(spnTolerance!=null) spnTolerance.setValue(tol);
            if(spnHoldOffset!=null)spnHoldOffset.setValue(hHeight);
            if(chkHold!=null)      chkHold.setSelected(hold);
            HOLD_DETECTION_ENABLED=hold;

            if(!cols.isEmpty()){
                // Build defs from loaded columns
                String[][] defs=new String[cols.size()][];
                for(int i=0;i<cols.size();i++){
                    String[] c=cols.get(i);
                    // { internalKey, x, y, hexR, hexG, hexB, keyBinding }
                    Color col=hexToColor(c[3]);
                    defs[i]=new String[]{
                        "col"+(i+1), c[0], c[1],
                        String.format("%02X",col.getRed()),
                        String.format("%02X",col.getGreen()),
                        String.format("%02X",col.getBlue()),
                        c[2]
                    };
                }
                DEFAULTS.put(km,defs);
                selectedKeyMode=km;
                applyKeyModeDefaults(km);
            }
            log("Config loaded from: "+f.getName(),C_GREEN);
            log("Columns: "+cols.size()+"  |  Key mode: "+km+"K",C_TEXT2);
        }catch(Exception ex){
            log("Failed to load config: "+ex.getMessage(),C_PINK);
            JOptionPane.showMessageDialog(frame,"Error reading config:\n"+ex.getMessage(),"Load Config",JOptionPane.ERROR_MESSAGE);
        }
    }

    static int jsonInt(String json, String key, int def){
        try{
            int i=json.indexOf("\""+key+"\":"); if(i<0)return def;
            int s=i+key.length()+3;
            int e=s; while(e<json.length()&&(Character.isDigit(json.charAt(e))||json.charAt(e)=='-'))e++;
            return Integer.parseInt(json.substring(s,e));
        }catch(Exception ex){ return def; }
    }
    static boolean jsonBool(String json, String key, boolean def){
        try{
            int i=json.indexOf("\""+key+"\":"); if(i<0)return def;
            int s=i+key.length()+3;
            return json.substring(s,s+4).startsWith("true");
        }catch(Exception ex){ return def; }
    }
    /** Parse the "columns":[{...},{...}] array. Returns list of {x,y,key,color}. */
    static List<String[]> parseColumns(String json){
        List<String[]> list=new ArrayList<>();
        int ci=json.indexOf("\"columns\":["); if(ci<0)return list;
        int start=json.indexOf('[',ci)+1;
        int end=json.indexOf(']',start);
        if(start<0||end<0)return list;
        String arr=json.substring(start,end);
        // Split on },{
        String[] objs=arr.split("\\},\\{");
        for(String obj:objs){
            obj=obj.replace("{","").replace("}","");
            String x=jsonStrOrNum(obj,"x","640");
            String y=jsonStrOrNum(obj,"y","728");
            String key=jsonStr(obj,"key","space");
            String color=jsonStr(obj,"color","#FF375F");
            list.add(new String[]{x,y,key,color});
        }
        return list;
    }
    static String jsonStr(String obj, String key, String def){
        try{
            int i=obj.indexOf("\""+key+"\":\""); if(i<0)return def;
            int s=i+key.length()+4;
            int e=obj.indexOf('"',s); if(e<0)return def;
            return obj.substring(s,e);
        }catch(Exception ex){ return def; }
    }
    static String jsonStrOrNum(String obj, String key, String def){
        // Try string first, then bare number
        String sv=jsonStr(obj,key,null);
        if(sv!=null)return sv;
        try{
            int i=obj.indexOf("\""+key+"\":"); if(i<0)return def;
            int s=i+key.length()+3;
            int e=s; while(e<obj.length()&&(Character.isDigit(obj.charAt(e))||obj.charAt(e)=='-'))e++;
            if(s==e)return def;
            return obj.substring(s,e);
        }catch(Exception ex){ return def; }
    }
    static Color hexToColor(String hex){
        try{
            hex=hex.replace("#","").replace("\"","").trim();
            return new Color(Integer.parseInt(hex,16));
        }catch(Exception e){ return new Color(0xFF375F); }
    }

    // ════════════════════════════════════════════════════════════════════
    // GUI
    // ════════════════════════════════════════════════════════════════════
    static void buildGUI(){
        applyTheme();
        frame=new JFrame("Dev_J's Auto Osu Mania");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1040,660);
        frame.setMinimumSize(new Dimension(880,580));
        frame.getContentPane().setBackground(C_BG);

        JPanel root=new JPanel(new BorderLayout(0,0));
        root.setBackground(C_BG);
        root.add(buildSidebar(),BorderLayout.WEST);

        JPanel right=new JPanel(new BorderLayout(0,0));
        right.setBackground(C_BG);
        right.setBorder(BorderFactory.createEmptyBorder(12,14,0,14));
        right.add(buildTitleBar(),BorderLayout.NORTH);

        JPanel split=new JPanel(new GridLayout(1,2,12,0));
        split.setBackground(C_BG);
        split.setBorder(BorderFactory.createEmptyBorder(10,0,0,0));

        cardLayout=new CardLayout();
        mainContent=new JPanel(cardLayout);
        mainContent.setBackground(C_BG);
        mainContent.add(buildAttachPage(),   "Attach");
        mainContent.add(buildKeyModePage(),  "Key Mode");
        mainContent.add(buildTimingPage(),   "Timing");
        mainContent.add(buildDetectionPage(),"Detection");
        mainContent.add(buildColumnsPage(),  "Columns");
        split.add(mainContent);
        split.add(buildStatsPanel());
        right.add(split,BorderLayout.CENTER);
        right.add(buildFooter(),BorderLayout.SOUTH);
        root.add(right,BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        applyKeyModeDefaults(4);
        new javax.swing.Timer(250,e->refreshStats()).start();

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(ke->{
            if(ke.getID()!=KeyEvent.KEY_PRESSED)return false;
            int kc=ke.getKeyCode();
            if(kc==KeyEvent.VK_F1)     { SwingUtilities.invokeLater(OsuManiaBot::startBot);   }
            else if(kc==KeyEvent.VK_F2){ SwingUtilities.invokeLater(OsuManiaBot::togglePause);}
            else if(kc==KeyEvent.VK_F3){ SwingUtilities.invokeLater(OsuManiaBot::stopBot);    }
            else if(kc==KeyEvent.VK_ESCAPE&&running){ SwingUtilities.invokeLater(OsuManiaBot::stopBot); }
            return false;
        });
    }

    // ── SIDEBAR ──────────────────────────────────────────────────────────
    static JPanel buildSidebar(){
        JPanel sb=new JPanel();
        sb.setLayout(new BoxLayout(sb,BoxLayout.Y_AXIS));
        sb.setBackground(C_SIDEBAR);
        sb.setPreferredSize(new Dimension(192,0));
        sb.setMinimumSize(new Dimension(192,0));
        sb.setBorder(BorderFactory.createMatteBorder(0,0,0,1,C_SEP));

        // App logo
        JPanel logo=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));
        logo.setBackground(C_SIDEBAR);
        logo.setMaximumSize(new Dimension(Integer.MAX_VALUE,54));
        logo.setBorder(BorderFactory.createEmptyBorder(14,14,12,14));
        logo.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel icon=new JLabel("\uD83C\uDFB9");
        icon.setFont(new Font("Segoe UI Emoji",Font.PLAIN,20));
        JPanel nameBox=new JPanel();
        nameBox.setLayout(new BoxLayout(nameBox,BoxLayout.Y_AXIS));
        nameBox.setBackground(C_SIDEBAR);
        JLabel n1=new JLabel("Dev_J's"); n1.setFont(new Font("Segoe UI",Font.BOLD,12)); n1.setForeground(C_TEXT);
        JLabel n2=new JLabel("Auto Osu Mania"); n2.setFont(new Font("Segoe UI",Font.PLAIN,10)); n2.setForeground(C_TEXT2);
        nameBox.add(n1); nameBox.add(n2);
        logo.add(icon); logo.add(nameBox);
        sb.add(logo);

        JSeparator sep=new JSeparator(); sep.setForeground(C_SEP); sep.setBackground(C_SEP);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE,1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        sb.add(sep);
        sb.add(Box.createVerticalStrut(14)); // enough room so top icon is never clipped

        navBtns=new JButton[NAV_ITEMS.length];
        for(int i=0;i<NAV_ITEMS.length;i++){
            final int idx=i;
            JButton b=makeNavBtn(NAV_ICONS[i], NAV_ITEMS[i], i==0);
            b.addActionListener(e->selectNav(idx));
            navBtns[i]=b;
            sb.add(b);
            sb.add(Box.createVerticalStrut(2));
        }
        sb.add(Box.createVerticalGlue());
        return sb;
    }

    /**
     * Nav button: icon LEFT, text RIGHT, horizontal.
     * Fixed 36 px height so every button is identical regardless of emoji size.
     * Icon sits in a 22×22 fixed cell so all icons start at the same X.
     */
    static JButton makeNavBtn(String icon, String label, boolean active){
        // icon cell — fixed width prevents emoji-width differences shifting text
        JLabel iconLbl=new JLabel(icon, SwingConstants.CENTER);
        iconLbl.setFont(new Font("Segoe UI Emoji",Font.PLAIN,13));
        iconLbl.setForeground(active?C_ACCENT:C_TEXT2);
        iconLbl.setPreferredSize(new Dimension(22,22));
        iconLbl.setMinimumSize(new Dimension(22,22));
        iconLbl.setMaximumSize(new Dimension(22,22));

        JLabel textLbl=new JLabel(label);
        textLbl.setFont(new Font("Segoe UI",Font.PLAIN,12));
        textLbl.setForeground(active?C_ACCENT:C_TEXT);

        // Horizontal row: fixed icon cell + 8px gap + text
        JPanel inner=new JPanel();
        inner.setLayout(new BoxLayout(inner,BoxLayout.X_AXIS));
        inner.setOpaque(false);
        inner.add(iconLbl);
        inner.add(Box.createHorizontalStrut(8));
        inner.add(textLbl);

        JButton b=new JButton(){
            @Override protected void paintComponent(Graphics g){
                Graphics2D g2=(Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                if(Boolean.TRUE.equals(getClientProperty("active")))
                    g2.setColor(new Color(10,132,255,40));
                else if(getModel().isRollover())
                    g2.setColor(new Color(255,255,255,15));
                else g2.setColor(new Color(0,0,0,0));
                g2.fill(new RoundRectangle2D.Float(4,1,getWidth()-8,getHeight()-2,8,8));
                g2.dispose();
                super.paintComponent(g);
            }
            // Fixed 36 px — same for every button, no clipping
            @Override public Dimension getMaximumSize(){ return new Dimension(Integer.MAX_VALUE,36); }
            @Override public Dimension getMinimumSize(){ return new Dimension(192,36); }
            @Override public Dimension getPreferredSize(){ return new Dimension(192,36); }
        };
        b.setLayout(new BorderLayout());
        b.add(inner,BorderLayout.WEST);
        // Equal vertical padding (8px top & bottom) — nothing clips
        b.setBorder(BorderFactory.createEmptyBorder(8,14,8,8));
        b.setOpaque(false); b.setContentAreaFilled(false); b.setFocusPainted(false);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.putClientProperty("iconLbl",iconLbl);
        b.putClientProperty("textLbl",textLbl);
        if(active) b.putClientProperty("active",true);
        return b;
    }

    static void selectNav(int idx){
        for(int i=0;i<navBtns.length;i++){
            boolean sel=(i==idx);
            navBtns[i].putClientProperty("active",sel);
            // Update both child labels
            JLabel il=(JLabel)navBtns[i].getClientProperty("iconLbl");
            JLabel tl=(JLabel)navBtns[i].getClientProperty("textLbl");
            if(il!=null) il.setForeground(sel?C_ACCENT:C_TEXT2);
            if(tl!=null) tl.setForeground(sel?C_ACCENT:C_TEXT);
            navBtns[i].repaint();
        }
        cardLayout.show(mainContent,NAV_ITEMS[idx]);
    }

    // ── TITLE BAR ────────────────────────────────────────────────────────
    static JPanel buildTitleBar(){
        JPanel p=new JPanel(new BorderLayout()); p.setBackground(C_BG);
        JLabel t=new JLabel("Dev_J's Auto Osu Mania");
        t.setFont(new Font("Segoe UI",Font.BOLD,18)); t.setForeground(C_TEXT);
        JLabel s=new JLabel("osu!mania automation  \u2022  4K \u2013 9K");
        s.setFont(new Font("Segoe UI",Font.PLAIN,11)); s.setForeground(C_TEXT2);
        JPanel left=new JPanel(); left.setLayout(new BoxLayout(left,BoxLayout.Y_AXIS)); left.setBackground(C_BG);
        left.add(t); left.add(s);
        p.add(left,BorderLayout.WEST);
        return p;
    }

    // ════════════════════════════════════════════════════════════════════
    // PAGES
    // ════════════════════════════════════════════════════════════════════
    static JScrollPane pageWrap(JPanel inner){
        // Wrap in a BorderLayout panel so content always sticks to NORTH (top)
        JPanel anchor=new JPanel(new BorderLayout());
        anchor.setBackground(C_BG);
        anchor.add(inner,BorderLayout.NORTH);
        inner.setBackground(C_BG);
        JScrollPane sp=new JScrollPane(anchor);
        sp.setBorder(null); sp.setBackground(C_BG);
        sp.getViewport().setBackground(C_BG);
        sp.getVerticalScrollBar().setUnitIncrement(10);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return sp;
    }

    /** Shared page header matching Note Columns pattern:
     *  FlowLayout(LEFT,0,0) wrapper for each row → text always snaps left. */
    static JPanel buildPageHeader(String title, String subtitle){
        JPanel top=new JPanel();
        top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        top.setBackground(C_BG);

        JPanel titleRow=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
        titleRow.setBackground(C_BG);
        JLabel tl=new JLabel(title);
        tl.setFont(new Font("Segoe UI",Font.BOLD,13));
        tl.setForeground(C_TEXT);
        titleRow.add(tl);

        JPanel hintRow=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
        hintRow.setBackground(C_BG);
        JLabel hl=new JLabel(subtitle);
        hl.setFont(new Font("Segoe UI",Font.PLAIN,11));
        hl.setForeground(C_TEXT2);
        hintRow.add(hl);

        top.add(titleRow);
        top.add(Box.createVerticalStrut(3));
        top.add(hintRow);
        top.add(Box.createVerticalStrut(8));
        return top;
    }

    // ── ATTACH PAGE ──────────────────────────────────────────────────────
    static JScrollPane buildAttachPage(){
        JPanel page=new JPanel(new BorderLayout(0,0)); page.setBackground(C_BG);
        page.setBorder(BorderFactory.createEmptyBorder(0,0,8,0));
        page.add(buildPageHeader("Window Attach","Attach to a running osu! window to use relative pixel coordinates."),BorderLayout.NORTH);

        // Card uses BorderLayout so JEditorPane in NORTH always fills width → proper HTML wrapping
        RoundPanel card=new RoundPanel(12,C_SURFACE);
        card.setLayout(new BorderLayout(0,10));
        card.setBorder(BorderFactory.createEmptyBorder(14,16,14,16));

        JEditorPane desc=new JEditorPane("text/html",
            "<html><body style='font-family:Segoe UI;font-size:10pt;color:#8E8E93;margin:0;padding:0'>" +
            "Attaches to an <b>open app window</b> so your note coordinates are measured " +
            "from that window\u2019s top-left corner \u2014 not the full screen.<br><br>" +
            "<b style='color:#FF375F'>\u26A0 This is NOT an injector or hack.</b> " +
            "It only reads the window\u2019s screen position to offset your pixel coordinates. " +
            "No code is inserted into osu! and no memory is read or written." +
            "</body></html>");
        desc.setEditable(false); desc.setOpaque(false); desc.setBorder(null);
        card.add(desc,BorderLayout.NORTH);

        // Bottom: status label + buttons
        JPanel bottom=new JPanel(new BorderLayout(0,8)); bottom.setBackground(C_SURFACE);
        attachLabel=new JLabel("Not attached");
        attachLabel.setFont(new Font("Segoe UI",Font.BOLD,12));
        attachLabel.setForeground(C_TEXT2);
        bottom.add(attachLabel,BorderLayout.NORTH);

        JPanel btnRow=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));
        btnRow.setBackground(C_SURFACE);
        btnAttach=pillBtn("Attach\u2026",C_ACCENT);
        btnDetach=pillBtn("Detach",C_PINK); btnDetach.setEnabled(false);
        btnAttach.addActionListener(e->showAttachDialog());
        btnDetach.addActionListener(e->detachWindow());
        btnRow.add(btnAttach); btnRow.add(btnDetach);
        bottom.add(btnRow,BorderLayout.CENTER);
        card.add(bottom,BorderLayout.CENTER);

        page.add(cardWrap(card),BorderLayout.CENTER);
        return pageWrap(page);
    }

    // ── KEY MODE PAGE ────────────────────────────────────────────────────
    static JScrollPane buildKeyModePage(){
        JPanel page=new JPanel(new BorderLayout(0,0)); page.setBackground(C_BG);
        page.setBorder(BorderFactory.createEmptyBorder(0,0,8,0));

        // NORTH: header + both cards stacked
        JPanel north=new JPanel(); north.setLayout(new BoxLayout(north,BoxLayout.Y_AXIS)); north.setBackground(C_BG);
        north.add(buildPageHeader("Key Mode","Choose how many columns your map uses (4K\u20139K)."));

        RoundPanel card=new RoundPanel(12,C_SURFACE);
        card.setLayout(new BorderLayout(12,0));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createEmptyBorder(12,14,12,14));

        JPanel textSide=new JPanel();
        textSide.setLayout(new BoxLayout(textSide,BoxLayout.Y_AXIS));
        textSide.setBackground(C_SURFACE);
        JLabel kmTitle=new JLabel("Column Count");
        kmTitle.setFont(new Font("Segoe UI",Font.BOLD,13)); kmTitle.setForeground(C_TEXT);
        JLabel kmSub=new JLabel("<html><font color='#8E8E93' size='2'>Select how many columns<br>your map uses.</font></html>");
        kmSub.setFont(new Font("Segoe UI",Font.PLAIN,11));
        textSide.add(kmTitle); textSide.add(Box.createVerticalStrut(3)); textSide.add(kmSub);

        JPanel btnSide=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        btnSide.setBackground(C_SURFACE);
        for(int k=4;k<=9;k++){
            final int km=k;
            JToggleButton b=kmToggle(k+"K");
            b.setSelected(k==4); keyModeGroup.add(b);
            b.addActionListener(e->{ if(b.isSelected()){ selectedKeyMode=km; applyKeyModeDefaults(km); log("Switched to "+km+"K",C_ACCENT); }});
            btnSide.add(b);
        }
        card.add(textSide,BorderLayout.WEST);
        card.add(btnSide,BorderLayout.EAST);
        north.add(cardWrap(card));

        RoundPanel loadCard=new RoundPanel(12,C_SURFACE);
        loadCard.setLayout(new BorderLayout(12,0));
        loadCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        loadCard.setBorder(BorderFactory.createEmptyBorder(10,14,10,14));

        JPanel loadText=new JPanel();
        loadText.setLayout(new BoxLayout(loadText,BoxLayout.Y_AXIS));
        loadText.setBackground(C_SURFACE);
        JLabel loadTitle=new JLabel("Config File");
        loadTitle.setFont(new Font("Segoe UI",Font.BOLD,13)); loadTitle.setForeground(C_TEXT);
        JLabel loadSub=new JLabel("<html><font color='#8E8E93' size='2'>Load a .json config to apply<br>columns & settings at once.</font></html>");
        loadSub.setFont(new Font("Segoe UI",Font.PLAIN,11));
        loadText.add(loadTitle); loadText.add(Box.createVerticalStrut(3)); loadText.add(loadSub);

        JButton btnLoad=pillBtn("Load Config\u2026",new Color(0xBF5AF2));
        btnLoad.addActionListener(e->loadConfigFile());
        JButton btnSave=pillBtn("Save Config",new Color(0x30D158));
        btnSave.addActionListener(e->saveConfigFile());
        JPanel loadBtnPanel=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        loadBtnPanel.setBackground(C_SURFACE);
        loadBtnPanel.add(btnSave); loadBtnPanel.add(btnLoad);

        loadCard.add(loadText,BorderLayout.WEST);
        loadCard.add(loadBtnPanel,BorderLayout.EAST);
        north.add(cardWrap(loadCard));

        page.add(north,BorderLayout.NORTH);
        return pageWrap(page);
    }

    // ── TIMING PAGE ──────────────────────────────────────────────────────
    static JScrollPane buildTimingPage(){
        JPanel page=new JPanel(new BorderLayout(0,0)); page.setBackground(C_BG);
        page.setBorder(BorderFactory.createEmptyBorder(0,0,8,0));

        JPanel north=new JPanel(); north.setLayout(new BoxLayout(north,BoxLayout.Y_AXIS)); north.setBackground(C_BG);
        north.add(buildPageHeader("Timing","Adjust scan and hit delay in milliseconds."));
        RoundPanel card=new RoundPanel(12,C_SURFACE);
        card.setLayout(new BoxLayout(card,BoxLayout.Y_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createEmptyBorder(4,0,4,0));
        spnBaseDelay=mkSpinner(1,0,50,1);
        spnHitDelay =mkSpinner(0,0,50,1);
        card.add(settingRow("Base Scan Delay","ms",spnBaseDelay));
        card.add(rowDivider());
        card.add(settingRow("Hit Delay","ms",spnHitDelay));
        north.add(cardWrap(card));

        page.add(north,BorderLayout.NORTH);
        return pageWrap(page);
    }

    // ── DETECTION PAGE ───────────────────────────────────────────────────
    static JScrollPane buildDetectionPage(){
        JPanel page=new JPanel(new BorderLayout(0,0)); page.setBackground(C_BG);
        page.setBorder(BorderFactory.createEmptyBorder(0,0,8,0));

        JPanel north=new JPanel(); north.setLayout(new BoxLayout(north,BoxLayout.Y_AXIS)); north.setBackground(C_BG);
        north.add(buildPageHeader("Detection","Tune color tolerance and hold note recognition."));
        RoundPanel card=new RoundPanel(12,C_SURFACE);
        card.setLayout(new BoxLayout(card,BoxLayout.Y_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createEmptyBorder(4,0,4,0));
        spnTolerance =mkSpinner(40,5,120,5);
        spnHoldOffset=mkSpinner(50,10,200,5);
        ToggleSwitch sw=new ToggleSwitch(true);
        chkHold=new JCheckBox(); chkHold.setSelected(true);
        sw.onChange=v->{ chkHold.setSelected(v); HOLD_DETECTION_ENABLED=v; };
        card.add(settingRow("Color Tolerance \u00b1","",spnTolerance));
        card.add(rowDivider());
        card.add(settingRowSwitch("Hold Note Detection",sw));
        card.add(rowDivider());
        card.add(settingRow("Hold Check Height","px",spnHoldOffset));
        north.add(cardWrap(card));

        page.add(north,BorderLayout.NORTH);
        return pageWrap(page);
    }

    // ── COLUMNS PAGE ─────────────────────────────────────────────────────
    static JPanel buildColumnsPage(){
        // Return JPanel directly — the CardLayout slot accepts any JComponent
        JPanel page=new JPanel(new BorderLayout(0,6));
        page.setBackground(C_BG);
        page.setBorder(BorderFactory.createEmptyBorder(0,0,8,0));

        // TOP: title + hint — left-aligned, never cut
        JPanel top=new JPanel();
        top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        top.setBackground(C_BG);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel secRow=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
        secRow.setBackground(C_BG);
        JLabel secLbl=new JLabel("Note Columns");
        secLbl.setFont(new Font("Segoe UI",Font.BOLD,12));
        secLbl.setForeground(C_TEXT);
        secRow.add(secLbl);

        JPanel hintRow=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
        hintRow.setBackground(C_BG);
        JLabel hint=new JLabel("<html><font color='#8E8E93'>" +
            "Set hit position (X, Y), the key to press, and the <b>note color</b> to detect.<br>" +
            "Use the color picker or paste a hex value. Drag \u2630 to reorder rows." +
            "</font></html>");
        hint.setFont(new Font("Segoe UI",Font.PLAIN,11));
        hintRow.add(hint);

        top.add(secRow);
        top.add(Box.createVerticalStrut(4));
        top.add(hintRow);
        top.add(Box.createVerticalStrut(6));
        page.add(top,BorderLayout.NORTH);

        // CENTER: scrollable column list
        columnsListPanel=new JPanel();
        columnsListPanel.setLayout(new BoxLayout(columnsListPanel,BoxLayout.Y_AXIS));
        columnsListPanel.setBackground(C_BG);

        JScrollPane scroll=new JScrollPane(columnsListPanel);
        scroll.setBorder(null); scroll.setBackground(C_BG);
        scroll.getViewport().setBackground(C_BG);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        page.add(scroll,BorderLayout.CENTER);

        return page;
    }

    // ── STATS PANEL ──────────────────────────────────────────────────────
    static JPanel buildStatsPanel(){
        JPanel p=new JPanel(new BorderLayout(0,8)); p.setBackground(C_BG);

        // TOP: IDLE indicator + stat grid + held keys (fixed)
        JPanel top=new JPanel(); top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS)); top.setBackground(C_BG);

        // ── Status row (was at sidebar bottom, now lives here) ──
        JPanel stRow=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));
        stRow.setBackground(C_BG);
        stRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,26));
        stRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        stRow.setBorder(BorderFactory.createEmptyBorder(0,2,4,0));
        statusDot=new JLabel("\u25cf"); statusDot.setFont(new Font("Segoe UI",Font.PLAIN,12)); statusDot.setForeground(C_TEXT2);
        statusLabel=new JLabel("IDLE"); statusLabel.setFont(new Font("Segoe UI",Font.BOLD,11)); statusLabel.setForeground(C_TEXT2);
        stRow.add(statusDot); stRow.add(statusLabel);
        top.add(stRow);

        top.add(secLabel("Live Stats"));
        JPanel grid=new JPanel(new GridLayout(2,2,6,6)); grid.setBackground(C_BG);
        fpsLabel   =bigStat("0.0"); kpsLabel=bigStat("0");
        holdLabel  =bigStat("0");  uptimeLabel=bigStat("00:00:00");
        grid.add(statCard("Scanner FPS",fpsLabel)); grid.add(statCard("Total Presses",kpsLabel));
        grid.add(statCard("Hold Notes",holdLabel)); grid.add(statCard("Uptime",uptimeLabel));
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE,120));
        top.add(grid);
        top.add(Box.createVerticalStrut(8));

        top.add(secLabel("Currently Held Keys"));
        RoundPanel hk=new RoundPanel(10,C_SURFACE);
        hk.setBorder(BorderFactory.createEmptyBorder(10,14,10,14));
        hk.setMaximumSize(new Dimension(Integer.MAX_VALUE,46));
        heldKeysLabel=new JLabel("None");
        heldKeysLabel.setFont(new Font("Consolas",Font.BOLD,13));
        heldKeysLabel.setForeground(C_TEXT2);
        hk.add(heldKeysLabel); top.add(cardWrap(hk));

        p.add(top,BorderLayout.NORTH);

        // CENTER: log fills all remaining height
        JPanel logWrap=new JPanel(new BorderLayout(0,4)); logWrap.setBackground(C_BG);
        logWrap.add(secLabel("Log"),BorderLayout.NORTH);

        logDoc=new DefaultStyledDocument();
        logPane=new JTextPane(logDoc);
        logPane.setEditable(false);
        logPane.setBackground(new Color(0x111113));
        logPane.setFont(new Font("Consolas",Font.PLAIN,11));
        logPane.setBorder(BorderFactory.createEmptyBorder(6,8,6,8));
        JScrollPane ls=new JScrollPane(logPane);
        ls.setBorder(BorderFactory.createLineBorder(C_SEP,1));
        ls.getViewport().setBackground(new Color(0x111113));
        logWrap.add(ls,BorderLayout.CENTER);

        p.add(logWrap,BorderLayout.CENTER);
        return p;
    }

    // ── FOOTER ───────────────────────────────────────────────────────────
    static JPanel buildFooter(){
        JPanel p=new JPanel(new BorderLayout()); p.setBackground(C_BG);
        p.setBorder(BorderFactory.createEmptyBorder(8,0,10,0));
        JSeparator sep=new JSeparator(); sep.setForeground(C_SEP); sep.setBackground(C_SEP);

        JPanel br=new JPanel(new FlowLayout(FlowLayout.CENTER,10,6)); br.setBackground(C_BG);
        btnStart=fBtn("\u25b6  START","F1",C_GREEN);
        btnPause=fBtn("\u23F8  PAUSE","F2",C_YELLOW);
        btnStop =fBtn("\u25a0  STOP","F3",C_PINK);
        btnPause.setEnabled(false); btnStop.setEnabled(false);
        btnStart.addActionListener(e->startBot());
        btnPause.addActionListener(e->togglePause());
        btnStop .addActionListener(e->stopBot());
        br.add(btnStart); br.add(btnPause); br.add(btnStop);

        JLabel hint=new JLabel("F1 Start  \u2022  F2 Pause  \u2022  F3 Stop  \u2022  ESC Emergency Stop");
        hint.setFont(new Font("Segoe UI",Font.PLAIN,11)); hint.setForeground(C_TEXT2);
        hint.setHorizontalAlignment(SwingConstants.CENTER);

        p.add(sep,BorderLayout.NORTH);
        p.add(br,BorderLayout.CENTER);
        p.add(hint,BorderLayout.SOUTH);
        return p;
    }

    static JButton fBtn(String text, String key, Color c){
        JButton b=new JButton(){
            @Override protected void paintComponent(Graphics g){
                Graphics2D g2=(Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg=isEnabled()?(getModel().isArmed()?c:getModel().isRollover()?c.brighter():c.darker()):C_SURFACE2;
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),10,10));
                g2.dispose(); super.paintComponent(g);
            }
        };
        b.setLayout(new BorderLayout());
        JLabel tl=new JLabel(text,SwingConstants.CENTER);
        tl.setFont(new Font("Segoe UI",Font.BOLD,13)); tl.setForeground(Color.WHITE);
        JLabel kl=new JLabel(key,SwingConstants.CENTER);
        kl.setFont(new Font("Segoe UI",Font.PLAIN,10)); kl.setForeground(new Color(255,255,255,140));
        b.add(tl,BorderLayout.CENTER); b.add(kl,BorderLayout.SOUTH);
        b.setOpaque(false); b.setContentAreaFilled(false); b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(7,20,5,20));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    // ════════════════════════════════════════════════════════════════════
    // ATTACH DIALOG
    // ════════════════════════════════════════════════════════════════════
    static void showAttachDialog(){
        attachLabel.setText("Scanning\u2026"); attachLabel.setForeground(C_YELLOW);
        scheduler.execute(()->{
            List<String> titles=listWindowTitles();
            SwingUtilities.invokeLater(()->{
                attachLabel.setText("Not attached"); attachLabel.setForeground(C_TEXT2);
                if(titles.isEmpty()){ JOptionPane.showMessageDialog(frame,"No windows found.","Attach",JOptionPane.WARNING_MESSAGE); return; }
                DefaultListModel<String> model=new DefaultListModel<>();
                titles.forEach(model::addElement);
                JList<String> list=new JList<>(model);
                list.setBackground(C_SURFACE); list.setForeground(C_TEXT);
                list.setFont(new Font("Segoe UI",Font.PLAIN,12));
                list.setSelectionBackground(C_ACCENT); list.setSelectionForeground(Color.WHITE);
                for(int i=0;i<titles.size();i++) if(titles.get(i).toLowerCase().contains("osu")){list.setSelectedIndex(i);break;}
                RoundField filter=new RoundField("",300); filter.setToolTipText("Filter\u2026");
                filter.getDocument().addDocumentListener(new DocumentListener(){
                    public void insertUpdate(DocumentEvent e){doF();} public void removeUpdate(DocumentEvent e){doF();} public void changedUpdate(DocumentEvent e){doF();}
                    void doF(){ String q=filter.getText().toLowerCase(); model.clear(); titles.stream().filter(t->t.toLowerCase().contains(q)).forEach(model::addElement); }
                });
                JScrollPane sc=new JScrollPane(list); sc.setPreferredSize(new Dimension(460,240)); sc.setBorder(BorderFactory.createLineBorder(C_SEP));
                JPanel panel=new JPanel(new BorderLayout(0,7)); panel.setBackground(C_SURFACE);
                panel.add(filter,BorderLayout.NORTH); panel.add(sc,BorderLayout.CENTER);
                int res=JOptionPane.showConfirmDialog(frame,panel,"Attach to Window",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
                if(res!=JOptionPane.OK_OPTION)return;
                String chosen=list.getSelectedValue(); if(chosen==null)return;
                attachLabel.setText("Attaching\u2026"); attachLabel.setForeground(C_YELLOW);
                scheduler.execute(()->{
                    Point origin=getClientOrigin(chosen);
                    SwingUtilities.invokeLater(()->{
                        attachedTitle=chosen; windowOrigin=origin; lastOriginTime=System.currentTimeMillis();
                        rebuildKeyMap();
                        attachLabel.setText("Attached: "+trunc(chosen,24)); attachLabel.setForeground(C_GREEN);
                        btnDetach.setEnabled(true);
                        log("Attached to: "+chosen,C_GREEN);
                        log("Client origin: ("+origin.x+","+origin.y+")",C_TEXT2);
                    });
                });
            });
        });
    }
    static void detachWindow(){
        attachedTitle=null; windowOrigin=new Point(0,0); rebuildKeyMap();
        attachLabel.setText("Not attached"); attachLabel.setForeground(C_TEXT2);
        btnDetach.setEnabled(false);
        log("Detached \u2014 using absolute screen coordinates",C_YELLOW);
    }

    // ════════════════════════════════════════════════════════════════════
    // COLUMNS — DRAGGABLE ROWS
    // ════════════════════════════════════════════════════════════════════
    static int dragSourceIdx=-1;

    static void applyKeyModeDefaults(int km){
        noteCoords.clear(); noteColors.clear(); keyBindings.clear();
        String[][] defs=DEFAULTS.get(km); if(defs==null)return;
        for(String[] d:defs){
            noteCoords .put(d[0],new Point(Integer.parseInt(d[1]),Integer.parseInt(d[2])));
            noteColors .put(d[0],new Color(Integer.parseInt(d[3],16),Integer.parseInt(d[4],16),Integer.parseInt(d[5],16)));
            keyBindings.put(d[0],keyCode(d[6]));
        }
        rebuildColumnsPanel(defs);
        rebuildKeyMap();
    }

    static void rebuildColumnsPanel(String[][] defs){
        if(columnsListPanel==null)return;
        columnsListPanel.removeAll();
        for(int i=0;i<defs.length;i++){
            columnsListPanel.add(makeColumnRow(defs,i));
            if(i<defs.length-1) columnsListPanel.add(Box.createVerticalStrut(4));
        }
        columnsListPanel.revalidate();
        columnsListPanel.repaint();
    }

    static JPanel makeColumnRow(String[][] defs, int idx){
        final String key=defs[idx][0];
        final String displayName="Column "+(idx+1);

        RoundPanel row=new RoundPanel(9,C_SURFACE);
        row.setLayout(new BoxLayout(row,BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(8,10,8,10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,44));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        // drag handle
        JLabel handle=new JLabel("\u2630");
        handle.setFont(new Font("Segoe UI Symbol",Font.PLAIN,13));
        handle.setForeground(C_TEXT2);
        handle.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        handle.setToolTipText("Drag to reorder");
        handle.setPreferredSize(new Dimension(16,26));
        handle.setMaximumSize(new Dimension(16,26));

        // "Column N" label
        JLabel nameLbl=new JLabel(displayName);
        nameLbl.setFont(new Font("Segoe UI",Font.BOLD,11));
        nameLbl.setForeground(C_ACCENT);
        nameLbl.setPreferredSize(new Dimension(62,26));
        nameLbl.setMaximumSize(new Dimension(62,26));

        // fields
        RoundField txX  =new RoundField(defs[idx][1],44); txX.setToolTipText("Hit X coordinate");
        RoundField txY  =new RoundField(defs[idx][2],44); txY.setToolTipText("Hit Y coordinate");
        RoundField txKey=new RoundField(defs[idx][6],52); txKey.setToolTipText("Key: a-z, space, semicolon...");

        Color initCol=noteColors.getOrDefault(key,Color.WHITE);
        String initHex=String.format("#%02X%02X%02X",initCol.getRed(),initCol.getGreen(),initCol.getBlue());
        RoundField txHex=new RoundField(initHex,70); txHex.setToolTipText("Paste hex color, e.g. #FF375F");

        // color circle
        JButton colCircle=new JButton(){
            @Override protected void paintComponent(Graphics g){
                Graphics2D g2=(Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(noteColors.getOrDefault(key,Color.WHITE));
                g2.fill(new Ellipse2D.Float(2,2,getWidth()-4,getHeight()-4));
                g2.setColor(C_SEP);
                g2.draw(new Ellipse2D.Float(2,2,getWidth()-5,getHeight()-5));
                g2.dispose();
            }
        };
        colCircle.setPreferredSize(new Dimension(26,26)); colCircle.setMaximumSize(new Dimension(26,26));
        colCircle.setOpaque(false); colCircle.setContentAreaFilled(false);
        colCircle.setBorderPainted(false); colCircle.setFocusPainted(false);
        colCircle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        colCircle.setToolTipText("Click to pick color");

        // hex → live update
        txHex.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){ syncHex(); }
            public void removeUpdate(DocumentEvent e){ syncHex(); }
            public void changedUpdate(DocumentEvent e){ syncHex(); }
            void syncHex(){
                try{ Color c=hexToColor(txHex.getText().trim()); noteColors.put(key,c); colCircle.repaint(); rebuildKeyMap(); }
                catch(Exception ign){}
            }
        });
        // picker → update hex
        colCircle.addActionListener(e->{
            Color cur=noteColors.getOrDefault(key,Color.WHITE);
            Color picked=JColorChooser.showDialog(frame,"Note color for "+displayName,cur);
            if(picked!=null){
                noteColors.put(key,picked);
                String h=String.format("#%02X%02X%02X",picked.getRed(),picked.getGreen(),picked.getBlue());
                txHex.setText(h); colCircle.repaint(); rebuildKeyMap();
                log(displayName+" color \u2192 "+h,C_TEXT2);
            }
        });
        // X/Y/Key live
        DocumentListener dl=new DocumentListener(){
            public void insertUpdate(DocumentEvent e){sync();}
            public void removeUpdate(DocumentEvent e){sync();}
            public void changedUpdate(DocumentEvent e){sync();}
            void sync(){
                try{
                    noteCoords .put(key,new Point(Integer.parseInt(txX.getText().trim()),Integer.parseInt(txY.getText().trim())));
                    keyBindings.put(key,keyCode(txKey.getText().trim())); rebuildKeyMap();
                }catch(NumberFormatException ign){}
            }
        };
        txX.getDocument().addDocumentListener(dl);
        txY.getDocument().addDocumentListener(dl);
        txKey.getDocument().addDocumentListener(dl);

        // assemble left→right
        row.add(handle);
        row.add(Box.createHorizontalStrut(6)); row.add(nameLbl);
        row.add(Box.createHorizontalStrut(8));
        row.add(tiny("X"));  row.add(Box.createHorizontalStrut(3)); row.add(txX);
        row.add(Box.createHorizontalStrut(6));
        row.add(tiny("Y"));  row.add(Box.createHorizontalStrut(3)); row.add(txY);
        row.add(Box.createHorizontalStrut(6));
        row.add(tiny("Key")); row.add(Box.createHorizontalStrut(3)); row.add(txKey);
        row.add(Box.createHorizontalStrut(6));
        row.add(txHex);
        row.add(Box.createHorizontalStrut(4)); row.add(colCircle);

        // drag to reorder
        final int[] di={idx};
        MouseAdapter drag=new MouseAdapter(){
            int sy;
            public void mousePressed(MouseEvent e){ dragSourceIdx=di[0]; sy=e.getYOnScreen(); row.bg=C_SURFACE2; row.repaint(); }
            public void mouseDragged(MouseEvent e){ int dy=e.getYOnScreen()-sy; row.setLocation(row.getX(),row.getY()+dy); sy=e.getYOnScreen(); }
            public void mouseReleased(MouseEvent e){
                row.bg=C_SURFACE;
                int cy=row.getY()+row.getHeight()/2; int ti=0,ci2=0;
                for(Component c:columnsListPanel.getComponents()){
                    if(c instanceof RoundPanel){ if(c.getY()+c.getHeight()/2<cy)ti=ci2; ci2++; }
                }
                String[][] cur=DEFAULTS.get(selectedKeyMode);
                if(cur!=null&&dragSourceIdx>=0&&dragSourceIdx!=ti)
                    applyKeyModeDefaults2(reorder(cur,dragSourceIdx,ti));
                dragSourceIdx=-1;
            }
        };
        handle.addMouseListener(drag); handle.addMouseMotionListener(drag);
        return row;
    }

    static String[][] reorder(String[][] defs, int from, int to){
        if(from<0||to<0||from>=defs.length||to>=defs.length||from==to)return defs;
        List<String[]> l=new ArrayList<>(Arrays.asList(defs));
        l.add(to,l.remove(from));
        return l.toArray(new String[0][]);
    }
    static void applyKeyModeDefaults2(String[][] defs){
        DEFAULTS.put(selectedKeyMode,defs);
        noteCoords.clear(); noteColors.clear(); keyBindings.clear();
        for(String[] d:defs){
            noteCoords .put(d[0],new Point(Integer.parseInt(d[1]),Integer.parseInt(d[2])));
            noteColors .put(d[0],new Color(Integer.parseInt(d[3],16),Integer.parseInt(d[4],16),Integer.parseInt(d[5],16)));
            keyBindings.put(d[0],keyCode(d[6]));
        }
        rebuildColumnsPanel(defs); rebuildKeyMap();
    }

    // ════════════════════════════════════════════════════════════════════
    // KEY MAP
    // ════════════════════════════════════════════════════════════════════
    static void rebuildKeyMap(){
        keyMap.clear(); if(noteCoords.isEmpty())return;
        int ox=windowOrigin.x,oy=windowOrigin.y;
        int mnX=Integer.MAX_VALUE,mnY=Integer.MAX_VALUE,mxX=Integer.MIN_VALUE,mxY=Integer.MIN_VALUE;
        for(Point r:noteCoords.values()){int sx=r.x+ox,sy=r.y+oy; mnX=Math.min(mnX,sx);mnY=Math.min(mnY,sy);mxX=Math.max(mxX,sx);mxY=Math.max(mxY,sy);}
        int hco=spnHoldOffset!=null?(int)spnHoldOffset.getValue():50;
        captureBox=new Rectangle(mnX-PADDING,mnY-PADDING-hco,mxX-mnX+PADDING*2+1,mxY-mnY+PADDING*2+hco+1);
        for(String lane:noteCoords.keySet()){
            Point rel=noteCoords.get(lane); Color col=noteColors.getOrDefault(lane,Color.WHITE);
            int kc=keyBindings.getOrDefault(lane,KeyEvent.VK_SPACE);
            Point scr=new Point(rel.x+ox,rel.y+oy);
            keyMap.put(scr,new NoteData(col,kc,scr));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // BOT CONTROL
    // ════════════════════════════════════════════════════════════════════
    static void startBot(){
        if(running)return;
        BASE_DELAY_MS=(int)spnBaseDelay.getValue(); HIT_DELAY_MS=(int)spnHitDelay.getValue();
        TOLERANCE=(int)spnTolerance.getValue(); HOLD_CHECK_OFFSET=(int)spnHoldOffset.getValue();
        HOLD_DETECTION_ENABLED=chkHold.isSelected();
        rebuildKeyMap();
        running=true; paused.set(false); keypresses.set(0); holdNotes.set(0);
        startTime.set(System.currentTimeMillis());
        setStatus("RUNNING",C_GREEN);
        btnStart.setEnabled(false); btnPause.setEnabled(true); btnStop.setEnabled(true);
        log("Started ["+selectedKeyMode+"K]"+(attachedTitle!=null?" ["+trunc(attachedTitle,18)+"]":" [absolute]"),C_GREEN);
        scheduler.execute(OsuManiaBot::scanner);
    }
    static void togglePause(){
        boolean now=!paused.get(); paused.set(now);
        if(now){ setStatus("PAUSED",C_YELLOW); log("Paused",C_YELLOW); }
        else   { setStatus("RUNNING",C_GREEN); log("Resumed",C_GREEN); }
    }
    static void stopBot(){
        if(!running)return;
        running=false; paused.set(false);
        for(int kc:heldKeys.keySet())try{robot.keyRelease(kc);}catch(Exception ign){}
        heldKeys.clear();
        setStatus("IDLE",C_TEXT2);
        btnStart.setEnabled(true); btnPause.setEnabled(false); btnStop.setEnabled(false);
        log("Stopped",C_PINK);
    }

    // ════════════════════════════════════════════════════════════════════
    // SCANNER
    // ════════════════════════════════════════════════════════════════════
    static void scanner(){
        long lf=System.currentTimeMillis(); int fr=0;
        try{
            Robot sc=new Robot();
            while(running){
                updateWindowOriginIfNeeded();
                if(paused.get()||!isFocused()){Thread.sleep(80);continue;}
                if(captureBox==null||keyMap.isEmpty()){Thread.sleep(50);continue;}
                BufferedImage img=sc.createScreenCapture(captureBox);
                Set<Integer> toHold=new HashSet<>();
                for(Map.Entry<Point,NoteData> e:keyMap.entrySet()){
                    boolean hit=HOLD_DETECTION_ENABLED?detectHold(img,e.getKey(),e.getValue().targetColor):detectSingle(img,e.getKey(),e.getValue().targetColor);
                    if(hit)toHold.add(e.getValue().keyCode);
                }
                updateKeys(toHold);
                fr++; long now=System.currentTimeMillis();
                if(now-lf>=1000){fps=fr/((now-lf)/1000.0);fr=0;lf=now;}
                if(BASE_DELAY_MS>0)Thread.sleep(BASE_DELAY_MS);
            }
        }catch(Exception e){log("Scanner error: "+e.getMessage(),C_PINK);}
    }
    static boolean detectHold(BufferedImage img, Point o, Color t){
        int rx=o.x-captureBox.x,ry=o.y-captureBox.y,m=0,step=Math.max(1,HOLD_CHECK_OFFSET/10);
        for(int i=0;i<10;i++){int cy=ry-(i*step);if(cy<0)break;
            outer:for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){int x=rx+dx,y=cy+dy;
                if(x<0||x>=img.getWidth()||y<0||y>=img.getHeight())continue;
                if(colorMatch(img.getRGB(x,y),t)){m++;break outer;}}}
        return m>=3;
    }
    static boolean detectSingle(BufferedImage img, Point o, Color t){
        int rx=o.x-captureBox.x,ry=o.y-captureBox.y;
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){int x=rx+dx,y=ry+dy;
            if(x<0||x>=img.getWidth()||y<0||y>=img.getHeight())continue;
            if(colorMatch(img.getRGB(x,y),t))return true;}
        return false;
    }
    static boolean colorMatch(int rgb, Color t){
        int r=(rgb>>16)&0xFF,g=(rgb>>8)&0xFF,b=rgb&0xFF;
        return Math.abs(r-t.getRed())<=TOLERANCE&&Math.abs(g-t.getGreen())<=TOLERANCE&&Math.abs(b-t.getBlue())<=TOLERANCE;
    }
    static void updateKeys(Set<Integer> toHold){
        Iterator<Map.Entry<Integer,Long>> it=heldKeys.entrySet().iterator();
        while(it.hasNext()){Map.Entry<Integer,Long> e=it.next();if(!toHold.contains(e.getKey())){robot.keyRelease(e.getKey());it.remove();}}
        for(int kc:toHold)if(!heldKeys.containsKey(kc)){
            if(HIT_DELAY_MS>0)try{Thread.sleep(HIT_DELAY_MS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
            robot.keyPress(kc); heldKeys.put(kc,System.currentTimeMillis()); keypresses.incrementAndGet(); if(HOLD_DETECTION_ENABLED)holdNotes.incrementAndGet();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // STATS
    // ════════════════════════════════════════════════════════════════════
    static void refreshStats(){
        if(!running)return;
        fpsLabel.setText(String.format("%.1f",fps)); kpsLabel.setText(String.valueOf(keypresses.get()));
        holdLabel.setText(String.valueOf(holdNotes.get()));
        long up=(System.currentTimeMillis()-startTime.get())/1000;
        uptimeLabel.setText(String.format("%02d:%02d:%02d",up/3600,(up%3600)/60,up%60));
        if(heldKeys.isEmpty()){heldKeysLabel.setText("None");heldKeysLabel.setForeground(C_TEXT2);}
        else{StringBuilder sb=new StringBuilder();for(int kc:heldKeys.keySet()){if(sb.length()>0)sb.append("  ");sb.append("[").append(KeyEvent.getKeyText(kc)).append("]");}
            heldKeysLabel.setText(sb.toString());heldKeysLabel.setForeground(C_GREEN);}
        if(attachedTitle!=null) attachLabel.setText("Attached: "+trunc(attachedTitle,22)+" @ ("+windowOrigin.x+","+windowOrigin.y+")");
    }
    static boolean isFocused(){ return attachedTitle==null||isWindowFocused(attachedTitle); }

    // ════════════════════════════════════════════════════════════════════
    // KEY CODES
    // ════════════════════════════════════════════════════════════════════
    static int keyCode(String k){
        if(k==null)return KeyEvent.VK_SPACE;
        switch(k.toLowerCase()){
            case "a":return KeyEvent.VK_A;case "b":return KeyEvent.VK_B;case "c":return KeyEvent.VK_C;
            case "d":return KeyEvent.VK_D;case "e":return KeyEvent.VK_E;case "f":return KeyEvent.VK_F;
            case "g":return KeyEvent.VK_G;case "h":return KeyEvent.VK_H;case "i":return KeyEvent.VK_I;
            case "j":return KeyEvent.VK_J;case "k":return KeyEvent.VK_K;case "l":return KeyEvent.VK_L;
            case "m":return KeyEvent.VK_M;case "n":return KeyEvent.VK_N;case "o":return KeyEvent.VK_O;
            case "p":return KeyEvent.VK_P;case "q":return KeyEvent.VK_Q;case "r":return KeyEvent.VK_R;
            case "s":return KeyEvent.VK_S;case "t":return KeyEvent.VK_T;case "u":return KeyEvent.VK_U;
            case "v":return KeyEvent.VK_V;case "w":return KeyEvent.VK_W;case "x":return KeyEvent.VK_X;
            case "y":return KeyEvent.VK_Y;case "z":return KeyEvent.VK_Z;
            case "space":return KeyEvent.VK_SPACE;case "semicolon":return KeyEvent.VK_SEMICOLON;
            case "left":return KeyEvent.VK_LEFT;case "right":return KeyEvent.VK_RIGHT;
            case "up":return KeyEvent.VK_UP;case "down":return KeyEvent.VK_DOWN;
            default:return KeyEvent.VK_SPACE;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // WIDGET FACTORIES
    // ════════════════════════════════════════════════════════════════════
    static void setStatus(String t, Color c){ SwingUtilities.invokeLater(()->{ statusLabel.setText(t);statusLabel.setForeground(c);statusDot.setForeground(c); }); }
    static void log(String msg, Color c){
        SwingUtilities.invokeLater(()->{
            try{
                SimpleAttributeSet ts=new SimpleAttributeSet(); StyleConstants.setForeground(ts,C_TEXT2);
                logDoc.insertString(logDoc.getLength(),String.format("[%tT] ",System.currentTimeMillis()),ts);
                SimpleAttributeSet ms=new SimpleAttributeSet(); StyleConstants.setForeground(ms,c);
                logDoc.insertString(logDoc.getLength(),msg+"\n",ms);
                logPane.setCaretPosition(logDoc.getLength());
                if(logDoc.getLength()>14000)logDoc.remove(0,2000);
            }catch(BadLocationException ign){}
        });
    }

    static JPanel cardWrap(JComponent card){
        JPanel w=new JPanel(new BorderLayout()); w.setBackground(C_BG);
        w.setAlignmentX(Component.LEFT_ALIGNMENT);
        w.setBorder(BorderFactory.createEmptyBorder(0,0,6,0)); w.add(card); return w;
    }
    static JPanel secLabel(String text){
        JPanel p=new JPanel(new BorderLayout()); p.setBackground(C_BG);
        p.setBorder(BorderFactory.createEmptyBorder(0,2,5,0));
        JLabel l=new JLabel(text); l.setFont(new Font("Segoe UI",Font.BOLD,12)); l.setForeground(C_TEXT);
        p.add(l); return p;
    }

    /** Section label with a subtitle hint line directly below. */
    static JPanel secLabelHint(String title, String hint){
        JPanel p=new JPanel(); p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS)); p.setBackground(C_BG);
        p.setBorder(BorderFactory.createEmptyBorder(0,2,6,0));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel tl=new JLabel(title); tl.setFont(new Font("Segoe UI",Font.BOLD,12)); tl.setForeground(C_TEXT);
        tl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hl=new JLabel(hint); hl.setFont(new Font("Segoe UI",Font.PLAIN,11)); hl.setForeground(C_TEXT2);
        hl.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(tl); p.add(Box.createVerticalStrut(2)); p.add(hl);
        return p;
    }
    static JPanel settingRow(String label, String unit, JComponent ctrl){
        JPanel p=new JPanel(new BorderLayout(8,0)); p.setBackground(C_SURFACE);
        p.setBorder(BorderFactory.createEmptyBorder(8,14,8,14));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE,42));
        JLabel l=new JLabel(label); l.setFont(new Font("Segoe UI",Font.PLAIN,12)); l.setForeground(C_TEXT);
        JPanel r=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0)); r.setBackground(C_SURFACE);
        if(!unit.isEmpty()){ JLabel u=new JLabel(unit); u.setFont(new Font("Segoe UI",Font.PLAIN,10)); u.setForeground(C_TEXT2); r.add(u); }
        r.add(ctrl); p.add(l,BorderLayout.WEST); p.add(r,BorderLayout.EAST); return p;
    }
    static JPanel settingRowSwitch(String label, ToggleSwitch sw){
        JPanel p=new JPanel(new BorderLayout(8,0)); p.setBackground(C_SURFACE);
        p.setBorder(BorderFactory.createEmptyBorder(8,14,8,14)); p.setMaximumSize(new Dimension(Integer.MAX_VALUE,42));
        JLabel l=new JLabel(label); l.setFont(new Font("Segoe UI",Font.PLAIN,12)); l.setForeground(C_TEXT);
        p.add(l,BorderLayout.WEST); p.add(sw,BorderLayout.EAST); return p;
    }
    static JPanel rowDivider(){
        JPanel p=new JPanel(new BorderLayout()); p.setBackground(C_SURFACE);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE,1));
        p.setBorder(BorderFactory.createEmptyBorder(0,14,0,14));
        JSeparator s=new JSeparator(); s.setForeground(C_SEP); s.setBackground(C_SEP); p.add(s); return p;
    }
    static JPanel statCard(String lbl, JLabel val){
        RoundPanel p=new RoundPanel(10,C_SURFACE);
        p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(10,12,10,12));
        JLabel l=new JLabel(lbl); l.setFont(new Font("Segoe UI",Font.PLAIN,10)); l.setForeground(C_TEXT2);
        p.add(l); p.add(Box.createVerticalStrut(3)); p.add(val); return p;
    }
    static JLabel bigStat(String t){ JLabel l=new JLabel(t); l.setFont(new Font("Consolas",Font.BOLD,18)); l.setForeground(C_TEXT); return l; }
    static JLabel tiny(String t){ JLabel l=new JLabel(t); l.setFont(new Font("Segoe UI",Font.PLAIN,9)); l.setForeground(C_TEXT2); return l; }

    static JButton pillBtn(String text, Color c){
        JButton b=new JButton(text){
            @Override protected void paintComponent(Graphics g){
                Graphics2D g2=(Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg=isEnabled()?(getModel().isArmed()?c:getModel().isRollover()?c.brighter():c.darker()):C_SURFACE2;
                g2.setColor(bg); g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),18,18));
                g2.dispose(); super.paintComponent(g);
            }
        };
        b.setFont(new Font("Segoe UI",Font.BOLD,12)); b.setForeground(Color.WHITE);
        b.setOpaque(false); b.setContentAreaFilled(false); b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(6,16,6,16));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }
    static JToggleButton kmToggle(String text){
        JToggleButton b=new JToggleButton(text){
            @Override protected void paintComponent(Graphics g){
                Graphics2D g2=(Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isSelected()?C_ACCENT:C_SURFACE2);
                g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),9,9));
                g2.dispose(); super.paintComponent(g);
            }
        };
        b.setFont(new Font("Segoe UI",Font.BOLD,12)); b.setForeground(Color.WHITE);
        b.setOpaque(false); b.setContentAreaFilled(false); b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(5,12,5,12));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addChangeListener(e->b.repaint());
        return b;
    }
    static String trunc(String s, int max){ return s.length()<=max?s:s.substring(0,max-1)+"\u2026"; }

    static void applyTheme(){
        try{ UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }catch(Exception ign){}
        UIManager.put("Panel.background",C_BG); UIManager.put("OptionPane.background",C_SURFACE);
        UIManager.put("OptionPane.messageForeground",C_TEXT); UIManager.put("Button.background",C_SURFACE);
        UIManager.put("Button.foreground",C_TEXT); UIManager.put("Label.foreground",C_TEXT);
        UIManager.put("TextField.background",C_FIELD); UIManager.put("TextField.foreground",C_TEXT);
        UIManager.put("TextField.caretForeground",C_TEXT); UIManager.put("Spinner.background",C_SURFACE2);
        UIManager.put("CheckBox.background",C_BG); UIManager.put("CheckBox.foreground",C_TEXT);
        UIManager.put("ScrollBar.background",C_SURFACE); UIManager.put("ScrollBar.thumb",C_SEP);
        UIManager.put("List.background",C_SURFACE); UIManager.put("List.foreground",C_TEXT);
        UIManager.put("List.selectionBackground",C_ACCENT); UIManager.put("List.selectionForeground",Color.WHITE);
        UIManager.put("ColorChooser.background",C_SURFACE);
        UIManager.put("FileChooser.background",C_SURFACE); UIManager.put("FileChooser.foreground",C_TEXT);
    }
}
