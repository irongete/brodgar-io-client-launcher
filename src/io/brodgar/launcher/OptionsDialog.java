package io.brodgar.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Modal dialog over the settings {@link Launcher#command} and {@link Settings#clientConfig} use, each with the
 * flag or key it sets, plus a live preview of the command. OK writes all of them to
 * <code>launcher.properties</code>; Cancel writes nothing. Fixed flags (exports, native access, Unsafe) are not
 * shown.
 */
final class OptionsDialog {
    private OptionsDialog() {}

    /** Heap slider: <code>MIN_GB</code> to <code>max(MIN_GB + 1, min(CAP_GB, totalGb / 2))</code>. */
    static final int MIN_GB = 1, CAP_GB = 16;

    /** Show modally; returns on close. <code>java</code> is the executable the preview starts with. No owner: the
     *  main window is JavaFX, which Swing's modality does not reach ({@link Ui#swingDialog}). */
    static void show(JFrame owner, Settings settings, Path java) {
        JDialog d = new JDialog(owner, "Options", true);
        d.setIconImage(Launcher.icon());

        long totalGb = totalMemoryGb();
        JSlider heap = heapSlider(settings.heap(), totalGb);
        JPanel heapRow = heapRow(heap);
        JCheckBox pretouch = new JCheckBox("Reserve it all at start", settings.pretouch());
        JComboBox<String> gc = new JComboBox<>(new String[] {"Concurrent (ZGC): frees memory while the game keeps running",
                                                              "Standard (G1): frees memory in short stops"});
        gc.setSelectedIndex(settings.gc().equals("g1") ? 1 : 0);
        JCheckBox uiScale = new JCheckBox("Let Windows scale the game window", settings.uiScale());
        JComboBox<String> ipv6 = new JComboBox<>(new String[] {"As Windows prefers", "IPv4 first", "IPv6 first"});
        ipv6.setSelectedIndex(switch(settings.ipv6()) { case "false" -> 1; case "true" -> 2; default -> 0; });
        JCheckBox pack = new JCheckBox("Download the brodgar.io resource pack", settings.resourcePack());
        JCheckBox proxy = new JCheckBox("Use brodgar.io resource cache", settings.resourceProxy());
        JCheckBox sqlite = new JCheckBox("Use the SQLite store", settings.store().equals("sqlite"));
        JTextField opts = new JTextField(settings.javaOptsText(), 30);
        JTextField proxyUrl = new JTextField(settings.resourceProxyUrl(), 30);
        proxyUrl.setEnabled(proxy.isSelected());
        proxy.addActionListener(ev -> proxyUrl.setEnabled(proxy.isSelected()));
        JCheckBox addonsOverride = new JCheckBox("Override addons folder", settings.addonsOverride());
        JTextField addonsDir = new JTextField(settings.addonsDir(), 30);
        addonsDir.setEnabled(addonsOverride.isSelected());
        addonsOverride.addActionListener(ev -> addonsDir.setEnabled(addonsOverride.isSelected()));
        JCheckBox savedataOverride = new JCheckBox("Override savedata folder", settings.savedataOverride());
        JTextField savedataDir = new JTextField(settings.savedataDir(), 30);
        savedataDir.setEnabled(savedataOverride.isSelected());
        savedataOverride.addActionListener(ev -> savedataDir.setEnabled(savedataOverride.isSelected()));
        JCheckBox updates = new JCheckBox("Look for a newer launcher and game when the launcher opens", settings.checkUpdates());

        JTextArea preview = new JTextArea(5, 64);
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, preview.getFont().getSize() - 2)));

        Runnable refresh = () -> {
            Settings.Launch l = new Settings.Launch(heap.getValue() + "g", pretouch.isSelected(),
                (gc.getSelectedIndex() == 1) ? "g1" : "zgc", uiScale.isSelected(),
                switch(ipv6.getSelectedIndex()) { case 1 -> "false"; case 2 -> "true"; default -> "system"; },
                sqlite.isSelected() ? "sqlite" : "files", Settings.split(opts.getText()));
            preview.setText(String.join(" ", Launcher.command(java, l)));
            preview.setCaretPosition(0);
        };
        onChange(opts, refresh);
        heap.addChangeListener(ev -> refresh.run());
        pretouch.addActionListener(ev -> refresh.run());
        gc.addActionListener(ev -> refresh.run());
        uiScale.addActionListener(ev -> refresh.run());
        ipv6.addActionListener(ev -> refresh.run());
        sqlite.addActionListener(ev -> refresh.run());
        refresh.run();

        JPanel form = new JPanel(new GridBagLayout());
        Row r = new Row(form);
        r.add("Game memory", heapRow,
              "-Xms/-Xmx" + ((totalGb > 0) ? " (installed: " + totalGb + " GB)" : ""));
        r.add(null, pretouch,
              "-XX:+AlwaysPreTouch");
        r.add("Memory cleanup", gc,
              "-XX:+UseZGC, or G1 (the JVM default)");
        r.add(null, uiScale,
              "Off: -Dsun.java2d.uiScale.enabled=false");
        r.add("Network addresses", ipv6,
              "-Djava.net.preferIPv6Addresses=system|false|true");
        r.add(null, pack,
              "Download every game resource in one pack (about 250 MB, once) instead of one by one as the game needs them");
        r.add(null, proxy,
              "Fetch the resource files from the brodgar.io cache instead of the Haven server: about x2.5 faster");
        r.add(null, sqlite,
              "Keep the map and the resource files in SQLite: map reads about x10 faster");
        r.add("Extra Java options", opts,
              "Appended to the JVM command line, space-separated");
        r.add("Resource cache address", proxyUrl,
              "haven.resurl in client/haven-config.properties while the resource cache is on");
        r.labelled(addonsOverride, addonsDir,
              "haven.addondir in client/haven-config.properties; off: client/addons");
        r.labelled(savedataOverride, savedataDir,
              "haven.savedatadir in client/haven-config.properties; off: client/savedata");
        r.add(null, updates,
              "check.updates: GitHub release lookup for the launcher and the client at start");
        r.preview("The game will be started as:", new JScrollPane(preview));

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(ev -> {
            settings.set("heap", heap.getValue() + "g");
            settings.set("heap.pretouch", String.valueOf(pretouch.isSelected()));
            settings.set("gc", (gc.getSelectedIndex() == 1) ? "g1" : "zgc");
            settings.set("ui.scale", String.valueOf(uiScale.isSelected()));
            settings.set("ipv6", switch(ipv6.getSelectedIndex()) { case 1 -> "false"; case 2 -> "true"; default -> "system"; });
            settings.set("resource.pack", String.valueOf(pack.isSelected()));
            settings.set("resource.proxy", String.valueOf(proxy.isSelected()));
            settings.set("store", sqlite.isSelected() ? "sqlite" : "files");
            settings.set("java.opts", opts.getText().trim());
            settings.set("resource.proxy.url", proxyUrl.getText().trim());
            settings.set("addons.override", String.valueOf(addonsOverride.isSelected()));
            settings.set("addons.dir", addonsDir.getText().trim());
            settings.set("savedata.override", String.valueOf(savedataOverride.isSelected()));
            settings.set("savedata.dir", savedataDir.getText().trim());
            settings.set("check.updates", String.valueOf(updates.isSelected()));
            d.dispose();
        });
        cancel.addActionListener(ev -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(ok);
        buttons.add(cancel);
        buttons.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        panel.add(form, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(panel);
        d.getRootPane().setDefaultButton(ok);
        d.pack();
        d.setMinimumSize(new Dimension(680, d.getHeight()));
        d.setResizable(false);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    /** The heap slider: {@link #MIN_GB} to <code>max(MIN_GB + 1, min(CAP_GB, totalGb / 2))</code>, 8 when the
     *  memory is unknown, at <code>heap</code>'s value clamped into that range. Also the setup's
     *  ({@link FirstRunDialog}). */
    static JSlider heapSlider(String heap, long totalGb) {
        int maxGb = (totalGb <= 0) ? 8 : (int)Math.max(MIN_GB + 1, Math.min(CAP_GB, totalGb / 2));
        JSlider s = new JSlider(MIN_GB, maxGb, Math.max(MIN_GB, Math.min(maxGb, heapGb(heap))));
        int step = (maxGb - MIN_GB > 8) ? 2 : 1;             // label every GB up to 9 labels, else every 2
        s.setMajorTickSpacing(step);
        s.setMinorTickSpacing(1);
        s.setLabelTable(s.createStandardLabels(step, (step == 1) ? MIN_GB : 2));   // even labels at step 2
        s.setPaintTicks(true);
        s.setPaintLabels(true);
        s.setSnapToTicks(true);
        return s;
    }

    /** The slider with its value, <code>N GB</code> in bold, at its right, following it. */
    static JPanel heapRow(JSlider heap) {
        JLabel value = new JLabel(heap.getValue() + " GB");
        value.setFont(value.getFont().deriveFont(Font.BOLD));
        value.setPreferredSize(new Dimension(52, value.getPreferredSize().height));
        heap.addChangeListener(ev -> value.setText(heap.getValue() + " GB"));
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.add(heap, BorderLayout.CENTER);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    /** The system look and feel for a Swing window; the cross-platform one stays if it fails. Set before each
     *  Swing dialog, since the main window is JavaFX. */
    static void systemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch(Exception e) {
            // cross-platform look and feel then
        }
    }

    /** <code>Ng</code> → N; <code>Nm</code> → ceil(N / 1024); else <code>MIN_GB</code>. */
    static int heapGb(String value) {
        String v = value.trim().toLowerCase();
        if(v.matches("\\d+g"))
            return Integer.parseInt(v.substring(0, v.length() - 1));
        if(v.matches("\\d+m"))
            return (Integer.parseInt(v.substring(0, v.length() - 1)) + 1023) / 1024;
        return MIN_GB;
    }

    /** Physical memory in GB (rounded) via <code>com.sun.management.OperatingSystemMXBean</code>; 0 if
     *  unavailable. */
    static long totalMemoryGb() {
        try {
            if(ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
                return Math.round(os.getTotalMemorySize() / (1024.0 * 1024 * 1024));
        } catch(RuntimeException | LinkageError e) {
            // no jdk.management module
        }
        return 0;
    }

    /** GridBag rows: label (a name, or a checkbox that governs the control) + control, then a help line
     *  spanning both columns. */
    private static final class Row {
        private final JPanel form;
        private final GridBagConstraints c = new GridBagConstraints();
        private int y = 0;

        Row(JPanel form) {
            this.form = form;
            c.anchor = GridBagConstraints.WEST;
        }

        void add(String name, JComponent field, String what) {
            labelled((name == null) ? null : new JLabel(name), field, what);
        }

        /** The row with <code>label</code> in the first column: a name, or the checkbox that governs the control. */
        void labelled(JComponent label, JComponent field, String what) {
            c.insets = new Insets(8, 4, 0, 4);
            c.gridy = y++;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            if(label != null) {
                c.gridx = 0;
                c.gridwidth = 1;
                label.setFont(label.getFont().deriveFont(Font.BOLD));
                form.add(label, c);
                c.gridx = 1;
            } else {
                c.gridx = 0;
                c.gridwidth = 2;
                if(field instanceof JCheckBox b)
                    b.setFont(b.getFont().deriveFont(Font.BOLD));
            }
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            form.add(field, c);
            JLabel help = new JLabel("<html><div style='width:560px'>" + what + "</div></html>");
            help.setFont(help.getFont().deriveFont(Font.PLAIN, Math.max(10f, help.getFont().getSize() - 1)));
            Color fg = UIManager.getColor("Label.disabledForeground");
            help.setForeground((fg != null) ? fg : Color.GRAY);
            c.insets = new Insets(0, 4, 0, 4);
            c.gridx = 0;
            c.gridy = y++;
            c.gridwidth = 2;
            form.add(help, c);
        }

        void preview(String caption, JComponent area) {
            c.insets = new Insets(16, 4, 0, 4);
            c.gridx = 0;
            c.gridy = y++;
            c.gridwidth = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            form.add(new JLabel(caption), c);
            c.insets = new Insets(4, 4, 0, 4);
            c.gridy = y++;
            form.add(area, c);
        }
    }

    private static void onChange(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        });
    }
}
