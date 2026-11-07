final class Pipeline {
    static long run(Item item) {
        double[] v = Rules.parse(item);
        boolean ok = Rules.checksumOk(item, v);

        long fp = Rules.fingerprint(item);
        boolean dup = false;
        for (int j = Math.max(0, item.id - Rules.DEDUPE_WINDOW); j < item.id; j++)
            if (Rules.fingerprint(Item.TABLE[j]) == fp) dup = true;

        long mask = Rules.relevance(item, v);
        double[] smooth = Rules.normalize(item, v);
        int clen = Rules.compress(item, smooth);

        return Rules.verdict(item, ok, dup, fp, mask, smooth, clen);
    }
}
