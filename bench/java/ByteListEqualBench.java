import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares ways to implement ByteList#equal for jruby/jruby#10078.
 *
 * Usage: javac -d out ByteListEqualBench.java && java -cp out ByteListEqualBench [impl ...]
 *
 * Without arguments it checks every implementation for correctness, then runs each
 * implementation in its own JVM (so each JIT profile sees only one implementation)
 * and prints a table of ns/op. With an implementation name it only benchmarks that
 * one and prints CSV lines (used by the parent process).
 */
public class ByteListEqualBench {
    interface Impl {
        boolean equal(byte[] a, int aBegin, byte[] b, int bBegin, int size);
    }

    private static final VarHandle LONGS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    // master: front-and-back byte scan
    static boolean bytes(byte[] buf, int begin, byte[] otherBuf, int otherBegin, int size) {
        int first;
        int last = size;
        for (first = -1;
             --last > first && buf[begin + last] == otherBuf[otherBegin + last] &&
                     ++first < last && buf[begin + first] == otherBuf[otherBegin + first] ; ) ;
        return first >= last;
    }

    // PR #10078: last and first long, then a long at a time
    static boolean longs(byte[] buf, int begin, byte[] otherBuf, int otherBegin, int size) {
        if (size < Long.BYTES) return bytes(buf, begin, otherBuf, otherBegin, size);
        int tail = size - Long.BYTES;
        if ((long) LONGS.get(buf, begin + tail) != (long) LONGS.get(otherBuf, otherBegin + tail)) return false;
        if ((long) LONGS.get(buf, begin) != (long) LONGS.get(otherBuf, otherBegin)) return false;
        for (int i = Long.BYTES; i < tail; i += Long.BYTES) {
            if ((long) LONGS.get(buf, begin + i) != (long) LONGS.get(otherBuf, otherBegin + i)) return false;
        }
        return true;
    }

    // Arrays.equals for every size (vectorizedMismatch intrinsic)
    static boolean arrays(byte[] buf, int begin, byte[] otherBuf, int otherBegin, int size) {
        return Arrays.equals(buf, begin, begin + size, otherBuf, otherBegin, otherBegin + size);
    }

    // hybrid: byte scan below 8 bytes, Arrays.equals from 8 bytes
    static boolean hybrid(byte[] buf, int begin, byte[] otherBuf, int otherBegin, int size) {
        if (size < Long.BYTES) return bytes(buf, begin, otherBuf, otherBegin, size);
        return arrays(buf, begin, otherBuf, otherBegin, size);
    }

    // hybrid + ends: byte scan below 8 bytes; otherwise check the last and first long,
    // then Arrays.equals for the bytes in between
    static boolean hybridEnds(byte[] buf, int begin, byte[] otherBuf, int otherBegin, int size) {
        if (size < Long.BYTES) return bytes(buf, begin, otherBuf, otherBegin, size);
        int tail = size - Long.BYTES;
        if ((long) LONGS.get(buf, begin + tail) != (long) LONGS.get(otherBuf, otherBegin + tail)) return false;
        if ((long) LONGS.get(buf, begin) != (long) LONGS.get(otherBuf, otherBegin)) return false;
        if (tail <= Long.BYTES) return true;
        return Arrays.equals(buf, begin + Long.BYTES, begin + tail, otherBuf, otherBegin + Long.BYTES, otherBegin + tail);
    }

    // hybrid with longs: PR's long loop below 64 bytes, Arrays.equals from 64 bytes
    static boolean hybridLongs(byte[] buf, int begin, byte[] otherBuf, int otherBegin, int size) {
        if (size < 64) return longs(buf, begin, otherBuf, otherBegin, size);
        return arrays(buf, begin, otherBuf, otherBegin, size);
    }

    static final Map<String, Impl> IMPLS = new LinkedHashMap<>();
    static {
        IMPLS.put("bytes", ByteListEqualBench::bytes);
        IMPLS.put("longs", ByteListEqualBench::longs);
        IMPLS.put("arrays", ByteListEqualBench::arrays);
        IMPLS.put("hybrid", ByteListEqualBench::hybrid);
        IMPLS.put("hybridEnds", ByteListEqualBench::hybridEnds);
        IMPLS.put("hybridLongs", ByteListEqualBench::hybridLongs);
    }

    static final int[] SIZES = {3, 7, 8, 12, 16, 24, 32, 48, 64, 100, 256, 1024, 8192};
    static final String[] CASES = {"equal", "diffFirst", "diffMid", "diffLast"};

