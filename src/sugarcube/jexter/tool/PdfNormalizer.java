package sugarcube.jexter.tool;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.convert.PdfImporter;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.write.Conversion;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PDF → OCD → PDF: regenerates a <em>normalized</em> PDF through jexter's canonical OCD model.
 *
 * <p>Whatever the source's authoring quirks, the output inherits every normalization the pipeline
 * performs: repaired embedded fonts ({@code post}-table fix), per-glyph self-clips dropped, a single
 * regular content structure, consistent color/transform handling. Two text strategies:
 * a selectable embedded-font layer (default) or outlined glyphs.
 *
 * <p>Runnable as a CLI (arguments) or, with no arguments, as a small Swing GUI that normalizes a
 * single file or a whole folder (batch). The GUI renders the {@link ConvertOptions} registry
 * generically, so new options surface with no extra UI code.
 */
public final class PdfNormalizer {

    private PdfNormalizer() {}

    /**
     * @param opts       conversion settings (see {@link ConvertOptions})
     * @param selectable {@code true} → embedded-font text layer (searchable); {@code false} → outlined glyphs
     */
    public static OCDDocument normalize(File in, File out, ConvertOptions opts, boolean selectable) throws IOException {
        OCDDocument doc = PdfImporter.convert(in, opts);
        Map<String, String> m = new LinkedHashMap<>();
        opts.toMap().forEach((k, v) -> m.put(k, String.valueOf(v)));   // carry the import/export settings
        m.put("selectable", String.valueOf(selectable));
        Files.write(out.toPath(), Conversion.convert(doc, "pdf", m).bytes());
        return doc;
    }

    public static OCDDocument normalize(File in, File out) throws IOException {
        return normalize(in, out, ConvertOptions.defaults(), true);
    }

