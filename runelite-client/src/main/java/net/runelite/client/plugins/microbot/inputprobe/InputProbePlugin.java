package net.runelite.client.plugins.microbot.inputprobe;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.util.mouse.BotEventGuard;

import javax.inject.Inject;
import java.awt.Canvas;
import java.awt.event.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.*;

/**
 * LOCAL PATCH (detection audit 23-09): logs every mouse/key event that reaches the game canvas
 * to ~/.runelite/microbot/input-probe-*.tsv, tagged BOT or HUMAN. Play a few minutes yourself,
 * let a bot run a few minutes, and compare the columns (press duration, modifiers, timestamps,
 * event order) to see where synthetic input still differs from real input.
 */
@PluginDescriptor(
        name = "<html>[<font color=#0077B6>🔍</font>] Input Probe",
        description = "Logs canvas input events (bot vs human) for detection analysis",
        tags = {"microbot", "debug", "input", "antiban"},
        enabledByDefault = false
)
@Slf4j
public class InputProbePlugin extends Plugin implements MouseListener, MouseMotionListener, MouseWheelListener, KeyListener {

    private static final String HEADER = "t_ms\tsrc\tevent\twhen_delta\tx\ty\tbutton\tmods\tmodsEx\tclicks\tpopup\tkeyCode\tkeyChar\twheel";

    @Inject
    private Client client;

    private Canvas canvas;
    private BufferedWriter writer;
    private ExecutorService io;
    private long startNanos;
    private int linesSinceFlush; // only touched on the writer thread

    @Override
    protected void startUp() throws IOException {
        File dir = new File(RuneLite.RUNELITE_DIR, "microbot");
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File out = new File(dir, "input-probe-" + stamp + ".tsv");
        writer = new BufferedWriter(new FileWriter(out));
        writer.write(HEADER);
        writer.newLine();
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "InputProbe-writer");
            t.setDaemon(true);
            return t;
        });
        startNanos = System.nanoTime();
        attach();
        log.info("[InputProbe] logging to {}", out.getAbsolutePath());
    }

    @Override
    protected void shutDown() {
        detach();
        if (io != null) {
            io.submit(() -> {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            });
            io.shutdown();
        }
    }

    private void attach() {
        canvas = client.getCanvas();
        if (canvas == null) return;
        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);
        canvas.addMouseWheelListener(this);
        canvas.addKeyListener(this);
    }

    private void detach() {
        if (canvas == null) return;
        canvas.removeMouseListener(this);
        canvas.removeMouseMotionListener(this);
        canvas.removeMouseWheelListener(this);
        canvas.removeKeyListener(this);
        canvas = null;
    }

    private void record(InputEvent e) {
        long tMs = (System.nanoTime() - startNanos) / 1_000_000L;
        String src = BotEventGuard.isSynthetic() ? "BOT" : "HUMAN";
        // when_delta: event timestamp minus wall clock at receipt; >0 means a future-dated event
        long whenDelta = e.getWhen() - System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(96);
        sb.append(tMs).append('\t').append(src).append('\t').append(name(e.getID())).append('\t').append(whenDelta).append('\t');
        if (e instanceof MouseEvent) {
            MouseEvent m = (MouseEvent) e;
            sb.append(m.getX()).append('\t').append(m.getY()).append('\t').append(m.getButton()).append('\t')
                    .append(m.getModifiers()).append('\t').append(m.getModifiersEx()).append('\t')
                    .append(m.getClickCount()).append('\t').append(m.isPopupTrigger()).append("\t\t\t");
            if (m instanceof MouseWheelEvent) sb.append(((MouseWheelEvent) m).getWheelRotation());
        } else if (e instanceof KeyEvent) {
            KeyEvent k = (KeyEvent) e;
            char c = k.getKeyChar();
            // never write typed text (chat, bank PIN, passwords) to disk: only its category
            String ch = c == KeyEvent.CHAR_UNDEFINED ? "UNDEF"
                    : Character.isISOControl(c) ? "0x" + Integer.toHexString(c)
                    : Character.isLetter(c) ? (Character.isUpperCase(c) ? "UPPER" : "LOWER")
                    : Character.isDigit(c) ? "DIGIT" : c == ' ' ? "SPACE" : "SYMBOL";
            sb.append("\t\t\t").append(k.getModifiers()).append('\t').append(k.getModifiersEx()).append("\t\t\t")
                    .append(Character.isLetterOrDigit(c) ? "-" : String.valueOf(k.getKeyCode())).append('\t').append(ch).append('\t');
        }
        String line = sb.toString();
        ExecutorService ex = io;
        if (ex == null || ex.isShutdown()) return;
        try {
            ex.submit(() -> {
                try {
                    writer.write(line);
                    writer.newLine();
                    if (++linesSinceFlush >= 50) {
                        writer.flush();
                        linesSinceFlush = 0;
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static String name(int id) {
        switch (id) {
            case MouseEvent.MOUSE_PRESSED: return "PRESSED";
            case MouseEvent.MOUSE_RELEASED: return "RELEASED";
            case MouseEvent.MOUSE_CLICKED: return "CLICKED";
            case MouseEvent.MOUSE_MOVED: return "MOVED";
            case MouseEvent.MOUSE_DRAGGED: return "DRAGGED";
            case MouseEvent.MOUSE_ENTERED: return "ENTERED";
            case MouseEvent.MOUSE_EXITED: return "EXITED";
            case MouseEvent.MOUSE_WHEEL: return "WHEEL";
            case KeyEvent.KEY_PRESSED: return "KEY_PRESSED";
            case KeyEvent.KEY_RELEASED: return "KEY_RELEASED";
            case KeyEvent.KEY_TYPED: return "KEY_TYPED";
            default: return String.valueOf(id);
        }
    }

    @Override public void mouseClicked(MouseEvent e) { record(e); }
    @Override public void mousePressed(MouseEvent e) { record(e); }
    @Override public void mouseReleased(MouseEvent e) { record(e); }
    @Override public void mouseEntered(MouseEvent e) { record(e); }
    @Override public void mouseExited(MouseEvent e) { record(e); }
    @Override public void mouseDragged(MouseEvent e) { record(e); }
    @Override public void mouseMoved(MouseEvent e) { record(e); }
    @Override public void mouseWheelMoved(MouseWheelEvent e) { record(e); }
    @Override public void keyTyped(KeyEvent e) { record(e); }
    @Override public void keyPressed(KeyEvent e) { record(e); }
    @Override public void keyReleased(KeyEvent e) { record(e); }
}