    static volatile int sink;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && IMPLS.containsKey(args[0])) {
            runOne(args[0]);
            return;
        }
        List<String> names = args.length == 0 ? new ArrayList<>(IMPLS.keySet()) : Arrays.asList(args);

        checkCorrectness();

        Map<String, Map<String, Double>> results = new TreeMap<>(ByteListEqualBench::compareKeys);
        for (String name : names) {
            for (String line : runChild(name)) {
                String[] f = line.split(",");
                if (f.length != 4 || !f[0].equals(name)) continue;
                results.computeIfAbsent(f[1] + "," + f[2], k -> new LinkedHashMap<>())
                        .put(name, Double.parseDouble(f[3]));
            }
        }
        report(names, results);
    }

    static int compareKeys(String x, String y) {
        String[] a = x.split(","), b = y.split(",");
        int c = Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0]));
        if (c != 0) return c;
        return Integer.compare(Arrays.asList(CASES).indexOf(a[1]), Arrays.asList(CASES).indexOf(b[1]));
    }

    static void checkCorrectness() {
        int checks = 0;
        for (Map.Entry<String, Impl> e : IMPLS.entrySet()) {
            Impl impl = e.getValue();
            for (int size = 0; size <= 80; size++) {
                for (int aBegin = 0; aBegin < 9; aBegin++) {
                    for (int bBegin = 0; bBegin < 9; bBegin++) {
                        byte[] a = new byte[aBegin + size + 9];
                        byte[] b = new byte[bBegin + size + 9];
                        Arrays.fill(a, (byte) 0x55);
                        Arrays.fill(b, (byte) 0x66);
                        for (int i = 0; i < size; i++) {
                            byte v = (byte) (i * 37 + 0x80);
                            a[aBegin + i] = v;
                            b[bBegin + i] = v;
                        }
                        expect(e.getKey(), impl, a, aBegin, b, bBegin, size, true);
                        checks++;
                        for (int pos = 0; pos < size; pos++) {
                            byte old = b[bBegin + pos];
                            for (byte d : new byte[] {1, (byte) 0x80, (byte) 0xff}) {
                                b[bBegin + pos] = (byte) (old ^ d);
                                expect(e.getKey(), impl, a, aBegin, b, bBegin, size, false);
                                checks++;
                            }
                            b[bBegin + pos] = old;
                        }
                    }
                }
            }
        }
        System.out.println("correctness: " + checks + " checks passed for " + IMPLS.keySet());
    }

    static void expect(String name, Impl impl, byte[] a, int aBegin, byte[] b, int bBegin, int size, boolean want) {
        if (impl.equal(a, aBegin, b, bBegin, size) != want) {
            throw new AssertionError(name + " wrong for size=" + size + " aBegin=" + aBegin + " bBegin=" + bBegin + " want=" + want);
        }
    }

    static List<String> runChild(String name) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = List.of(java, "-Xms256m", "-Xmx256m",
                "-cp", System.getProperty("java.class.path"), "ByteListEqualBench", name);
        System.err.println("running " + name + " ...");
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        List<String> lines = new String(p.getInputStream().readAllBytes()).lines().toList();
        if (p.waitFor() != 0) throw new RuntimeException(name + " failed");
        return lines;
    }

    static void runOne(String name) {
        Impl impl = IMPLS.get(name);
        for (int size : SIZES) {
            for (String c : CASES) {
                System.out.println(name + "," + size + "," + c + "," + String.format("%.3f", measure(impl, size, c)));
            }
        }
    }

    static double measure(Impl impl, int size, String c) {
        int pairs = 64;
        byte[][] as = new byte[pairs][];
        byte[][] bs = new byte[pairs][];
        for (int p = 0; p < pairs; p++) {
            byte[] a = new byte[size];
            for (int i = 0; i < size; i++) a[i] = (byte) ('a' + (i + p) % 26);
            byte[] b = a.clone();
            int pos = switch (c) {
                case "diffFirst" -> 0;
                case "diffMid" -> size / 2;
                case "diffLast" -> size - 1;
                default -> -1;
            };
            if (pos >= 0) b[pos] ^= 1;
            as[p] = a;
            bs[p] = b;
        }
        // about 50ms per round for any size
        long ops = Math.max(1 << 14, (50_000_000L / (2 + size / 8)));
        int reps = (int) Math.max(1, ops / pairs);

        for (int w = 0; w < 10; w++) round(impl, as, bs, size, reps);
        double[] times = new double[9];
        for (int r = 0; r < times.length; r++) {
            long t0 = System.nanoTime();
            round(impl, as, bs, size, reps);
            times[r] = (System.nanoTime() - t0) / (double) (reps * (long) pairs);
        }
        Arrays.sort(times);
        return times[times.length / 2];
    }

    static void round(Impl impl, byte[][] as, byte[][] bs, int size, int reps) {
        int n = 0;
        for (int r = 0; r < reps; r++) {
            for (int p = 0; p < as.length; p++) {
                if (impl.equal(as[p], 0, bs[p], 0, size)) n++;
            }
        }
        sink += n;
    }

    static void report(List<String> names, Map<String, Map<String, Double>> results) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("### ByteList#equal, ns/op (lower is better)\n\n");
        md.append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch"))
                .append(", Java ").append(System.getProperty("java.vm.version"))
                .append(" (").append(System.getProperty("java.vm.vendor")).append("), ")
                .append(Runtime.getRuntime().availableProcessors()).append(" cpus\n\n");
        md.append("| size | case |");
        for (String n : names) md.append(' ').append(n).append(" |");
        md.append("\n|---:|---|");
        for (String n : names) md.append("---:|");
        md.append('\n');
        for (Map.Entry<String, Map<String, Double>> e : results.entrySet()) {
            String[] k = e.getKey().split(",");
            md.append("| ").append(k[0]).append(" | ").append(k[1]).append(" |");
            double best = e.getValue().values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
            for (String n : names) {
                Double v = e.getValue().get(n);
                String s = v == null ? "-" : String.format("%.2f", v);
                if (v != null && v <= best * 1.05) s = "**" + s + "**";
                md.append(' ').append(s).append(" |");
            }
            md.append('\n');
        }
        md.append("\nBold: within 5% of the fastest in the row.\n");
        System.out.println(md);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null) {
            Files.writeString(Path.of(summary), md + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
