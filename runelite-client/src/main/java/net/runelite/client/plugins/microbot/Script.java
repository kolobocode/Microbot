package net.runelite.client.plugins.microbot;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.agentserver.handler.ScriptHeartbeatRegistry;
import net.runelite.client.plugins.microbot.util.antiban.SessionFatigue;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2PathApi;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for Microbot automation scripts.
 * Provides scheduling helpers, guards against client-thread misuse, and common shutdown/reset logic.
 */
@Slf4j
public abstract class Script extends Global implements IScript {
    protected ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(10,
        new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            @Override
            public Thread newThread(@NotNull Runnable r) {
                Thread t = new Thread(r);
                t.setName(Script.this.getClass().getSimpleName() + "-" + threadNumber.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    protected ScheduledFuture<?> scheduledFuture;
    protected ScheduledFuture<?> mainScheduledFuture;

    /**
     * Indicates whether the main scheduled script loop is still active.
     */
    public boolean isRunning() {
        return mainScheduledFuture != null && !mainScheduledFuture.isDone();
    }

    @Getter
    protected static WorldPoint initialPlayerLocation;

    /**
     * Cancel scheduled tasks, clear shared state, and reset helpers.
     * Safe to call multiple times; no-ops if already shut down.
     */
    public void shutdown() {
        ScriptHeartbeatRegistry.remove(this.getClass().getName());
        lastLoopAt = 0;
        loopPeriodEma = -1;
        if (mainScheduledFuture != null && !mainScheduledFuture.isDone()) {
            mainScheduledFuture.cancel(true);
            Rs2PathApi.exit();
            if (Microbot.getClientThread().scheduledFuture != null)
                Microbot.getClientThread().scheduledFuture.cancel(true);
            initialPlayerLocation = null;
            // Backstop for a script stopped between a hold and its release. Fires inconsistently:
            // most scripts catch-and-continue without reaching here.
            Rs2Keyboard.releaseHeldKeys();
            Microbot.pauseAllScripts.set(false);
            Rs2Walker.disableTeleports = false;
            Microbot.getSpecialAttackConfigs().reset();
        }
        if (scheduledFuture != null && !scheduledFuture.isDone()) {
            scheduledFuture.cancel(true);
        }
    }

    /**
     * Default pre-loop guard invoked by script schedulers.
     * Returns {@code false} to pause a loop when a blocking event is executing, scripts are paused,
     * tutorial island is incomplete, or the current thread is interrupted.
     */
    public boolean run() {
        ScriptHeartbeatRegistry.recordHeartbeat(this.getClass().getName());
        loopJitter();

        if (Microbot.isLoggedIn() && !SessionFatigue.isActive()) {
            SessionFatigue.startSession();
        }

        if (Microbot.isLoggedIn() && !Rs2Player.hasCompletedTutorialIsland())
            return true;

        if (Rs2Player.hasCompletedTutorialIsland() && Microbot.getBlockingEventManager().shouldBlockAndProcess()) {
            // A blocking event was found & is executing
            return false;
        }
        // The arbiter keeps its own flag, so a takeover idles every script through the gate that
        // already exists, cancelling nothing.
        boolean humanOwnsInput = InputArbiter.isHuman();
        if (humanOwnsInput) {
            // A held key is not gesture-scoped, so InputLoop cannot unwind it.
            Rs2Keyboard.releaseHeldKeys();
        }
        if (Microbot.pauseAllScripts.get() || humanOwnsInput)
            return false;
        if (Thread.currentThread().isInterrupted())
            return false;

        if (Microbot.isLoggedIn()) {
            boolean hasRunEnergy = Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getClient().getEnergy()).orElse(0) > Microbot.runEnergyThreshold;
            if (Microbot.enableAutoRunOn && hasRunEnergy)
                Rs2Player.toggleRunEnergy(true);
            if (!hasRunEnergy && Microbot.useStaminaPotsIfNeeded && Rs2Player.isMoving()) {
                Rs2Inventory.useRestoreEnergyItem();
            }
            Microbot.getConfigManager().setConfiguration(MicrobotConfig.configGroup, MicrobotConfig.keyEnableAutoRunOn, Microbot.enableAutoRunOn);
            Microbot.getConfigManager().setConfiguration(MicrobotConfig.configGroup, MicrobotConfig.keyUseStaminaPotsIfNeeded, Microbot.useStaminaPotsIfNeeded);
        }
        net.runelite.client.plugins.microbot.util.bank.BankHumanizer.heartbeat(); // LOCAL PATCH: a script loop ran
        return true;
    }

    // LOCAL PATCH (detection audit 23-09): scripts schedule with a fixed delay, so every action
    // lands on the same beat. Add a small per-iteration delay scaled to the loop's own period
    // (fast loops such as the walker are left alone), plus a rare hesitation outside combat.
    // Disable with -Dmicrobot.loopJitter=false.
    private static final boolean LOOP_JITTER = !"false".equalsIgnoreCase(System.getProperty("microbot.loopJitter"));
    private long lastLoopAt;
    private double loopPeriodEma = -1;

    private void loopJitter() {
        if (!LOOP_JITTER || Microbot.getClient() == null || Microbot.getClient().isClientThread()) return;
        long now = System.currentTimeMillis();
        if (lastLoopAt > 0) {
            // clamp: a restart or one long iteration must not inflate the period estimate
            long gap = Math.min(now - lastLoopAt, loopPeriodEma < 0 ? 5000 : (long) (3 * loopPeriodEma + 600));
            loopPeriodEma = loopPeriodEma < 0 ? gap : loopPeriodEma * 0.9 + gap * 0.1;
        }
        lastLoopAt = now;
        if (loopPeriodEma < 250 || !Microbot.isLoggedIn() || Rs2Player.isInCombat()) return;

        int cap = (int) Math.max(12, Math.min(220, loopPeriodEma * 0.15));
        int jitter = Rs2Random.logNormalBounded(4, cap);
        if (loopPeriodEma >= 500
                && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.015) {
            jitter += Rs2Random.logNormalBounded(400, 2500);
        }
        Global.sleep(jitter);
    }
}
