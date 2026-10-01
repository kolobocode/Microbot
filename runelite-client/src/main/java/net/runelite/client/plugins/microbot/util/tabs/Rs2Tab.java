package net.runelite.client.plugins.microbot.util.tabs;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
public class Rs2Tab {
    private static final int TAB_SWITCH_SCRIPT = 915;
    private static volatile InterfaceTab cachedTab = InterfaceTab.NOTHING_SELECTED;

    // Derived from InterfaceTab.varcIntIndex — single source of truth.
    public static final Map<Integer, InterfaceTab> INDEX_TO_TAB;
    static {
        Map<Integer, InterfaceTab> map = new HashMap<>();
        for (InterfaceTab tab : InterfaceTab.values()) {
            if (tab.getVarcIntIndex() < 0) continue;
            InterfaceTab existing = map.put(tab.getVarcIntIndex(), tab);
            if (existing != null) {
                throw new ExceptionInInitializerError(
                        "Duplicate varcIntIndex " + tab.getVarcIntIndex()
                                + " for " + existing + " and " + tab);
            }
        }
        INDEX_TO_TAB = Collections.unmodifiableMap(map);
    }

    public static void onVarClientIntChanged(VarClientIntChanged event) {
        if (event.getIndex() != VarClientID.TOPLEVEL_PANEL) return;
        int value = Microbot.getClient().getVarcIntValue(VarClientID.TOPLEVEL_PANEL);
        cachedTab = INDEX_TO_TAB.getOrDefault(value, InterfaceTab.NOTHING_SELECTED);
    }

    public static InterfaceTab getCurrentTab() {
        return cachedTab;
    }

    public static boolean isCurrentTab(InterfaceTab tab) {
        return getCurrentTab() == tab;
    }

    public static boolean switchTo(InterfaceTab tab) {
        if (isCurrentTab(tab)) return true;

        if (tab == InterfaceTab.NOTHING_SELECTED && Microbot.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 0)
            return false;

        // LOCAL PATCH (detection audit 23-09): switch like a player does — the bound F-key, or a
        // click on the tab stone — instead of running the tab script with no input at all.
        // The script path stays as a last resort (and for client-thread callers, who can't wait).
        if (!Microbot.getClient().isClientThread() && switchByInput(tab)) {
            return true;
        }

        int varcIntIndex = tab.getVarcIntIndex();
        if (varcIntIndex != -1) {
            Microbot.getClientThread().runOnClientThreadOptional(() -> {
                Microbot.getClient().runScript(TAB_SWITCH_SCRIPT, varcIntIndex);
                return true;
            });
        } else {
            int hotkey = tab.getHotkey();
            if (hotkey == -1) {
                log.warn("Tab {} does not have a hotkey assigned, cannot switch to it.", tab.getName());
                return false;
            }
            Rs2Keyboard.keyPress(hotkey);
        }

        return sleepUntil(() -> isCurrentTab(tab));
    }

    private static final int[] TOPLEVEL_GROUPS = {
            InterfaceID.TOPLEVEL_OSRS_STRETCH, InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL
    };

    private static boolean switchByInput(InterfaceTab tab) {
        // Players mix hotkeys and clicks; lean on the hotkey when one is bound.
        int hotkey = tab.getHotkey();
        boolean useHotkey = hotkey != -1 && hotkey != KeyEvent.VK_ESCAPE
                && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.7;
        if (useHotkey) {
            Rs2Keyboard.keyPress(hotkey);
            if (sleepUntil(() -> isCurrentTab(tab), 1200)) return true;
        }
        Rectangle stone = findTabStone(tab);
        if (stone != null) {
            Microbot.getMouse().click(stone);
            if (sleepUntil(() -> isCurrentTab(tab), 1200)) return true;
        }
        if (!useHotkey && hotkey != -1 && hotkey != KeyEvent.VK_ESCAPE) {
            Rs2Keyboard.keyPress(hotkey);
            return sleepUntil(() -> isCurrentTab(tab), 1200);
        }
        return false;
    }

    /** Bounds of the visible side-panel stone whose action is the tab's name ("Inventory", "Prayer", ...). */
    private static Rectangle findTabStone(InterfaceTab tab) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            for (int group : TOPLEVEL_GROUPS) {
                for (int child = 0; child < 120; child++) {
                    Widget w = Microbot.getClient().getWidget(group, child);
                    if (w == null || w.isHidden()) continue;
                    String[] actions = w.getActions();
                    if (actions == null) continue;
                    for (String a : actions) {
                        if (a != null && a.equalsIgnoreCase(tab.getName())) {
                            Rectangle b = w.getBounds();
                            if (b != null && b.width > 4 && b.height > 4) return b;
                        }
                    }
                }
            }
            return null;
        }).orElse(null);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToInventoryTab() {
        return switchTo(InterfaceTab.INVENTORY);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToCombatOptionsTab() {
        return switchTo(InterfaceTab.COMBAT);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToSkillsTab() {
        return switchTo(InterfaceTab.SKILLS);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToQuestTab() {
        return switchTo(InterfaceTab.QUESTS);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToEquipmentTab() {
        return switchTo(InterfaceTab.EQUIPMENT);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToPrayerTab() {
        return switchTo(InterfaceTab.PRAYER);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToMagicTab() {
        return switchTo(InterfaceTab.MAGIC);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToGroupingTab() {
        return switchTo(InterfaceTab.CHAT);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToFriendsTab() {
        return switchTo(InterfaceTab.FRIENDS);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToAccountManagementTab() {
        return switchTo(InterfaceTab.ACC_MAN);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToSettingsTab() {
        return switchTo(InterfaceTab.SETTINGS);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToEmotesTab() {
        return switchTo(InterfaceTab.EMOTES);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToMusicTab() {
        return switchTo(InterfaceTab.MUSIC);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToLogout() {
        return switchTo(InterfaceTab.LOGOUT);
    }

	public static Widget getSpellBookTab()
	{
		if (Microbot.getClient().isResized())
		{
			if (Microbot.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1)
			{
				return Rs2Widget.getWidget(InterfaceID.ToplevelPreEoc.STONE6);
			}
			return Rs2Widget.getWidget(InterfaceID.ToplevelOsrsStretch.STONE6);
		}
		return Rs2Widget.getWidget(InterfaceID.Toplevel.STONE6);
	}
}
