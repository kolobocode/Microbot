package net.runelite.client.plugins.microbot.util.mouse;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.input.AwtEmitter;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.input.InputLoop;
import net.runelite.client.plugins.microbot.util.input.PointerState;
import net.runelite.client.plugins.microbot.humanmouse.Pace;
import net.runelite.client.plugins.microbot.util.human.ConsumeGuard;
import net.runelite.client.plugins.microbot.util.human.HumanInput;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;

import javax.inject.Inject;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static net.runelite.client.plugins.microbot.util.Global.sleep;

@Slf4j
public class VirtualMouse extends Mouse {

    private final ScheduledExecutorService scheduledExecutorService;

    // LOCAL PATCH (detection audit 23-09): per-session press-duration band for the plain click path,
    // so different sessions/accounts don't share one histogram. (HumanInput's own click path models
    // the button hold itself.)
    private final int pressMinMs = 42 + java.util.concurrent.ThreadLocalRandom.current().nextInt(28);
    private final int pressMaxMs = pressMinMs + 55 + java.util.concurrent.ThreadLocalRandom.current().nextInt(45);

    @Inject
    public VirtualMouse() {
        super();
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    }

    public void setLastClick(Point point) {
        lastClick2 = lastClick;
        lastClick = point;
    }

    // Feeds the debug overlay's fading trail only; position itself lives in PointerState.
    private void recordTrailPoint(Point point) {
        points.add(point);
        if (points.size() > MAX_POINTS) {
            points.pollFirst();
        }
    }

    private void handleClick(InputLoop.Emit emit, Point point, boolean rightClick) {
        int button = rightClick ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
        // A human clicking where the pointer already is sends no fresh MOVED, so emit one only
        // when it is not there, which happens when NaturalMouse was skipped. No ENTERED/EXITED:
        // no human click sends those, and the EXITED wrote (-1,-1) into the tracked position.
        if (!PointerState.isAt(point.getX(), point.getY())) {
            emit.move(point.getX(), point.getY());
        }
        emit.press(point.getX(), point.getY(), button);
        sleep(Rs2Random.logNormalBounded(pressMinMs, pressMaxMs)); // LOCAL PATCH: a button hold, not an instant triad
        emit.release(point.getX(), point.getY(), button);
        emit.click(point.getX(), point.getY(), button);
        setLastClick(point);
    }

    private boolean shouldMoveNaturally(Point point) {
        return point.getX() > 1
                && point.getY() > 1
                && Microbot.naturalMouse != null;
    }

    /**
     * A gesture takes the {@link InputLoop} lock and sleeps while holding it, and neither may
     * happen on the client thread. Every gesture goes through here so none can forget.
     */
    private void runGesture(Runnable gesture) {
        if (Microbot.getClient().isClientThread()) {
            scheduledExecutorService.schedule(gesture, 0, TimeUnit.MILLISECONDS);
        } else {
            gesture.run();
        }
    }

    /**
     * LOCAL PATCH: the one click path when {@link HumanInput} is on. A HumanTiming think time on the calling
     * thread, then one gesture with HumanMouse's path, press point and button hold.
     */
    private Mouse humanClick(Supplier<java.awt.Shape> target, Point point, boolean rightClick, NewMenuEntry entry) {
        boolean mayPause = !Microbot.getClient().isClientThread();
        if (!ConsumeGuard.allow(entry, mayPause)) return this; // eat/drink: game timer and the player's own moment
        runGesture(() -> {
            Pace pace = HumanInput.beforeClick(entry, point, mayPause);
            InputLoop.run(emit -> {
                Point at = HumanInput.playClick(emit, target, HumanInput.buttonFor(rightClick), entry, pace, HumanInput.habitKey(entry));
                if (at != null) {
                    recordTrailPoint(at);
                    setLastClick(at);
                }
            });
        });
        return this;
    }

    /** LOCAL PATCH: a click somewhere inside {@code bounds} (or on the entry's own target), with the entry swapped in. */
    public Mouse click(Rectangle bounds, boolean rightClick, NewMenuEntry entry) {
        Point centre = bounds == null ? null : new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
        if (!HumanInput.ENABLED) {
            return entry == null ? click(Rs2UiHelper.getClickingPoint(bounds, true), rightClick)
                    : click(Rs2UiHelper.getClickingPoint(bounds, true), rightClick, entry);
        }
        return humanClick(HumanInput.shapeFor(entry, bounds, centre), centre, rightClick, entry);
    }

