package net.runelite.client.plugins.microbot.util.walker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.RuneLite;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knowledge-base feed for the walker's data files. Every obstacle the walker could not pass (a door
 * that refused, a transport that got nowhere, a target proven unreachable) is appended as one row to
 * {@code ~/.runelite/microbot/walker-unknown-obstacles.tsv}, with everything needed to turn it into
 * a transports.tsv / agility_shortcuts.tsv / restrictions.tsv row later: tiles, object id + name,
 * the action tried, whether the catalog already knew the edge, and the game's own chat explanation
 * ("The door is locked.", "You need an Agility level of ...").
 *
 * <p>Append-only, best effort, never throws into the walker. One row per (kind, edge) per session.
 */
@Slf4j
public final class UnknownObstacleLog {
    public static final String KIND_DOOR_REFUSED = "DOOR_REFUSED";
    public static final String KIND_DOOR_GAVE_UP = "DOOR_GAVE_UP";
    public static final String KIND_DOOR_REQUIREMENT_DIALOGUE = "DOOR_REQUIREMENT_DIALOGUE";
    public static final String KIND_TRANSPORT_REFUSED = "TRANSPORT_REFUSED";
    public static final String KIND_TRANSPORT_GAVE_UP = "TRANSPORT_GAVE_UP";
    public static final String KIND_DEAD_END = "DEAD_END_TARGET";

    private static final String HEADER = "# time\tkind\tplayer\tfrom\tto\tobjectId\tobjectName\taction\tinCatalog\twalkTarget\tgameMessage\tnote";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long CHAT_RELEVANCE_MS = 6_000L;
    private static final Set<String> loggedThisSession = ConcurrentHashMap.newKeySet();
    private static final Object fileLock = new Object();

    private static volatile String lastGameMessage;
    private static volatile long lastGameMessageAtMs;
    private static volatile File fileOverride;

    private UnknownObstacleLog() {
    }

    public static File defaultFile() {
        return new File(new File(RuneLite.RUNELITE_DIR, "microbot"), "walker-unknown-obstacles.tsv");
    }

    /** Test seam. */
    static void setFileForTest(File file) {
        fileOverride = file;
        loggedThisSession.clear();
    }

    /** Fed from the central chat hook; remembers the game's most recent explanation line. */
    public static void onChatMessage(ChatMessage event) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.ENGINE
                && type != ChatMessageType.MESBOX && type != ChatMessageType.SPAM) {
            return;
        }
        lastGameMessage = event.getMessage().replaceAll("<[^>]*>", "");
        lastGameMessageAtMs = System.currentTimeMillis();
    }

    /** The game message, only if it arrived recently enough to plausibly be about this obstacle. */
    static String recentGameMessage(long nowMs) {
        String msg = lastGameMessage;
        return msg != null && nowMs - lastGameMessageAtMs <= CHAT_RELEVANCE_MS ? msg : "";
    }

    public static void record(String kind, WorldPoint player, WorldPoint from, WorldPoint to,
                              int objectId, String objectName, String action, boolean inCatalog,
                              WorldPoint walkTarget, String note) {
        try {
            String dedupe = kind + '|' + pt(from) + '|' + pt(to) + '|' + objectId;
            if (!loggedThisSession.add(dedupe)) {
                return;
            }
            long now = System.currentTimeMillis();
            String row = String.join("\t",
                    LocalDateTime.now().format(TIME), clean(kind), pt(player), pt(from), pt(to),
                    objectId > 0 ? String.valueOf(objectId) : "", clean(objectName), clean(action),
                    String.valueOf(inCatalog), pt(walkTarget), clean(recentGameMessage(now)), clean(note));
            File file = fileOverride != null ? fileOverride : defaultFile();
            synchronized (fileLock) {
                boolean fresh = !file.exists() || file.length() == 0;
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                    if (fresh) {
                        w.write(HEADER + "\n");
                    }
                    w.write(row + "\n");
                }
            }
            WebWalkLog.spInfo("unknown_obstacle | kind={} from={} to={} obj={} name={} action={} inCatalog={} msg=\"{}\"",
                    kind, pt(from), pt(to), objectId, objectName, action, inCatalog, recentGameMessage(now));
        } catch (Exception ex) {
            log.debug("[Walker] could not record unknown obstacle", ex);
        }
    }

    private static String pt(WorldPoint p) {
        return p == null ? "" : p.getX() + " " + p.getY() + " " + p.getPlane();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
