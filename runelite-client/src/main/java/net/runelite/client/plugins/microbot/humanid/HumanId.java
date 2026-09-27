package net.runelite.client.plugins.microbot.humanid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Locale;

/**
 * A player's humanization identity: one SHA-256 of the canonical in-game name. Every module that
 * humanizes a player (HumanTiming, HumanMouse, anything later, on any platform) derives that player's
 * traits from this, so the same player behaves the same everywhere.
 *
 * <pre>
 * canonical = NFKC, lower case, NBSP / '_' / '-' as space, spaces collapsed, trimmed
 * master    = SHA-256("humanid/v1|osrs|" + canonical)
 * id        = "HID1-" + Crockford base32 of master[0..10), in groups of 4
 * trait u   = first 53 bits of SHA-256(master + "|" + traitKey) / 2^53
 * </pre>
 *
 * Nothing secret, nothing random: anyone with this code and a name gets the same ID, fingerprint and
 * picture. Keep the derivation string out of public places so it can't be matched from outside.
 *
 * <p>Run {@code java -cp <jar> net.runelite.client.plugins.microbot.humanid.HumanId "Player Name"} to
 * print a player's ID, fingerprint and randomart.
 */
public final class HumanId {
    public static final String VERSION = "v1";
    static final String DOMAIN = "humanid/v1|osrs|";
    private static final char[] BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    /** OpenSSH's randomart symbols, from least to most visited; S and E mark start and end. */
    private static final String ART = " .o+=*BOX@%&#/^SE";
    static final int ART_W = 17;
    static final int ART_H = 9;

    private final String canonicalName;
    private final byte[] master;

    private HumanId(String canonicalName, byte[] master) {
        this.canonicalName = canonicalName;
        this.master = master;
    }

    /** The identity of an in-game name. "Zezima", " zezima " and "ZEZIMA" are the same player. */
    public static HumanId of(String playerName) {
        String c = canonical(playerName);
        return new HumanId(c, sha256((DOMAIN + c).getBytes(StandardCharsets.UTF_8)));
    }

