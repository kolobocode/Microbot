package net.runelite.client.plugins.microbot.util.human;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.humanmouse.HumanMouse;
import net.runelite.client.plugins.microbot.humanmouse.MousePlan;
import net.runelite.client.plugins.microbot.humanmouse.MouseProfile;
import net.runelite.client.plugins.microbot.humanmouse.Pace;
import net.runelite.client.plugins.microbot.humanmouse.SafeMiss;
import net.runelite.client.plugins.microbot.humanmouse.Target;
import net.runelite.client.plugins.microbot.humantiming.Action;
import net.runelite.client.plugins.microbot.humantiming.HumanProfile;
import net.runelite.client.plugins.microbot.humantiming.HumanTiming;
import net.runelite.client.plugins.microbot.util.antiban.HumanTimingBridge;
import net.runelite.client.plugins.microbot.util.input.InputLoop;
import net.runelite.client.plugins.microbot.util.input.PointerState;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * LOCAL PATCH (not upstream): every bot click in the client goes through HumanTiming and HumanMouse.
 * <p>
 * {@code VirtualMouse} is the one place all script clicks pass ({@code Microbot.doInvoke}, {@code Microbot.click},
 * {@code Microbot.getMouse().click(...)}), and {@code NaturalMouse.move} is the one place all cursor travel
 * passes. Both hand over to this class, so no script can click without it:
 * <ul>
 *   <li><b>When</b> (HumanTiming): one think time before each click, chosen from what the click is: the same
 *       kind of click again soon after the last one is a SEQUENCE (dropping, next slot, next rock), a click
 *       after the script has been waiting a while is a REACT, anything else a ROUTINE. The script's own
 *       HumanTiming is used when it attached one ({@link HumanTimingBridge}), and a click the script or
 *       {@code BankHumanizer} already paced is not paced again. Nothing is clamped.</li>
 *   <li><b>What the mouse does</b> (HumanMouse): the press point inside the real target (NPC hull, object
 *       clickbox, widget bounds, walk tile), the path there, the dwell and the button hold. A click whose
 *       target can't be found presses where the cursor already is, never at a fixed pixel such as (1,1).
 *       Misses are never pressed ({@link SafeMiss#NEVER}): the menu swap would still run the action.</li>
 * </ul>
 * Not calm (wilderness, in combat, HP under 50%, client thread): no think time, and the mouse hurries.
 * Both traits come from the player's HumanId, the same person as a HumanTiming / HumanMouse plugin gets.
 * <p>
 * {@code -Dmicrobot.humanInput=false} at client start gives the stock input back. Read once, so a
 * script can't switch it off.
 */
@Slf4j
public final class HumanInput {
    /** Off only with {@code -Dmicrobot.humanInput=false} on the command line. */
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("microbot.humanInput"));

    /** Same kind of click again within this long: part of a run (SEQUENCE). */
    static final long SEQUENCE_WINDOW_MS = 2_500;
    /** The script waited at least this long since the last click: the next one reacts to something. */
    static final long REACT_AFTER_MS = 3_000;
    /** Plans drawn before giving up on a press point inside a concave clickbox. */
    private static final int PLAN_TRIES = 6;
    /** Corrections onto a target that moved (camera follows the player) during the approach. */
    private static final int MAX_RETARGETS = 2;
    /** Half size of the target around a bare point (a script's own x/y, a walk tile's centre). */
    private static final int POINT_HALF = 2;
    private static final int WALK_HALF_X = 5;
    private static final int WALK_HALF_Y = 3;
    private static final long MAX_PARK_NS = 1_000_000L;

    /** Guards the HumanTiming and HumanMouse instances, which are not thread-safe. */
    private static final Object LOCK = new Object();
    private static volatile HumanTiming timing;
    private static volatile String timingName;
    private static volatile HumanMouse mouse;
    private static volatile String mouseName;

    /**
     * When client code on this thread last paced the click about to happen (BankHumanizer); 0 = not. Expires,
     * so a paced click that never happened doesn't let a later one through unpaced.
     */
    private static final ThreadLocal<Long> PACED_AT = ThreadLocal.withInitial(() -> 0L);
    private static final long PACED_VALID_MS = 3_000;
    /** A menu action this long after the last button press (bot or human) came without a click. */
    static final long BARE_MENU_ACTION_MS = 1_500;
    private static final long BARE_WARN_EVERY_MS = 60_000;
    private static volatile long lastPressMs;
    private static final long NAME_CACHE_MS = 5_000;
    private static volatile String cachedName;
    private static volatile long cachedNameMs;
    private static final java.util.Map<String, Long> bareWarned = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String lastKey;
    private static volatile long lastClickMs;

    private HumanInput() {
    }

    // ------------------------------------------------------------------------------------------
    // Timing
    // ------------------------------------------------------------------------------------------

    /** True when it is safe to spend time on human-like pauses. */
    public static boolean calm() {
        if (Microbot.getClient().isClientThread()) return false;
        // One game tick of caching: the checks below cost client-thread round trips, a click needs one answer.
        long now = System.currentTimeMillis();
        if (now - calmAtMs < CALM_CACHE_MS) return calmCached;
        boolean calm = calmNow();
        calmCached = calm;
        calmAtMs = now;
        return calm;
    }

    private static final long CALM_CACHE_MS = 600;
    private static volatile boolean calmCached;
    private static volatile long calmAtMs;

    private static boolean calmNow() {
        try {
            if (!Microbot.isLoggedIn()) return false;
            if (Rs2Pvp.isInWilderness()) return false;
            if (Rs2Player.isInCombat()) return false;
            double hp = Rs2Player.getHealthPercentage();
            return !Double.isNaN(hp) && hp >= 50;
        } catch (Exception ex) {
            return false;
        }
    }

    /** The next click on this thread was paced already; don't add a think time to it. */
    public static void markPaced() {
        PACED_AT.set(System.currentTimeMillis());
    }

    /** A mouse button went down on the canvas, from a script or the human. */
    public static void notePress() {
        lastPressMs = System.currentTimeMillis();
    }

    /**
     * Called for every menu action the game runs. One that no button press led to was sent straight to the
     * client ({@code client.menuAction}, reflection), past HumanMouse and HumanTiming: logged so the script
     * can be found and fixed. Nothing is blocked.
     */
    public static void checkMenuAction(String option, String target) {
        if (!ENABLED) return;
        long now = System.currentTimeMillis();
        if (now - lastPressMs <= BARE_MENU_ACTION_MS) return;
        String key = option + "|" + target;
        Long last = bareWarned.get(key);
        if (last != null && now - last < BARE_WARN_EVERY_MS) return;
        bareWarned.put(key, now);
        log.warn("[HumanInput] menu action without a mouse click: '{}' '{}'. A script sent it straight to the client "
                + "(client.menuAction), so it skipped HumanMouse and HumanTiming", option, target);
    }

    /**
     * Think time before a click, from the script's HumanTiming if it attached one, else the client-wide one
     * for the logged-in player. 0 when there is no player yet.
     */
    public static long delay(HumanTimingBridge.Pacer script, Action kind) {
        if (script != null) return script.delayMs(kind.name());
        String name = playerName();
        synchronized (LOCK) {
            HumanTiming t = timing(name);
            return t == null ? 0 : t.delay(kind);
        }
    }

    /**
     * Tells the client-wide timing the player is playing (a script loop ran, a menu action was sent), so the
     * time between clicks isn't taken for a break. Cheap; safe from any thread.
     */
    public static void heartbeat() {
        HumanTiming t = timing;
        if (!ENABLED || t == null) return;
        try {
            // The client thread never waits for LOCK: a script thread may hold it for a moment.
            if (Microbot.getClient().isClientThread()) return;
            if (!Microbot.isLoggedIn() || BreakHandlerScript.isBreakActive()) return;
            synchronized (LOCK) {
                t.active();
            }
        } catch (Exception ex) {
            log.debug("HumanInput.heartbeat skipped", ex);
        }
    }

    /** The client-wide timing's profile for the logged-in player, or null before login. */
    public static HumanProfile timingProfile() {
        String name = playerName();
        synchronized (LOCK) {
            HumanTiming t = timing(name);
            return t == null ? null : t.profile();
        }
    }

    /** Call with LOCK held, the name read before taking it. Created on first use, per player name. */
    private static HumanTiming timing(String name) {
        if (name == null) return timing;
        if (!name.equals(timingName)) {
            timing = new HumanTiming(HumanProfile.fromName(name));
            timingName = name;
        }
        return timing;
    }

    /**
     * One think time before a click. Runs on the thread that clicks, before the input lock is taken, so a
     * long pause never holds up another script's input.
     *
     * @param mayPause false for a click sent from the client thread (it runs later on the mouse's own thread,
     *                 and the caller never waited for it)
     * @return how hurried the mouse should be for this click
     */
    public static Pace beforeClick(NewMenuEntry entry, Point point, boolean mayPause) {
        String key = clickKey(entry, point);
        long gap = System.currentTimeMillis() - lastClickMs;
        Action kind = gap > REACT_AFTER_MS ? Action.REACT
                : key.equals(lastKey) && gap <= SEQUENCE_WINDOW_MS ? Action.SEQUENCE
                : Action.ROUTINE;
        boolean paced = System.currentTimeMillis() - PACED_AT.get() <= PACED_VALID_MS;
        PACED_AT.set(0L);
        boolean calm = mayPause && calm();
        try {
            HumanTimingBridge.Pacer script = HumanTimingBridge.forCurrentThread();
            // Always consumed, so a script's "I paced this" never carries over to a later, unrelated click.
            boolean scriptPaced = script != null && script.consumeScriptPace();
            if (paced || scriptPaced || !calm) return paceFor(kind, calm);
            long ms = delay(script, kind);
            if (ms > 0) HumanTiming.pause(ms);
        } catch (Exception ex) {
            log.debug("HumanInput.beforeClick skipped", ex);
        } finally {
            lastKey = key;
            lastClickMs = System.currentTimeMillis();
        }
        return paceFor(kind, calm);
    }

    /**
     * What makes two clicks "the same kind": the menu action and its target (item slots of one container
     * count as one, so dropping or using slot after slot is a run). Bare clicks group by screen area.
     */
    static String clickKey(NewMenuEntry entry, Point point) {
        if (entry == null || entry.getType() == null) {
            return point == null ? "pt" : "pt|" + point.getX() / 40 + "," + point.getY() / 40;
        }
        MenuAction type = entry.getType();
        if (type == MenuAction.WALK) return "walk";
        if (entry.getWidget() != null || isWidgetAction(type)) {
            return "w|" + type + "|" + entry.getParam1() + "|" + entry.getIdentifier();
        }
        return type + "|" + entry.getTarget();
    }

    private static boolean isWidgetAction(MenuAction type) {
        String n = type.name();
        return n.startsWith("CC_OP") || n.startsWith("WIDGET_");
    }

    // ------------------------------------------------------------------------------------------
    // Mouse
    // ------------------------------------------------------------------------------------------

    /**
     * Where a click should land, read again just before the press. Null when nothing better than the point
     * is known.
     *
     * @param bounds the rectangle the caller clicks in (widget bounds, a clickbox's bounds), or null
     */
    public static Supplier<Shape> shapeFor(NewMenuEntry entry, Rectangle bounds, Point point) {
        Rectangle fixed = fixedShape(bounds, point);
        if (entry != null) {
            Actor actor = entry.getActor();
            if (actor != null) return new Live(actor::getConvexHull, fixed);
            TileObject object = entry.getGameObject();
            if (object != null) return new Live(object::getClickbox, fixed);
            if (entry.getType() == MenuAction.WALK && entry.getParam0() > 1 && entry.getParam1() > 1) {
                Rectangle walk = new Rectangle(entry.getParam0() - WALK_HALF_X, entry.getParam1() - WALK_HALF_Y,
                        2 * WALK_HALF_X + 1, 2 * WALK_HALF_Y + 1);
                return () -> walk;
            }
            Widget widget = entry.getWidget();
            if (widget != null && !usable(bounds)) return new Live(widget::getBounds, fixed);
        }
        return fixed == null ? null : () -> fixed;
    }

    /** The caller's own rectangle, else a small box around its point; null when it only has placeholders. */
    private static Rectangle fixedShape(Rectangle bounds, Point point) {
        if (usable(bounds)) return new Rectangle(bounds);
        if (point != null && point.getX() > 1 && point.getY() > 1) {
            return new Rectangle(point.getX() - POINT_HALF, point.getY() - POINT_HALF, 2 * POINT_HALF + 1, 2 * POINT_HALF + 1);
        }
        return null;
    }

    /**
     * A target read from the game (hull, clickbox, widget), so on the client thread, and read again before the
     * press. When the read fails (client thread busy, target gone) the caller's own rectangle stands in.
     */
    static final class Live implements Supplier<Shape> {
        private final Supplier<Shape> read;
        private final Shape fallback;

        Live(Supplier<Shape> read, Shape fallback) {
            this.read = read;
            this.fallback = fallback;
        }

        @Override
        public Shape get() {
            Shape s = onClientThread(read);
            return s != null ? s : fallback;
        }
    }

    /** A real on-canvas rectangle, not upstream's (1,1) / (0,0) placeholders. */
    static boolean usable(Rectangle r) {
        if (r == null || r.width < 2 || r.height < 2) return false;
        if (r.x <= 1 && r.y <= 1 && r.width <= 2 && r.height <= 2) return false;
        int w = Microbot.getClient().getCanvasWidth(), h = Microbot.getClient().getCanvasHeight();
        return w <= 0 || h <= 0 || new Rectangle(0, 0, w, h).contains(r.getCenterX(), r.getCenterY());
    }

    /** Hurried when not calm (a fight) or for a run of the same click, otherwise normal. */
    static Pace paceFor(Action kind, boolean calm) {
        if (!calm || kind == Action.SEQUENCE) return Pace.HURRIED;
        return Pace.NORMAL;
    }

    /** Names the target across clicks so repeats keep a habitual spot (inventory slot, same object). */
    public static String habitKey(NewMenuEntry entry) {
        if (entry == null || entry.getType() == null) return null;
        if (entry.getWidget() != null || isWidgetAction(entry.getType())) {
            return "w:" + entry.getParam1() + ":" + entry.getParam0();
        }
        if (entry.getGameObject() != null) return "obj:" + entry.getIdentifier();
        return null;
    }

    /**
     * Plays one click inside a gesture: HumanMouse path onto the target, re-read and corrected if the target
     * slid, the menu entry armed right before the button goes down, a human button hold.
     *
     * @return where the button went down
     */
    public static Point playClick(InputLoop.Emit emit, Supplier<Shape> source, int button, NewMenuEntry entry, Pace pace, String habitKey) {
        HumanMouse m = mouse();
        int[] from = cursor();
        Shape shape = source == null ? null : source.get();
        Target target = toTarget(shape);
        if (target == null) {
            // Nothing to aim at (the swapped menu entry runs wherever the press lands): press where the cursor
            // is if that's on the 3D view, else somewhere in the middle of it. Never a fixed pixel, never the
            // minimap, chatbox or side panel, where the press would do something of its own.
            Rectangle spot = blindSpot(from);
            shape = spot;
            target = Target.rect(spot.x, spot.y, spot.width, spot.height);
            source = null;
        }
        MousePlan plan = choosePlan(m, from, shape, target, pace, habitKey);
        for (int attempt = 0; ; attempt++) {
            MousePlan.Press press = plan.finalPress();
            // A click plan always ends in a press; a stopping script (interrupt) presses nothing.
            if (press == null || !playMoves(emit, plan.steps(), press.downMs)) return null;

            if (source != null && attempt < MAX_RETARGETS) {
                Shape now = source.get();
                if (now != null && !now.contains(press.x + 0.5, press.y + 0.5)) {
                    Target moved = toTarget(now);
                    if (moved != null) {
                        plan = choosePlan(m, new int[]{press.x, press.y}, now, moved, Pace.CAREFUL, null);
                        continue;
                    }
                }
            }

            if (entry != null) Microbot.targetMenu = entry;
            emit.press(press.x, press.y, button);
            // The model lets the cursor slip a pixel during the hold; AWT would call that a drag, so the
            // button comes up where it went down.
            waitFor(press.upMs - press.downMs);
            emit.release(press.x, press.y, button);
            emit.click(press.x, press.y, button);
            return new Point(press.x, press.y);
        }
    }

    /**
     * Moves the cursor to about (x, y) the way the player's hand does, outside a click (hovering, parking,
     * leaving the canvas). Replaces NaturalMouse's trajectory; each step goes through the Mouse's own move,
     * which stops when the human takes over.
     */
    public static void travel(int x, int y) {
        // Runs inside NaturalMouse's synchronized move: never wait on the client thread here.
        HumanMouse m = mouse(cachedName);
        int[] from = cursor();
        int[] canvas = canvasSize();
        int tx = x, ty = y;
        if (canvas != null) {
            tx = Math.max(0, Math.min(canvas[0] - 1, x));
            ty = Math.max(0, Math.min(canvas[1] - 1, y));
        }
        if (from[0] == tx && from[1] == ty) return;
        MousePlan plan;
        synchronized (LOCK) {
            plan = m.move(from[0], from[1], tx, ty);
        }
        long t0 = System.nanoTime();
        List<MousePlan.Step> steps = plan.steps();
        for (int i = 1; i < steps.size(); i++) {
            MousePlan.Step s = steps.get(i);
            if (s.kind != MousePlan.Kind.MOVE) continue;
            if (!waitUntil(t0, s.tMs)) return;
            Microbot.getMouse().move(new Point(s.x, s.y));
        }
    }

    /** A HumanMouse click point inside {@code r} (for drags), or its centre when there is no usable box. */
    public static Point pointIn(Rectangle r) {
        if (!usable(r)) return r == null ? null : new Point((int) r.getCenterX(), (int) r.getCenterY());
        int[] from = cursor();
        HumanMouse m = mouse();
        int[] p;
        synchronized (LOCK) {
            p = m.clickPoint(from[0], from[1], Target.rect(r.x, r.y, r.width, r.height), Pace.NORMAL, null);
        }
        return new Point(p[0], p[1]);
    }

    /** Where a click without a target goes: the cursor's spot on the 3D view, or the view's middle part. */
    static Rectangle blindSpot(int[] cursor) {
        int vx = Microbot.getClient().getViewportXOffset(), vy = Microbot.getClient().getViewportYOffset();
        int vw = Microbot.getClient().getViewportWidth(), vh = Microbot.getClient().getViewportHeight();
        if (vw < 50 || vh < 50) return new Rectangle(cursor[0] - 1, cursor[1] - 1, 3, 3);
        // Resizable mode draws the minimap, orbs and chatbox over the view's corners and bottom.
        Rectangle middle = new Rectangle(vx + vw / 5, vy + vh / 5, vw * 3 / 5, vh * 3 / 5);
        if (middle.contains(cursor[0], cursor[1])) return new Rectangle(cursor[0] - 1, cursor[1] - 1, 3, 3);
        double rx = ThreadLocalRandom.current().nextGaussian() * 0.15, ry = ThreadLocalRandom.current().nextGaussian() * 0.15;
        int x = (int) Math.round(middle.getCenterX() + Math.max(-0.45, Math.min(0.45, rx)) * middle.width);
        int y = (int) Math.round(middle.getCenterY() + Math.max(-0.45, Math.min(0.45, ry)) * middle.height);
        return new Rectangle(x - 4, y - 4, 9, 9);
    }

    public static int buttonFor(boolean rightClick) {
        return rightClick ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
    }

    /** The player's own mouse traits, from the same HumanId as the timing. Average before login. */
    private static HumanMouse mouse() {
        return mouse(playerName());
    }

    private static HumanMouse mouse(String name) {
        synchronized (LOCK) {
            if (mouse == null || (name != null && !name.equals(mouseName))) {
                mouse = new HumanMouse(name == null ? MouseProfile.average() : MouseProfile.fromName(name));
                mouseName = name;
            }
            return mouse;
        }
    }

    /** A plan whose press lands inside the real (possibly concave) clickbox, on the canvas. */
    private static MousePlan choosePlan(HumanMouse m, int[] from, Shape shape, Target target, Pace pace, String habitKey) {
        int[] canvas = canvasSize();
        MousePlan plan = null;
        synchronized (LOCK) {
            for (int i = 0; i < PLAN_TRIES; i++) {
                plan = m.click(from[0], from[1], target, pace, habitKey, SafeMiss.NEVER);
                MousePlan.Press p = plan.finalPress();
                if (p != null && shape.contains(p.x + 0.5, p.y + 0.5)
                        && (canvas == null || (p.x >= 0 && p.y >= 0 && p.x < canvas[0] && p.y < canvas[1]))) {
                    return plan;
                }
            }
        }
        return plan;
    }

    /**
     * Plays the moves up to {@code untilMs}. Step 0 is where the cursor already is. A move already overdue
     * when the next is due too is skipped, so a late start catches up instead of replaying slowly.
     */
    private static boolean playMoves(InputLoop.Emit emit, List<MousePlan.Step> steps, long untilMs) {
        long t0 = System.nanoTime();
        for (int i = 1; i < steps.size(); i++) {
            MousePlan.Step s = steps.get(i);
            if (s.tMs >= untilMs) break;
            if (s.kind != MousePlan.Kind.MOVE) continue;
            MousePlan.Step next = i + 1 < steps.size() ? steps.get(i + 1) : null;
            boolean superseded = next != null && next.kind == MousePlan.Kind.MOVE
                    && next.tMs < untilMs && elapsedMs(t0) >= next.tMs;
            if (superseded) continue;
            if (!waitUntil(t0, s.tMs)) return false;
            emit.move(s.x, s.y);
        }
        return waitUntil(t0, untilMs);
    }

    /** The convex hull of a clickbox as a HumanMouse target; null when missing or off the canvas. */
    static Target toTarget(Shape shape) {
        if (shape == null) return null;
        Rectangle bounds = shape.getBounds();
        if (bounds == null || bounds.width < 2 || bounds.height < 2) return null;
        int[] canvas = canvasSize();
        if (canvas != null && !new Rectangle(0, 0, canvas[0], canvas[1]).contains(bounds.getCenterX(), bounds.getCenterY())) {
            return null;
        }
        List<double[]> points = new ArrayList<>();
        double[] c = new double[6];
        for (PathIterator it = shape.getPathIterator(null, 1.0); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
                points.add(new double[]{c[0], c[1]});
            }
        }
        double[][] hull = convexHull(points);
        if (hull == null) return null;
        try {
            return Target.polygon(hull[0], hull[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Andrew's monotone chain. Returns {xs, ys}, or null for fewer than 3 distinct corners. */
    static double[][] convexHull(List<double[]> points) {
        double[][] p = points.toArray(new double[0][]);
        Arrays.sort(p, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = p.length;
        if (n < 3) return null;
        double[][] h = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
            h[k++] = p[i];
        }
        for (int i = n - 2, lower = k + 1; i >= 0; i--) {
            while (k >= lower && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
            h[k++] = p[i];
        }
        k--;
        if (k < 3) return null;
        double[] xs = new double[k], ys = new double[k];
        for (int i = 0; i < k; i++) {
            xs[i] = h[i][0];
            ys[i] = h[i][1];
        }
        return new double[][]{xs, ys};
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /**
     * The logged-in player's name, cached for a few seconds. Never call it with LOCK (or any other monitor the
     * client thread may want) held: a refresh waits on the client thread.
     */
    private static String playerName() {
        long now = System.currentTimeMillis();
        String cached = cachedName;
        if (cached != null && now - cachedNameMs < NAME_CACHE_MS) return cached;
        try {
            if (!Microbot.isLoggedIn()) {
                cachedName = null;
                return null;
            }
            String name = onClientThread(HumanInput::nameNow);
            if (name == null || name.isEmpty()) return cached;
            cachedName = name;
            cachedNameMs = now;
            return name;
        } catch (Exception ex) {
            return cached;
        }
    }

    private static String nameNow() {
        Player p = Microbot.getClient().getLocalPlayer();
        return p == null ? null : p.getName();
    }

    private static int[] canvasSize() {
        int w = Microbot.getClient().getCanvasWidth();
        int h = Microbot.getClient().getCanvasHeight();
        return w > 0 && h > 0 ? new int[]{w, h} : null;
    }

    /** Where the cursor is; a pointer that left the canvas starts from the nearest edge. */
    private static int[] cursor() {
        int x = PointerState.getX(), y = PointerState.getY();
        int[] c = canvasSize();
        if (c != null) {
            x = Math.max(0, Math.min(c[0] - 1, x));
            y = Math.max(0, Math.min(c[1] - 1, y));
        }
        return new int[]{x, y};
    }

    private static <T> T onClientThread(Supplier<T> work) {
        try {
            if (Microbot.getClient().isClientThread() || Microbot.getClientThread() == null) return work.get();
            return Microbot.getClientThread().runOnClientThreadOptional(work::get).orElse(null);
        } catch (Exception ex) {
            log.debug("HumanInput: target read failed", ex);
            return null;
        }
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static boolean waitUntil(long t0, long tMs) {
        long due = t0 + tMs * 1_000_000L;
        long left;
        while ((left = due - System.nanoTime()) > 0) {
            if (Thread.currentThread().isInterrupted()) return false;
            LockSupport.parkNanos(Math.min(left, MAX_PARK_NS));
        }
        return !Thread.currentThread().isInterrupted();
    }

    private static void waitFor(long ms) {
        waitUntil(System.nanoTime(), ms);
    }
}
