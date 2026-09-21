package sugarcube.jexter.ui.jx;

import sugarcube.jexter.ocd.model.OCDDocument;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * JexterCli — the headless side of {@link Jexter}: convert a single file, batch a folder, or run the
 * hot-folder daemon. All three share one planner ({@link #plan}) and one converter
 * ({@link #convertLogged}); the daemon adds only the size-stability gate and the watch loop.
 *
 * <pre>
 *   Jexter &lt;in.pdf|folder&gt; [out] [--recursive] [--threads=&lt;n&gt;] [--outline] [--suffix=&lt;s&gt;] [--&lt;opt&gt;=&lt;val&gt;]
 *   Jexter --hotfolder &lt;dir&gt; [outDir] [--recursive] [--threads=&lt;n&gt;] [--interval=&lt;sec&gt;] [--done[=&lt;name&gt;]] [--suffix=&lt;s&gt;] [--&lt;opt&gt;=&lt;val&gt;]
 * </pre>
 *
 * <p>{@code --done} moves each source, once its output is written, into a sub-folder of the input
 * folder ({@code Done} unless named) — or, given a full path ({@code --done=D:\Archive}), into that
 * folder — mirroring its relative path; a failed source stays where it is.
 * The hot folder never reads that sub-folder, so what is left in the inbox is what is still to do.
 * {@code --suffix} names the outputs {@code <name><suffix>.pdf} ({@code -normalized} by default) — the
 * same rule as the window's Settings field; {@code --suffix=} (empty) keeps the source name, which
 * needs a separate output folder.
 *
 * <p>Console output stays ASCII on purpose: {@code stdout.encoding} follows the platform (a Windows
 * console is cp850/cp1252, this sandbox reports ANSI_X3.4-1968), so an em-dash prints as {@code ?}.
 *
 * {@code --recursive} descends into sub-folders and, with a separate output dir, mirrors the source
 * sub-tree; {@code --threads} converts in parallel. Outputs are written atomically by the engine and
 * a {@code *-normalized.pdf} is never taken as input.
 */
final class JexterCli {

    private JexterCli() {}

    private record Job(File in, File out) {}

    static void run(String[] args) throws Exception {
        Opts o = Opts.parse(args);
        if (o.hot && o.in != null && o.suffix.isEmpty() && sameFolder(o)) {
            System.err.println("--suffix= (empty) keeps the source name: give the hot folder a separate output folder");
            System.exit(2); return;
        }
        if (o.hot) { hotfolder(o); return; }
        if (o.in == null) { usage(); System.exit(2); return; }
        if (o.suffix.isEmpty() && sameFolder(o)) {       // the output would BE the source
            System.err.println("--suffix= (empty) keeps the source name: give a separate output folder");
            System.exit(2); return;
        }
        if (o.in.isDirectory()) batch(o); else single(o);
    }

    // ── single file ──────────────────────────────────────────────────────────────
    private static void single(Opts o) throws Exception {
        File out = (o.out != null) ? o.out
                : new File(o.in.getAbsoluteFile().getParentFile(), Jexter.outName(o.in.getName(), o.suffix));
        OCDDocument d = Jexter.normalize(o.in, out, o.map, o.selectable);
        System.out.println("normalized " + o.in.getName() + " -> " + out.getName()
                + (o.selectable ? " (selectable)" : " (outline)") + "  (" + d.pageCount() + " pages)");
    }

    // ── folder batch — one pass, then exit ───────────────────────────────────────
    private static void batch(Opts o) throws Exception {
        File root = o.in.getAbsoluteFile();
        File outRoot = (o.out != null ? o.out : o.in).getAbsoluteFile();
        ensureDir(outRoot);
        List<Job> jobs = plan(root, outRoot, o);
        if (jobs.isEmpty()) { System.err.println("no PDF files in " + root); System.exit(1); return; }

        ExecutorService pool = Executors.newFixedThreadPool(o.threads);
        AtomicInteger ok = new AtomicInteger(), ko = new AtomicInteger();
        for (Job j : jobs) pool.submit(() -> convertLogged(root, outRoot, j, o, ok, ko));
        pool.shutdown();
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        System.out.println("done: " + ok.get() + " ok, " + ko.get() + " failed");
    }

    // ── hot-folder daemon — watch <dir>, normalize PDFs as they land (Ctrl-C to stop) ──
    private static void hotfolder(Opts o) throws Exception {
        if (o.in == null || !o.in.isDirectory()) { usage(); System.exit(2); return; }
        File root = o.in.getAbsoluteFile();
        File outRoot = (o.out != null ? o.out : o.in).getAbsoluteFile();
        ensureDir(outRoot);

        ExecutorService pool = Executors.newFixedThreadPool(o.threads);
        AtomicInteger ok = new AtomicInteger(), ko = new AtomicInteger();
        Map<String, String> sizes = new HashMap<>();  // path → last seen signature (stability gate)
        Map<String, String> done = new HashMap<>();   // path → signature of the version handed to the pool
        // both maps are touched only on this thread; they are pruned each pass to the live tree

        System.out.println("hotfolder: watching " + root + (o.recursive ? " (recursive)" : "")
                + "  ->  " + outRoot + (o.done != null ? "   done -> " + o.done : "")
                + "   threads=" + o.threads + "   (Ctrl-C to stop)");

        Path path = root.toPath();
        try (WatchService ws = path.getFileSystem().newWatchService()) {
            path.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);  // root wake-up
            sweep(root, outRoot, o, sizes, done, pool, ok, ko, true);            // initial pass over existing files
            while (true) {
                WatchKey key = ws.poll(o.intervalMs, TimeUnit.MILLISECONDS);     // wake on events, else time out and sweep anyway
                if (key != null) { key.pollEvents(); key.reset(); }              // drain; the recursive sweep below is the source of truth
                sweep(root, outRoot, o, sizes, done, pool, ok, ko, false);
            }
        } finally {
            pool.shutdown();
        }
    }

    /**
     * One daemon pass: submit every stable PDF whose CURRENT version has not been handled; prune the
     * bookkeeping to the live tree.
     *
     * <p>A source is known by what it is — size and modification time — not by its name. The daemon used
     * to skip any source whose output already existed, and so a PDF deleted and delivered again under the
     * same name was never normalized a second time: its old output was still there. Now an existing output
     * means «done» on the {@code first} pass only (work from an earlier session, output newer than its
     * source); afterwards, a source that appears or changes is new work and its output is replaced.
     */
    private static void sweep(File root, File outRoot, Opts o, Map<String, String> sizes, Map<String, String> done,
                              ExecutorService pool, AtomicInteger ok, AtomicInteger ko, boolean first) {
        Set<String> present = new HashSet<>();
        for (Job j : plan(root, outRoot, o)) {
            String key = j.in().getPath();                                       // full path: names can repeat across sub-dirs
            present.add(key);
            long size = j.in().length();
            String sig = size + ":" + j.in().lastModified();
            if (sig.equals(done.get(key))) continue;                             // this very version was handled
            if (first && j.out().exists() && j.out().lastModified() >= j.in().lastModified()) {
                done.put(key, sig); sizes.remove(key); continue;                 // normalized in an earlier session
            }
            if (size == 0L || !sig.equals(sizes.get(key))) { sizes.put(key, sig); continue; }  // still arriving → wait a pass
            sizes.remove(key);
            done.put(key, sig);
            pool.submit(() -> convertLogged(root, outRoot, j, o, ok, ko));
        }
        done.keySet().retainAll(present);                                         // forget vanished sources: a return is new work
        sizes.keySet().retainAll(present);
    }

    // ── shared bits ──────────────────────────────────────────────────────────────

    /** Every source PDF under {@code root} paired with its output path (mirrored under {@code outRoot}
     *  when that differs from {@code root}); our own {@code *-normalized.pdf} outputs are skipped. */
    private static List<Job> plan(File root, File outRoot, Opts o) {
        List<Job> jobs = new ArrayList<>();
        Path doneDir = o.done == null ? null : root.toPath().resolve(o.done).toAbsolutePath().normalize();
        for (File p : listPdfs(root, o.recursive)) {
            if (Jexter.isOutput(p.getName(), o.suffix)) continue;
            if (doneDir != null && p.toPath().toAbsolutePath().normalize().startsWith(doneDir)) continue;   // already done: never re-read
            jobs.add(new Job(p, outFor(root, outRoot, p, o.suffix)));
        }
        return jobs;
    }

    private static void convertLogged(File root, File outRoot, Job j, Opts o, AtomicInteger ok, AtomicInteger ko) {
        try {
            OCDDocument d = Jexter.normalize(j.in(), j.out(), o.map, o.selectable);
            System.out.println("OK   " + rel(root, j.in()) + " -> " + rel(outRoot, j.out()) + "  (" + d.pageCount() + " pages)"
                    + moveToDone(root, j.in(), o.done));
            ok.incrementAndGet();
        } catch (Exception e) {
            System.out.println("ERR  " + rel(root, j.in()) + "  :  " + e.getMessage());
            ko.incrementAndGet();
        }
    }

    /** With {@code --done}: move a converted source to {@code <root>/<doneName>/<its relative path>} — an
     *  absolute {@code doneName} replaces {@code <root>/<doneName>} ({@link Path#resolve} semantics),
     *  replacing a same-named file there (a re-delivery). Only after its output was written — a failure
     *  never reaches here and leaves the source in the inbox. Returns the log suffix. */
    private static String moveToDone(File root, File src, String doneName) {
        if (doneName == null) return "";
        try {
            Path rel = root.toPath().relativize(src.toPath());
            Path target = root.toPath().resolve(doneName).resolve(rel);
            Files.createDirectories(target.getParent());
            Files.move(src.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "  [moved to " + doneName + "]";
        } catch (IOException e) {
            return "  [left in place: " + e.getMessage() + "]";
        }
    }

    /** Output path for a source PDF: next to the source, or mirrored under a separate {@code outRoot}. */
    private static File outFor(File root, File outRoot, File pdf, String suffix) {
        String name = Jexter.outName(pdf.getName(), suffix);
        if (outRoot.equals(root)) return new File(pdf.getParentFile(), name);    // in place (recursive: in its own sub-dir)
        Path relDir = root.toPath().relativize(pdf.getParentFile().toPath());    // mirror the source sub-tree
        return new File(new File(outRoot, relDir.toString()), name);
    }

    /** Every {@code *.pdf} under {@code root} (recursively when asked), sorted for stable ordering. */
    private static List<File> listPdfs(File root, boolean recursive) {
        List<File> out = new ArrayList<>();
        if (recursive) {
            try (Stream<Path> w = Files.walk(root.toPath())) {
                w.filter(Files::isRegularFile)
                 .filter(q -> q.getFileName().toString().toLowerCase().endsWith(".pdf"))
                 .forEach(q -> out.add(q.toFile()));
            } catch (IOException e) { /* tree changed mid-walk — next sweep retries */ }
        } else {
            File[] fs = root.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
            if (fs != null) out.addAll(Arrays.asList(fs));
        }
        out.sort(Comparator.comparing(File::getPath));
        return out;
    }

    /** True when the outputs would land beside their sources: no output given, or the input folder itself. */
    private static boolean sameFolder(Opts o) {
        if (o.out == null) return true;
        File inDir = o.in.isDirectory() ? o.in : o.in.getAbsoluteFile().getParentFile();
        File outDir = o.in.isDirectory() || o.out.isDirectory() ? o.out : o.out.getAbsoluteFile().getParentFile();
        if (!o.in.isDirectory() && !o.out.isDirectory()) return o.in.getAbsoluteFile().equals(o.out.getAbsoluteFile());
        return inDir.getAbsoluteFile().toPath().normalize().equals(outDir.getAbsoluteFile().toPath().normalize());
    }

    private static void ensureDir(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
    }
    private static String rel(File base, File f) {
        try { return base.toPath().relativize(f.toPath()).toString(); } catch (Exception e) { return f.getName(); }
    }
    private static void usage() {
        System.err.println("usage: Jexter <in.pdf|folder> [out] [--recursive] [--threads=<n>] [--outline] [--suffix=<s>] [--<option>=<value>]");
        System.err.println("       --threads defaults to half the cores (max " + MAX_LANES + "); one document per thread. Higher is allowed.");
        System.err.println("       Jexter --hotfolder <dir> [outDir] [--recursive] [--threads=<n>] [--interval=<sec>] [--done[=<name>]] [--<option>=<value>]");
        System.err.println("       --done moves each converted source into <dir>/Done; --done=<name> into <dir>/<name>; --done=<full path> into that folder");
        System.err.println("       --suffix=<s> names outputs <name><s>.pdf (default -normalized); --suffix= keeps the name (use a separate out folder)");
    }

    // ── one parser for every headless mode ───────────────────────────────────────
    /** Documents converted at once by default. Three is the house recommendation: each in-flight
     *  conversion holds a whole document model, so the ceiling is the heap and not the core count,
     *  and three keeps a batch fast without letting a handful of large books exhaust it. */
    static final int MAX_LANES = 3;

    private static final class Opts {
        File in, out;
        boolean hot, recursive, selectable = true;
        String done;                                    // --done[=<name|path>]: where processed sources go (a name is a sub-folder of the input); null = off
        String suffix = Jexter.SUFFIX;                  // --suffix=<s>: outputs are <name><s>.pdf
        /** Documents converted AT ONCE. The engine is single-threaded WITHIN a document on purpose
         *  (PDFBox's PDDocument is not thread-safe for concurrent page access) — the parallel axis is
         *  the document. Defaulting to 1 left a batch on one core unless the caller knew about the
         *  flag; defaulting to every core risks an OutOfMemoryError, since each in-flight conversion
         *  holds a full model (~1.1 KB per node: ~157 MB for a 600-page book). Half the cores, capped
         *  at {@link #MAX_LANES}, uses the machine without betting the heap. {@code --threads=} is a
         *  deliberate expert override and is NOT capped — a server with the heap for it may go higher. */
        int threads = Math.max(1, Math.min(MAX_LANES, Runtime.getRuntime().availableProcessors() / 2));
        long intervalMs = 2000L;
        final Map<String, String> map = new LinkedHashMap<>();

        static Opts parse(String[] args) {
            Opts o = new Opts();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if      (a.equals("--hotfolder"))                  o.hot = true;
                else if (a.equals("--recursive") || a.equals("-r")) o.recursive = true;
                else if (a.equals("--outline"))                    o.selectable = false;
                else if (a.equals("--selectable"))                 o.selectable = true;
                else if (a.equals("--done"))                       o.done = "Done";
                else if (a.startsWith("--done="))                  o.done = a.substring(7).isBlank() ? "Done" : a.substring(7);
                else if (a.startsWith("--suffix="))                o.suffix = Jexter.suffix(a.substring(9));
                else if (a.equals("--port") || a.equals("--web"))  i++;                       // window flags carry a value
                else if (a.startsWith("--threads="))  o.threads    = Math.max(1, Integer.parseInt(a.substring(10)));
                else if (a.startsWith("--interval=")) o.intervalMs = Math.max(500L, (long) (Double.parseDouble(a.substring(11)) * 1000));
                else if (a.startsWith("--")) { int eq = a.indexOf('='); if (eq > 2) o.map.put(a.substring(2, eq), a.substring(eq + 1)); }
                else if (o.in == null)  o.in  = new File(a);
                else if (o.out == null) o.out = new File(a);
            }
            return o;
        }
    }
}
