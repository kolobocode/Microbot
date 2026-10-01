package net.runelite.client.plugins.microbot.util.bank;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.antiban.SessionFatigue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * LOCAL PATCH (not upstream): varies how {@link Rs2Bank} is used so every script does not bank with the
 * exact same steps and timing. All behaviour lives here; Rs2Bank only contains one-line hooks into this class.
 * <p>
 * Rules every hook follows:
 * - all delays happen BEFORE the click, never after, so script-side timeouts (sleepUntil after a withdraw) are unaffected
 * - when the situation is not calm (wilderness, combat, low hp, client thread) every hook is a no-op
 * - any exception falls back to the original Rs2Bank behaviour
 * <p>
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

    private static final Random RNG = new Random();

    // habits: stable per account, with a small drift per client session
    private static long profileAccountHash = -1;
    private static double tempo = 1.0;
    private static double escapeBias = 0.85;
    private static double mistakeChance = 0.02;
    private static double stackDepositChance = 0.3;

    private static volatile long lastActionMs = 0;
    private static volatile long lastMistakeMs = System.currentTimeMillis();
    private static volatile boolean inMistake = false;

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
            if (Rs2Player.getHealthPercentage() < 50) return false;
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static void loadProfile() {
        long hash = Microbot.getClient().getAccountHash();
        if (hash == -1 || hash == profileAccountHash) return;
        Random seeded = new Random(hash);
        tempo = clamp(0.85 + seeded.nextDouble() * 0.45 + RNG.nextGaussian() * 0.04, 0.8, 1.4);
        escapeBias = clamp(0.70 + seeded.nextDouble() * 0.25 + RNG.nextGaussian() * 0.03, 0.6, 0.97);
        mistakeChance = clamp(0.010 + seeded.nextDouble() * 0.020, 0.01, 0.03);
        stackDepositChance = clamp(0.15 + seeded.nextDouble() * 0.35, 0.1, 0.5);
        profileAccountHash = hash;
        log.info("BankHumanizer profile: tempo={} escapeBias={} mistakeChance={} stackDepositChance={}",
                String.format("%.2f", tempo), String.format("%.2f", escapeBias),
                String.format("%.3f", mistakeChance), String.format("%.2f", stackDepositChance));
    }

    /**
     * Pacing before every bank item click. Only tops up the gap since the previous bank click,
     * so scripts that already wait between actions do not get slower.
     *
     * @param hadToSearch the item needed a tab switch or a scroll, which costs a human extra time
     */
    static void beforeAction(boolean hadToSearch) {
        if (!calm()) return;
        try {
            loadProfile();
            long target = nextGapMs();
            if (hadToSearch) target += logNormal(650, 0.45, 300, 2200);
            target = SessionFatigue.applyTo((int) (target * tempo));
            long elapsed = System.currentTimeMillis() - lastActionMs;
            if (elapsed < target) sleep((int) (target - elapsed));
        } catch (Exception ex) {
            log.debug("BankHumanizer.beforeAction skipped", ex);
        } finally {
            lastActionMs = System.currentTimeMillis();
        }
    }

    /**
     * Time a human needs to look at the bank after it opened, before the first click.
     */
    static void afterOpen() {
        if (!calm()) return;
        try {
            loadProfile();
            sleep(SessionFatigue.applyTo((int) (logNormal(620, 0.5, 250, 2600) * tempo)));
        } catch (Exception ex) {
            log.debug("BankHumanizer.afterOpen skipped", ex);
        } finally {
            lastActionMs = System.currentTimeMillis();
        }
    }

    /**
     * Small hesitation before closing, and the choice between Escape and the close button.
     *
     * @param escapeAvailable the "Esc closes interface" setting is on
     * @return true to close with Escape, false to click the close button
     */
    static boolean closeWithEscape(boolean escapeAvailable) {
        if (!escapeAvailable) return false;
        if (!calm()) return true;
        try {
            loadProfile();
            long elapsed = System.currentTimeMillis() - lastActionMs;
            long target = (long) (logNormal(380, 0.5, 120, 1800) * tempo);
            if (elapsed < target) sleep((int) (target - elapsed));
            return RNG.nextDouble() < escapeBias;
        } catch (Exception ex) {
            return true;
        }
    }

    /**
     * With only a few item types in the inventory people often click the stacks instead of the deposit-all button.
     *
     * @return true if the inventory was emptied this way, false to fall back to the button
     */
    static boolean tryDepositByStacks() {
        if (!calm()) return false;
        try {
            loadProfile();
            List<Integer> ids = Rs2Inventory.items().map(Rs2ItemModel::getId).distinct().collect(Collectors.toList());
            if (ids.isEmpty() || ids.size() > 2) return false;
            if (RNG.nextDouble() >= stackDepositChance) return false;
            // the button skips locked slots, clicking a stack does not
            if (Rs2Inventory.items().anyMatch(i -> Rs2Bank.isLockedSlot(i.getSlot()))) return false;
            for (int id : ids) {
                Rs2Bank.depositAll(id);
            }
            return sleepUntil(Rs2Inventory::isEmpty, 2400);
        } catch (Exception ex) {
            log.debug("BankHumanizer.tryDepositByStacks skipped", ex);
            return false;
        }
    }

    /**
     * Rarely withdraws the item next to the wanted one, notices, and puts it back.
     * Runs before the real withdraw, so for the calling script the wanted item simply arrives a bit later.
     */
    static void maybeMisclick(Rs2ItemModel wanted) {
        if (!mistakesEnabled || inMistake || wanted == null) return;
        if (!calm()) return;
        try {
            loadProfile();
            if (System.currentTimeMillis() - lastMistakeMs < MISTAKE_COOLDOWN_MS) return;
            if (RNG.nextDouble() >= mistakeChance) return;
            if (Rs2Bank.hasWithdrawAsNote()) return;
            if (Rs2Inventory.emptySlotCount() < MIN_FREE_SLOTS_FOR_MISTAKE) return;

            Rs2ItemModel neighbour = pickNeighbour(wanted);
            if (neighbour == null) return;

            inMistake = true;
            lastMistakeMs = System.currentTimeMillis();
            final int wrongId = neighbour.getId();
            log.info("BankHumanizer: misclick on '{}' instead of '{}'", neighbour.getName(), wanted.getName());

            if (!Rs2Bank.withdrawOne(wrongId)) return;
            if (!sleepUntil(() -> Rs2Inventory.hasItem(wrongId), 3000)) return;

            sleep(SessionFatigue.applyTo((int) (logNormal(900, 0.4, 450, 2500) * tempo)));

            putBack(wrongId);
        } catch (Exception ex) {
            log.warn("BankHumanizer.maybeMisclick failed", ex);
        } finally {
            inMistake = false;
            lastActionMs = System.currentTimeMillis();
        }
    }

    private static void putBack(int wrongId) {
        for (int attempt = 0; attempt < 3 && Rs2Inventory.hasItem(wrongId); attempt++) {
            if (!Rs2Bank.isOpen()) break;
            Rs2Bank.depositAll(wrongId);
            sleepUntil(() -> !Rs2Inventory.hasItem(wrongId), 2400);
        }
        if (Rs2Inventory.hasItem(wrongId)) {
            // never again this session: a stray item in the inventory is worse than a recognisable pattern
            mistakesEnabled = false;
            log.warn("BankHumanizer: could not put item {} back, mistakes disabled for this session", wrongId);
        }
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

    /** Gap between two consecutive bank clicks: mostly quick, sometimes slow, rarely a real pause. */
    private static long nextGapMs() {
        double roll = RNG.nextDouble();
        if (roll < 0.007) return 3500 + RNG.nextInt(8500);
        if (roll < 0.08) return logNormal(1500, 0.35, 900, 3200);
        return logNormal(430, 0.4, 170, 1300);
    }

    private static long logNormal(double median, double sigma, long min, long max) {
        double value = median * Math.exp(RNG.nextGaussian() * sigma);
        return (long) clamp(value, min, max);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
