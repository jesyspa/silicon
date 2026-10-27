import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import ch.qos.logback.classic.Logger;
import com.sun.management.HotSpotDiagnosticMXBean;
import scala.Option;
import scala.collection.immutable.ArraySeq;
import viper.silicon.SiliconFrontend;
import viper.silver.frontend.DefaultStates;
import viper.silver.logger.ViperStdOutLogger;
import viper.silver.reporter.StdIOReporter;
import viper.silver.verifier.Success$;

/**
 * Long-lived Silicon driver for the corpus harness. Each stdin line is
 * "log-path TAB silicon-arg TAB ..."; each result is one JSON line on stdout.
 * Uses only the public frontend API so it runs against older Silicon JARs.
 */
public final class WarmRunner {
    /** Forwards to the current request's log; installed before Scala's Console captures System.out. */
    private static final class Switch extends OutputStream {
        volatile OutputStream target;

        Switch(OutputStream target) { this.target = target; }

        @Override public void write(int b) throws IOException { target.write(b); }

        @Override public void write(byte[] b, int off, int len) throws IOException { target.write(b, off, len); }

        @Override public void flush() throws IOException { target.flush(); }
    }

    public static void main(String[] argv) throws IOException {
        PrintStream protocol = new PrintStream(new FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        PrintStream stderr = System.err;
        Switch output = new Switch(stderr);
        PrintStream redirected = new PrintStream(output, true, "UTF-8");
        System.setOut(redirected);
        System.setErr(redirected);
        // One logger for all requests: each ViperLogger construction adds another appender.
        Logger logger = ViperStdOutLogger.apply("SiliconFrontend", "INFO").get();
        HotSpotDiagnosticMXBean hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        // Committed heap above which the post-request collection returns free heap, in MiB;
        // 0 disables it. Regrowing a small heap costs short inputs up to a third of their time.
        long shrinkAbove = Long.getLong("warmrunner.idleShrinkAboveMiB", 1024) << 20;
        String minFree = hotspot.getVMOption("MinHeapFreeRatio").getValue();
        String maxFree = hotspot.getVMOption("MaxHeapFreeRatio").getValue();
        BufferedReader requests = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        protocol.println("{\"ready\":true}");
        for (String line; (line = requests.readLine()) != null; ) {
            String[] fields = line.split("\t", -1);
            String[] args = Arrays.copyOfRange(fields, 1, fields.length);
            long[] before = procStat();
            long[][] gcBefore = gcStat();
            long start = System.nanoTime();
            boolean success = false;
            Throwable fatal = null;
            try (FileOutputStream log = new FileOutputStream(fields[0])) {
                output.target = log;
                SiliconFrontend frontend = new SiliconFrontend(new StdIOReporter("stdout_reporter", true), logger);
                try {
                    frontend.execute(ArraySeq.unsafeWrapArray(args), Option.empty());
                    success = frontend.state().id() >= DefaultStates.Verification().id()
                        && frontend.result() == Success$.MODULE$;
                } catch (Throwable t) {
                    // Matches the cold runner's crash classification.
                    redirected.println("An exception occurred: " + t);
                    t.printStackTrace(redirected);
                    if (t instanceof Error) fatal = t;
                } finally {
                    if (frontend.verifier() != null) frontend.verifier().stop();
                }
                redirected.flush();
            } finally {
                output.target = stderr;
            }
            double wall = (System.nanoTime() - start) / 1e9;
            long[] after = procStat();
            long[][] gcAfter = gcStat();
            // An idle runner otherwise keeps up to MaxHeapFreeRatio of free heap committed.
            // Tight ratios slow GC-heavy inputs, so they hold only for this collection.
            boolean shrink = shrinkAbove > 0
                && ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted() > shrinkAbove;
            if (shrink) {
                hotspot.setVMOption("MinHeapFreeRatio", "10");
                hotspot.setVMOption("MaxHeapFreeRatio", "20");
            }
            System.gc();
            if (shrink) {
                hotspot.setVMOption("MaxHeapFreeRatio", maxFree);
                hotspot.setVMOption("MinHeapFreeRatio", minFree);
            }
            long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            protocol.println(String.format(Locale.ROOT,
                "{\"success\":%b,\"wall_s\":%.6f,\"jvm_cpu_ticks\":%d,\"children_cpu_ticks\":%d,"
                    + "\"heap_after_gc_bytes\":%d,\"gc\":%s,\"fatal\":%b}",
                success, wall, after[0] - before[0], after[1] - before[1], heap,
                gcJson(gcBefore, gcAfter), fatal != null));
            // An Error may leave shared JVM state broken; let the harness start a fresh runner.
            if (fatal != null) System.exit(2);
        }
    }

    /** Collection count and accumulated milliseconds of each collector, in bean order. */
    private static long[][] gcStat() {
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        long[][] stat = new long[beans.size()][];
        for (int i = 0; i < stat.length; i++)
            stat[i] = new long[] {beans.get(i).getCollectionCount(), beans.get(i).getCollectionTime()};
        return stat;
    }

    /** {"collector":{"count":n,"ms":t},...} of the change across the request. */
    private static String gcJson(long[][] before, long[][] after) {
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < after.length; i++) {
            if (i > 0) json.append(',');
            json.append('"').append(beans.get(i).getName().replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\":{\"count\":").append(after[i][0] - before[i][0])
                .append(",\"ms\":").append(after[i][1] - before[i][1]).append('}');
        }
        return json.append('}').toString();
    }

    /** utime+stime and reaped-children cutime+cstime of this process, in clock ticks. */
    private static long[] procStat() throws IOException {
        String stat = new String(Files.readAllBytes(Paths.get("/proc/self/stat")), StandardCharsets.US_ASCII);
        String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        // Fields after "(comm)" start at field 3 (state); utime is field 14.
        return new long[] {
            Long.parseLong(f[11]) + Long.parseLong(f[12]),
            Long.parseLong(f[13]) + Long.parseLong(f[14])};
    }
}
