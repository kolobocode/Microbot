package net.runelite.client.plugins.microbot.util.mouse;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;

import java.awt.Rectangle;
import java.awt.Shape;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.runelite.client.plugins.microbot.util.Global.sleep;

/**
 * LOCAL PATCH (detection audit 23-09): decides where a menu invoke may click.
 * <p>
 * Stock Microbot falls back to a click at canvas (1,1) (or a random point anywhere on the canvas)
 * whenever the caller has no usable bounds, while the menu swap executes the action on a target
 * somewhere else. This class instead resolves the real on-screen bounds of the target from the
 * menu entry, opens the right side tab or turns the camera when needed, and returns {@code null}
 * when the target cannot be made visible, so the caller skips the action rather than clicking blind.
 * <p>
 * Launch with {@code -Dmicrobot.legacyInvoke=true} to restore the old behaviour.
 */
@Slf4j
public final class InvokeGuard {

    public static volatile boolean strict = !Boolean.getBoolean("microbot.legacyInvoke");

    /** Returned when the entry should go through the stock (pre-patch) invoke path. */
    public static final Rectangle LEGACY = new Rectangle();

    private static final Map<String, Long> lastSkipLog = new ConcurrentHashMap<>();

    private InvokeGuard() {
    }

    /**
     * @return the rectangle to click for this entry, or {@code null} when no honest click exists.
     */
    public static Rectangle targetFor(NewMenuEntry entry, Rectangle callerRect) {
        if (isWidgetAction(entry)) {
            Widget w = widgetFor(entry);
            if (w != null) {
                if (isHidden(w)) {
                    // Prayer switching must stay tick-fast (eat/pray/PvP); keep the stock path there.
                    if ((entry.getParam1() >>> 16) == InterfaceID.PRAYERBOOK) return LEGACY;
                    if (!showWidget(entry)) return null;
                    Rectangle shown = widgetBounds(widgetFor(entry));
                    return shown != null ? shown : usable(callerRect);
                }
                Rectangle given = usable(callerRect);
                return given != null ? given : widgetBounds(w);
            }
            return usable(callerRect);
        }

        Rectangle given = usable(callerRect);
        if (given != null) return given;

        // Action types we can't locate on screen (SET_HEADING, world entities, RuneLite entries):
        // leave them on the stock path rather than breaking them.
        if (entry.getActor() == null && entry.getGameObject() == null
                && !isActorType(entry) && !isSceneTileType(entry)) return LEGACY;

        Rectangle resolved = worldBounds(entry);
        if (resolved != null) return resolved;
        if (Microbot.getClient().isClientThread()) return null;

        if (turnCameraTo(entry)) {
            sleep(Rs2Random.logNormalBounded(120, 320));
            return worldBounds(entry);
        }
        return null;
    }

    /** WALK entries carry their canvas point in param0/param1; it must lie inside the 3D viewport. */
    public static boolean isWalkPointValid(NewMenuEntry entry) {
        Client client = Microbot.getClient();
        int x = entry.getParam0();
        int y = entry.getParam1();
        int vx = client.getViewportXOffset();
        int vy = client.getViewportYOffset();
        return x > vx + 1 && y > vy + 1
                && x < vx + client.getViewportWidth() - 1
                && y < vy + client.getViewportHeight() - 1;
    }

    public static void logSkip(NewMenuEntry entry, String reason) {
        String key = entry.getType() + "|" + entry.getOption();
        long now = System.currentTimeMillis();
        Long last = lastSkipLog.get(key);
        if (last != null && now - last < 30_000) return;
        lastSkipLog.put(key, now);
        log.warn("[InvokeGuard] skipped {} '{}' on '{}' (p0={}, p1={}): {}",
                entry.getType(), entry.getOption(), entry.getTarget(), entry.getParam0(), entry.getParam1(), reason);
    }

