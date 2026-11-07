import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

final class Storage {
    private final FileOutputStream out;

    Storage(Path path) throws IOException {
        out = new FileOutputStream(path.toFile());
    }

    synchronized void save(int id, long result) {
        try {
            out.write((id + "," + result + "\n").getBytes());
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    void close() {
        try { out.close(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
