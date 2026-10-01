package net.runelite.client.plugins.microbot.util.walker;

import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UnknownObstacleLogTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void writesHeaderOnceAndOneRowPerObstaclePerSession() throws Exception {
		File file = new File(tmp.getRoot(), "sub/walker-unknown-obstacles.tsv");
		UnknownObstacleLog.setFileForTest(file);
		try {
			WorldPoint from = new WorldPoint(3115, 3449, 0);
			WorldPoint to = new WorldPoint(3115, 3450, 0);
			for (int i = 0; i < 3; i++) {
				UnknownObstacleLog.record(UnknownObstacleLog.KIND_DOOR_REFUSED, from, from, to, 1804, "Door",
						"Open", true, new WorldPoint(3121, 9985, 0), "tab\there");
			}
			UnknownObstacleLog.record(UnknownObstacleLog.KIND_DOOR_GAVE_UP, from, from, to, 1804, "Door",
					"Open", true, null, null);

			List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
			assertEquals("header + refused (deduped) + gave-up", 3, lines.size());
			assertTrue(lines.get(0).startsWith("# time\tkind"));
			String[] cols = lines.get(1).split("\t", -1);
			assertEquals(12, cols.length);
			assertEquals("DOOR_REFUSED", cols[1]);
			assertEquals("3115 3449 0", cols[3]);
			assertEquals("3115 3450 0", cols[4]);
			assertEquals("1804", cols[5]);
			assertEquals("tab here", cols[11]);
			assertTrue(lines.get(2).contains("DOOR_GAVE_UP"));
		} finally {
			UnknownObstacleLog.setFileForTest(null);
		}
	}
}
