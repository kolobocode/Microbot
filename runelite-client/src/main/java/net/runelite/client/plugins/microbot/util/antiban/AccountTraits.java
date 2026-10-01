package net.runelite.client.plugins.microbot.util.antiban;

import net.runelite.client.plugins.microbot.Microbot;

import java.util.Random;

/**
 * Per-account behavioural traits.
 *
 * <p>The strongest signal a bot userbase emits is not that one account looks robotic — it is that a
 * thousand accounts look robotic in exactly the same way. Every install running the same script with
 * the same constants produces one shared fingerprint that clusters trivially.
 *
 * <p>This class gives each account its own stable playstyle. A trait is <b>derived</b>, not stored:
 * {@code hash(playerName) ^ installSeed ^ hash(key)} seeds a PRNG, so the same account always gets
 * the same value for the same key, across sessions and restarts, while two accounts — or the same
 * account on someone else's install — get unrelated values. Nothing is written to disk.
 *
 * <p>The install seed comes from {@link Microbot#getInstallSeed()} (persisted once in the Microbot
 * config group), which is also what {@code Rs2Walker}'s per-account stamina threshold is built on.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // this account's stable "how long do I linger after banking" window
 * int idle = AccountTraits.between("silver_post_bank_idle", 200, 900);
 *
 * // this account hand-drops somewhere between 15% and 60% of the time...
 * // ...and roll() decides per action, so trips still vary
 * if (AccountTraits.roll("silver_handdrop", 15, 60)) {
 *     Rs2Inventory.dropAll(itemId);
 * }
 * }</pre>
 *
 * <p>Prefix your keys with the bot name to avoid collisions between scripts.
 */
public final class AccountTraits {

    /** Rolls for {@link #roll} — deliberately not seeded, this is the per-action dice. */
    private static final Random LIVE = new Random();

    private static volatile String cachedName = null;
    private static volatile long cachedAccountSeed = 0L;

    private AccountTraits() {
    }

    /**
     * Stable integer in {@code [min, max]} for this account and key.
     *
     * @param key  trait identifier, prefix it with your bot name
     * @param min  inclusive lower bound
     * @param max  inclusive upper bound
     */
    public static int between(String key, int min, int max) {
        if (max <= min) return min;
        return min + rngFor(key).nextInt(max - min + 1);
    }

    /**
     * Stable double in {@code [min, max)} for this account and key.
     */
    public static double betweenDouble(String key, double min, double max) {
        if (max <= min) return min;
        return min + rngFor(key).nextDouble() * (max - min);
    }

    /**
     * Stable coin flip for this account and key.
     *
     * @param probability chance of {@code true}, in {@code [0.0, 1.0]}
     */
    public static boolean flag(String key, double probability) {
        return rngFor(key).nextDouble() < probability;
    }

    /**
     * Rolls a fresh die against this account's stable percentage for {@code key}.
     *
     * <p>The percentage is fixed per account; the outcome varies per call. That combination is what
     * produces "this account hand-drops about a third of the time" instead of either "always" or
     * "exactly every third trip".
     *
     * @param minPct inclusive lower bound of the per-account percentage
     * @param maxPct inclusive upper bound of the per-account percentage
     */
    public static boolean roll(String key, int minPct, int maxPct) {
        return LIVE.nextInt(100) < between(key, minPct, maxPct);
    }

    /**
     * This account's stable percentage for {@code key}, without rolling. Useful for logging or when
     * you want to weight something other than a boolean.
     */
    public static int percentage(String key, int minPct, int maxPct) {
        return between(key, minPct, maxPct);
    }

    /**
     * Picks one of {@code options} — stable per account, so this account always makes the same choice.
     * Returns {@code null} for an empty array.
     */
    @SafeVarargs
    public static <T> T choice(String key, T... options) {
        if (options == null || options.length == 0) return null;
        return options[rngFor(key).nextInt(options.length)];
    }

    private static Random rngFor(String key) {
        return new Random(accountSeed() ^ mix64(key == null ? "" : key));
    }

    /**
     * Seed for the logged-in account, recomputed when the player name changes. Falls back to the
     * install seed alone when no name is available (login screen), so callers still get stable —
     * if less account-specific — values rather than a moving target.
     */
    private static long accountSeed() {
        String name = null;
        try {
            var player = Microbot.getClient().getLocalPlayer();
            if (player != null) name = player.getName();
        } catch (Exception ignored) {
        }

        long installSeed = Microbot.getInstallSeed();
        if (name == null || name.isEmpty()) {
            return installSeed;
        }
        if (!name.equals(cachedName)) {
            cachedName = name;
            cachedAccountSeed = mix64(name.toLowerCase()) ^ installSeed;
        }
        return cachedAccountSeed;
    }

    /** FNV-1a, matching the idiom already used by {@code Rs2Walker}. */
    private static long mix64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
