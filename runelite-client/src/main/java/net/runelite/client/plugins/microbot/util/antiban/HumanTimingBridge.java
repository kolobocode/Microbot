package net.runelite.client.plugins.microbot.util.antiban;

import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;

/**
 * LOCAL PATCH (not upstream): lets a script that paces itself with HumanTiming hand its timing model to
 * client code that clicks on the script's behalf (see {@code BankHumanizer}), so every click comes from
 * one model and nothing is paced twice.
 * <p>
 * Plugins compile against the stock client and load in their own child-first class loader, so they can't
 * link to this class. HumanTiming's {@code MicrobotTiming} finds it by name through reflection and passes
 * only JDK types; on a stock client the lookup fails and nothing happens.
 * <p>
 * The script attaches from its own loop every tick. An attachment only applies to calls from the thread
 * that attached last, and lapses when the script stops ticking.
 */
public final class HumanTimingBridge {
    /** An attachment without a tick for this long is stale (script stopped, crashed or on a break). */
    static final long STALE_MS = 10_000;

    /** Timing handed over by a script. */
    public interface Pacer {
        /** Think time before the next input of this kind (HumanTiming {@code Action} name), ms. */
        long delayMs(String action);

        /** True, once, if the script paced the click that is about to happen itself. */
        boolean consumeScriptPace();
    }

    private static final class Attachment implements Pacer {
        final Object owner;
        final ToLongFunction<String> delay;
        final BooleanSupplier consumePaced;
        volatile Thread thread;
        volatile long tickMs;

        Attachment(Object owner, ToLongFunction<String> delay, BooleanSupplier consumePaced) {
            this.owner = owner;
            this.delay = delay;
            this.consumePaced = consumePaced;
        }

        @Override
        public long delayMs(String action) {
            return delay.applyAsLong(action);
        }

        @Override
        public boolean consumeScriptPace() {
            return consumePaced.getAsBoolean();
        }
    }

    private static volatile Attachment current;

    private HumanTimingBridge() {
    }

    /**
     * Called by the script from its loop, on the script thread, every tick.
     *
     * @param owner        identifies the script's timing instance
     * @param delay        maps an Action name (SEQUENCE, ROUTINE, REACT, DECISION) to a think time in ms
     * @param consumePaced true once if the script has just paced the next click itself
     */
    public static void attach(Object owner, ToLongFunction<String> delay, BooleanSupplier consumePaced) {
        Attachment a = current;
        if (a == null || a.owner != owner) {
            a = new Attachment(owner, delay, consumePaced);
            current = a;
        }
        a.thread = Thread.currentThread();
        a.tickMs = System.currentTimeMillis();
    }

    /** Called when the script stops. */
    public static void detach(Object owner) {
        Attachment a = current;
        if (a != null && a.owner == owner) current = null;
    }

    /** The script timing that applies to a call on the current thread, or null. */
    public static Pacer forCurrentThread() {
        Attachment a = current;
        if (a == null) return null;
        if (a.thread != Thread.currentThread()) return null;
        if (System.currentTimeMillis() - a.tickMs > STALE_MS) return null;
        return a;
    }
}