    /** An identity from a number, for tests and population simulations (no name behind it). */
    public static HumanId ofSeed(long seed) {
        return new HumanId("#" + seed, sha256((DOMAIN + "#seed:" + seed).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Name canonicalisation: RuneLite and the game show names with non-breaking spaces, and Jagex
     * treats space, underscore and hyphen alike in lookups.
     */
    public static String canonical(String name) {
        String s = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKC);
        s = s.replace(' ', ' ').replace('_', ' ').replace('-', ' ');
        s = s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return s;
    }

    public String canonicalName() {
        return canonicalName;
    }

    /** Short, readable ID: HID1-XXXX-XXXX-XXXX-XXXX (80 bits). */
    public String id() {
        String b32 = base32(master, 10);
        StringBuilder s = new StringBuilder("HID1");
        for (int i = 0; i < b32.length(); i += 4) s.append('-').append(b32, i, Math.min(b32.length(), i + 4));
        return s.toString();
    }

    /** Full fingerprint in the OpenSSH style: SHA256:base64 without padding. */
    public String fingerprint() {
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(master);
    }

    /** 64-bit seed for code that needs one (e.g. a planner seeding each day). */
    public long seed() {
        long s = 0;
        for (int i = 0; i < 8; i++) s = (s << 8) | (master[i] & 0xff);
        return s;
    }

    /** A number in [0, 1) that belongs to this player and {@code key} only. */
    public double unit(String key) {
        MessageDigest md = digest();
        md.update(master);
        md.update(("|" + key).getBytes(StandardCharsets.UTF_8));
        byte[] h = md.digest();
        long bits = 0;
        for (int i = 0; i < 8; i++) bits = (bits << 8) | (h[i] & 0xff);
        return (bits >>> 11) / (double) (1L << 53);
    }

    /** A standard-normal number for {@code key}, via the inverse normal CDF of {@link #unit}. */
    public double normal(String key) {
        double u = Math.min(1 - 1e-12, Math.max(1e-12, unit(key)));
        return inversePhi(u);
    }

    /** This player's standing on a shared personality factor (standard normal). */
    public double factor(Factor f) {
        return normal("factor/" + f.key);
    }

    /**
     * A trait's influence: at most {@code cap} (e.g. 0.05 = ±5%) around the population value.
     * {@code loading} ties it to a shared factor (-1..1, sign = direction), so traits that go together
     * in people go together here; the rest is the trait's own.
     */
    public Influence influence(String trait, Factor factor, double loading, double cap) {
        double own = normal("trait/" + trait);
        double z = factor == null ? own : loading * factor(factor) + Math.sqrt(Math.max(0, 1 - loading * loading)) * own;
        double periodDays = Influence.MIN_PERIOD_DAYS + (Influence.MAX_PERIOD_DAYS - Influence.MIN_PERIOD_DAYS) * unit("period/" + trait);
        double phase = 2 * Math.PI * unit("phase/" + trait);
        return new Influence(trait, Math.tanh(z), cap, periodDays, phase);
    }

    /** An influence with no shared factor. */
    public Influence influence(String trait, double cap) {
        return influence(trait, null, 0, cap);
    }

    /**
     * OpenSSH-style randomart ("drunken bishop") of the fingerprint: a 17×9 picture that is easy to
     * compare by eye. Same name, same picture, on any machine.
     */
    public String randomArt() {
        int[][] field = new int[ART_W][ART_H];
        int x = ART_W / 2, y = ART_H / 2;
        int sx = x, sy = y;
        for (byte b : master) {
            int v = b & 0xff;
            for (int i = 0; i < 4; i++) {
                x += (v & 1) != 0 ? 1 : -1;
                y += (v & 2) != 0 ? 1 : -1;
                x = Math.max(0, Math.min(ART_W - 1, x));
                y = Math.max(0, Math.min(ART_H - 1, y));
                if (field[x][y] < ART.length() - 3) field[x][y]++;
                v >>= 2;
            }
        }
        StringBuilder s = new StringBuilder();
        s.append(frame("[HumanID " + VERSION + "]")).append('\n');
        for (int row = 0; row < ART_H; row++) {
            s.append('|');
            for (int col = 0; col < ART_W; col++) {
                char c;
                if (col == sx && row == sy) c = 'S';
                else if (col == x && row == y) c = 'E';
                else c = ART.charAt(field[col][row]);
                s.append(c);
            }
            s.append("|\n");
        }
        s.append(frame("[SHA256]"));
        return s.toString();
    }

    /** Everything a person needs to check an identity: name, ID, fingerprint and picture. */
    public String describe() {
        return "player      " + canonicalName + "\n"
                + "id          " + id() + "\n"
                + "fingerprint " + fingerprint() + "\n"
                + randomArt();
    }

    @Override
    public String toString() {
        return id();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HumanId && java.util.Arrays.equals(master, ((HumanId) o).master);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(master);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("usage: HumanId \"Player Name\" [more names...]");
            return;
        }
        for (String name : args) {
            System.out.println(of(name).describe());
            System.out.println();
        }
    }

    // ------------------------------------------------------------------------------------------

    private static String frame(String title) {
        int pad = ART_W - title.length();
        int left = pad / 2;
        StringBuilder s = new StringBuilder("+");
        for (int i = 0; i < left; i++) s.append('-');
        s.append(title);
        for (int i = 0; i < pad - left; i++) s.append('-');
        return s.append('+').toString();
    }

    private static String base32(byte[] data, int bytes) {
        StringBuilder s = new StringBuilder();
        int buffer = 0, bits = 0;
        for (int i = 0; i < bytes; i++) {
            buffer = (buffer << 8) | (data[i] & 0xff);
            bits += 8;
            while (bits >= 5) {
                s.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) s.append(BASE32[(buffer << (5 - bits)) & 31]);
        return s.toString();
    }

    private static byte[] sha256(byte[] data) {
        return digest().digest(data);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inverse standard normal CDF (Acklam; relative error < 1.2e-9). Fixed so every port agrees. */
    static double inversePhi(double p) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double lo = 0.02425, hi = 1 - lo;
        if (p < lo) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > hi) {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5, r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }
}