    public Mouse click(Point point, boolean rightClick) {
        if (point == null) return this;
        if (HumanInput.ENABLED) return humanClick(HumanInput.shapeFor(null, null, point), point, rightClick, null); // LOCAL PATCH

        runGesture(() -> InputLoop.run(emit -> {
            if (shouldMoveNaturally(point)) {
                Microbot.naturalMouse.moveTo(point.getX(), point.getY());
            }
            handleClick(emit, point, rightClick);
        }));

        return this;
    }


    public Mouse click(Point point, boolean rightClick, NewMenuEntry entry) {
        if (point == null) return this;
        if (HumanInput.ENABLED) return humanClick(HumanInput.shapeFor(entry, null, point), point, rightClick, entry); // LOCAL PATCH

        runGesture(() -> InputLoop.run(emit -> {
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
            handleClick(emit, newPoint, rightClick);
        }));

        return this;
    }


    public Mouse click(int x, int y) {
        return click(new Point(x, y), false);
    }

    public Mouse click(double x, double y) {
        return click(new Point((int) x, (int) y), false);
    }

    public Mouse click(Rectangle rectangle) {
        return click(rectangle, false, null); // LOCAL PATCH: HumanMouse picks the point inside
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

    // NaturalMouse steps through here, so this one check also stops a trajectory mid-curve.
    public Mouse move(Point point) {
        if (InputArbiter.isHuman()) {
            return this;
        }
        recordTrailPoint(point);
        AwtEmitter.moved(point.getX(), point.getY());
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

    /**
     * One gesture covering both the move and the wheel, so another script's click cannot land
     * between them and leave the wheel firing at a point the cursor has left.
     *
     * <p>The pause stays: a human turns the wheel a moment after arriving, not in the same
     * instant. A takeover during it aborts at the wheel's checkpoint.
     *
     * <p>LOCAL PATCH (detection audit 23-09): two separate one-notch wheel events (3 lines each,
     * the Windows default) instead of a single rotation=2 event.
     */
    private Mouse scroll(Point point, int direction) {
        if (point == null) return this;

        runGesture(() -> InputLoop.run(emit -> {
            if (HumanInput.ENABLED) HumanInput.travel(point.getX(), point.getY()); // LOCAL PATCH: the hand gets there
            emit.move(point.getX(), point.getY());
            recordTrailPoint(point);
            sleep(Rs2Random.logNormalBounded(40, 110));
            emit.wheel(point.getX(), point.getY(), direction, 3);
            sleep(Rs2Random.logNormalBounded(25, 80));
            emit.wheel(point.getX(), point.getY(), direction, 3);
        }));

        return this;
    }

    @Override
    public java.awt.Point getMousePosition() {
        Point point = PointerState.get();
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

    public void shutdown() {
        scheduledExecutorService.shutdownNow();
    }

    private void moveTowards(Point point) {
        if (HumanInput.ENABLED) {
            HumanInput.travel(point.getX(), point.getY()); // LOCAL PATCH
            move(point);
        } else if (shouldMoveNaturally(point))
            Microbot.naturalMouse.moveTo(point.getX(), point.getY());
        else
            move(point);
    }

    // The one gesture holding a button across time, and the reason the held-button set exists.
    // The sleeps return immediately under a takeover and the next emit aborts, releasing at the
    // human's point rather than falling through to a RELEASED at the stale end point.
    public Mouse drag(Point startPoint, Point endPoint) {
        if (startPoint == null || endPoint == null) return this;

        runGesture(() -> InputLoop.run(emit -> {
            moveTowards(startPoint);
            sleep(Rs2Random.logNormalBounded(50, 80));
            emit.press(startPoint.getX(), startPoint.getY(), MouseEvent.BUTTON1);
            sleep(Rs2Random.logNormalBounded(80, 120));
            moveTowards(endPoint);
            sleep(Rs2Random.logNormalBounded(80, 120));
            emit.release(endPoint.getX(), endPoint.getY(), MouseEvent.BUTTON1);
        }));

        return this;
    }
}
