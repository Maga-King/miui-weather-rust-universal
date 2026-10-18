package miui.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** Physical memory accessor used reflectively by the original MIUIX DeviceUtils. */
public final class HardwareInfo {
    private HardwareInfo() { }

    /** Original API contract: static no-argument method returning bytes as long. */
    public static long getTotalPhysicalMemory() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("MemTotal:")) continue;
                String[] values = line.substring("MemTotal:".length()).trim().split("\\s+");
                if (values.length != 2 || !"kB".equals(values[1])) return 0L;
                long kilobytes = Long.parseLong(values[0]);
                if (kilobytes <= 0L || kilobytes > Long.MAX_VALUE / 1024L) return 0L;
                return kilobytes * 1024L;
            }
        } catch (IOException | NumberFormatException | SecurityException ignored) {
            // Zero preserves DeviceUtils's unavailable-memory fallback.
        }
        return 0L;
    }
}