    /**
     * Rejects the sentinel rectangles callers use for "no bounds": (1,1)-sized boxes, boxes that
     * start at the canvas corner and cover the canvas, and anything mostly off-canvas.
     */
    public static Rectangle usable(Rectangle r) {
        if (r == null || r.width <= 0 || r.height <= 0) return null;
        Client client = Microbot.getClient();
        int cw = client.getCanvasWidth();
        int ch = client.getCanvasHeight();
        if (r.width <= 1 && r.height <= 1) return null;
        // "no bounds" sentinels: getDefaultRectangle() and new Rectangle(1, 1, canvasW, canvasH)
        if (Math.abs(r.x) <= 1 && Math.abs(r.y) <= 1 && r.width >= cw - 2 && r.height >= ch - 2) return null;
        Rectangle clipped = r.intersection(new Rectangle(0, 0, cw, ch));
        if (clipped.width < 2 || clipped.height < 2) return null;
        return clipped;
    }

    // ---- widgets ----------------------------------------------------------------------------

    private static boolean isWidgetAction(NewMenuEntry e) {
        if (e.getType() == null) return false;
        switch (e.getType()) {
            case CC_OP:
            case CC_OP_LOW_PRIORITY:
            case WIDGET_TARGET:
            case WIDGET_TARGET_ON_WIDGET:
            case WIDGET_TYPE_1:
            case WIDGET_TYPE_4:
            case WIDGET_TYPE_5:
            case WIDGET_CONTINUE:
            case WIDGET_CLOSE:
            case WIDGET_USE_ON_ITEM:
            case WIDGET_FIRST_OPTION:
            case WIDGET_SECOND_OPTION:
            case WIDGET_THIRD_OPTION:
            case WIDGET_FOURTH_OPTION:
            case WIDGET_FIFTH_OPTION:
            case ITEM_USE_ON_ITEM:
            case ITEM_FIRST_OPTION:
            case ITEM_SECOND_OPTION:
            case ITEM_THIRD_OPTION:
            case ITEM_FOURTH_OPTION:
            case ITEM_FIFTH_OPTION:
            case ITEM_USE:
                return true;
            default:
                return false;
        }
    }

