import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.zip.Deflater;

final class Rules {
    static final int DEDUPE_WINDOW = 300;
    private static final int CHECK_ROUNDS = 600, WINDOW = 1500, LOCATIONS = 40, STRIDE = 2;

    private static AtomicLongArray firstTouch = new AtomicLongArray(0);
    static void init(int n) { firstTouch = new AtomicLongArray(n); }
    static long firstTouch(int id) { return firstTouch.get(id); }
    private static void touch(int id) {
        if (firstTouch.get(id) == 0) firstTouch.compareAndSet(id, 0, Math.max(1, System.nanoTime()));
    }

    // ---- steps (each records when the item first entered processing) ----
    static double[] parse(Item it) { touch(it.id); return it.readings(); }
    static boolean checksumOk(Item it, double[] v) { touch(it.id); return rawChecksum(v) == it.declaredChecksum; }
    static long fingerprint(Item it) { return rawFingerprint(it.kind, it.payloadSeed); }
    static long relevance(Item it, double[] v) { touch(it.id); return rawRelevance(v); }
    static double[] normalize(Item it, double[] v) { touch(it.id); return rawNormalize(v); }
    static int compress(Item it, double[] smooth) { touch(it.id); return rawCompress(smooth); }

    static long verdict(Item it, boolean ok, boolean dup, long fp, long mask, double[] smooth, int clen) {
        if (!ok) return mix(it.id, 1);
        if (dup) return mix(mix(it.id, 2), fp);
        return mix(mix(mix(it.id, 3), mask), mix(hashOf(smooth), clen));
    }

    // ---- trusted expected result (used by the grader) ----
    private static final ConcurrentHashMap<Integer, Long> MEMO = new ConcurrentHashMap<>();
    static long expected(Item it) {
        double[] v = it.readings();
        if (rawChecksum(v) != it.declaredChecksum) return mix(it.id, 1);
        long fp = rawFingerprint(it.kind, it.payloadSeed);
        for (int j = Math.max(0, it.id - DEDUPE_WINDOW); j < it.id; j++) {
            Item p = Item.TABLE[j];
            long f = MEMO.computeIfAbsent(j, k -> rawFingerprint(p.kind, p.payloadSeed));
            if (f == fp) return mix(mix(it.id, 2), fp);
        }
        long mask = rawRelevance(v);
        double[] smooth = rawNormalize(v);
        return mix(mix(mix(it.id, 3), mask), mix(hashOf(smooth), rawCompress(smooth)));
    }

    // ---- raw computations ----
    static long rawChecksum(double[] v) {
        long h = 0xcbf29ce484222325L;
        for (int r = 0; r < CHECK_ROUNDS; r++)
            for (double d : v) { h ^= Double.doubleToRawLongBits(d); h *= 0x100000001b3L; h ^= h >>> 31; }
        return h;
    }

    static long rawFingerprint(Item.Kind kind, long seed) {
        double[] v = Item.readings(kind, seed);
        long h = 1125899906842597L;
        for (double d : v) h = 31 * h + Double.doubleToRawLongBits(d);
        return h;
    }

    private static long rawRelevance(double[] v) {
        long mask = 0;
        for (int i = 0; i + 1 < v.length; i += STRIDE) {
            double lat = Math.toRadians(35 + v[i] * 0.1), lon = Math.toRadians(-95 + v[i + 1] * 0.1);
            for (int l = 0; l < LOCATIONS; l++) {
                double lat2 = Math.toRadians(30 + (l * 37 % 17)), lon2 = Math.toRadians(-100 + (l * 53 % 23));
                double s1 = StrictMath.sin((lat2 - lat) / 2), s2 = StrictMath.sin((lon2 - lon) / 2);
                double a = s1 * s1 + StrictMath.cos(lat) * StrictMath.cos(lat2) * s2 * s2;
                double km = 12742 * StrictMath.asin(StrictMath.sqrt(a));
                if (km < 250) mask |= 1L << l;
            }
        }
        return mask;
    }

    private static double[] rawNormalize(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            int lo = Math.max(0, i - WINDOW / 2), hi = Math.min(v.length, i + WINDOW / 2);
            double s = 0;
            for (int k = lo; k < hi; k++) s += v[k];
            out[i] = s / (hi - lo);
        }
        return out;
    }

    private static int rawCompress(double[] smooth) {
        ByteBuffer bb = ByteBuffer.allocate(smooth.length * 8);
        for (double d : smooth) bb.putDouble(d);
        Deflater def = new Deflater(9);
        def.setInput(bb.array());
        def.finish();
        byte[] buf = new byte[8192];
        int total = 0;
        while (!def.finished()) total += def.deflate(buf);
        def.end();
        return total;
    }

    private static long hashOf(double[] v) {
        long h = 7;
        for (double d : v) h = 31 * h + Double.doubleToRawLongBits(d);
        return h;
    }

    static long mix(long a, long b) {
        long h = a * 0x9E3779B97F4A7C15L + b;
        h ^= h >>> 32; h *= 0xD6E8FEB86659FD93L; h ^= h >>> 32;
        return h;
    }
}
