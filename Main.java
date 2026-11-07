import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

public class Main {
    
    static double defaultRate() { return Math.min(120, Math.max(40, 10.0 * Runtime.getRuntime().availableProcessors())); }
    static final int DEFAULT_BURST = 300;
    static final double SEVERE_LIMIT_S = 0.75, ROUTINE_LIMIT_S = 60.0, REFRESH_LIMIT_S = 90.0;

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("Thread " + t.getName() + " crashed, so the run was stopped:");
            e.printStackTrace();
            Runtime.getRuntime().halt(1);
        });
        double minutes = 6, rate = defaultRate(); int burst = -1; long seed = 2024; boolean details = false;
        
        Path existing = Paths.get("results.csv");
            long rows = Files.exists(existing)
                    ? Files.readAllLines(existing).stream()
                            .filter(l -> { String[] f = l.split(","); return f.length >= 2 && f[1].trim().matches("\\d+"); }).count()
                    : 0;
            String label = String.valueOf(rows + 1);
        
        if (burst < 0) burst = (int) Math.round(DEFAULT_BURST * Math.min(1.0, rate / 50.0) + 1);

        System.out.println("Preparing the run (this can take a few seconds)...");
        Item[] items = buildItems(minutes * 60, rate, burst, seed);
        int n = items.length;
        double[] at = new double[n];

        System.arraycopy(ARRIVALS, 0, at, 0, n);
        Item.TABLE = items;
        Rules.init(n);

        long[] results = new long[n], done = new long[n];
        AtomicIntegerArray seen = new AtomicIntegerArray(n);
        ResultSink sink = (id, r) -> { results[id] = r; done[id] = System.nanoTime(); seen.incrementAndGet(id); };
        Path store = Paths.get("storage.log");

        int[] counts = new int[3];
        for (Item it : items) counts[it.kind.ordinal()]++;
        System.out.printf("=== %s: %d updates over ~%.1f min at %.0f routine updates/s (%d routine, %d severe, %d refresh) ===%n",
                label, n, minutes, rate, counts[0], counts[1], counts[2]);
        System.out.println("TIP: unplug the charger, fix screen brightness, close other apps.");
        int batStart = Battery.percent();
        System.out.println("Battery at start: " + (batStart < 0 ? "unavailable (record it by hand)" : batStart + "%"));

        WeatherService svc = new WeatherService(sink, store);
        long cpu0 = cpuNanos(), start = System.nanoTime();
        svc.start();
        for (int i = 0; i < n; i++) {
            long when = start + (long) (at[i] * 1e9);
            long wait;
            while ((wait = when - System.nanoTime()) > 0) LockSupport.parkNanos(Math.min(wait, 2_000_000));
            items[i].submittedNanos = System.nanoTime();
            svc.submit(items[i]);
        }
        svc.shutdown();
        double wall = (System.nanoTime() - start) / 1e9, cpu = (cpuNanos() - cpu0) / 1e9;
        int batEnd = Battery.percent();

        // ---- verification (outside the measured window) ----
        System.out.println("\nVerifying...");
        List<String> problems = new ArrayList<>();
        double[] maxLat = new double[3];
        for (int i = 0; i < n; i++) {
            if (seen.get(i) != 1) { problems.add("Update " + i + " reported " + seen.get(i) + " times"); continue; }
            int k = items[i].kind.ordinal();
            maxLat[k] = Math.max(maxLat[k], (done[i] - items[i].submittedNanos) / 1e9);
        }
        if (maxLat[1] > SEVERE_LIMIT_S) problems.add(String.format("A severe warning took %.2f s (limit %.1f s)", maxLat[1], SEVERE_LIMIT_S));
        if (maxLat[0] > ROUTINE_LIMIT_S) problems.add(String.format("A routine update took %.2f s (limit %.1f s)", maxLat[0], ROUTINE_LIMIT_S));
        if (maxLat[2] > REFRESH_LIMIT_S) problems.add(String.format("A refresh took %.2f s (limit %.1f s)", maxLat[2], REFRESH_LIMIT_S));

        problems.addAll(checkBarrier(items, done));

        int[] wrong = new int[1];
        List<Integer> sampled = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Item.Kind k = items[i].kind;
            if (k == Item.Kind.REFRESH || (k == Item.Kind.SEVERE && i % 3 == 0) || (k == Item.Kind.ROUTINE && i % 20 == 0)) sampled.add(i);
        }
        List<String> wrongList = Collections.synchronizedList(new ArrayList<>());
        sampled.parallelStream().forEach(i -> {
            if (seen.get(i) == 1 && results[i] != Rules.expected(items[i])) wrongList.add("Wrong result for update " + i);
        });
        problems.addAll(wrongList);
        problems.addAll(checkStorage(store, results, n));

        System.out.println();
        System.out.println("Battery at end: " + (batEnd < 0 ? "unavailable" : batEnd + "%")
                + (batStart >= 0 && batEnd >= 0 ? "  (used " + (batStart - batEnd) + " points)" : ""));
        if (details || !problems.isEmpty()) {
            System.out.printf("Wall time     : %.1f s%n", wall);
            System.out.printf("CPU time      : %.1f s%n", cpu);
            System.out.printf("Avg cores busy: %.2f%n", cpu / wall);
            System.out.printf("Longest wait  : severe %.2f s, routine %.2f s, refresh %.2f s%n", maxLat[1], maxLat[0], maxLat[2]);
        }
        if (problems.isEmpty()) System.out.println("Correctness   : PASS");
        else {
            System.out.println("Correctness   : FAIL (energy savings don't count)");
            problems.stream().limit(8).forEach(p -> System.out.println("  - " + p));
        }
        Path csv = Paths.get("results.csv");
        boolean needHeader = !Files.exists(csv) || Files.size(csv) == 0;
        try (FileWriter w = new FileWriter("results.csv", true)) {
            if (needHeader) w.write("run #,updates,wall_seconds,cpu_seconds,battery_start_pct,battery_end_pct,result,rate_per_sec\n");
            w.write(String.format(Locale.ROOT, "%s,%d,%.1f,%.1f,%d,%d,%s,%.0f%n", label, n, wall, cpu, batStart, batEnd,
                    problems.isEmpty() ? "PASS" : "FAIL", rate));
        }
    }

    // ---- the refresh rule: nothing else (except severe) overlaps a refresh ----
    static List<String> checkBarrier(Item[] items, long[] done) {
        List<String> problems = new ArrayList<>();
        int n = items.length;
        long[] start = new long[n];
        for (int i = 0; i < n; i++) { long t = Rules.firstTouch(i); start[i] = t == 0 ? done[i] : Math.min(t, done[i]); }
        for (Item r : items) {
            if (r.kind != Item.Kind.REFRESH) continue;
            long sR = start[r.id], eR = done[r.id];
            for (Item x : items) {
                if (x.kind == Item.Kind.SEVERE || x == r) continue;
                boolean before = x.submittedNanos < r.submittedNanos;
                if (before && done[x.id] > sR) problems.add("Update " + x.id + " was still in progress when refresh " + r.id + " began");
                if (!before && start[x.id] < eR) problems.add("Update " + x.id + " began before refresh " + r.id + " finished");
                if (problems.size() > 20) return problems;
            }
        }
        return problems;
    }

    static List<String> checkStorage(Path store, long[] results, int n) throws IOException {
        List<String> problems = new ArrayList<>();
        boolean[] found = new boolean[n];
        for (String line : Files.readAllLines(store)) {
            String[] f = line.split(",");
            try {
                int id = Integer.parseInt(f[0]); long r = Long.parseLong(f[1]);
                if (id < 0 || id >= n || r != results[id] || found[id]) problems.add("Bad saved line: " + line);
                else found[id] = true;
            } catch (RuntimeException e) { problems.add("Unreadable saved line: " + line); }
        }
        int missing = 0;
        for (boolean b : found) if (!b) missing++;
        if (missing > 0) problems.add(missing + " updates missing from storage.log");
        return problems;
    }

    // ---- building the run ----
    static double[] ARRIVALS;

    static Item[] buildItems(double totalSec, double rate, int burst, long seed) {
        List<double[]> ev = new ArrayList<>();      // {time, kind ordinal}
        int nBase = (int) Math.round(rate * totalSec);
        for (int i = 0; i < nBase; i++) ev.add(new double[] {(i + 0.4 * Math.sin(i * 1.7)) / rate, 0});
        Random rnd = new Random(seed);
        for (double t = 3; t < totalSec - 2; t += 4 + rnd.nextDouble() * 8) ev.add(new double[] {t, 1});
        for (double t = 8; t < totalSec - 5; t += 50) ev.add(new double[] {t, 2});
        for (double t = 20; t < totalSec - 5; t += 60) {
            for (int j = 0; j < burst; j++) ev.add(new double[] {t + j * 0.5 / burst, 0});
            for (int k = 1; k <= 3; k++) ev.add(new double[] {t + 0.5 * k / 4.0, 1});
        }
        ev.sort(Comparator.comparingDouble(e -> e[0]));
        int n = ev.size();
        ARRIVALS = new double[n];

        Item.Kind[] kind = new Item.Kind[n];
        long[] pseed = new long[n], declared = new long[n];
        int[] dupOf = new int[n];
        boolean[] invalid = new boolean[n];
        Random r = new Random(seed ^ 0x5DEECE66DL);
        for (int i = 0; i < n; i++) {
            ARRIVALS[i] = ev.get(i)[0];
            kind[i] = Item.Kind.values()[(int) ev.get(i)[1]];
            pseed[i] = Rules.mix(seed, i);
            dupOf[i] = -1;
            if (kind[i] == Item.Kind.ROUTINE) {
                double p = r.nextDouble();
                if (p < 0.15) { invalid[i] = true; declared[i] = r.nextLong(); }
                else if (p < 0.30 && i > 0) {
                    int j = i - 1 - r.nextInt(Math.min(i, 100));
                    if (kind[j] == Item.Kind.ROUTINE) { dupOf[i] = j; pseed[i] = pseed[j]; }
                }
            }
        }
        IntStream.range(0, n).parallel().filter(i -> !invalid[i] && dupOf[i] < 0)
                .forEach(i -> declared[i] = Rules.rawChecksum(Item.readings(kind[i], pseed[i])));
        for (int i = 0; i < n; i++) if (dupOf[i] >= 0) declared[i] = declared[dupOf[i]];

        Item[] items = new Item[n];
        for (int i = 0; i < n; i++) items[i] = new Item(i, kind[i], pseed[i], declared[i]);
        return items;
    }

    static long cpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }
}
