import java.nio.file.*;
import java.util.regex.*;

final class Battery {
    static int percent() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                return parse(exec("powershell", "-NoProfile", "-Command",
                        "(Get-CimInstance Win32_Battery).EstimatedChargeRemaining"), "(\\d+)");
            } else if (os.contains("mac")) {
                return parse(exec("pmset", "-g", "batt"), "(\\d+)%");
            } else {
                try (var dirs = Files.newDirectoryStream(Paths.get("/sys/class/power_supply"), "BAT*")) {
                    for (Path d : dirs) return Integer.parseInt(Files.readString(d.resolve("capacity")).trim());
                }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private static String exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }

    private static int parse(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
