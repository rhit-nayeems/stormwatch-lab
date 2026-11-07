import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class WeatherService {
    private final ResultSink sink;
    private final Storage storage;
    private final Queue<Item> express = new ConcurrentLinkedQueue<>();
    private final Deque<Item> line = new ArrayDeque<>();
    private final Object lock = new Object();
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicInteger submitted = new AtomicInteger(), completed = new AtomicInteger();
    private final Screen screen = new Screen(() -> submitted.get() - completed.get());
    private final FeedSync sync = new FeedSync();
    private final Thread screenThread = new Thread(screen, "screen");
    private final Thread syncThread = new Thread(sync, "sync");
    private int inFlight = 0;
    private boolean refreshActive = false;
    private volatile boolean running = true;

    WeatherService(ResultSink sink, Path storePath) throws IOException {
        this.sink = sink;
        this.storage = new Storage(storePath);
    }

    void start() {
        int workerCount = Math.max(4, Runtime.getRuntime().availableProcessors());
        for (int i = 0; i < workerCount; i++) {
            Thread t = new Thread(this::workerLoop, "worker-" + i);
            workers.add(t);
            t.start();
        }
        screenThread.start();
        syncThread.start();
    }

    void submit(Item item) {
        submitted.incrementAndGet();
        if (item.kind == Item.Kind.SEVERE) express.add(item);
        else synchronized (lock) { line.addLast(item); }
    }

    /** Blocks until every submitted item is finished and saved, then stops all threads. */
    void shutdown() throws InterruptedException {
        while (completed.get() < submitted.get()) Thread.sleep(5);
        running = false;
        screen.stop();
        sync.stop();
        for (Thread t : workers) t.join();
        screenThread.join();
        syncThread.join();
        storage.close();
    }

    private Item next() {
        Item s = express.poll();
        if (s != null) return s;
        synchronized (lock) {
            Item head = line.peekFirst();
            if (head == null || refreshActive) return null;
            if (head.kind == Item.Kind.REFRESH) {
                if (inFlight > 0) return null;
                refreshActive = true;
            }
            line.pollFirst();
            inFlight++;
            return head;
        }
    }

    private void workerLoop() {
        while (running) {
            Item item = next();
            if (item == null) {
                try { Thread.sleep(1); } catch (InterruptedException e) { return; }
                continue;
            }
            long result = Pipeline.run(item);
            storage.save(item.id, result);
            screen.record(item.kind);
            sink.accept(item.id, result);
            if (item.kind != Item.Kind.SEVERE) {
                synchronized (lock) {
                    inFlight--;
                    if (item.kind == Item.Kind.REFRESH) refreshActive = false;
                }
            }
            completed.incrementAndGet();
        }
    }
}
