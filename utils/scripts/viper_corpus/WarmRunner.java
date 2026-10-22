import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import ch.qos.logback.classic.Logger;
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
        BufferedReader requests = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        protocol.println("{\"ready\":true}");
        for (String line; (line = requests.readLine()) != null; ) {
            String[] fields = line.split("\t", -1);
            String[] args = Arrays.copyOfRange(fields, 1, fields.length);
            long[] before = procStat();
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
            System.gc();
            long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            protocol.println(String.format(
                "{\"success\":%b,\"wall_s\":%.6f,\"jvm_cpu_ticks\":%d,\"children_cpu_ticks\":%d,"
                    + "\"heap_after_gc_bytes\":%d,\"fatal\":%b}",
                success, wall, after[0] - before[0], after[1] - before[1], heap, fatal != null));
            // An Error may leave shared JVM state broken; let the harness start a fresh runner.
            if (fatal != null) System.exit(2);
        }
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
