final class FeedSync implements Runnable {
    private volatile boolean running = true;
    private volatile long lastCheck;

    void stop() { running = false; }

    public void run() {
        while (running) {
            long h = 7;
            for (int i = 0; i < 150_000; i++) h = h * 31 + (i ^ (h >>> 7));
            lastCheck = h;
            try { Thread.sleep(2); } catch (InterruptedException e) { return; }
        }
    }
}
