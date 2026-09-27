package net.runelite.client.plugins.microbot.humanmouse;

/**
 * Says whether a click that missed its target would be harmless at (x, y): an empty gap between
 * inventory slots, a blank bit of an interface, open ground far from anything that matters.
 *
 * <p>Only the caller knows the screen, so only the caller can say. A miss that is not harmless is
 * never clicked: the player "sees" the cursor is off and corrects before pressing, which people do
 * too. Use {@link #NEVER} (the default) where a stray click could cost something: drops, alchs,
 * withdraws, deposits, shops, prayers, combat, dialogue options (see README, "Misclicks").
 */
@FunctionalInterface
public interface SafeMiss {
    SafeMiss NEVER = (x, y) -> false;

    boolean isHarmless(int x, int y);
}
