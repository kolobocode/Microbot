package net.runelite.client.plugins.microbot.util.bank;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.humantiming.Action;
import net.runelite.client.plugins.microbot.humantiming.HumanProfile;
import net.runelite.client.plugins.microbot.humantiming.HumanTiming;
import net.runelite.client.plugins.microbot.util.antiban.HumanTimingBridge;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * LOCAL PATCH (not upstream): paces {@link Rs2Bank} clicks with HumanTiming and varies how the bank is
 * used, so every script does not bank with the exact same steps and timing. All behaviour lives here;
 * Rs2Bank only contains one-line hooks into this class.
 * <p>
 * Pacing. Every bank click (item, tab, deposit-all button, close) gets one HumanTiming think time, from:
 * <ul>
 *   <li>the script's own HumanTiming, when the script attached it through {@link HumanTimingBridge}. A
 *       click the script paced itself ({@code timing.pace(...)} or {@code reactAfter} just before the
 *       Rs2Bank call) gets nothing extra; the clicks Rs2Bank adds on its own (tab switch, the next item of
 *       a deposit loop, close button) are paced from the script's model.</li>
 *   <li>otherwise a client-wide HumanTiming for the logged-in player, with the same per-name profile a
 *       HumanTiming plugin would use. Script loops and menu clicks keep it active ({@link #heartbeat()}),
 *       so walking between banks is not taken for a break.</li>
 * </ul>
 * The think time goes in front of the click. It is never a top-up (the max of the script's wait and
 * ours), which would erase HumanTiming's rhythm, and nothing is clamped.
 * <p>
 * Rules every hook follows:
 * <ul>
 *   <li>all delays happen BEFORE the click, so script-side timeouts (sleepUntil after a withdraw) are unaffected</li>
 *   <li>when the situation is not calm (wilderness, combat, low hp, client thread) every hook is a no-op</li>
 *   <li>any exception falls back to the original Rs2Bank behaviour</li>
 * </ul>
 * Set {@link #enabled} to false to get the stock Rs2Bank behaviour back.
 */
@Slf4j
public final class BankHumanizer {
    /** Master switch. false = Rs2Bank behaves exactly like upstream. */
    public static volatile boolean enabled = true;
    /** Occasionally withdraw a neighbouring item by mistake and put it back. */
    public static volatile boolean mistakesEnabled = true;

    private static final long MISTAKE_COOLDOWN_MS = 12 * 60_000L;
    private static final int MIN_FREE_SLOTS_FOR_MISTAKE = 2;
    /** A bank click this long after the previous one is a fresh look at the bank (REACT, not a routine step). */
    private static final long FRESH_LOOK_MS = 20_000;

    private static final Random RNG = new Random();
    /** Guards the client-wide HumanTiming, which is not thread-safe. */
    private static final Object LOCK = new Object();

    // client-wide timing for scripts that don't bring their own, one per player name
    private static String timingName;
    private static volatile HumanTiming ownTiming;

    // habits: stable per player name (same key as the HumanTiming profile)
    private static volatile double escapeBias = 0.85;
    private static volatile double mistakeChance = 0.02;
    private static volatile double stackDepositChance = 0.3;

    /** The bank was just opened, or something unexpected appeared: the next click reacts to it. */
    private static volatile boolean freshLook = false;
    private static volatile long lastClickMs = 0;
    private static volatile int lastItemId = -1;
    // the first mistake can come at any time, not only a full cooldown after client start
    private static volatile long lastMistakeMs = System.currentTimeMillis() - (long) (RNG.nextDouble() * MISTAKE_COOLDOWN_MS);
    private static final ThreadLocal<Boolean> IN_MISTAKE = ThreadLocal.withInitial(() -> false);

    private BankHumanizer() {
    }

    /**
     * True when it is safe to spend time on human-like behaviour.
     */
    static boolean calm() {
        if (!enabled) return false;
        try {
            if (Microbot.getClient().isClientThread()) return false;
            if (Rs2Pvp.isInWilderness()) return false;
            if (Rs2Player.isInCombat()) return false;
            double hp = Rs2Player.getHealthPercentage();
            return !Double.isNaN(hp) && hp >= 50;
        } catch (Exception ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pacing
    // ------------------------------------------------------------------------------------------

    /**
     * The bank interface just opened. The next click reacts to it.
     *
     * @return true if that reaction replaces the upstream fixed post-open sleep
     */
    static boolean bankOpened() {
        freshLook = true;
        lastItemId = -1;
        if (!calm()) return false;
        try {
            loadHabitsForCurrentPlayer();
        } catch (Exception ex) {
            log.debug("BankHumanizer.bankOpened: no habits yet", ex);
        }
        return true;
    }

    /**
     * Before clicking a bank or inventory item inside the bank.
     *
     * @param tabClicked a tab was clicked just before (paced itself): now find the item in it
     * @param scrolled   the bank view scrolled without a click: find the spot, then the item
     */
    static void beforeItemClick(int itemId, boolean tabClicked, boolean scrolled) {
        if (!calm()) return;
        try {
            if (tabClicked) pace(Action.REACT, null);
            else if (scrolled) pace(Action.ROUTINE, Action.REACT);
            else pace(itemId == lastItemId ? Action.SEQUENCE : Action.ROUTINE, null);
        } catch (Exception ex) {
            log.debug("BankHumanizer.beforeItemClick skipped", ex);
        } finally {
            lastItemId = itemId;
        }
    }

    /** Before clicking a bank tab. */
    static void beforeTabClick() {
        if (!calm()) return;
        try {
            pace(Action.ROUTINE, null);
        } catch (Exception ex) {
            log.debug("BankHumanizer.beforeTabClick skipped", ex);
        }
    }

    /** Before clicking a bank button (deposit inventory, deposit equipment). */
    static void beforeButtonClick() {
        if (!calm()) return;
        try {
            pace(Action.ROUTINE, null);
        } catch (Exception ex) {
            log.debug("BankHumanizer.beforeButtonClick skipped", ex);
        } finally {
            lastItemId = -1;
        }
    }

    /**
     * Pacing before closing, and the choice between Escape and the close button.
     *
     * @param escapeAvailable the "Esc closes interface" setting is on
     * @return true to close with Escape, false to click the close button
     */
    static boolean beforeClose(boolean escapeAvailable) {
        if (!calm()) return escapeAvailable;
        try {
            pace(Action.ROUTINE, null);
            return escapeAvailable && RNG.nextDouble() < escapeBias;
        } catch (Exception ex) {
            return escapeAvailable;
        } finally {
            lastItemId = -1;
        }
    }

    /**
     * Before one digit of the bank PIN ({@link BankPinHandler}). The first digit reacts to the PIN screen.
     * With the mouse the keypad shuffles after every press, so each digit is a fresh search for the button;
     * from the keyboard the digits come from memory as a quick sequence. Paced even when not calm: the PIN
     * screen only comes up at a bank, and four instant digits are the one thing never to do.
     */
    static void beforePinDigit(boolean first, boolean keyboard) {
        if (!enabled) return;
        try {
            HumanTimingBridge.Pacer script = HumanTimingBridge.forCurrentThread();
            long ms = first ? delay(script, Action.REACT) : 0;
            ms += keyboard
                    ? (first ? 0 : delay(script, Action.SEQUENCE))
                    : delay(script, Action.REACT) + delay(script, Action.SEQUENCE);
            if (ms > 0) HumanTiming.pause(ms);
        } catch (Exception ex) {
            log.debug("BankHumanizer.beforePinDigit skipped", ex);
        } finally {
            lastClickMs = System.currentTimeMillis();
            freshLook = true; // the bank opening after the PIN is a new screen to react to
        }
    }

    /**
     * One think time before a click. The first click after the bank opened, or after a long gap, reacts
     * to what is on screen instead. A click the script paced itself gets nothing extra.
     *
     * @param extra a second think time after the first (finding the item after scrolling to it), or null
     */
    private static void pace(Action kind, Action extra) {
        boolean react = freshLook || System.currentTimeMillis() - lastClickMs > FRESH_LOOK_MS;
        freshLook = false;
        try {
            HumanTimingBridge.Pacer script = HumanTimingBridge.forCurrentThread();
            if (script != null && script.consumeScriptPace()) return;
            long ms = delay(script, react ? Action.REACT : kind) + (extra == null ? 0 : delay(script, extra));
            if (ms > 0) HumanTiming.pause(ms);
        } finally {
            lastClickMs = System.currentTimeMillis();
        }
    }

    private static long delay(HumanTimingBridge.Pacer script, Action kind) {
        if (script != null) return script.delayMs(kind.name());
        synchronized (LOCK) {
            HumanTiming t = ownTiming();
            return t == null ? 0 : t.delay(kind);
        }
    }

    /**
     * Tells the client-wide timing the player is playing (a script loop ran, a menu action was sent), so
     * the time between two bank visits isn't taken for a break. Cheap; safe from any thread.
     */
    public static void heartbeat() {
        HumanTiming t = ownTiming;
        if (!enabled || t == null) return;
        try {
            if (!Microbot.isLoggedIn() || BreakHandlerScript.isBreakActive()) return;
            synchronized (LOCK) {
                t.active();
            }
        } catch (Exception ex) {
            log.debug("BankHumanizer.heartbeat skipped", ex);
        }
    }

    /** Client-wide timing for the logged-in player (created on first use, per name). Call with LOCK held. */
    private static HumanTiming ownTiming() {
        String name = playerName();
        if (name == null) return ownTiming;
        if (!name.equals(timingName)) {
            HumanProfile profile = HumanProfile.fromName(name);
            ownTiming = new HumanTiming(profile);
            timingName = name;
            loadHabits(profile);
        }
        return ownTiming;
    }

    private static void loadHabits(HumanProfile profile) {
        Random seeded = new Random(profile.seed() ^ 0x62616e6bL); // "bank"
        escapeBias = 0.70 + seeded.nextDouble() * 0.25;
        mistakeChance = 0.010 + seeded.nextDouble() * 0.020;
        stackDepositChance = 0.15 + seeded.nextDouble() * 0.35;
        log.info("BankHumanizer habits: escapeBias={} mistakeChance={} stackDepositChance={}",
                String.format("%.2f", escapeBias), String.format("%.3f", mistakeChance), String.format("%.2f", stackDepositChance));
    }

    private static String playerName() {
        Player p = Microbot.getClient().getLocalPlayer();
        String name = p == null ? null : p.getName();
        return name == null || name.isEmpty() ? null : name;
    }

    private static void loadHabitsForCurrentPlayer() {
        synchronized (LOCK) {
            ownTiming();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Variation
    // ------------------------------------------------------------------------------------------

    /**
     * With only a few item types in the inventory people often click the stacks instead of the deposit-all button.
     *
     * @return true if the inventory was emptied this way, false to fall back to the button
     */
    static boolean tryDepositByStacks() {
        if (!calm()) return false;
        try {
            loadHabitsForCurrentPlayer();
            List<Integer> ids = Rs2Inventory.items().map(Rs2ItemModel::getId).distinct().collect(Collectors.toList());
            if (ids.isEmpty() || ids.size() > 2) return false;
            if (RNG.nextDouble() >= stackDepositChance) return false;
            // the button skips locked slots, clicking a stack does not
            if (Rs2Inventory.items().anyMatch(i -> Rs2Bank.isLockedSlot(i.getSlot()))) return false;
            for (int id : ids) {
                if (!Rs2Bank.isOpen()) return false;
                Rs2Bank.depositAll(id);
            }
            return sleepUntil(Rs2Inventory::isEmpty, 3600);
        } catch (Exception ex) {
            log.debug("BankHumanizer.tryDepositByStacks skipped", ex);
            return false;
        }
    }

    /**
     * Rarely withdraws the item next to the wanted one, notices, and puts it back.
     * Runs before the real withdraw, so for the calling script the wanted item simply arrives a bit later.
     *
     * @return false if the real withdraw can't go ahead (the bank closed, or the wrong item could not be
     * put back); the caller then reports the withdraw as failed instead of clicking a closed bank
     */
    static boolean maybeMisclick(Rs2ItemModel wanted) {
        if (!mistakesEnabled || IN_MISTAKE.get() || wanted == null) return true;
        if (!calm()) return true;
        try {
            loadHabitsForCurrentPlayer();
            if (System.currentTimeMillis() - lastMistakeMs < MISTAKE_COOLDOWN_MS) return true;
            if (RNG.nextDouble() >= mistakeChance) return true;
            if (Rs2Bank.hasWithdrawAsNote()) return true;
            if (Rs2Inventory.emptySlotCount() < MIN_FREE_SLOTS_FOR_MISTAKE) return true;

            Rs2ItemModel neighbour = pickNeighbour(wanted);
            if (neighbour == null) return true;

            IN_MISTAKE.set(true);
            lastMistakeMs = System.currentTimeMillis();
            final int wrongId = neighbour.getId();
            log.info("BankHumanizer: misclick on '{}' instead of '{}'", neighbour.getName(), wanted.getName());

            if (!Rs2Bank.withdrawOne(wrongId)) return Rs2Bank.isOpen(); // nothing was clicked
            // Once the click went out, always try to undo it, even if the item shows up late.
            sleepUntil(() -> Rs2Inventory.hasItem(wrongId), 5000);
            freshLook = true; // noticing the wrong item is a reaction, not a routine step
            return putBack(wrongId) && Rs2Bank.isOpen();
        } catch (Exception ex) {
            log.warn("BankHumanizer.maybeMisclick failed", ex);
            return Rs2Bank.isOpen();
        } finally {
            IN_MISTAKE.set(false);
        }
    }

    /** @return true if the wrong item is not in the inventory (any more) */
    private static boolean putBack(int wrongId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!Rs2Inventory.hasItem(wrongId)) {
                // it may still be on its way: give a late withdraw one more chance to land before trusting this
                if (attempt > 0 || !sleepUntil(() -> Rs2Inventory.hasItem(wrongId), 1200)) break;
            }
            if (!Rs2Bank.isOpen()) break;
            Rs2Bank.depositAll(wrongId);
            sleepUntil(() -> !Rs2Inventory.hasItem(wrongId), 2400);
        }
        if (Rs2Inventory.hasItem(wrongId)) {
            // never again this session: a stray item in the inventory is worse than a recognisable pattern
            mistakesEnabled = false;
            log.warn("BankHumanizer: could not put item {} back, mistakes disabled for this session", wrongId);
            return false;
        }
        return true;
    }

    /**
     * Same row, directly left or right, same tab, and not something we already carry
     * (so "put it back" can be verified as "no longer in the inventory").
     */
    private static Rs2ItemModel pickNeighbour(Rs2ItemModel wanted) {
        final int slot = wanted.getSlot();
        final int row = slot / Rs2Bank.BANK_ITEMS_PER_ROW;
        final int tab = Rs2Bank.getItemTabForBankItem(slot);
        List<Rs2ItemModel> candidates = Rs2Bank.bankItems().stream()
                .filter(i -> Math.abs(i.getSlot() - slot) == 1)
                .filter(i -> i.getSlot() / Rs2Bank.BANK_ITEMS_PER_ROW == row)
                // taking the last one would shift every slot after it and re-order the bank on deposit
                .filter(i -> i.getQuantity() > 1)
                .filter(i -> i.getId() != wanted.getId())
                .filter(i -> !Rs2Inventory.hasItem(i.getId()))
                .filter(i -> Rs2Bank.getItemTabForBankItem(i.getSlot()) == tab)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return null;
        return candidates.get(RNG.nextInt(candidates.size()));
    }
}
