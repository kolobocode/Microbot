package net.runelite.client.plugins.microbot.util.walker;

import net.runelite.api.coords.WorldPoint;

import java.util.Objects;

/**
 * LOCAL PATCH: how often the same walled route edge was handed to the door pipeline because a door sits
 * NEXT to it (the double-gate-wing rule in {@code Rs2WalkerMovement.learnWalledRouteEdge}).
 * <p>
 * Handing it over is right while a door there is shut and being opened. It is wrong when the door is
 * already open and the edge is a diagonal past its frame, which the game never lets you take: the door
 * pipeline finds nothing to open, the route still crosses the edge, the click is refused again and the
 * walker waits for a door handler that will never act. Lumbridge castle's large doors, on the way to the
 * tree patch: edge (3213,3222)->(3212,3221), "await-door-handler" every 6 s for 1-3 minutes on 5 of 6
 * farm runs (02-10 to 10-10).
 * <p>
 * After {@link #MAX_STRIKES} hand-overs of the same edge within {@link #WINDOW_MS}, the door pipeline
 * has had its chance and the edge is learned as walled like any other, so the planner routes around it.
 */
final class DoorAdjacentEdgeStrikes
{
	static final int MAX_STRIKES = 3;
	static final long WINDOW_MS = 60_000L;

	private WorldPoint from;
	private WorldPoint to;
	private int strikes;
	private long firstAtMs;

	/**
	 * Records one hand-over of {@code from -> to} to the door pipeline.
	 *
	 * @return true when this edge has now been handed over too often and should be learned as walled
	 */
	synchronized boolean strike(WorldPoint from, WorldPoint to, long nowMs)
	{
		boolean sameEdge = Objects.equals(this.from, from) && Objects.equals(this.to, to);
		if (!sameEdge || nowMs - firstAtMs > WINDOW_MS)
		{
			this.from = from;
			this.to = to;
			this.strikes = 0;
			this.firstAtMs = nowMs;
		}
		strikes++;
		if (strikes >= MAX_STRIKES)
		{
			reset();
			return true;
		}
		return false;
	}

	synchronized void reset()
	{
		from = null;
		to = null;
		strikes = 0;
		firstAtMs = 0L;
	}
}
