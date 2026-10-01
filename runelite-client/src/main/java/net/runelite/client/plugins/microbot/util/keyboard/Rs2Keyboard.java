package net.runelite.client.plugins.microbot.util.keyboard;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.mouse.BotEventGuard;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static java.awt.event.KeyEvent.CHAR_UNDEFINED;

/**
 * Utility class for simulating keyboard input.
 * <p>
 * LOCAL PATCH (detection audit 23-09): every key produces the PRESSED / TYPED / RELEASED sequence a
 * physical keyboard does, stamped with the current time (no future-dated events), with a real dwell
 * between press and release, the correct modifier masks and key locations, and OS-style auto-repeat
 * for held non-character keys.
 * <p>
 * Every emission is gated on {@link InputArbiter}: while the human owns input nothing but a RELEASED
 * goes out, and a suppressed press is never followed by its release.
 */
public class Rs2Keyboard
{
	private static final String SHIFTED_SYMBOLS = "~!@#$%^&*()_+{}|:\"<>?";

	// Windows defaults: ~500 ms before repeat starts, then ~30 repeats per second.
	private static final int REPEAT_DELAY_MS = 500;
	private static final int REPEAT_INTERVAL_MS = 33;

	/** Keys the bot currently holds down. See {@link #releaseHeldKeys()}. */
	private static final Set<Integer> HELD_KEYS = ConcurrentHashMap.newKeySet();

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
	 * Delivers a synthetic KeyEvent to the canvas's registered listeners directly,
	 * bypassing AWT's focus-aware dispatch pipeline. This is what eliminates the
	 * focus-steal that {@code Canvas.dispatchEvent} combined with a focusable flip
	 * used to cause.
	 *
	 * @param id       the KeyEvent type (e.g. KEY_TYPED, KEY_PRESSED, etc.)
	 * @param keyCode  the key code from {@link KeyEvent}
	 * @param keyChar  the character to type, if applicable
	 * @return false when the event was suppressed because the human owns input
	 */
	private static boolean dispatchKeyEvent(int id, int keyCode, char keyChar)
	{
		// Keyboard emission never goes through InputLoop, so this is its only checkpoint. Without
		// it a takeover mid-typeString sprays the rest of the string into whatever the human just
		// took over. RELEASED is exempt: releaseHeldKeys runs while the human owns input, and
		// suppressing it would strand a key down.
		if (id != KeyEvent.KEY_RELEASED && InputArbiter.isHuman())
		{
			return false;
		}
		Canvas canvas = getCanvas();
		int location = id == KeyEvent.KEY_TYPED ? KeyEvent.KEY_LOCATION_UNKNOWN : locationOf(keyCode);
		KeyEvent event = new KeyEvent(canvas, id, System.currentTimeMillis(), heldModifiers, keyCode, keyChar, location);
		KeyListener[] listeners = canvas.getKeyListeners();
		// The arbiter's observe-only KeyListener is one of these, so without the guard the bot
		// reads its own keystrokes as a takeover.
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
		return true;
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
	 *
	 * @return false when the stroke was suppressed before its press went out
	 */
	private static boolean strokeChar(char c)
	{
		int code = KeyEvent.getExtendedKeyCodeForChar(c);
		if (c == '\n') code = KeyEvent.VK_ENTER;
		if (code == KeyEvent.VK_UNDEFINED)
		{
			// no physical key for this character (e.g. IME input): only a TYPED event exists
			return dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c);
		}
		boolean needsShift = (Character.isUpperCase(c) || SHIFTED_SYMBOLS.indexOf(c) >= 0)
				&& (heldModifiers & InputEvent.SHIFT_DOWN_MASK) == 0;
		if (needsShift)
		{
			if (!pressModifier(KeyEvent.VK_SHIFT))
			{
				return false;
			}
			Global.sleep(Rs2Random.logNormalBounded(30, 90));
		}
		if (!dispatchKeyEvent(KeyEvent.KEY_PRESSED, code, c))
		{
			// A suppressed press must not be followed by a release; shift did go down, so undo that.
			if (needsShift) releaseModifier(KeyEvent.VK_SHIFT);
			return false;
		}
		dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c);
		dwell();
		// Unconditional: the press went out, so the release owes the client its pair even if the
		// human took over in between.
		dispatchKeyEvent(KeyEvent.KEY_RELEASED, code, c);
		if (needsShift)
		{
			Global.sleep(Rs2Random.logNormalBounded(15, 60));
			releaseModifier(KeyEvent.VK_SHIFT);
		}
		return true;
	}

	/** @return false when the press was suppressed (the mask is then left untouched) */
	private static boolean pressModifier(int keyCode)
	{
		int mask = modifierMaskOf(keyCode);
		// A real SHIFT PRESSED already carries the shift mask.
		heldModifiers |= mask;
		if (!dispatchKeyEvent(KeyEvent.KEY_PRESSED, keyCode, CHAR_UNDEFINED))
		{
			heldModifiers &= ~mask;
			return false;
		}
		return true;
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
		char[] chars = word.toCharArray();
		for (int i = 0; i < chars.length; i++)
		{
			// Stop at the first suppressed character rather than spinning through the rest.
			if (!strokeChar(chars[i]))
			{
				return;
			}
			if (i == chars.length - 1) break;
			int gap = Rs2Random.logNormalBounded(100, 200);
			// occasional hesitation, as when reading ahead or thinking mid-word
			if (ThreadLocalRandom.current().nextDouble() < 0.03)
			{
				gap += Rs2Random.logNormalBounded(250, 700);
			}
			Global.sleep(gap);
		}
	}

	/**
	 * Simulates pressing a single character.
	 *
	 * @param key the character to press
	 */
	public static void keyPress(final char key)
	{
		strokeChar(key);
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
	 * <p>Not locked against {@link #releaseHeldKeys()}. A takeover landing between the dispatch and
	 * the add leaves the key down until the next {@code Script.run} tick releases it, at most 600ms.
	 * A lock here would have to be held across the dispatch, which runs the client's own key
	 * handler, and stalling every script thread behind that is the worse failure.
	 *
	 * @param key the key code from {@link KeyEvent}
	 */
	public static void keyHold(int key)
	{
		if (HELD_KEYS.contains(key)) return; // a keyboard cannot press a key that is already down
		boolean sent = modifierMaskOf(key) != 0
				? pressModifier(key)
				: dispatchKeyEvent(KeyEvent.KEY_PRESSED, key, CHAR_UNDEFINED);
		// Only if the press went out, or releaseHeldKeys would release a key never held.
		if (!sent)
		{
			return;
		}
		HELD_KEYS.add(key);
		if (toTypedChar(key) == CHAR_UNDEFINED)
		{
			int interval = REPEAT_INTERVAL_MS + ThreadLocalRandom.current().nextInt(-3, 4);
			ScheduledFuture<?> f = repeater.scheduleAtFixedRate(
					() -> {
						synchronized (keyStateLock)
						{
							// dispatchKeyEvent itself stays silent during a takeover
							if (repeating.containsKey(key) && HELD_KEYS.contains(key))
							{
								dispatchKeyEvent(KeyEvent.KEY_PRESSED, key, CHAR_UNDEFINED);
							}
						}
					},
					REPEAT_DELAY_MS + ThreadLocalRandom.current().nextInt(-40, 41), interval, TimeUnit.MILLISECONDS);
			repeating.put(key, f);
		}
	}

	/**
	 * Simulates releasing a key.
	 *
	 * @param key the key code from {@link KeyEvent}
	 */
	public static void keyRelease(int key)
	{
		synchronized (keyStateLock)
		{
			// removing under the lock guarantees no repeat PRESSED can land after this RELEASED
			ScheduledFuture<?> f = repeating.remove(key);
			if (f != null) f.cancel(false);
			// Only for a key this class saw go down. A takeover suppresses the press, and releasing
			// anyway sends a RELEASED with no PRESSED before it, which no keyboard produces.
			if (!HELD_KEYS.remove(key))
			{
				return;
			}
			if (modifierMaskOf(key) != 0)
			{
				releaseModifier(key);
			}
			else
			{
				dispatchKeyEvent(KeyEvent.KEY_RELEASED, key, CHAR_UNDEFINED);
			}
		}
	}

	/**
	 * Releases every key the bot still holds. A hold spans arbitrary script code rather than one
	 * gesture, so InputLoop cannot unwind it; {@code Script.run} calls this on a takeover instead.
	 *
	 * <p>Idempotent and safe from several script threads at once.
	 */
	public static void releaseHeldKeys()
	{
		for (Integer key : new ArrayList<>(HELD_KEYS))
		{
			keyRelease(key);
		}
	}

	static boolean isKeyHeld(int key)
	{
		return HELD_KEYS.contains(key);
	}

	/**
	 * Simulates pressing and releasing a key.
	 *
	 * @param key the key code from {@link KeyEvent}
	 */
	public static void keyPress(int key)
	{
		char typed = toTypedChar(key);
		if (typed == CHAR_UNDEFINED)
		{
			// A suppressed press must not be followed by a release.
			if (!dispatchKeyEvent(KeyEvent.KEY_PRESSED, key, CHAR_UNDEFINED))
			{
				return;
			}
			dwell();
			dispatchKeyEvent(KeyEvent.KEY_RELEASED, key, CHAR_UNDEFINED);
			return;
		}

		if (!dispatchKeyEvent(KeyEvent.KEY_PRESSED, key, typed))
		{
			return;
		}
		dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, typed);
		dwell();
		// Unconditional: the press went out, so the release owes the client its pair even if the
		// human took over in between.
		dispatchKeyEvent(KeyEvent.KEY_RELEASED, key, typed);
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
		dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\n');
	}
}