    private static Widget widgetFor(NewMenuEntry e) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget parent = Microbot.getClient().getWidget(e.getParam1());
            if (parent == null) return null;
            int child = e.getParam0();
            if (child < 0) return parent;
            Widget[] dyn = parent.getDynamicChildren();
            if (dyn != null && dyn.length > 0) {
                return child < dyn.length ? dyn[child] : null;
            }
            Widget c = parent.getChild(child);
            return c != null ? c : parent;
        }).orElse(null);
    }

    private static boolean isHidden(Widget w) {
        return Microbot.getClientThread().runOnClientThreadOptional(w::isHidden).orElse(true);
    }

    private static Rectangle widgetBounds(Widget w) {
        if (w == null) return null;
        Rectangle b = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (w.isHidden()) return null;
            Rectangle r = w.getBounds();
            // Clip to every ancestor, so an item scrolled out of a bank/shop container counts as
            // not visible instead of being clicked where it would be if the list were longer.
            for (Widget p = w.getParent(); r != null && p != null; p = p.getParent()) {
                Rectangle pb = p.getBounds();
                if (pb != null && pb.width > 0 && pb.height > 0) r = r.intersection(pb);
            }
            return r;
        }).orElse(null);
        if (b == null || b.width < 4 || b.height < 4) return null;
        return usable(b);
    }

    private static boolean showWidget(NewMenuEntry e) {
        if (Microbot.getClient().isClientThread()) return false;
        InterfaceTab tab = tabFor(e.getParam1() >>> 16);
        if (tab == null || Rs2Tab.isCurrentTab(tab)) return false;
        if (!Rs2Tab.switchTo(tab)) return false;
        sleep(Rs2Random.logNormalBounded(90, 260));
        return true;
    }

    private static InterfaceTab tabFor(int group) {
        switch (group) {
            case InterfaceID.INVENTORY: return InterfaceTab.INVENTORY;
            case InterfaceID.WORNITEMS: return InterfaceTab.EQUIPMENT;
            case InterfaceID.PRAYERBOOK: return InterfaceTab.PRAYER;
            case InterfaceID.MAGIC_SPELLBOOK: return InterfaceTab.MAGIC;
            case InterfaceID.LOGOUT:
            case InterfaceID.WORLDSWITCHER: return InterfaceTab.LOGOUT;
            case InterfaceID.COMBAT_INTERFACE: return InterfaceTab.COMBAT;
            case InterfaceID.STATS: return InterfaceTab.SKILLS;
            case InterfaceID.EMOTE: return InterfaceTab.EMOTES;
            case InterfaceID.FRIENDS: return InterfaceTab.FRIENDS;
            case InterfaceID.SETTINGS_SIDE: return InterfaceTab.SETTINGS;
            default: return null;
        }
    }

    // ---- world targets ----------------------------------------------------------------------

    private static Rectangle worldBounds(NewMenuEntry e) {
        if (e.getActor() != null) return usable(Rs2UiHelper.getActorClickbox(e.getActor()));
        if (e.getGameObject() != null) return usable(Rs2UiHelper.getObjectClickbox(e.getGameObject()));
        Actor a = actorFor(e);
        if (a != null) return usable(Rs2UiHelper.getActorClickbox(a));
        LocalPoint lp = sceneTileFor(e);
        if (lp != null) {
            Shape poly = Microbot.getClientThread().runOnClientThreadOptional(
                    () -> Perspective.getCanvasTilePoly(Microbot.getClient(), lp)).orElse(null);
            return poly != null ? usable(poly.getBounds()) : null;
        }
        return null;
    }

    private static boolean turnCameraTo(NewMenuEntry e) {
        try {
            if (e.getActor() != null) {
                Rs2Camera.turnTo(e.getActor());
                return true;
            }
            if (e.getGameObject() != null) {
                Rs2Camera.turnTo(e.getGameObject());
                return true;
            }
            Actor a = actorFor(e);
            if (a != null) {
                Rs2Camera.turnTo(a);
                return true;
            }
            LocalPoint lp = sceneTileFor(e);
            if (lp != null) {
                Rs2Camera.turnTo(lp);
                return true;
            }
        } catch (Exception ex) {
            log.debug("[InvokeGuard] camera turn failed: {}", ex.getMessage());
        }
        return false;
    }

    private static boolean isActorType(NewMenuEntry e) {
        return actorTypeOf(e) != 0;
    }

    private static boolean isSceneTileType(NewMenuEntry e) {
        if (e.getType() == null) return false;
        switch (e.getType()) {
            case GAME_OBJECT_FIRST_OPTION: case GAME_OBJECT_SECOND_OPTION: case GAME_OBJECT_THIRD_OPTION:
            case GAME_OBJECT_FOURTH_OPTION: case GAME_OBJECT_FIFTH_OPTION: case WIDGET_TARGET_ON_GAME_OBJECT:
            case ITEM_USE_ON_GAME_OBJECT: case EXAMINE_OBJECT: case GROUND_ITEM_FIRST_OPTION:
            case GROUND_ITEM_SECOND_OPTION: case GROUND_ITEM_THIRD_OPTION: case GROUND_ITEM_FOURTH_OPTION:
            case GROUND_ITEM_FIFTH_OPTION: case WIDGET_TARGET_ON_GROUND_ITEM: case ITEM_USE_ON_GROUND_ITEM:
            case EXAMINE_ITEM_GROUND:
                return true;
            default:
                return false;
        }
    }

    /** 1 = npc, 2 = player, 0 = not an actor action. */
    private static int actorTypeOf(NewMenuEntry e) {
        if (e.getType() == null) return 0;
        switch (e.getType()) {
            case NPC_FIRST_OPTION: case NPC_SECOND_OPTION: case NPC_THIRD_OPTION: case NPC_FOURTH_OPTION:
            case NPC_FIFTH_OPTION: case WIDGET_TARGET_ON_NPC: case ITEM_USE_ON_NPC: case EXAMINE_NPC:
                return 1;
            case PLAYER_FIRST_OPTION: case PLAYER_SECOND_OPTION: case PLAYER_THIRD_OPTION: case PLAYER_FOURTH_OPTION:
            case PLAYER_FIFTH_OPTION: case PLAYER_SIXTH_OPTION: case PLAYER_SEVENTH_OPTION: case PLAYER_EIGHTH_OPTION:
            case WIDGET_TARGET_ON_PLAYER: case ITEM_USE_ON_PLAYER:
                return 2;
            default:
                return 0;
        }
    }

    private static Actor actorFor(NewMenuEntry e) {
        if (e.getType() == null) return null;
        switch (e.getType()) {
            case NPC_FIRST_OPTION:
            case NPC_SECOND_OPTION:
            case NPC_THIRD_OPTION:
            case NPC_FOURTH_OPTION:
            case NPC_FIFTH_OPTION:
            case WIDGET_TARGET_ON_NPC:
            case ITEM_USE_ON_NPC:
            case EXAMINE_NPC:
                return Microbot.getClientThread().runOnClientThreadOptional(
                        () -> (Actor) worldView(e).npcs().byIndex(e.getIdentifier())).orElse(null);
            case PLAYER_FIRST_OPTION:
            case PLAYER_SECOND_OPTION:
            case PLAYER_THIRD_OPTION:
            case PLAYER_FOURTH_OPTION:
            case PLAYER_FIFTH_OPTION:
            case PLAYER_SIXTH_OPTION:
            case PLAYER_SEVENTH_OPTION:
            case PLAYER_EIGHTH_OPTION:
            case WIDGET_TARGET_ON_PLAYER:
            case ITEM_USE_ON_PLAYER:
                return Microbot.getClientThread().runOnClientThreadOptional(
                        () -> (Actor) worldView(e).players().byIndex(e.getIdentifier())).orElse(null);
            default:
                return null;
        }
    }

    private static LocalPoint sceneTileFor(NewMenuEntry e) {
        if (e.getType() == null) return null;
        switch (e.getType()) {
            case GAME_OBJECT_FIRST_OPTION:
            case GAME_OBJECT_SECOND_OPTION:
            case GAME_OBJECT_THIRD_OPTION:
            case GAME_OBJECT_FOURTH_OPTION:
            case GAME_OBJECT_FIFTH_OPTION:
            case WIDGET_TARGET_ON_GAME_OBJECT:
            case ITEM_USE_ON_GAME_OBJECT:
            case EXAMINE_OBJECT:
            case GROUND_ITEM_FIRST_OPTION:
            case GROUND_ITEM_SECOND_OPTION:
            case GROUND_ITEM_THIRD_OPTION:
            case GROUND_ITEM_FOURTH_OPTION:
            case GROUND_ITEM_FIFTH_OPTION:
            case WIDGET_TARGET_ON_GROUND_ITEM:
            case ITEM_USE_ON_GROUND_ITEM:
            case EXAMINE_ITEM_GROUND:
                return Microbot.getClientThread().runOnClientThreadOptional(
                        () -> LocalPoint.fromScene(e.getParam0(), e.getParam1(), worldView(e))).orElse(null);
            default:
                return null;
        }
    }

    private static WorldView worldView(NewMenuEntry e) {
        Client client = Microbot.getClient();
        int id = e.getWorldViewId();
        if (id != WorldView.TOPLEVEL) {
            WorldView wv = client.getWorldView(id);
            if (wv != null) return wv;
        }
        return client.getTopLevelWorldView();
    }
}
