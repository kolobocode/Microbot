package net.runelite.client.plugins.microbot.util.human;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** LOCAL PATCH: thresholds as a held bell-curve draw, and the eat/drink click gate. */
public class HumanPolicyTest
{
	@Before
	public void before()
	{
		HumanPolicy.logEnabled = false;
		HumanPolicy.clearForTest();
		ConsumeGuard.clearForTest();
	}

	@After
	public void after()
	{
		HumanPolicy.clearForTest();
		ConsumeGuard.clearForTest();
		HumanPolicy.logEnabled = true;
	}

	@Test
	public void bellStaysInsideItsLimits()
	{
		Random rng = new Random(1);
		for (int i = 0; i < 20_000; i++)
		{
			double v = HumanPolicy.bell(50, 6, 30, 75, rng);
			assertTrue(v >= 30 && v <= 75);
		}
	}

	@Test
	public void limitShiftsTheWholeCurveInsteadOfPilingUpAtIt()
	{
		// Centre 50 with a hard ceiling at 52: the curve must move down, not stack draws on 52.
		Random rng = new Random(2);
		int n = 50_000, top = 0;
		double sum = 0;
		for (int i = 0; i < n; i++)
		{
			double v = HumanPolicy.bell(50, 4, 20, 52, rng);
			sum += v;
			if (v > 51.5) top++;
		}
		double mean = sum / n;
		assertEquals(52 - HumanPolicy.K * 4, mean, 0.1);
		// The last half point holds the thin tail only (a clamp would put about 30% there).
		assertTrue("top bin " + top, top < n * 0.01);
	}

	@Test
	public void tooNarrowRangeNarrowsTheCurve()
	{
		Random rng = new Random(3);
		int n = 50_000;
		double sum = 0, sq = 0;
		for (int i = 0; i < n; i++)
		{
			double v = HumanPolicy.bell(50, 6, 40, 45, rng);
			assertTrue(v >= 40 && v <= 45);
			sum += v;
			sq += v * v;
		}
		double mean = sum / n;
		double sd = Math.sqrt(sq / n - mean * mean);
		assertEquals(42.5, mean, 0.05);
		assertTrue("sd " + sd, sd > 0.8 && sd < 1.0);
	}

	@Test
	public void drawIsHeldUntilConsumed()
	{
		double first = HumanPolicy.eatPercent(50);
		for (int i = 0; i < 100; i++)
		{
			assertEquals(first, HumanPolicy.eatPercent(50), 0);
		}
		boolean changed = false;
		for (int i = 0; i < 20 && !changed; i++)
		{
			HumanPolicy.consumed("eat");
			changed = HumanPolicy.eatPercent(50) != first;
		}
		assertTrue(changed);
	}

	@Test
	public void eatPointsSpreadAroundTheScriptValue()
	{
		int n = 5_000;
		double sum = 0, min = 100, max = 0;
		for (int i = 0; i < n; i++)
		{
			double v = HumanPolicy.eatPercent(50);
			HumanPolicy.consumed("eat");
			sum += v;
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		HumanPolicy.Traits t = HumanPolicy.traits();
		double sd = Math.min(HumanPolicy.EAT_SD * t.spread, 45 / (2 * HumanPolicy.K));
		double centre = Math.max(30 + HumanPolicy.K * sd, Math.min(75 - HumanPolicy.K * sd, 50 + t.eatBias));
		assertEquals(centre, sum / n, 0.6);
		assertTrue(min >= 30 && max <= 75);
		assertTrue(max - min > 10);
	}

	@Test
	public void prayerPotionNeverDrinksOverAFullDose()
	{
		for (int i = 0; i < 5_000; i++)
		{
			int at = HumanPolicy.prayerPotionPoints(62, 99);
			HumanPolicy.consumed("pray");
			assertTrue("at " + at, at <= 62 && at >= 27);
		}
	}

	@Test
	public void recognisesEatAndDrinkClicks()
	{
		assertEquals(ConsumeGuard.Kind.FOOD, ConsumeGuard.kindOf("Eat", "<col=ff9040>Shark</col>"));
		assertEquals(ConsumeGuard.Kind.COMBO_FOOD, ConsumeGuard.kindOf("eat", "Cooked karambwan"));
		assertEquals(ConsumeGuard.Kind.FOOD, ConsumeGuard.kindOf("Drink", "Jug of wine"));
		assertEquals(ConsumeGuard.Kind.PRAYER, ConsumeGuard.kindOf("Drink", "Prayer potion(3)"));
		assertEquals(ConsumeGuard.Kind.PRAYER, ConsumeGuard.kindOf("Drink", "Super restore(4)"));
		assertEquals(ConsumeGuard.Kind.POTION, ConsumeGuard.kindOf("Drink", "Super combat potion(2)"));
		assertNull(ConsumeGuard.kindOf("Use", "Shark"));
		assertNull(ConsumeGuard.kindOf(null, "Shark"));
	}

	@Test
	public void potionsShareOneTimerAndFoodHasItsOwn()
	{
		assertEquals(ConsumeGuard.Kind.PRAYER.timer, ConsumeGuard.Kind.POTION.timer);
		assertNotEquals(ConsumeGuard.Kind.FOOD.timer, ConsumeGuard.Kind.POTION.timer);
		assertNotEquals(ConsumeGuard.Kind.FOOD.timer, ConsumeGuard.Kind.COMBO_FOOD.timer);
	}

	@Test
	public void humanLagIsRightSkewedAroundItsMedian()
	{
		Random rng = new Random(4);
		long[] v = new long[20_001];
		for (int i = 0; i < v.length; i++)
		{
			v[i] = ConsumeGuard.humanLag(rng);
			assertTrue(v[i] > 0);
		}
		java.util.Arrays.sort(v);
		assertEquals(300, v[v.length / 2], 15);
		assertTrue(v[v.length * 9 / 10] - 300 > 300 - v[v.length / 10]);
	}
}
