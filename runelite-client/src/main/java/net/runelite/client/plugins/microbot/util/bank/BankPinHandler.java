package net.runelite.client.plugins.microbot.util.bank;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigProfile;
import net.runelite.client.plugins.bank.BankPlugin;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.security.Encryption;
import net.runelite.client.plugins.microbot.util.security.LoginManager;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * LOCAL PATCH (not upstream): the bank PIN screen, the same way for every script.
 * <p>
 * Upstream typed a stored PIN four clicks in a row with no pause, and without a stored PIN
 * {@link Rs2Bank#isOpen()} simply stayed false: scripts then clicked the bank booth again and again
 * behind a PIN screen nobody filled in.
 * <ul>
 *   <li><b>PIN in the active Microbot profile</b> (profile panel, "Bank PIN"): typed like a person, a
 *       look at the screen first, then one think time per digit on the shuffled keypad (see
 *       {@link BankHumanizer#beforePinDigit}).</li>
 *   <li><b>No PIN, or the stored PIN was refused</b>: {@link net.runelite.client.plugins.microbot.util.events.BankPinEvent}
 *       pauses every script, you get one notification, and nothing is clicked until the screen is gone
 *       (you typed the PIN, closed the screen, or the game logged out).</li>
 *   <li>A PIN that didn't open anything is never typed again (wrong PINs lock the bank) until the
 *       profile PIN changes.</li>
 * </ul>
 */
@Slf4j
public final class BankPinHandler {
    private static final int PIN_GROUP = 213;
    private static final int DIGIT_HINT_CHILD = 10;
    private static final String[] DIGIT_HINTS = {"FIRST digit", "SECOND digit", "THIRD digit", "FOURTH digit"};

    /** One typist at a time: a script's isOpen() and the blocking event can both see the screen. */
    private static final ReentrantLock TYPING = new ReentrantLock();
    /** hashCode of a PIN that was typed and didn't open anything, 0 = none. */
    private static volatile int refusedPinHash = 0;
    private static volatile boolean userNotified = false;

    private BankPinHandler() {
    }

    public static boolean isVisible() {
        return Rs2Widget.isWidgetVisible(ComponentID.BANK_PIN_CONTAINER);
    }

    /**
     * For {@link Rs2Bank#isOpen()}: types a stored PIN, but never waits for the user.
     *
     * @return true when no PIN screen is up (any more)
     */
    static boolean handleFromScript() {
        if (!isVisible()) {
            userNotified = false;
            return true;
        }
        String pin = storedPin();
        if (pin == null || pin.hashCode() == refusedPinHash) return false; // BankPinEvent waits for the user
        return enter(pin);
    }

    /**
     * For the blocking event: types a stored PIN, or waits (scripts paused) for the user to deal with the
     * screen.
     *
     * @return true when the PIN screen is gone
     */
    public static boolean handleFromEvent() {
        if (!isVisible()) {
            userNotified = false;
            return true;
        }
        if (TYPING.isLocked()) {
            // a script is typing it right now
            sleepUntil(() -> !TYPING.isLocked(), 15_000);
            return !isVisible();
        }
        String pin = storedPin();
        if (pin != null && pin.hashCode() != refusedPinHash) {
            if (enter(pin)) return true;
            if (!isVisible()) return true;
            if (pin.hashCode() != refusedPinHash) return false; // not refused, just interrupted: try again next round
        }
        return waitForUser(pin == null);
    }

    /**
     * Types the PIN with human pacing, then checks that the bank (or GE / collection box) opened.
     *
     * @return true when the PIN screen is gone and what it guarded opened
     */
    static boolean enter(String pin) {
        if (!isVisible()) return true;
        if (Microbot.getClient().isClientThread()) return false; // can't wait on the client thread
        if (pin == null || !pin.matches("\\d{4}")) {
            log.warn("Bank PIN: the PIN given is not 4 digits, not typing it");
            if (pin != null) refusedPinHash = pin.hashCode();
            return false;
        }
        if (!TYPING.tryLock()) return false;
        try {
            if (!isVisible()) return true;
            boolean keyboard = keyboardPinEnabled();
            Microbot.status = "Entering bank PIN";
            for (int i = 0; i < DIGIT_HINTS.length; i++) {
                final String hint = DIGIT_HINTS[i];
                if (!sleepUntil(() -> !isVisible() || Rs2Widget.hasWidgetText(hint, PIN_GROUP, DIGIT_HINT_CHILD, false), 3000)) {
                    // the screen isn't asking what we expect: stop, don't guess on a PIN screen
                    log.warn("Bank PIN: '{}' didn't show, stopped typing", hint);
                    refusedPinHash = pin.hashCode();
                    return false;
                }
                if (!isVisible()) return true; // closed by the user or the game
                BankHumanizer.beforePinDigit(i == 0, keyboard);
                if (!isVisible()) return true;
                String digit = String.valueOf(pin.charAt(i));
                if (keyboard) {
                    Rs2Keyboard.typeString(digit);
                } else if (!clickDigit(digit)) {
                    log.warn("Bank PIN: no keypad button for a digit, stopped typing");
                    refusedPinHash = pin.hashCode();
                    return false;
                }
            }
            // The game checks the PIN after the fourth digit. A wrong one doesn't open the bank.
            boolean accepted = sleepUntil(() -> !isVisible() && guardedInterfaceOpen(), 6000);
            if (!accepted) {
                refusedPinHash = pin.hashCode();
                log.warn("Bank PIN: typed, but nothing opened. Not typing it again until the profile PIN changes");
                Microbot.getNotifier().notify("Bank PIN was not accepted. Check the PIN in your Microbot profile.");
            }
            return accepted;
        } finally {
            TYPING.unlock();
        }
    }

    /** No usable PIN: pause and let the player handle it. Called on the blocking-event thread only. */
    private static boolean waitForUser(boolean noPinStored) {
        Microbot.status = "Bank PIN: waiting for you";
        if (!userNotified) {
            userNotified = true;
            String why = noPinStored ? "no bank PIN in the Microbot profile" : "the stored bank PIN was refused";
            log.info("Bank PIN screen: {}, scripts paused until it's gone", why);
            Microbot.getNotifier().notify("Bank PIN needed (" + why + "). Scripts wait until you enter it.");
        }
        // The event is re-queued while this returns false, so scripts stay paused between rounds.
        sleepUntil(() -> !isVisible() || !Microbot.isLoggedIn(), 60_000);
        if (!isVisible()) {
            userNotified = false;
            return true;
        }
        return false;
    }

    private static boolean clickDigit(String digit) {
        Widget button = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget root = Rs2Widget.getWidget(PIN_GROUP, 0);
            Widget w = root == null ? null : Rs2Widget.findWidget(digit, List.of(root), true);
            return w == null || w.isHidden() ? null : w;
        }).orElse(null);
        if (button == null) return false;
        return Rs2Widget.clickWidget(button); // click off the client thread, like every other bank click
    }

    private static boolean guardedInterfaceOpen() {
        return Rs2Widget.isWidgetVisible(12, 1)                          // bank
                || Rs2Widget.isWidgetVisible(InterfaceID.GE_OFFERS, 1)  // Grand Exchange
                || Rs2Widget.isWidgetVisible(InterfaceID.GeCollect.UNIVERSE); // collection box
    }

    /** The decrypted 4-digit PIN of the active profile, or null when there is none. */
    private static String storedPin() {
        try {
            ConfigProfile profile = LoginManager.getActiveProfile();
            if (profile == null) return null;
            String encrypted = profile.getBankPin();
            if (encrypted == null || encrypted.isBlank() || encrypted.equalsIgnoreCase("**bankpin**")) return null;
            return Encryption.decrypt(encrypted);
        } catch (Exception ex) {
            log.warn("Bank PIN: couldn't read the profile PIN", ex);
            return null;
        }
    }

    private static boolean keyboardPinEnabled() {
        try {
            return Microbot.isPluginEnabled(BankPlugin.class)
                    && "true".equalsIgnoreCase(Microbot.getConfigManager().getConfiguration("bank", "bankPinKeyboard"));
        } catch (Exception ex) {
            return false;
        }
    }
}
