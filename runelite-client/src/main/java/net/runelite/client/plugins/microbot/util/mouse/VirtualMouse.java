package net.runelite.client.plugins.microbot.util.mouse;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;

import javax.inject.Inject;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.Global.sleep;

@Slf4j
public class VirtualMouse extends Mouse {

    private final ScheduledExecutorService scheduledExecutorService;

    @Inject
    public VirtualMouse() {
        super();
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    }

    public void setLastClick(Point point) {
        lastClick2 = lastClick;
        lastClick = point;
    }

	public void setLastMove(Point point) {
		lastMove = point;
		points.add(point);
		if (points.size() > MAX_POINTS) {
			points.pollFirst();
		}
	}

    private int[] scaleForDispatch(int x, int y) {
        Client c;
        try {
            c = Microbot.getClient();
        } catch (Exception ex) {
            return new int[]{x, y};
        }
        if (c == null || !c.isStretchedEnabled()) {
            return new int[]{x, y};
        }
        Dimension stretched = c.getStretchedDimensions();
        Dimension real = c.getRealDimensions();
        if (stretched == null || real == null || real.width == 0 || real.height == 0) {
            return new int[]{x, y};
        }
        return new int[]{
                (int) ((long) x * stretched.width / real.width),
                (int) ((long) y * stretched.height / real.height)
        };
    }

    // LOCAL PATCH (detection audit 23-09): events now carry the modifier masks, popup trigger,
    // click count and enter/exit sequence a real Windows mouse produces. Use the Input Probe
    // plugin to compare against your own mouse.
    private static final boolean POPUP_ON_PRESS = !System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final int DOUBLE_CLICK_MS = 500;
    private static final int DOUBLE_CLICK_SLOP_PX = 4;

    // Per-session press-duration band, so different sessions/accounts don't share one histogram.
    private final int pressMinMs = 42 + java.util.concurrent.ThreadLocalRandom.current().nextInt(28);
    private final int pressMaxMs = pressMinMs + 55 + java.util.concurrent.ThreadLocalRandom.current().nextInt(45);

    private volatile boolean outsideCanvas = true;
    private volatile int heldButtonMask = 0;
    private long lastReleaseAt = 0;
    private Point lastReleasePoint = new Point(-100, -100);
    private int clickStreak = 0;

    private void dispatchMouse(int id, Point point, int button, int clickCount, int modifiersEx, boolean popupTrigger) {
        int[] s = scaleForDispatch(point.getX(), point.getY());
        Canvas canvas = getCanvas();
        MouseEvent event = new MouseEvent(canvas, id, System.currentTimeMillis(), modifiersEx,
                s[0], s[1], clickCount, popupTrigger, button);
        dispatchWithoutFocusGrab(canvas, event);
    }

    private void dispatchMouseMove(int id, Point point) {
        int[] s = scaleForDispatch(point.getX(), point.getY());
        Canvas canvas = getCanvas();
        MouseEvent event = new MouseEvent(canvas, id, System.currentTimeMillis(), heldButtonMask,
                s[0], s[1], 0, false);
        dispatchWithoutFocusGrab(canvas, event);
    }

