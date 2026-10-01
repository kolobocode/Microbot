package net.runelite.client.plugins.microbot.util.input;

import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.mouse.BotEventGuard;

import java.awt.AWTEvent;
import java.awt.Canvas;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dispatches synthetic AWT mouse events on the game canvas, taking canvas coordinates.
 *
 * <p>Does not decide whether an emit is allowed; that is {@link InputArbiter}'s job, upstream.
 *
 * <p>LOCAL PATCH (detection audit 23-09): events carry the modifier masks, popup trigger, click
 * count and drag state a real Windows mouse produces. PRESSED carries the button's DOWN mask,
 * RELEASED/CLICKED carry none (the button is up), motion while a button is held goes out as
 * MOUSE_DRAGGED, a right click sets popupTrigger on RELEASED (Windows) or PRESSED (elsewhere), and
 * quick repeat clicks on the same spot count up like the OS double-click counter does. Use the
 * Input Probe plugin to compare against your own mouse.
 */
public final class AwtEmitter
{
	/** Exit coordinates can land a pixel outside the component. */
	private static final int EDGE_SLOP = 2;

	// Re-entry spread. Guesses, not a model of a person; the point is only that entry stops being
	// a function of exit. Wider after a covered exit, where nothing anchors the pointer.
	private static final int EDGE_SIGMA_PX = 35;
	private static final int COVERED_SIGMA_PX = 120;
	private static final double COVERED_EDGE_RETURN_CHANCE = 0.35;

	private static final boolean POPUP_ON_PRESS = !System.getProperty("os.name", "").toLowerCase().contains("win");
	private static final int DOUBLE_CLICK_MS = 500;
	private static final int DOUBLE_CLICK_SLOP_PX = 4;

	private static volatile int heldButtonMask = 0;
	private static long lastReleaseAt = 0;
	private static Point lastReleasePoint = new Point(-100, -100);
	private static Point lastPressPoint = new Point(-100, -100);
	private static int clickStreak = 0;

	private AwtEmitter()
	{
	}

	public static void moved(int canvasX, int canvasY)
	{
		// Here, not at the callers: every motion funnels through this method.
		if (exitIfOutside(canvasX, canvasY))
		{
			return;
		}
		Canvas canvas = canvas();
		if (canvas == null)
		{
			return;
		}
		enterIfOutside(canvas, canvasX, canvasY);
		Point component = StretchMapper.toComponent(canvasX, canvasY);
		recordPosition(canvasX, canvasY);
		int mask = heldButtonMask;
		dispatch(canvas, new MouseEvent(canvas, mask != 0 ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED,
			System.currentTimeMillis(), mask, component.getX(), component.getY(), 0, false));
	}

	public static void pressed(int canvasX, int canvasY, int button)
	{
		net.runelite.client.plugins.microbot.util.human.HumanInput.notePress(); // LOCAL PATCH
		button(MouseEvent.MOUSE_PRESSED, canvasX, canvasY, button);
	}

	public static void released(int canvasX, int canvasY, int button)
	{
		button(MouseEvent.MOUSE_RELEASED, canvasX, canvasY, button);
	}

	public static void clicked(int canvasX, int canvasY, int button)
	{
		button(MouseEvent.MOUSE_CLICKED, canvasX, canvasY, button);
	}

