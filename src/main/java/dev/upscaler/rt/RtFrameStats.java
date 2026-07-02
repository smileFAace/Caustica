package dev.upscaler.rt;

import dev.upscaler.UpscalerConfig;
import dev.upscaler.UpscalerMod;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Opt-in per-stage timing and hitch detection for the terrain-tick and composite-frame hot loops.
 * Gated by {@code -Dupscaler.rt.frameStats}; every method is a cheap branch when disabled. {@link #TERRAIN}
 * and {@link #COMPOSITE} are independent {@link Profile}s since the two loops run on different cadences
 * (the client tick vs. once per render frame). Every completed frame is appended as one row to
 * {@code <gameDir>/rt-frame-stats/<name>.csv} (fresh file per session), and a profile additionally logs one
 * line whenever a frame's total exceeds {@value #HITCH_MULTIPLIER}x its own rolling median, with that
 * frame's per-stage/counter breakdown.
 */
public final class RtFrameStats {
    private static final int MEDIAN_WINDOW = 64;
    private static final double HITCH_MULTIPLIER = 1.5;

    public static final Profile TERRAIN = new Profile("terrain-tick",
            new String[] {"windowSync", "dirtyDrain"},
            new String[] {});
    // One row per streaming pass (dispatch + drain + build kick), whichever loop drove it: the per-render-
    // frame call from RtComposite or the tick fallback. Idle passes (nothing to do) write no row.
    public static final Profile STREAM = new Profile("terrain-stream",
            new String[] {"finalize", "snapshotDispatch", "drainUpload", "startBuild"},
            new String[] {"sectionsSnapshotted", "sectionsUploaded"});
    public static final Profile COMPOSITE = new Profile("composite",
            new String[] {"entityCapture", "beCapture", "particles", "blasRecord", "prepareTlas", "traceRecord"},
            new String[] {"entitiesCaptured", "refits", "vmaBufferCreates"});

    private RtFrameStats() {
    }

    public static boolean enabled() {
        return UpscalerConfig.Rt.FrameStats.ENABLED.value();
    }

    public interface Scope extends AutoCloseable {
        Scope NOOP = () -> {};

        @Override
        void close();
    }

    /** A timed loop: per-stage nanos + named counters for the frame in progress, plus a rolling-median hitch log. */
    public static final class Profile {
        private final String name;
        private final String[] stageNames;
        private final String[] counterNames;
        private final long[] stageNanos;
        private final long[] counters;
        private final long[] history = new long[MEDIAN_WINDOW];
        private int historyCount;
        private int historyPos;
        private long frameStart;
        private long frameIndex;
        private PrintWriter csv;
        private boolean csvOpenAttempted;

        private Profile(String name, String[] stageNames, String[] counterNames) {
            this.name = name;
            this.stageNames = stageNames;
            this.counterNames = counterNames;
            this.stageNanos = new long[stageNames.length];
            this.counters = new long[counterNames.length];
        }

        /** Start timing a new frame; clears this frame's stage/counter accumulators. */
        public void begin() {
            if (!enabled()) {
                return;
            }
            frameStart = System.nanoTime();
            Arrays.fill(stageNanos, 0L);
            Arrays.fill(counters, 0L);
        }

        /** Time one named stage of the current frame; close the returned scope when the stage completes. */
        public Scope stage(String stageName) {
            if (!enabled()) {
                return Scope.NOOP;
            }
            int idx = indexOf(stageNames, stageName);
            long start = System.nanoTime();
            return () -> stageNanos[idx] += System.nanoTime() - start;
        }

        /** Add to a named counter for the current frame. */
        public void count(String counterName, int delta) {
            if (!enabled() || delta == 0) {
                return;
            }
            counters[indexOf(counterNames, counterName)] += delta;
        }

        /** Finish the current frame: record it into the rolling median and log a hitch line if it's slow. */
        public void end() {
            if (!enabled()) {
                return;
            }
            long total = System.nanoTime() - frameStart;
            long median = median();
            history[historyPos] = total;
            historyPos = (historyPos + 1) % MEDIAN_WINDOW;
            if (historyCount < MEDIAN_WINDOW) {
                historyCount++;
            }
            boolean hitch = median > 0 && total > (long) (median * HITCH_MULTIPLIER);
            writeCsvRow(total, median, hitch);
            if (hitch) {
                logHitch(total, median);
            }
        }

        private long median() {
            if (historyCount == 0) {
                return 0L;
            }
            long[] sorted = Arrays.copyOf(history, historyCount);
            Arrays.sort(sorted);
            return sorted[historyCount / 2];
        }

        private void writeCsvRow(long total, long median, boolean hitch) {
            ensureCsv();
            if (csv == null) {
                return;
            }
            StringBuilder row = new StringBuilder();
            row.append(frameIndex++).append(',').append(ms(total)).append(',').append(ms(median)).append(',').append(hitch ? 1 : 0);
            for (long stageNano : stageNanos) {
                row.append(',').append(ms(stageNano));
            }
            for (long counter : counters) {
                row.append(',').append(counter);
            }
            csv.println(row);
            csv.flush();
        }

        /** Opens (or gives up on) this profile's CSV file at most once per session. */
        private void ensureCsv() {
            if (csvOpenAttempted) {
                return;
            }
            csvOpenAttempted = true;
            Path dir = FabricLoader.getInstance().getGameDir().resolve("rt-frame-stats");
            Path file = dir.resolve(name + ".csv");
            try {
                Files.createDirectories(dir);
                csv = new PrintWriter(new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8)));
                StringBuilder header = new StringBuilder("frame,totalMs,medianMs,hitch");
                for (String stageName : stageNames) {
                    header.append(',').append(stageName).append("Ms");
                }
                for (String counterName : counterNames) {
                    header.append(',').append(counterName);
                }
                csv.println(header);
                csv.flush();
            } catch (IOException e) {
                UpscalerMod.LOGGER.warn("RtFrameStats: failed to open CSV {} for profile {}: {}", file, name, e.toString());
                csv = null;
            }
        }

        private void logHitch(long total, long median) {
            StringBuilder sb = new StringBuilder("RT hitch [").append(name).append("] ")
                    .append(ms(total)).append("ms (median ").append(ms(median)).append("ms):");
            for (int i = 0; i < stageNames.length; i++) {
                sb.append(' ').append(stageNames[i]).append('=').append(ms(stageNanos[i])).append("ms");
            }
            for (int i = 0; i < counterNames.length; i++) {
                sb.append(' ').append(counterNames[i]).append('=').append(counters[i]);
            }
            UpscalerMod.LOGGER.info(sb.toString());
        }

        private static double ms(long nanos) {
            return Math.round(nanos / 1000.0) / 1000.0;
        }

        private static int indexOf(String[] arr, String name) {
            for (int i = 0; i < arr.length; i++) {
                if (arr[i].equals(name)) {
                    return i;
                }
            }
            throw new IllegalArgumentException("Unknown RtFrameStats name: " + name);
        }
    }
}