    private void dispatchWheel(Point point, int wheelRotation, int scrollAmount) {
        int[] s = scaleForDispatch(point.getX(), point.getY());
        Canvas canvas = getCanvas();
        MouseWheelEvent event = new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), heldButtonMask, s[0], s[1], 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, scrollAmount, wheelRotation);
        dispatchWithoutFocusGrab(canvas, event);
    }

    private boolean isInsideCanvas(Point p) {
        // points are in game (unstretched) coordinates, same as client canvas dimensions
        Client c = Microbot.getClient();
        return p.getX() >= 0 && p.getY() >= 0 && p.getX() < c.getCanvasWidth() && p.getY() < c.getCanvasHeight();
    }

    /**
     * Emits what AWT would for a pointer arriving at {@code point}: ENTERED when coming from
     * outside, MOVED/DRAGGED while inside, one EXITED on leaving and nothing while outside.
     */
    private void pointerTo(Point point) {
        boolean inside = isInsideCanvas(point);
        if (!inside) {
            if (!outsideCanvas) {
                outsideCanvas = true;
                dispatchMouseMove(MouseEvent.MOUSE_EXITED, point);
            }
            return;
        }
        if (outsideCanvas) {
            outsideCanvas = false;
            dispatchMouseMove(MouseEvent.MOUSE_ENTERED, point);
        }
        dispatchMouseMove(heldButtonMask != 0 ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED, point);
    }

    private static int downMask(int button) {
        switch (button) {
            case MouseEvent.BUTTON3: return InputEvent.BUTTON3_DOWN_MASK;
            case MouseEvent.BUTTON2: return InputEvent.BUTTON2_DOWN_MASK;
            default: return InputEvent.BUTTON1_DOWN_MASK;
        }
    }

    private int nextClickCount(Point point, long now) {
        boolean near = Math.abs(point.getX() - lastReleasePoint.getX()) <= DOUBLE_CLICK_SLOP_PX
                && Math.abs(point.getY() - lastReleasePoint.getY()) <= DOUBLE_CLICK_SLOP_PX;
        clickStreak = (near && now - lastReleaseAt <= DOUBLE_CLICK_MS) ? clickStreak + 1 : 1;
        return clickStreak;
    }

    // Jagex's MOUSE_PRESSED listener calls canvas.requestFocus() when the event source is the
    // Canvas, which yanks OS keyboard focus away from whatever app the user is typing in. Flip
    // focusable off for the duration of the synthetic dispatch so requestFocus is a no-op; mouse
    // delivery itself is unaffected by focusable state.
    //
    // IMPORTANT: only do this when the canvas is NOT currently the focus owner. If the user is
    // actively typing in the in-game chat (which lives inside the canvas), the canvas IS the focus
    // owner, and setFocusable(false) immediately yanks focus away to the parent container — exactly
    // the opposite of what this method is trying to prevent. Detect that case and skip the toggle.
    private void dispatchWithoutFocusGrab(Canvas canvas, AWTEvent event) {
        boolean canvasIsFocused = canvas.isFocusOwner();
        boolean wasFocusable = canvas.isFocusable();
        boolean shouldGuard = wasFocusable && !canvasIsFocused;
        if (shouldGuard) canvas.setFocusable(false);
        BotEventGuard.begin();
        try {
            canvas.dispatchEvent(event);
        } finally {
            BotEventGuard.end();
            if (shouldGuard) canvas.setFocusable(true);
        }
    }

    private synchronized void handleClick(Point point, boolean rightClick) {
        int button = rightClick ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
        // Only report a move when the pointer isn't already there (NaturalMouse usually put it there).
        if (outsideCanvas || lastMove == null || lastMove.getX() != point.getX() || lastMove.getY() != point.getY()) {
            setLastMove(point);
            pointerTo(point);
        }
        int clickCount = nextClickCount(point, System.currentTimeMillis());
        pressed(point, button, clickCount, rightClick && POPUP_ON_PRESS);
        try {
            sleep(Rs2Random.logNormalBounded(pressMinMs, pressMaxMs));
        } finally {
            released(point, button, clickCount, rightClick && !POPUP_ON_PRESS);
        }
        clicked(point, button, clickCount);
        lastReleaseAt = System.currentTimeMillis();
        lastReleasePoint = point;
        setLastClick(point);
    }

    private boolean shouldMoveNaturally(Point point) {
        return point.getX() > 1
                && point.getY() > 1
                && Microbot.naturalMouse != null;
    }

    public Mouse click(Point point, boolean rightClick) {
        if (point == null) return this;

        Runnable clickAction = () -> {
            if (shouldMoveNaturally(point)) {
                Microbot.naturalMouse.moveTo(point.getX(), point.getY());
            }
            handleClick(point, rightClick);
        };

        if (Microbot.getClient().isClientThread()) {
            scheduledExecutorService.schedule(clickAction, 0, TimeUnit.MILLISECONDS);
        } else {
            clickAction.run();
        }

        return this;
    }


    public Mouse click(Point point, boolean rightClick, NewMenuEntry entry) {
        if (point == null) return this;

        Runnable clickAction = () -> {
            Point newPoint = point;
            if (shouldMoveNaturally(point)) {
                Microbot.naturalMouse.moveTo(point.getX(), point.getY());

                if (Rs2UiHelper.hasActor(entry)) {
                    Rectangle rectangle = Rs2UiHelper.getActorClickbox(entry.getActor());
                    if (!Rs2UiHelper.isMouseWithinRectangle(rectangle)) {
                        newPoint = Rs2UiHelper.getClickingPoint(rectangle, true);
                        Microbot.naturalMouse.moveTo(newPoint.getX(), newPoint.getY());
                    }
                }

                if (Rs2UiHelper.isGameObject(entry)) {
                    Rectangle rectangle = Rs2UiHelper.getObjectClickbox(entry.getGameObject());
                    if (!Rs2UiHelper.isMouseWithinRectangle(rectangle)) {
                        newPoint = Rs2UiHelper.getClickingPoint(rectangle, true);
                        Microbot.naturalMouse.moveTo(newPoint.getX(), newPoint.getY());

                    }
                }
            }

            Microbot.targetMenu = entry;
            handleClick(newPoint, rightClick);
        };

        if (Microbot.getClient().isClientThread()) {
            scheduledExecutorService.schedule(clickAction, 0, TimeUnit.MILLISECONDS);
        } else {
            clickAction.run();
        }

        return this;
    }


    public Mouse click(int x, int y) {
        return click(new Point(x, y), false);
    }

    public Mouse click(double x, double y) {
        return click(new Point((int) x, (int) y), false);
    }

    public Mouse click(Rectangle rectangle) {
        return click(Rs2UiHelper.getClickingPoint(rectangle, true), false);
    }

    @Override
    public Mouse click(int x, int y, boolean rightClick) {
        return click(new Point(x, y), rightClick);
    }

    @Override
    public Mouse click(Point point) {
        return click(point, false);
    }

    @Override
    public Mouse click(Point point, NewMenuEntry entry) {
        return click(point, false, entry);
    }

    @Override
    public Mouse click() {
        return click(Microbot.getClient().getMouseCanvasPosition());
    }

    public Mouse move(Point point) {
        setLastMove(point);
        pointerTo(point);
        return this;
    }

    public Mouse move(Rectangle rect) {
        return move(new Point((int) rect.getCenterX(), (int) rect.getCenterY()));
    }

    public Mouse move(Polygon polygon) {
        return move(new Point((int) polygon.getBounds().getCenterX(), (int) polygon.getBounds().getCenterY()));
    }

    public Mouse scrollDown(Point point) {
        return scroll(point, 1);
    }

    public Mouse scrollUp(Point point) {
        return scroll(point, -1);
    }

    // LOCAL PATCH (detection audit 23-09): travel there along a path, then two separate one-notch
    // wheel events (3 lines each, the Windows default) instead of a single rotation=2 event.
    private Mouse scroll(Point point, int direction) {
        if (point == null) return this;
        java.util.concurrent.Future<?> done = scheduledExecutorService.submit(() -> {
            if (shouldMoveNaturally(point)) {
                Microbot.naturalMouse.moveTo(point.getX(), point.getY());
            } else {
                move(point);
            }
            sleep(Rs2Random.logNormalBounded(40, 110));
            dispatchWheel(point, direction, 3);
            sleep(Rs2Random.logNormalBounded(25, 80));
            dispatchWheel(point, direction, 3);
        });
        // Callers poll right after scrolling; wait so scrolls don't pile up in the queue.
        if (!Microbot.getClient().isClientThread()) {
            try {
                done.get(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            }
        }
        return this;
    }

    @Override
    public java.awt.Point getMousePosition() {
        Point point = lastMove;
        return new java.awt.Point(point.getX(), point.getY());
    }

    @Override
    public Mouse move(int x, int y) {
        return move(new Point(x, y));
    }

    @Override
    public Mouse move(double x, double y) {
        return move(new Point((int) x, (int) y));
    }

    // AWT: PRESSED carries the button's DOWN mask; RELEASED/CLICKED carry none (the button is up).
    private synchronized void pressed(Point point, int button, int clickCount, boolean popupTrigger) {
        heldButtonMask |= downMask(button);
        dispatchMouse(MouseEvent.MOUSE_PRESSED, point, button, clickCount, heldButtonMask, popupTrigger);
    }

    private synchronized void released(Point point, int button, int clickCount, boolean popupTrigger) {
        heldButtonMask &= ~downMask(button);
        dispatchMouse(MouseEvent.MOUSE_RELEASED, point, button, clickCount, heldButtonMask, popupTrigger);
    }

    private synchronized void clicked(Point point, int button, int clickCount) {
        dispatchMouse(MouseEvent.MOUSE_CLICKED, point, button, clickCount, heldButtonMask, false);
    }

    public void shutdown() {
        scheduledExecutorService.shutdownNow();
    }

    public Mouse drag(Point startPoint, Point endPoint) {
        if (startPoint == null || endPoint == null) return this;

        if (shouldMoveNaturally(startPoint))
            Microbot.naturalMouse.moveTo(startPoint.getX(), startPoint.getY());
        else
            move(startPoint);
        sleep(Rs2Random.logNormalBounded(50, 80));
        pressed(startPoint, MouseEvent.BUTTON1, 1, false);
        try {
            sleep(Rs2Random.logNormalBounded(80, 120));
            // heldButtonMask is set, so every step of this path goes out as MOUSE_DRAGGED
            if (shouldMoveNaturally(endPoint))
                Microbot.naturalMouse.moveTo(endPoint.getX(), endPoint.getY());
            else
                move(endPoint);
            sleep(Rs2Random.logNormalBounded(80, 120));
        } finally {
            released(endPoint, MouseEvent.BUTTON1, 1, false);
        }
        // a drag never produces MOUSE_CLICKED
        lastReleaseAt = 0;

        return this;
    }
}