    // ── CLI ─────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {                         // no arguments → GUI (or usage when headless)
            if (GraphicsEnvironment.isHeadless()) { usage(System.err); System.exit(2); }
            else SwingUtilities.invokeLater(PdfNormalizer::showGui);
            return;
        }

        File in = null, out = null;
        boolean selectable = true;
        Map<String, String> opt = new LinkedHashMap<>();
        for (String a : args) {
            if (a.equals("--outline")) selectable = false;
            else if (a.equals("--selectable")) selectable = true;
            else if (a.startsWith("--")) {                       // --<convertOption>=<value>
                int eq = a.indexOf('=');
                if (eq > 2) opt.put(a.substring(2, eq), a.substring(eq + 1));
            } else if (in == null) in = new File(a);
            else if (out == null) out = new File(a);
        }
        if (in == null) { usage(System.err); System.exit(2); }
        if (out == null) out = defaultOut(in);
        OCDDocument doc = normalize(in, out, ConvertOptions.fromMap(opt), selectable);
        System.out.println("normalized " + in.getName() + " -> " + out.getName()
                + (selectable ? " (selectable)" : " (outline)") + "  " + doc);
    }

    private static void usage(PrintStream e) {
        e.println("usage: PdfNormalizer [<in.pdf> [out.pdf]] [--outline] [--<option>=<value>]");
        e.println("  (no arguments)        launch the GUI (single file or batch folder)");
        e.println("  --outline             outline glyphs instead of a selectable text layer");
        for (ConvertOptions.Opt<?> o : ConvertOptions.ALL)
            e.printf("  --%s=<%s>   (def %s)  %s%n",
                    o.key(), o.type().name().toLowerCase(), o.def(), o.label());
    }

    /** {@code foo.pdf} → {@code foo-normalized.pdf} next to the source. */
    private static File defaultOut(File in) {
        return new File(in.getAbsoluteFile().getParentFile(), baseName(in) + "-normalized.pdf");
    }

    private static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // ── GUI ───────────────────────────────────────────────────────────────────
    private static void showGui() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignore) {}

        JFrame f = new JFrame("Jexter — PDF Normalizer");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // mode: single file vs folder (batch)
        JRadioButton single = new JRadioButton("Single file", true);
        JRadioButton batch  = new JRadioButton("Folder (batch)");
        ButtonGroup modeGroup = new ButtonGroup(); modeGroup.add(single); modeGroup.add(batch);

        JTextField inField  = new JTextField(36);
        JTextField outField = new JTextField(36);
        JButton inBrowse  = new JButton("Browse…");
        JButton outBrowse = new JButton("Browse…");

        // text strategy
        JRadioButton selectable = new JRadioButton("Selectable text layer", true);
        JRadioButton outline    = new JRadioButton("Outline glyphs");
        ButtonGroup stratGroup = new ButtonGroup(); stratGroup.add(selectable); stratGroup.add(outline);

        // conversion options — rendered generically from ConvertOptions.ALL
        Map<ConvertOptions.Opt<?>, JComponent> ctrls = new LinkedHashMap<>();
        JPanel optsBox = new JPanel();
        optsBox.setLayout(new BoxLayout(optsBox, BoxLayout.Y_AXIS));
        optsBox.setBorder(BorderFactory.createTitledBorder("Conversion options"));
        for (ConvertOptions.Opt<?> o : ConvertOptions.ALL) {
            if (o.type() == ConvertOptions.Type.BOOL) {
                JCheckBox cb = new JCheckBox(o.label(), Boolean.TRUE.equals(o.def()));
                cb.setToolTipText(o.help()); cb.setAlignmentX(Component.LEFT_ALIGNMENT);
                optsBox.add(cb); ctrls.put(o, cb);
            } else {
                JPanel row = leftRow(new JLabel(o.label() + ": "));
                JTextField tf = new JTextField(String.valueOf(o.def()), 12);
                tf.setToolTipText(o.help()); row.add(tf);
                optsBox.add(row); ctrls.put(o, tf);
            }
        }

        JTextArea log = new JTextArea(11, 56);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton run = new JButton("Normalize");

        // ── browse actions ─────────────────────────────────────────────────────
        inBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (batch.isSelected()) fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            else { fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
                fc.setFileFilter(new FileNameExtensionFilter("PDF files", "pdf")); }
            if (!inField.getText().isBlank()) fc.setSelectedFile(new File(inField.getText().trim()));
            if (fc.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) {
                File sel = fc.getSelectedFile();
                inField.setText(sel.getAbsolutePath());
                outField.setText(batch.isSelected() ? sel.getAbsolutePath()
                        : defaultOut(sel).getAbsolutePath());
            }
        });
        outBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(batch.isSelected() ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            if (!outField.getText().isBlank()) fc.setSelectedFile(new File(outField.getText().trim()));
            if (fc.showSaveDialog(f) == JFileChooser.APPROVE_OPTION)
                outField.setText(fc.getSelectedFile().getAbsolutePath());
        });

        // keep the output hint sensible when the mode flips
        Runnable onMode = () -> {
            String in = inField.getText().trim();
            if (in.isBlank()) return;
            File src = new File(in);
            outField.setText(batch.isSelected() ? (src.isDirectory() ? src.getAbsolutePath() : "")
                    : defaultOut(src).getAbsolutePath());
        };
        single.addActionListener(e -> onMode.run());
        batch.addActionListener(e -> onMode.run());

        // ── run (background thread; never blocks the EDT) ───────────────────────
        run.addActionListener(e -> {
            File in = new File(inField.getText().trim());
            String outTxt = outField.getText().trim();
            boolean isBatch = batch.isSelected();
            boolean sel = selectable.isSelected();
            if (inField.getText().isBlank() || !in.exists()) { append(log, "! choose a valid input " + (isBatch ? "folder" : "file")); return; }

            Map<String, String> om = new LinkedHashMap<>();
            for (var en : ctrls.entrySet()) {
                JComponent c = en.getValue();
                om.put(en.getKey().key(), c instanceof JCheckBox cb ? String.valueOf(cb.isSelected())
                        : ((JTextField) c).getText().trim());
            }
            ConvertOptions opts = ConvertOptions.fromMap(om);

            run.setEnabled(false);
            append(log, "— " + (sel ? "selectable" : "outline") + " —");
            new Thread(() -> {
                try {
                    if (isBatch) {
                        File outDir = outTxt.isBlank() ? in : new File(outTxt);
                        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("cannot create " + outDir);
                        File[] pdfs = in.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
                        if (pdfs == null || pdfs.length == 0) { append(log, "no PDF files in " + in); return; }
                        Arrays.sort(pdfs);
                        int ok = 0, ko = 0;
                        for (File p : pdfs) {
                            File out = new File(outDir, baseName(p) + "-normalized.pdf");
                            try { OCDDocument d = normalize(p, out, opts, sel);
                                append(log, "OK   " + p.getName() + "  ->  " + out.getName() + "   (" + d.pageCount() + " pages)"); ok++; }
                            catch (Exception ex) { append(log, "ERR  " + p.getName() + "  :  " + ex.getMessage()); ko++; }
                        }
                        append(log, "done — " + ok + " ok, " + ko + " failed");
                    } else {
                        File out = outTxt.isBlank() ? defaultOut(in) : new File(outTxt);
                        OCDDocument d = normalize(in, out, opts, sel);
                        append(log, "OK   " + in.getName() + "  ->  " + out.getName() + "   (" + d.pageCount() + " pages)");
                    }
                } catch (Exception ex) {
                    append(log, "ERR  " + ex);
                } finally {
                    SwingUtilities.invokeLater(() -> run.setEnabled(true));
                }
            }, "normalize").start();
        });

        // ── drag & drop a file or folder anywhere on the window ─────────────────
        java.util.function.Consumer<File> useInput = g -> {
            if (g == null) return;
            if (g.isDirectory()) {
                batch.setSelected(true);
                inField.setText(g.getAbsolutePath());
                outField.setText(g.getAbsolutePath());
                append(log, "input: " + g.getName() + "  (folder)");
            } else if (g.getName().toLowerCase().endsWith(".pdf")) {
                single.setSelected(true);
                inField.setText(g.getAbsolutePath());
                outField.setText(defaultOut(g).getAbsolutePath());
                append(log, "input: " + g.getName());
            } else {
                append(log, "! drop a PDF file or a folder (got " + g.getName() + ")");
            }
        };
        TransferHandler dnd = new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || s.isDataFlavorSupported(DataFlavor.stringFlavor);
            }
            @Override @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    Transferable t = s.getTransferable();
                    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        var files = (java.util.List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                        if (files == null || files.isEmpty()) return false;
                        useInput.accept(files.get(0));
                        return true;
                    }
                    if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {       // keep text paste/drop working in fields
                        String str = ((String) t.getTransferData(DataFlavor.stringFlavor)).trim();
                        if (s.getComponent() instanceof JTextField tf) { tf.replaceSelection(str); return true; }
                        File g = new File(str);
                        if (g.exists()) { useInput.accept(g); return true; }
                    }
                } catch (Exception ex) { append(log, "! drop failed: " + ex.getMessage()); }
                return false;
            }
        };

        // ── layout ──────────────────────────────────────────────────────────────
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        form.add(labeled("Mode", flow(single, batch)));
        form.add(Box.createVerticalStrut(6));
        form.add(labeled("Input", flow(inField, inBrowse)));
        form.add(Box.createVerticalStrut(4));
        form.add(labeled("Output", flow(outField, outBrowse)));
        form.add(Box.createVerticalStrut(6));
        form.add(labeled("Text", flow(selectable, outline)));
        form.add(Box.createVerticalStrut(8));
        optsBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(optsBox);
        JLabel hint = new JLabel("Tip — drag a PDF or a folder onto this window");
        hint.setForeground(Color.GRAY);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 2));
        form.add(hint);

        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        south.add(run, BorderLayout.EAST);

        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));
        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        center.add(logScroll, BorderLayout.CENTER);

        // accept drops across the whole window surface (and the text fields)
        for (JComponent c : new JComponent[]{ f.getRootPane(), form, center, south, optsBox, inField, outField, log })
            c.setTransferHandler(dnd);

        f.setLayout(new BorderLayout());
        f.add(form, BorderLayout.NORTH);
        f.add(center, BorderLayout.CENTER);
        f.add(south, BorderLayout.SOUTH);
        f.pack();
        f.setMinimumSize(f.getSize());
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    // ── tiny Swing helpers ──────────────────────────────────────────────────────
    private static JPanel flow(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (Component c : cs) p.add(c);
        return p;
    }

    private static JPanel leftRow(Component... cs) {
        JPanel p = flow(cs);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JComponent labeled(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(56, l.getPreferredSize().height));
        p.add(l, BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height + 4));
        return p;
    }

    private static void append(JTextArea log, String line) {
        SwingUtilities.invokeLater(() -> { log.append(line + "\n"); log.setCaretPosition(log.getDocument().getLength()); });
    }
}