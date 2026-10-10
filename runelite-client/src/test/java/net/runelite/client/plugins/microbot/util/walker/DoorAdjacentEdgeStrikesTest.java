package net.runelite.client.plugins.microbot.util.walker;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DoorAdjacentEdgeStrikesTest {
	// Lumbridge castle large doors, farm runs 02-10 to 10-10: the diagonal past the open door's frame.
	private static final WorldPoint FROM = new WorldPoint(3213, 3222, 0);
	private static final WorldPoint TO = new WorldPoint(3212, 3221, 0);

	@Test
	public void thirdHandOverOfTheSameEdgeWithinAMinuteLearnsIt() {
		DoorAdjacentEdgeStrikes strikes = new DoorAdjacentEdgeStrikes();
		long now = 1_000_000L;
		assertFalse(strikes.strike(FROM, TO, now));
		assertFalse(strikes.strike(FROM, TO, now + 6_000L));
		assertTrue(strikes.strike(FROM, TO, now + 12_000L));
		// Counting starts over afterwards.
		assertFalse(strikes.strike(FROM, TO, now + 18_000L));
	}

	@Test
	public void aDifferentEdgeStartsCountingAgain() {
		DoorAdjacentEdgeStrikes strikes = new DoorAdjacentEdgeStrikes();
		long now = 1_000_000L;
		assertFalse(strikes.strike(FROM, TO, now));
		assertFalse(strikes.strike(FROM, TO, now + 6_000L));
		assertFalse(strikes.strike(TO, FROM, now + 12_000L));
		assertFalse(strikes.strike(FROM, TO, now + 18_000L));
	}

	@Test
	public void handOversSpreadOverMoreThanAMinuteNeverLearn() {
		// A shut door the pipeline is busy opening is handed over now and then; that must stay its own.
		DoorAdjacentEdgeStrikes strikes = new DoorAdjacentEdgeStrikes();
		long now = 1_000_000L;
		assertFalse(strikes.strike(FROM, TO, now));
		assertFalse(strikes.strike(FROM, TO, now + 40_000L));
		assertFalse(strikes.strike(FROM, TO, now + 70_000L));
		assertFalse(strikes.strike(FROM, TO, now + 80_000L));
		assertTrue(strikes.strike(FROM, TO, now + 90_000L));
	}
}
