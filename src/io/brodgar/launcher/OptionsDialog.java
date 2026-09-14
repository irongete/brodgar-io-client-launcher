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
 * The Options dialog: what shapes the way the game is started, each setting under a name a player can read
 * and a line saying what the parameter does — never what it did on one particular machine — over a live
 * preview of the command they make. OK writes them to <code>launcher.properties</code>; Cancel leaves
 * everything as it was. What every client needs to run at all (the module exports, native access, the
 * Unsafe allowance) is not on offer.
 */
final class OptionsDialog {
    private OptionsDialog() {}

    /** The bar's ends: 1 GB, and half of what the machine has — never past 16 GB, and never under 2, so that a
     *  small machine still gets a bar rather than a single value. */
    static final int MIN_GB = 1, CAP_GB = 16;

    /** Open the dialog over <code>owner</code>, modal; back when it is closed. <code>java</code> is the executable
     *  the preview's command starts with: the one Play would use. */
    static void show(JFrame owner, Settings settings, Path java) {
        JDialog d = new JDialog(owner, "Options", true);

        long totalGb = totalMemoryGb();
        int maxGb = (totalGb <= 0) ? 8 : (int)Math.max(MIN_GB + 1, Math.min(CAP_GB, totalGb / 2));
        JSlider heap = new JSlider(MIN_GB, maxGb, Math.max(MIN_GB, Math.min(maxGb, heapGb(settings.heap()))));
        int step = (maxGb - MIN_GB > 8) ? 2 : 1;             // one label per GB up to 9 of them, else every other
        heap.setMajorTickSpacing(step);
        heap.setMinorTickSpacing(1);
        heap.setLabelTable(heap.createStandardLabels(step, (step == 1) ? MIN_GB : 2));   // even numbers when every other
        heap.setPaintTicks(true);
        heap.setPaintLabels(true);
        heap.setSnapToTicks(true);
        JLabel heapValue = new JLabel(heap.getValue() + " GB");
        heapValue.setFont(heapValue.getFont().deriveFont(Font.BOLD));
        heapValue.setPreferredSize(new Dimension(52, heapValue.getPreferredSize().height));
        JPanel heapRow = new JPanel(new BorderLayout(8, 0));
        heapRow.add(heap, BorderLayout.CENTER);
        heapRow.add(heapValue, BorderLayout.EAST);
        JCheckBox pretouch = new JCheckBox("Reserve it all at start", settings.pretouch());
        JComboBox<String> gc = new JComboBox<>(new String[] {"Concurrent (ZGC): frees memory while the game keeps running",
                                                              "Standard (G1): frees memory in short stops"});
        gc.setSelectedIndex(settings.gc().equals("g1") ? 1 : 0);
        JCheckBox uiScale = new JCheckBox("Let Windows scale the game window", settings.uiScale());
        JComboBox<String> ipv6 = new JComboBox<>(new String[] {"As Windows prefers", "IPv4 first", "IPv6 first"});
        ipv6.setSelectedIndex(switch(settings.ipv6()) { case "false" -> 1; case "true" -> 2; default -> 0; });
        JTextField opts = new JTextField(settings.javaOptsText(), 30);
        JTextField proxyUrl = new JTextField(settings.resourceProxyUrl(), 30);
        JCheckBox updates = new JCheckBox("Look for a newer launcher and game when the launcher opens", settings.checkUpdates());

        JTextArea preview = new JTextArea(5, 64);
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, preview.getFont().getSize() - 2)));

        Runnable refresh = () -> {
            heapValue.setText(heap.getValue() + " GB");
            Settings.Launch l = new Settings.Launch(heap.getValue() + "g", pretouch.isSelected(),
                (gc.getSelectedIndex() == 1) ? "g1" : "zgc", uiScale.isSelected(),
                switch(ipv6.getSelectedIndex()) { case 1 -> "false"; case 2 -> "true"; default -> "system"; },
                Settings.split(opts.getText()), settings.resourceProxy(), proxyUrl.getText().trim());
            preview.setText(String.join(" ", Launcher.command(java, l)));
            preview.setCaretPosition(0);
        };
        onChange(opts, refresh);
        onChange(proxyUrl, refresh);
        heap.addChangeListener(ev -> refresh.run());
        pretouch.addActionListener(ev -> refresh.run());
        gc.addActionListener(ev -> refresh.run());
        uiScale.addActionListener(ev -> refresh.run());
        ipv6.addActionListener(ev -> refresh.run());
        refresh.run();

        JPanel form = new JPanel(new GridBagLayout());
        Row r = new Row(form);
        r.add("Game memory", heapRow,
              "How much memory the game is given; it is set aside when the game starts. "
              + ((totalGb > 0) ? "Your PC has " + totalGb + " GB, and the bar stops at half of it." : "The bar stops at " + maxGb + " GB."));
        r.add(null, pretouch,
              "Take all of that memory the moment the game starts, rather than piece by piece as it is first used.");
        r.add("Memory cleanup", gc,
              "How the game frees the memory it no longer needs: while it keeps running, or in short stops.");
        r.add(null, uiScale,
              "On a high-DPI screen, let Windows enlarge the whole window. Off, the game draws at 1:1 and scales its own interface (see its options).");
        r.add("Network addresses", ipv6,
              "Which kind of address to try first when a server has both an IPv4 and an IPv6 one.");
        r.add("Extra Java options", opts,
              "Anything else to hand to Java when the game starts, separated by spaces. Leave empty unless you know what you want.");
        r.add("Resource cache address", proxyUrl,
              "Where the game fetches its resources from while 'Use brodgar.io resource cache proxy' is ticked.");
        r.add(null, updates,
              "Off, the launcher never looks for a release, its own or the game's, and offers whatever is installed.");
        r.preview("The game will be started as:", new JScrollPane(preview));

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(ev -> {
            settings.set("heap", heap.getValue() + "g");
            settings.set("heap.pretouch", String.valueOf(pretouch.isSelected()));
            settings.set("gc", (gc.getSelectedIndex() == 1) ? "g1" : "zgc");
            settings.set("ui.scale", String.valueOf(uiScale.isSelected()));
            settings.set("ipv6", switch(ipv6.getSelectedIndex()) { case 1 -> "false"; case 2 -> "true"; default -> "system"; });
            settings.set("java.opts", opts.getText().trim());
            settings.set("resource.proxy.url", proxyUrl.getText().trim());
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

    /** A heap setting as whole gigabytes: <code>4g</code> is 4, <code>1536m</code> rounds up to 2, anything else is
     *  the bar's low end. */
    static int heapGb(String value) {
        String v = value.trim().toLowerCase();
        if(v.matches("\\d+g"))
            return Integer.parseInt(v.substring(0, v.length() - 1));
        if(v.matches("\\d+m"))
            return (Integer.parseInt(v.substring(0, v.length() - 1)) + 1023) / 1024;
        return MIN_GB;
    }

    /** The machine's physical memory in whole gigabytes, or 0 when it cannot be read. */
    static long totalMemoryGb() {
        try {
            if(ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
                return Math.round(os.getTotalMemorySize() / (1024.0 * 1024 * 1024));
        } catch(RuntimeException | LinkageError e) {
            // a runtime without jdk.management: the bar takes its fallback
        }
        return 0;
    }

    /** The form's rows: a name, a control, and a line under them saying what the setting does. */
    private static final class Row {
        private final JPanel form;
        private final GridBagConstraints c = new GridBagConstraints();
        private int y = 0;

        Row(JPanel form) {
            this.form = form;
            c.anchor = GridBagConstraints.WEST;
        }

        void add(String name, JComponent field, String what) {
            c.insets = new Insets(8, 4, 0, 4);
            c.gridy = y++;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            if(name != null) {
                c.gridx = 0;
                c.gridwidth = 1;
                JLabel l = new JLabel(name);
                l.setFont(l.getFont().deriveFont(Font.BOLD));
                form.add(l, c);
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
