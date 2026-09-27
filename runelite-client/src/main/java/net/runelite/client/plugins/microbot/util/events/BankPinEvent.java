package net.runelite.client.plugins.microbot.util.events;

import net.runelite.client.plugins.microbot.BlockingEvent;
import net.runelite.client.plugins.microbot.BlockingEventPriority;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.BankPinHandler;

/**
 * LOCAL PATCH (not upstream): while the bank PIN screen is up, every script is paused. A PIN stored in the
 * Microbot profile is typed with human pacing; without one the player gets a notification and nothing is
 * clicked until the screen is gone. See {@link BankPinHandler}.
 */
public class BankPinEvent implements BlockingEvent {

    @Override
    public boolean validate() {
        return Microbot.isLoggedIn() && BankPinHandler.isVisible();
    }

    @Override
    public boolean execute() {
        return BankPinHandler.handleFromEvent();
    }

    @Override
    public BlockingEventPriority priority() {
        return BlockingEventPriority.HIGH;
    }
}
