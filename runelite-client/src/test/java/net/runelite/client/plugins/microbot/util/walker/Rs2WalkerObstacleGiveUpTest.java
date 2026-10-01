package net.runelite.client.plugins.microbot.util.walker;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-logic pins for the runtime obstacle give-up net (locked doors / failing transports). */
public class Rs2WalkerObstacleGiveUpTest {

	@Test
	public void givesUpOnThirdRefusalInsideWindow() {
		Map<String, long[]> store = new HashMap<>();
		assertFalse(Rs2Walker.registerObstacleRefusal(store, "edge", 1_000L, 3, 180_000L));
		assertFalse(Rs2Walker.registerObstacleRefusal(store, "edge", 5_000L, 3, 180_000L));
		assertTrue(Rs2Walker.registerObstacleRefusal(store, "edge", 9_000L, 3, 180_000L));
		// The counter resets after giving up, so an expired session block starts from scratch.
		assertFalse(Rs2Walker.registerObstacleRefusal(store, "edge", 10_000L, 3, 180_000L));
	}

	@Test
	public void staleRefusalsDoNotAccumulate() {
		Map<String, long[]> store = new HashMap<>();
		assertFalse(Rs2Walker.registerObstacleRefusal(store, "edge", 0L, 3, 180_000L));
		assertFalse(Rs2Walker.registerObstacleRefusal(store, "edge", 1_000L, 3, 180_000L));
		// Third refusal arrives after the window: counts as the first of a new window.
		assertFalse(Rs2Walker.registerObstacleRefusal(store, "edge", 500_000L, 3, 180_000L));
	}

	@Test
	public void refusalsAreCountedPerEdge() {
		Map<String, long[]> store = new HashMap<>();
		assertFalse(Rs2Walker.registerObstacleRefusal(store, "a", 0L, 2, 180_000L));
		assertFalse(Rs2Walker.registerObstacleRefusal(store, "b", 0L, 2, 180_000L));
		assertTrue(Rs2Walker.registerObstacleRefusal(store, "a", 1L, 2, 180_000L));
	}

	@Test
	public void standingStillAtAnAdjacentDoorIsNotProgress() {
		WorldPoint origin = new WorldPoint(3115, 3449, 0);
		WorldPoint dest = new WorldPoint(3115, 3450, 0);
		assertFalse(Rs2Walker.transportInteractionMadeProgress(origin, origin, dest));
		assertTrue(Rs2Walker.transportInteractionMadeProgress(origin, dest, dest));
	}

	@Test
	public void approachingOrPlaneChangeIsProgress() {
		WorldPoint start = new WorldPoint(3100, 3450, 0);
		WorldPoint dest = new WorldPoint(3115, 9852, 0);
		// Server walked us towards a far obstacle: not a refusal.
		assertTrue(Rs2Walker.transportInteractionMadeProgress(start, new WorldPoint(3108, 3450, 0), dest));
		assertTrue(Rs2Walker.transportInteractionMadeProgress(start, new WorldPoint(3100, 3450, 1), dest));
		// Unknown positions never count as a refusal.
		assertTrue(Rs2Walker.transportInteractionMadeProgress(null, start, dest));
	}
}