	public static void wheel(int canvasX, int canvasY, int wheelRotation, int unitsToScroll)
	{
		Canvas canvas = canvas();
		if (canvas == null)
		{
			return;
		}
		enterIfOutside(canvas, canvasX, canvasY);
		Point component = StretchMapper.toComponent(canvasX, canvasY);
		recordPosition(canvasX, canvasY);
		dispatch(canvas, new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), heldButtonMask,
			component.getX(), component.getY(), 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, unitsToScroll, wheelRotation));
	}

	private static synchronized void button(int id, int canvasX, int canvasY, int button)
	{
		Canvas canvas = canvas();
		if (canvas == null)
		{
			return;
		}
		enterIfOutside(canvas, canvasX, canvasY);
		Point component = StretchMapper.toComponent(canvasX, canvasY);
		recordPosition(canvasX, canvasY);

		boolean rightClick = button == MouseEvent.BUTTON3;
		long now = System.currentTimeMillis();
		int clickCount;
		boolean popupTrigger = false;
		switch (id)
		{
			case MouseEvent.MOUSE_PRESSED:
				clickCount = nextClickCount(canvasX, canvasY, now);
				lastPressPoint = new Point(canvasX, canvasY);
				heldButtonMask |= downMask(button);
				popupTrigger = rightClick && POPUP_ON_PRESS;
				break;
			case MouseEvent.MOUSE_RELEASED:
				clickCount = Math.max(1, clickStreak);
				heldButtonMask &= ~downMask(button);
				popupTrigger = rightClick && !POPUP_ON_PRESS;
				// A release away from its press is a drag, which never counts towards a double click.
				lastReleaseAt = near(canvasX, canvasY, lastPressPoint) ? now : 0;
				lastReleasePoint = new Point(canvasX, canvasY);
				break;
			default:
				clickCount = Math.max(1, clickStreak);
				break;
		}
		dispatch(canvas, new MouseEvent(canvas, id, now, heldButtonMask,
			component.getX(), component.getY(), clickCount, popupTrigger, button));
	}

	private static int downMask(int button)
	{
		switch (button)
		{
			case MouseEvent.BUTTON3:
				return InputEvent.BUTTON3_DOWN_MASK;
			case MouseEvent.BUTTON2:
				return InputEvent.BUTTON2_DOWN_MASK;
			default:
				return InputEvent.BUTTON1_DOWN_MASK;
		}
	}

	private static boolean near(int x, int y, Point other)
	{
		return Math.abs(x - other.getX()) <= DOUBLE_CLICK_SLOP_PX && Math.abs(y - other.getY()) <= DOUBLE_CLICK_SLOP_PX;
	}

	private static int nextClickCount(int x, int y, long now)
	{
		clickStreak = (near(x, y, lastReleasePoint) && now - lastReleaseAt <= DOUBLE_CLICK_MS) ? clickStreak + 1 : 1;
		return clickStreak;
	}


	/**
	 * Announces the pointer's return before any other event, when the client believes none is over
	 * the canvas. A real MOUSE_EXITED leaves it tracking (-1,-1), so a bare MOVED or PRESSED after
	 * that delivers motion for a pointer it does not think exists.
	 */
	private static void enterIfOutside(Canvas canvas, int fallbackX, int fallbackY)
	{
		if (!PointerState.isOutside())
		{
			return;
		}
		// Cleared first: the ENTERED below must not re-enter this method.
		PointerState.markInside();

		Point at = PointerState.get();
		int exitX = at.getX() < 0 ? fallbackX : at.getX();
		int exitY = at.getY() < 0 ? fallbackY : at.getY();

		Point entry = reentryPoint(exitX, exitY, canvasWidth(), canvasHeight(), ThreadLocalRandom.current());
		Point component = StretchMapper.toComponent(entry.getX(), entry.getY());
		// Recorded too, or the next click presses where the pointer never travelled.
		recordPosition(entry.getX(), entry.getY());
		dispatch(canvas, new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0,
			component.getX(), component.getY(), 0, false));
	}

	/**
	 * Mirror of {@link #enterIfOutside}. Off-canvas is a real destination: antiban parks the
	 * cursor there.
	 *
	 * @return true when the point is off the canvas, so there is no motion left to send
	 */
	private static boolean exitIfOutside(int canvasX, int canvasY)
	{
		int width = canvasWidth();
		int height = canvasHeight();
		// Unknown size: call everything inside, rather than silencing all motion.
		if (width <= 0 || height <= 0)
		{
			return false;
		}
		if (canvasX >= 0 && canvasX < width && canvasY >= 0 && canvasY < height)
		{
			return false;
		}
		if (PointerState.isOutside())
		{
			return true;
		}
		// Real events win, same rule recordPosition applies: a synthetic exit must not move the
		// human's pointer off the canvas underneath them.
		if (InputArbiter.isHuman())
		{
			return true;
		}

		Canvas canvas = canvas();
		if (canvas == null)
		{
			return false;
		}
		// Through recordPosition, so the bot reference moves out with the pointer. Left behind, the
		// motion threshold would be measured from a point the bot has already left.
		recordPosition(canvasX, canvasY);
		PointerState.markOutside();
		Point component = StretchMapper.toComponent(canvasX, canvasY);
		dispatch(canvas, new MouseEvent(canvas, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0,
			component.getX(), component.getY(), 0, false));
		return true;
	}

	/**
	 * Where the pointer comes back in, given where it went out. An edge exit returns along that
	 * edge; a mid-canvas exit means a window covered the client and the position is unknown, so
	 * the return is drawn from inside or across an edge. The exact exit point stays reachable:
	 * that is the user who never touched the mouse.
	 *
	 * <p>Randomness is a parameter so tests can exercise the distribution.
	 */
	static Point reentryPoint(int exitX, int exitY, int width, int height, Random random)
	{
		if (width <= 0 || height <= 0)
		{
			return new Point(exitX, exitY);
		}

		boolean onVerticalEdge = exitX <= EDGE_SLOP || exitX >= width - 1 - EDGE_SLOP;
		boolean onHorizontalEdge = exitY <= EDGE_SLOP || exitY >= height - 1 - EDGE_SLOP;

		if (onVerticalEdge)
		{
			return new Point(clamp(exitX, width), clamp(gaussian(random, exitY, EDGE_SIGMA_PX), height));
		}
		if (onHorizontalEdge)
		{
			return new Point(clamp(gaussian(random, exitX, EDGE_SIGMA_PX), width), clamp(exitY, height));
		}

		if (random.nextDouble() < COVERED_EDGE_RETURN_CHANCE)
		{
			return randomEdgePoint(width, height, random);
		}
		return new Point(clamp(gaussian(random, exitX, COVERED_SIGMA_PX), width),
			clamp(gaussian(random, exitY, COVERED_SIGMA_PX), height));
	}

	private static Point randomEdgePoint(int width, int height, Random random)
	{
		switch (random.nextInt(4))
		{
			case 0:
				return new Point(0, random.nextInt(height));
			case 1:
				return new Point(width - 1, random.nextInt(height));
			case 2:
				return new Point(random.nextInt(width), 0);
			default:
				return new Point(random.nextInt(width), height - 1);
		}
	}

	private static int gaussian(Random random, int mean, int sigma)
	{
		return (int) Math.round(mean + random.nextGaussian() * sigma);
	}

	private static int clamp(int value, int size)
	{
		return Math.max(0, Math.min(size - 1, value));
	}

	private static int canvasWidth()
	{
		try
		{
			return Microbot.getClient() == null ? 0 : Microbot.getClient().getCanvasWidth();
		}
		catch (Exception ex)
		{
			return 0;
		}
	}

	private static int canvasHeight()
	{
		try
		{
			return Microbot.getClient() == null ? 0 : Microbot.getClient().getCanvasHeight();
		}
		catch (Exception ex)
		{
			return 0;
		}
	}

	/**
	 * Real events win: while the human owns input a synthetic emit must not move the recorded
	 * position. The abort path still emits its RELEASED, it just does not drag the position along.
	 */
	private static void recordPosition(int canvasX, int canvasY)
	{
		if (InputArbiter.isHuman())
		{
			return;
		}
		PointerState.setFromBot(canvasX, canvasY);
	}

	private static Canvas canvas()
	{
		try
		{
			return Microbot.getClient() == null ? null : Microbot.getClient().getCanvas();
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	// Jagex's MOUSE_PRESSED listener calls canvas.requestFocus(), stealing OS focus from whatever
	// the user is typing in. Non-focusable for the dispatch neuters it; mouse delivery ignores
	// focusable state. Skipped when the canvas already owns focus, where setFocusable(false) would
	// hand focus to the parent instead, which is the thing being prevented.
	private static void dispatch(Canvas canvas, AWTEvent event)
	{
		boolean canvasIsFocused = canvas.isFocusOwner();
		boolean wasFocusable = canvas.isFocusable();
		boolean shouldGuard = wasFocusable && !canvasIsFocused;
		if (shouldGuard)
		{
			canvas.setFocusable(false);
		}
		BotEventGuard.begin();
		try
		{
			canvas.dispatchEvent(event);
		}
		finally
		{
			BotEventGuard.end();
			if (shouldGuard)
			{
				canvas.setFocusable(true);
			}
		}
	}
}
