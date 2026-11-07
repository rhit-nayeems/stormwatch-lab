final class Item {
    enum Kind { ROUTINE, SEVERE, REFRESH }

    static Item[] TABLE = new Item[0];

    final int id;
    final Kind kind;
    final long payloadSeed;
    final long declaredChecksum;
    volatile long submittedNanos;

    Item(int id, Kind kind, long payloadSeed, long declaredChecksum) {
        this.id = id; this.kind = kind; this.payloadSeed = payloadSeed; this.declaredChecksum = declaredChecksum;
    }

    static int sizeOf(Kind k) {
        return switch (k) { case ROUTINE -> 4_000; case SEVERE -> 1_500; case REFRESH -> 120_000; };
    }

    double[] readings() { return readings(kind, payloadSeed); }

    static double[] readings(Kind kind, long seed) {
        int n = sizeOf(kind);
        double[] v = new double[n];
        long s = seed | 1;
        for (int i = 0; i < n; i++) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            v[i] = (s >>> 11) * (1.0 / (1L << 53)) * 100 - 50;
        }
        return v;
    }
}
