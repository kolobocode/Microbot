package net.runelite.client.plugins.microbot.util.human;

import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.input.PointerState;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.mouse.VirtualMouse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** LOCAL PATCH: the client-wide HumanTiming / HumanMouse layer. */
public class HumanInputTest
{
	private Client client;
	private Object previousClient;
	private final List<MouseEvent> presses = new ArrayList<>();

	@Before
	public void before() throws Exception
	{
		Canvas canvas = new Canvas();
		canvas.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				presses.add(e);
			}
		});
		client = mock(Client.class);
		when(client.getCanvas()).thenReturn(canvas);
		when(client.isClientThread()).thenReturn(false);
		when(client.getCanvasWidth()).thenReturn(765);
		when(client.getCanvasHeight()).thenReturn(503);
		Field field = Microbot.class.getDeclaredField("client");
		field.setAccessible(true);
		previousClient = field.get(null);
		field.set(null, client);
		PointerState.reset();
		InputArbiter.resetForTest();
	}

	@After
	public void after() throws Exception
	{
		Field field = Microbot.class.getDeclaredField("client");
		field.setAccessible(true);
		field.set(null, previousClient);
		PointerState.reset();
		InputArbiter.resetForTest();
	}

	@Test
	public void onByDefault()
	{
		assertTrue(HumanInput.ENABLED);
	}

	@Test
	public void slotsOfOneContainerAreOneKindOfClick()
	{
		NewMenuEntry slot3 = widgetEntry(3);
		NewMenuEntry slot4 = widgetEntry(4);
		assertEquals("dropping slot after slot is a run (SEQUENCE)",
			HumanInput.clickKey(slot3, null), HumanInput.clickKey(slot4, null));
		assertNotEquals("but they keep their own habitual spot",
			HumanInput.habitKey(slot3), HumanInput.habitKey(slot4));
	}

	@Test
	public void walkClicksAreOneKind()
	{
		assertEquals("walk", HumanInput.clickKey(walkEntry(100, 100), null));
	}

	@Test
	public void placeholderRectanglesAreNeverATarget()
	{
		assertTrue(!HumanInput.usable(new Rectangle(1, 1)));
		assertTrue(!HumanInput.usable(new Rectangle(0, 0, 0, 0)));
		assertTrue(!HumanInput.usable(null));
		assertTrue("off the canvas", !HumanInput.usable(new Rectangle(2000, 2000, 30, 30)));
		assertTrue(HumanInput.usable(new Rectangle(560, 210, 36, 32)));
	}

	@Test
	public void anEntryWithNothingToAimAtHasNoShape()
	{
		NewMenuEntry blind = new NewMenuEntry().type(MenuAction.CC_OP).param0(-1).param1(12345).identifier(1).option("Go");
		assertNull(HumanInput.shapeFor(blind, new Rectangle(1, 1), new Point(1, 1)));
	}

	@Test
	public void aWalkAimsAtTheTileAroundItsPoint()
	{
		Supplier<Shape> shape = HumanInput.shapeFor(walkEntry(300, 200), new Rectangle(1, 1), null);
		Rectangle r = shape.get().getBounds();
		assertTrue(r.contains(300, 200));
		assertTrue(r.width <= 12 && r.height <= 8);
	}

	@Test
	public void aClickWithoutATargetPressesWhereTheCursorIsNeverAtAFixedPixel()
	{
		PointerState.setFromBot(300, 200);
		NewMenuEntry blind = new NewMenuEntry().type(MenuAction.CC_OP).param0(-1).param1(12345).identifier(1).option("Go");

		new VirtualMouse().click(new Rectangle(1, 1), false, blind);

		assertEquals(1, presses.size());
		MouseEvent press = presses.get(0);
		assertTrue("pressed at " + press.getX() + "," + press.getY(),
			Math.abs(press.getX() - 300) <= 1 && Math.abs(press.getY() - 200) <= 1);
	}

	@Test
	public void aClickInABoxLandsInsideItAndSpreads()
	{
		Rectangle slot = new Rectangle(560, 210, 36, 32);
		int distinct = 0;
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (int i = 0; i < 8; i++)
		{
			PointerState.setFromBot(100 + 30 * i, 100);
			presses.clear();
			new VirtualMouse().click(slot);
			assertEquals(1, presses.size());
			MouseEvent press = presses.get(0);
			assertTrue("pressed at " + press.getX() + "," + press.getY(), slot.contains(press.getX(), press.getY()));
			if (seen.add(((long) press.getX() << 32) | press.getY())) distinct++;
		}
		assertTrue("the same point every time is a bot signature", distinct >= 4);
	}

	@Test
	public void convexHullOfASquare()
	{
		List<double[]> pts = new ArrayList<>();
		pts.add(new double[]{0, 0});
		pts.add(new double[]{10, 0});
		pts.add(new double[]{5, 5});
		pts.add(new double[]{10, 10});
		pts.add(new double[]{0, 10});
		double[][] hull = HumanInput.convexHull(pts);
		assertEquals("the inner point is dropped", 4, hull[0].length);
	}

	private static NewMenuEntry widgetEntry(int slot)
	{
		return new NewMenuEntry().type(MenuAction.CC_OP).param0(slot).param1(9764864).identifier(7).itemId(1511).option("Drop");
	}

	private static NewMenuEntry walkEntry(int x, int y)
	{
		return new NewMenuEntry().type(MenuAction.WALK).param0(x).param1(y).identifier(0).itemId(-1).option("Walk here");
	}
}
