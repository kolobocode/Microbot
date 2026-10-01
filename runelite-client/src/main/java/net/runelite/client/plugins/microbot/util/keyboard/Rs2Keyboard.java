package net.runelite.client.plugins.microbot.util.keyboard;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.mouse.BotEventGuard;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.Map;
import java.util.concurrent.*;

import static java.awt.event.KeyEvent.CHAR_UNDEFINED;

/**
 * Utility class for simulating keyboard input.
 * <p>
 * LOCAL PATCH (detection audit 23-09): every key now produces the PRESSED / TYPED / RELEASED
 * sequence a physical keyboard does, stamped with the current time (no future-dated events),
 * with a real dwell between press and release, the correct modifier masks and key locations,
 * and OS-style auto-repeat for held non-character keys.
 */
public class Rs2Keyboard
{
	private static final String SHIFTED_SYMBOLS = "~!@#$%^&*()_+{}|:\"<>?";

	// Windows defaults: ~500 ms before repeat starts, then ~30 repeats per second.
	private static final int REPEAT_DELAY_MS = 500;
	private static final int REPEAT_INTERVAL_MS = 33;

	private static volatile int heldModifiers = 0;
	private static final Map<Integer, ScheduledFuture<?>> repeating = new ConcurrentHashMap<>();
	private static final Object keyStateLock = new Object();
	private static final ScheduledExecutorService repeater = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "Rs2Keyboard-repeat");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Gets the current game canvas.
	 *
	 * @return the game canvas
	 */
	private static Canvas getCanvas()
	{
		return Microbot.getClient().getCanvas();
	}

	/**
	 * Kept as a no-op wrapper so existing call sites still compile / read naturally.
	 * The previous implementation toggled {@code Canvas.setFocusable(true)} around
	 * dispatch; on many window managers that call nudges the OS to grant focus to the
	 * game window, stealing it from whatever app the user was actually typing in.
	 * Direct-listener dispatch (see {@link #dispatchKeyEvent}) makes the toggle
	 * unnecessary, so this wrapper just runs the action.
	 */
	private static void withFocusCanvas(Runnable action)
	{
		action.run();
	}

	/**
	 * Delivers a synthetic KeyEvent to the canvas's registered listeners directly,
	 * bypassing AWT's focus-aware dispatch pipeline. This is what eliminates the
	 * focus-steal that {@code Canvas.dispatchEvent} combined with a focusable flip
	 * used to cause.
	 */
	private static void dispatchKeyEvent(int id, int keyCode, char keyChar)
	{
		Canvas canvas = getCanvas();
		int location = id == KeyEvent.KEY_TYPED ? KeyEvent.KEY_LOCATION_UNKNOWN : locationOf(keyCode);
		KeyEvent event = new KeyEvent(canvas, id, System.currentTimeMillis(), heldModifiers, keyCode, keyChar, location);
		KeyListener[] listeners = canvas.getKeyListeners();
		BotEventGuard.begin();
		try
		{
			for (KeyListener l : listeners)
			{
				switch (id)
				{
					case KeyEvent.KEY_TYPED:
						l.keyTyped(event);
						break;
					case KeyEvent.KEY_PRESSED:
						l.keyPressed(event);
						break;
					case KeyEvent.KEY_RELEASED:
						l.keyReleased(event);
						break;
				}
			}
		}
		finally
		{
			BotEventGuard.end();
		}
	}

	private static int locationOf(int keyCode)
	{
		switch (keyCode)
		{
			case KeyEvent.VK_SHIFT:
			case KeyEvent.VK_CONTROL:
			case KeyEvent.VK_ALT:
				return KeyEvent.KEY_LOCATION_LEFT;
			default:
				return KeyEvent.KEY_LOCATION_STANDARD;
		}
	}

	private static int modifierMaskOf(int keyCode)
	{
		switch (keyCode)
		{
			case KeyEvent.VK_SHIFT: return InputEvent.SHIFT_DOWN_MASK;
			case KeyEvent.VK_CONTROL: return InputEvent.CTRL_DOWN_MASK;
			case KeyEvent.VK_ALT: return InputEvent.ALT_DOWN_MASK;
			default: return 0;
		}
	}

	private static void dwell()
	{
		Global.sleep(Rs2Random.logNormalBounded(45, 115));
	}

	/**
	 * Presses and releases one printable character the way a keyboard does:
	 * [shift down] → PRESSED → TYPED → dwell → RELEASED → [shift up].
	 */
	private static void strokeChar(char c)
	{
		int code = KeyEvent.getExtendedKeyCodeForChar(c);
		if (c == '\n') code = KeyEvent.VK_ENTER;
		if (code == KeyEvent.VK_UNDEFINED)
		{
			// no physical key for this character (e.g. IME input): only a TYPED event exists
			dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c);
			return;
		}
		boolean needsShift = (Character.isUpperCase(c) || SHIFTED_SYMBOLS.indexOf(c) >= 0)
				&& (heldModifiers & InputEvent.SHIFT_DOWN_MASK) == 0;
		if (needsShift)
		{
			pressModifier(KeyEvent.VK_SHIFT);
			Global.sleep(Rs2Random.logNormalBounded(30, 90));
		}
		dispatchKeyEvent(KeyEvent.KEY_PRESSED, code, c);
		dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c);
		dwell();
		dispatchKeyEvent(KeyEvent.KEY_RELEASED, code, c);
		if (needsShift)
		{
			Global.sleep(Rs2Random.logNormalBounded(15, 60));
			releaseModifier(KeyEvent.VK_SHIFT);
		}
	}

	private static void pressModifier(int keyCode)
	{
		heldModifiers |= modifierMaskOf(keyCode);
		dispatchKeyEvent(KeyEvent.KEY_PRESSED, keyCode, CHAR_UNDEFINED);
	}

	private static void releaseModifier(int keyCode)
	{
		heldModifiers &= ~modifierMaskOf(keyCode);
		dispatchKeyEvent(KeyEvent.KEY_RELEASED, keyCode, CHAR_UNDEFINED);
	}

	/**
	 * Types out a string character-by-character.
	 *
	 * @param word the string to type into the game
	 */
	public static void typeString(final String word)
	{
		withFocusCanvas(() -> {
			char[] chars = word.toCharArray();
			for (int i = 0; i < chars.length; i++)
			{
				strokeChar(chars[i]);
				if (i == chars.length - 1) break;
				int gap = Rs2Random.logNormalBounded(100, 200);
				// occasional hesitation, as when reading ahead or thinking mid-word
				if (ThreadLocalRandom.current().nextDouble() < 0.03)
				{
					gap += Rs2Random.logNormalBounded(250, 700);
				}
				Global.sleep(gap);
			}
		});
	}

	/**
	 * Simulates pressing a single character.
	 *
	 * @param key the character to press
	 */
	public static void keyPress(final char key)
	{
		withFocusCanvas(() -> strokeChar(key));
	}

	/**
	 * Simulates holding the Shift key.
	 */
	public static void holdShift()
	{
		keyHold(KeyEvent.VK_SHIFT);
	}

	/**
	 * Simulates releasing the Shift key.
	 */
	public static void releaseShift()
	{
		keyRelease(KeyEvent.VK_SHIFT);
	}

	/**
	 * Simulates holding down a key. Non-character keys auto-repeat KEY_PRESSED while held,
	 * like the OS does, until {@link #keyRelease(int)}.
	 *
	 * @param key the key code from {@link KeyEvent}
	 */
	public static void keyHold(int key)
	{
		withFocusCanvas(() -> {
			if (repeating.containsKey(key)) return;
			if (modifierMaskOf(key) != 0)
			{
				pressModifier(key);
			}
			else
			{
				dispatchKeyEvent(KeyEvent.KEY_PRESSED, key, CHAR_UNDEFINED);
			}
			if (toTypedChar(key) == CHAR_UNDEFINED)
			{
				int interval = REPEAT_INTERVAL_MS + ThreadLocalRandom.current().nextInt(-3, 4);
				ScheduledFuture<?> f = repeater.scheduleAtFixedRate(
						() -> {
							synchronized (keyStateLock)
							{
								if (repeating.containsKey(key)) dispatchKeyEvent(KeyEvent.KEY_PRESSED, key, CHAR_UNDEFINED);
							}
						},
						REPEAT_DELAY_MS + ThreadLocalRandom.current().nextInt(-40, 41), interval, TimeUnit.MILLISECONDS);
				repeating.put(key, f);
			}
		});
	}

	/**
	 * Simulates releasing a key.
	 *
	 * @param key the key code from {@link KeyEvent}
	 */
	public static void keyRelease(int key)
	{
		withFocusCanvas(() -> {
			synchronized (keyStateLock)
			{
				// removing under the lock guarantees no repeat PRESSED can land after this RELEASED
				ScheduledFuture<?> f = repeating.remove(key);
				if (f != null) f.cancel(false);
				if (modifierMaskOf(key) != 0)
				{
					releaseModifier(key);
				}
				else
				{
					dispatchKeyEvent(KeyEvent.KEY_RELEASED, key, CHAR_UNDEFINED);
				}
			}
		});
	}

	/**
	 * Simulates pressing and releasing a key.
	 *
	 * @param key the key code from {@link KeyEvent}
	 */
	public static void keyPress(int key)
	{
		char typed = toTypedChar(key);
		withFocusCanvas(() -> {
			if (typed == CHAR_UNDEFINED)
			{
				dispatchKeyEvent(KeyEvent.KEY_PRESSED, key, CHAR_UNDEFINED);
				dwell();
				dispatchKeyEvent(KeyEvent.KEY_RELEASED, key, CHAR_UNDEFINED);
				return;
			}
			dispatchKeyEvent(KeyEvent.KEY_PRESSED, key, typed);
			dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, typed);
			dwell();
			dispatchKeyEvent(KeyEvent.KEY_RELEASED, key, typed);
		});
	}

	/**
	 * Maps a Java {@link KeyEvent} virtual-key code to the printable character it produces
	 * when typed without modifiers. Returns {@link KeyEvent#CHAR_UNDEFINED} for non-printable keys.
	 *
	 * Needed because OSRS dialog option widgets react to {@code KEY_TYPED} char events,
	 * not the raw {@code KEY_PRESSED}/{@code KEY_RELEASED} pair that {@code keyHold}/{@code keyRelease} emit.
	 */
	static char toTypedChar(int vk)
	{
		if (vk >= KeyEvent.VK_0 && vk <= KeyEvent.VK_9) return (char) ('0' + (vk - KeyEvent.VK_0));
		if (vk >= KeyEvent.VK_A && vk <= KeyEvent.VK_Z) return (char) ('a' + (vk - KeyEvent.VK_A));
		if (vk == KeyEvent.VK_SPACE) return ' ';
		if (vk == KeyEvent.VK_ENTER) return '\n';
		if (vk == KeyEvent.VK_TAB) return '\t';
		if (vk == KeyEvent.VK_BACK_SPACE) return '\b';
		if (vk == KeyEvent.VK_ESCAPE) return (char) 27;
		return CHAR_UNDEFINED;
	}

	/**
	 * Simulates pressing the Enter key.
	 */
	public static void enter()
	{
		keyPress(KeyEvent.VK_ENTER);

		// The extra TYPED '\n' was a workaround for Enter "sticking" and auto-logging Jagex
		// accounts on the login screen. It is an event a real keyboard never sends, so only
		// keep it where the original problem lived.
		resetEnter();
	}

	/**
	 * Sends a KEY_TYPED event for the Enter key to ensure it is released.
	 */
	public static void resetEnter() {
		if (Microbot.isLoggedIn()) return; // a real keyboard never sends a lone TYPED newline
		withFocusCanvas(() -> dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\n'));
	}
}
