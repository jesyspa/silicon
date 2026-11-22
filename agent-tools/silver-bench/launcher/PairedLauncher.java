import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Loads two Silver builds into separate class loaders in one JVM and runs them back to back on every file, in a
 *  random order per file, so that drift in machine speed affects both alike.
 *  Usage: PairedLauncher ARM_A ARM_B LIST WARMLIST CORPUS OUT */
public class PairedLauncher {
  public static void main(String[] a) throws Exception {
    // Work directory (arms/, reflect-jar.txt) and harness class directory, set by run-pair.sh
    String S = System.getProperty("bench.dir");
    Path harness = Paths.get(System.getProperty("bench.harness"));
    String[] arms = {a[0], a[1]};
    Method[] run = new Method[2];
    ClassLoader[] cls = new ClassLoader[2];
    for (int i = 0; i < 2; i++) {
      URL[] urls = {harness.toUri().toURL(),
                    Paths.get(S, "arms", arms[i], "classes").toUri().toURL(),
                    // scala-reflect keeps a mirror per class loader it is loaded by, so each build gets its own copy
                    Paths.get(Files.readString(Paths.get(S, "reflect-jar.txt")).trim()).toUri().toURL()};
      ClassLoader cl = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
      cls[i] = cl;
      run[i] = Class.forName("SilverBench", true, cl).getMethod("runFile", String.class);
    }
    List<String> files = read(a[2]), warm = read(a[3]);
    Path corpus = Paths.get(a[4]);
    for (String f : warm) for (int i = 0; i < 2; i++) call(run, cls, i, corpus.resolve(f).toString());
    Random rng = new Random(a[0].hashCode() * 31L + a[1].hashCode());
    try (PrintWriter w = new PrintWriter(new FileWriter(a[5], true))) {
      for (String f : files) {
        int first = rng.nextInt(2);
        for (int k = 0; k < 2; k++) {
          int i = (first + k) % 2;
          String[] r = call(run, cls, i, corpus.resolve(f).toString()).split("\t", 2);
          w.println("{\"arm\":\"" + arms[i] + "\",\"pair\":\"" + arms[1] + "\",\"order\":" + k + ",\"file\":\"" + f
                    + "\",\"status\":\"" + r[0] + "\",\"phases\":{" + (r.length > 1 ? r[1] : "") + "}}");
        }
        w.flush();
      }
    }
  }
  // Plugins are loaded by name, so each build must see its own class loader as the context loader
  static String call(Method[] run, ClassLoader[] cls, int i, String path) throws Exception {
    Thread.currentThread().setContextClassLoader(cls[i]);
    return (String) run[i].invoke(null, path);
  }
  static List<String> read(String p) throws IOException {
    List<String> out = new ArrayList<>();
    for (String l : Files.readAllLines(Paths.get(p))) { String s = l.split("\t")[0].trim(); if (!s.isEmpty()) out.add(s); }
    return out;
  }
}
