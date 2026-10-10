package net.runelite.client.plugins.microbot.util.walker;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static net.runelite.client.plugins.microbot.util.walker.WalkStuckGuard.Decision.GO;
import static net.runelite.client.plugins.microbot.util.walker.WalkStuckGuard.Decision.HOLD;
import static net.runelite.client.plugins.microbot.util.walker.WalkStuckGuard.Decision.IN_FLIGHT;
import static net.runelite.client.plugins.microbot.util.walker.WalkStuckGuard.Decision.STUCK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WalkStuckGuardTest {
	// Romeo & Juliet, 2026-10-03: the script clicked Juliet's stairs from outside the shut house door.
	private static final WorldPoint STAIRS = new WorldPoint(3159, 3436, 0);
	private static final WorldPoint AT_DOOR = new WorldPoint(3161, 3440, 0);

	private long now = 1_000_000L;

	private static WalkStuckGuard clicks() {
		return new WalkStuckGuard("click", 1_800L, 10_000L,
				new int[] {3, 2, 2, 2}, new long[] {4_000L, 12_000L, 30_000L}, () -> 1.0);
	}

	/** One script loop: ask, and click when allowed. Returns the decision. */
	private WalkStuckGuard.Decision loop(WalkStuckGuard guard, WorldPoint target, WorldPoint player) {
		WalkStuckGuard.Decision decision = guard.before(target, player, false, now, null);
		if (decision == GO) {
			guard.attempted(target, player, now);
		}
		return decision;
	}

	@Test
	public void clickingTheSameSpotWithoutGettingCloserBacksOffThenGivesUpOnce() {
		WalkStuckGuard guard = clicks();
		int clicks = 0;
		int stuck = 0;
		WalkStuckGuard.Decision last = null;
		// The script loops every 600 ms for ten minutes, standing at the door.
		for (int i = 0; i < 1_000; i++) {
			last = loop(guard, STAIRS, AT_DOOR);
			if (last == GO) {
				clicks++;
			} else if (last == STUCK) {
				stuck++;
			}
			now += 600;
		}
		// 3 + 2 + 2 + 2 clicks, each judged a miss, then nothing.
		assertEquals(9, clicks);
		assertEquals(1, stuck);
		assertEquals(HOLD, last);
		assertTrue(guard.isGivenUp());
	}

	@Test
	public void aClickStillUnderWayIsNotSentAgain() {
		WalkStuckGuard guard = clicks();
		WorldPoint far = new WorldPoint(3180, 3436, 0);
		assertEquals(GO, loop(guard, STAIRS, far));
		now += 600;
		assertEquals("too soon to judge", IN_FLIGHT, guard.before(STAIRS, far, false, now, null));
		now += 2_000;
		assertEquals("walking", IN_FLIGHT, guard.before(STAIRS, new WorldPoint(3175, 3436, 0), true, now, null));
	}

	@Test
	public void gettingCloserIsNeverAMiss() {
		WalkStuckGuard guard = clicks();
		for (int x = 3190; x > 3160; x--) {
			assertEquals(GO, loop(guard, STAIRS, new WorldPoint(x, 3436, 0)));
			now += 2_000;
		}
		assertFalse(guard.isGivenUp());
	}

	@Test
	public void anotherTargetStartsAfresh() {
		WalkStuckGuard guard = clicks();
		for (int i = 0; i < 4; i++) {
			loop(guard, STAIRS, AT_DOOR);
			now += 2_000;
		}
		assertEquals(HOLD, guard.before(STAIRS, AT_DOOR, false, now, null));
		assertEquals("a different target is not held", GO, loop(guard, new WorldPoint(3170, 3420, 0), AT_DOOR));
	}

	@Test
	public void givenUpTriesAgainWhenTheCharacterWasMoved() {
		WalkStuckGuard guard = giveUp(clicks(), null);
		assertEquals(HOLD, guard.before(STAIRS, AT_DOOR, false, now, null));
		assertEquals(GO, guard.before(STAIRS, new WorldPoint(3164, 3442, 0), false, now, null));
		assertFalse(guard.isGivenUp());
	}

	@Test
	public void givenUpAtADoorTriesAgainOnceTheDoorIsOpen() {
		AtomicBoolean open = new AtomicBoolean(false);
		WalkStuckGuard guard = giveUp(clicks(), open::get);
		now += 6_000;
		assertEquals(HOLD, guard.before(STAIRS, AT_DOOR, false, now, open::get));
		open.set(true);
		now += 6_000;
		assertEquals(GO, guard.before(STAIRS, AT_DOOR, false, now, open::get));
	}

	@Test
	public void givenUpWithoutASeenBlockerDoesNotLoopThroughTheStagesAgain() {
		// The probe says "clear" all along (stuck for another reason): it must not re-arm the guard.
		WalkStuckGuard guard = giveUp(clicks(), () -> true);
		for (int i = 0; i < 200; i++) {
			now += 6_000;
			assertEquals(HOLD, guard.before(STAIRS, AT_DOOR, false, now, () -> true));
		}
	}

	@Test
	public void theBlockerProbeIsAskedAtMostEveryFiveSeconds() {
		AtomicInteger asked = new AtomicInteger();
		WalkStuckGuard guard = giveUp(clicks(), () -> {
			asked.incrementAndGet();
			return false;
		});
		asked.set(0);
		for (int i = 0; i < 100; i++) {
			now += 600;
			guard.before(STAIRS, AT_DOOR, false, now, () -> {
				asked.incrementAndGet();
				return false;
			});
		}
		assertTrue("asked " + asked.get(), asked.get() >= 10 && asked.get() <= 12);
	}

	@Test
	public void wholeWalksThatFailGiveUpAfterTwoTwoOne() {
		WalkStuckGuard guard = new WalkStuckGuard("walk", 0L, 3_000L,
				new int[] {2, 2, 1}, new long[] {10_000L, 30_000L}, () -> 1.0);
		int walks = 0;
		boolean stuck = false;
		for (int i = 0; i < 500 && !stuck; i++) {
			WalkStuckGuard.Decision decision = loop(guard, STAIRS, AT_DOOR);
			walks += decision == GO ? 1 : 0;
			stuck = decision == STUCK;
			now += 1_000;
		}
		assertTrue(stuck);
		assertEquals(2 + 2 + 1, walks);
	}

	@Test
	public void arrivingForgetsTheMisses() {
		WalkStuckGuard guard = giveUp(clicks(), null);
		guard.arrived();
		assertEquals(GO, guard.before(STAIRS, AT_DOOR, false, now, null));
	}

	private WalkStuckGuard giveUp(WalkStuckGuard guard, java.util.function.BooleanSupplier probe) {
		for (int i = 0; i < 1_000; i++) {
			WalkStuckGuard.Decision decision = guard.before(STAIRS, AT_DOOR, false, now, probe);
			if (decision == GO) {
				guard.attempted(STAIRS, AT_DOOR, now);
			}
			now += 600;
			if (decision == STUCK) {
				return guard;
			}
		}
		throw new AssertionError("never gave up");
	}
}
