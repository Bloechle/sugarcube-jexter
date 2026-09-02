package sugarcube.jexter.write;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.convert.ConvertOptions.Group;
import sugarcube.jexter.convert.ConvertOptions.Opt;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single drop-a-document launcher for every projection, backed entirely by {@link Conversion}:
 * pick a target, tweak the export options (read generically from {@link ConvertOptions}'s
 * {@link Group#EXPORT} section — no per-target UI code), drop a PDF (or an OCD-EPUB) and the
 * artifact is written beside it. There is one window for all targets; the CLI lives in
 * {@link Conversion#main}.
 */
final class WriterCli {

    private WriterCli() {}

    /** Open the launcher (no-arg entry from {@link Conversion#main}). Headless ⇒ a hint to stderr. */
    static void launch() {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("no display; use: <in.pdf> <out.ext> [--to=" + String.join("|", Conversion.targets()) + "]");
            return;
        }
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignore) {}
        SwingUtilities.invokeLater(() -> new Launcher().setVisible(true));
    }

    // palette — zinc neutrals + indigo accent
    private static final Color BG = new Color(0xFAFAFB), CARD = Color.WHITE, INK = new Color(0x18181B),
            MUTED = new Color(0x71717A), BORDER = new Color(0xD4D4D8), ACCENT = new Color(0x6366F1),
            ZONE = new Color(0xF7F7F8), OK = new Color(0x16A34A), ERR = new Color(0xDC2626);

    @SuppressWarnings("serial")   // a Swing frame, never serialized
    private static final class Launcher extends JFrame {
        private final JComboBox<String> target = new JComboBox<>(Conversion.targets().toArray(new String[0]));
        private final Map<String, JComponent> controls = new LinkedHashMap<>();
        private final JLabel status = new JLabel("Drop a PDF or .ocd.epub to convert", SwingConstants.CENTER);
        private File input;

        Launcher() {
            super("jexter — convert");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(440, 460);
            setLocationRelativeTo(null);
            getContentPane().setBackground(BG);
            ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            setLayout(new BorderLayout(0, 14));

            add(dropZone(), BorderLayout.NORTH);
            add(form(), BorderLayout.CENTER);
            add(footer(), BorderLayout.SOUTH);
        }

        private JComponent dropZone() {
            JPanel zone = new JPanel(new GridBagLayout());
            zone.setBackground(ZONE);
            zone.setBorder(BorderFactory.createDashedBorder(BORDER, 2, 6, 4, true));
            zone.setPreferredSize(new Dimension(0, 110));
            JLabel hint = new JLabel("⬇  drop a PDF / .ocd.epub here  ·  or click to browse");
            hint.setForeground(MUTED);
            zone.add(hint);
            zone.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            zone.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { browse(); }
            });
            new DropTarget(zone, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
                @Override public void drop(DropTargetDropEvent e) {
                    try {
                        e.acceptDrop(DnDConstants.ACTION_COPY);
                        @SuppressWarnings("unchecked")
                        List<File> fs = (List<File>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                        if (!fs.isEmpty()) pick(fs.get(0));
                    } catch (Exception ex) { fail(ex); }
                }
            });
            return zone;
        }

        private JComponent form() {
            JPanel card = new JPanel(new GridBagLayout());
            card.setBackground(CARD);
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(14, 14, 14, 14)));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(5, 5, 5, 5);
            g.anchor = GridBagConstraints.WEST;
            g.fill = GridBagConstraints.HORIZONTAL;
            int row = 0;

            g.gridx = 0; g.gridy = row; g.weightx = 0; card.add(label("Target"), g);
            g.gridx = 1; g.weightx = 1; card.add(target, g);
            row++;

            // generic controls for the EXPORT options — driven entirely by ConvertOptions.ALL
            for (Opt<?> o : ConvertOptions.ALL) {
                if (o.group() != Group.EXPORT) continue;
                g.gridx = 0; g.gridy = row; g.weightx = 0; card.add(label(o.label()), g);
                JComponent ctl = control(o);
                controls.put(o.key(), ctl);
                g.gridx = 1; g.weightx = 1; card.add(ctl, g);
                row++;
            }
            return card;
        }

        private JComponent footer() {
            JButton convert = new JButton("Convert");
            convert.setBackground(ACCENT);
            convert.setForeground(Color.WHITE);
            convert.setFocusPainted(false);
            convert.setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
            convert.addActionListener(e -> convert());
            status.setForeground(MUTED);
            status.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.setBackground(BG);
            p.add(convert, BorderLayout.NORTH);
            p.add(status, BorderLayout.SOUTH);
            return p;
        }

        private static JComponent control(Opt<?> o) {
            return switch (o.type()) {
                case BOOL -> { JCheckBox c = new JCheckBox(); c.setSelected(Boolean.TRUE.equals(o.def())); c.setBackground(CARD); yield c; }
                case INT  -> new JSpinner(new SpinnerNumberModel(((Number) o.def()).intValue(), 0, 100000, 1));
                case DOUBLE -> new JSpinner(new SpinnerNumberModel(((Number) o.def()).doubleValue(), 0d, 100000d, 0.1));
                case STRING -> new JTextField(String.valueOf(o.def()), 12);
            };
        }

        private Map<String, String> values() {
            Map<String, String> m = new LinkedHashMap<>();
            controls.forEach((k, c) -> {
                if (c instanceof JCheckBox cb)      m.put(k, String.valueOf(cb.isSelected()));
                else if (c instanceof JSpinner sp)  m.put(k, String.valueOf(sp.getValue()));
                else if (c instanceof JTextField tf) m.put(k, tf.getText());
            });
            return m;
        }

        private void browse() {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("PDF or OCD-EPUB", "pdf", "epub"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) pick(fc.getSelectedFile());
        }

        private void pick(File f) { input = f; status.setForeground(MUTED); status.setText("Selected: " + f.getName()); }

        private void convert() {
            if (input == null) { browse(); if (input == null) return; }
            String t = (String) target.getSelectedItem();
            File in = input;
            status.setForeground(MUTED); status.setText("Converting…");
            new SwingWorker<Path, Void>() {
                @Override protected Path doInBackground() throws Exception {
                    ConvertOptions opt = ConvertOptions.fromMap(values());
                    var doc = Conversion.load(in, opt);
                    Conversion.Output o = Conversion.convert(doc, t, opt);
                    Path out = beside(in, o.filename());
                    Files.write(out, o.bytes());
                    return out;
                }
                @Override protected void done() {
                    try { Path out = get(); status.setForeground(OK); status.setText("✓  " + out.getFileName()); }
                    catch (Exception ex) { fail(ex); }
                }
            }.execute();
        }

        private void fail(Exception ex) {
            Throwable c = (ex.getCause() != null) ? ex.getCause() : ex;
            status.setForeground(ERR);
            status.setText("✗  " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }

        /** Output beside the input: its base name + the target's default extension/suffix. */
        private static Path beside(File in, String targetFilename) {
            String name = in.getName();
            int dot = name.lastIndexOf('.');
            String base = (dot > 0) ? name.substring(0, dot) : name;
            // targetFilename is "document.ext" / "document-reflow.epub" / "page-N.svg"
            String suffix = targetFilename.startsWith("document")
                    ? targetFilename.substring("document".length())   // ".epub", "-reflow.epub", ".doctags.txt"
                    : "-" + targetFilename;                            // "page-N.svg" → base-page-N.svg
            return new File(in.getAbsoluteFile().getParentFile(), base + suffix).toPath();
        }

        private static JLabel label(String s) { JLabel l = new JLabel(s); l.setForeground(INK); return l; }
    }
}
