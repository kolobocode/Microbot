package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.client.plugins.microbot.shortestpath.pathfinder.CollisionMap;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.SplitFlagMap;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * The earth-warrior room is an isolated pocket in the static map; the monkeybars are its ONLY link.
 * Without the agility_shortcuts.tsv rows the walker walked 230 tiles to the south end and stranded.
 */
public class EdgevilleEarthWarriorProbeTest {
	private static final int MIN_X = 3060, MAX_X = 3170, MIN_Y = 9880, MAX_Y = 10020;

	@Test
	public void probe() {
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		Set<Long> south = flood(map, 3121, 9964);
		Set<Long> north = flood(map, 3121, 9985);
		System.out.println("PROBE southComponent=" + south.size() + " northComponent=" + north.size()
				+ " southReachesTarget=" + south.contains(key(3121, 9985))
				+ " northReachesGateNorth(3131,9918)=" + north.contains(key(3131, 9918))
				+ " southReachesGateNorth(3131,9918)=" + south.contains(key(3131, 9918))
				+ " southReachesGateSouth(3131,9917)=" + south.contains(key(3131, 9917)));
		for (int x = 3117; x <= 3123; x++) {
			StringBuilder sb = new StringBuilder("PROBE x=" + x + " y9958..9978: ");
			for (int y = 9958; y <= 9978; y++) {
				char c = south.contains(key(x, y)) ? 'S' : north.contains(key(x, y)) ? 'N' : '#';
				sb.append(c);
			}
			System.out.println(sb);
		}
		assertFalse("pocket became walkable - monkeybars rows may be obsolete", south.contains(key(3121, 9985)));
		assertTrue(south.contains(key(3120, 9964)) && south.contains(key(3121, 9964)));
		assertTrue(north.contains(key(3120, 9969)) && north.contains(key(3121, 9969)));
		String tsv;
		try {
			tsv = new String(Util.readAllBytes(ShortestPathPlugin.class.getResourceAsStream("agility_shortcuts.tsv")), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		assertTrue("Edgeville monkeybars missing from agility_shortcuts.tsv", tsv.contains("3120 9964 0	3120 9969 0	Swing across;Monkeybars;"));
		assertTrue("Edgeville monkeybars (return) missing", tsv.contains("3120 9969 0	3120 9964 0	Swing across;Monkeybars;"));
		int nMinX = Integer.MAX_VALUE, nMaxX = 0, nMinY = Integer.MAX_VALUE, nMaxY = 0;
		for (long k : north) {
			int x = (int) (k >> 16), y = (int) (k & 0xffff);
			nMinX = Math.min(nMinX, x); nMaxX = Math.max(nMaxX, x);
			nMinY = Math.min(nMinY, y); nMaxY = Math.max(nMaxY, y);
		}
		System.out.println("PROBE northBounds x=" + nMinX + ".." + nMaxX + " y=" + nMinY + ".." + nMaxY);
	}

	private static Set<Long> flood(CollisionMap map, int sx, int sy) {
		Set<Long> seen = new HashSet<>();
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[]{sx, sy});
		seen.add(key(sx, sy));
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		while (!queue.isEmpty()) {
			int[] cur = queue.poll();
			for (int[] d : dirs) {
				int nx = cur[0] + d[0], ny = cur[1] + d[1];
				if (nx < MIN_X || nx > MAX_X || ny < MIN_Y || ny > MAX_Y) continue;
				if (seen.contains(key(nx, ny))) continue;
				if (!map.canStep(cur[0], cur[1], 0, d[0], d[1])) continue;
				seen.add(key(nx, ny));
				queue.add(new int[]{nx, ny});
			}
		}
		return seen;
	}

	private static long key(long x, long y) {
		return (x << 16) | y;
	}
}
