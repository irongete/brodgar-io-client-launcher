package io.brodgar.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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

    private static final String[] HEAPS = {"1 GB", "2 GB", "3 GB", "4 GB", "6 GB", "8 GB"};

    /** Open the dialog over <code>owner</code>, modal; back when it is closed. */
    static void show(JFrame owner, Settings settings, Path java) {
        JDialog d = new JDialog(owner, "Options", true);

        JComboBox<String> heap = new JComboBox<>(HEAPS);
        heap.setEditable(true);
        heap.setSelectedItem(heapLabel(settings.heap()));
        JCheckBox pretouch = new JCheckBox("Reserve it all at start", settings.pretouch());
        JComboBox<String> gc = new JComboBox<>(new String[] {"Concurrent (ZGC): frees memory while the game keeps running",
                                                              "Standard (G1): frees memory in short stops"});
        gc.setSelectedIndex(settings.gc().equals("g1") ? 1 : 0);
        JCheckBox uiScale = new JCheckBox("Let Windows scale the game window", settings.uiScale());
        JComboBox<String> ipv6 = new JComboBox<>(new String[] {"As Windows prefers", "IPv4 first", "IPv6 first"});
        ipv6.setSelectedIndex(switch(settings.ipv6()) { case "false" -> 1; case "true" -> 2; default -> 0; });
        JTextField opts = new JTextField(settings.javaOptsText(), 30);
        JTextField proxyUrl = new JTextField(settings.resourceProxyUrl(), 30);
        JCheckBox updates = new JCheckBox("Look for a newer game version when the launcher opens", settings.checkUpdates());

        JTextArea preview = new JTextArea(5, 64);
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, preview.getFont().getSize() - 2)));

        Runnable refresh = () -> {
            Settings.Launch l = new Settings.Launch(heapValue(String.valueOf(heap.getEditor().getItem())), pretouch.isSelected(),
                (gc.getSelectedIndex() == 1) ? "g1" : "zgc", uiScale.isSelected(),
                switch(ipv6.getSelectedIndex()) { case 1 -> "false"; case 2 -> "true"; default -> "system"; },
                Settings.split(opts.getText()), settings.resourceProxy(), proxyUrl.getText().trim());
            preview.setText(String.join(" ", Launcher.command(java, l)));
            preview.setCaretPosition(0);
        };
        onChange((JTextField)heap.getEditor().getEditorComponent(), refresh);
        onChange(opts, refresh);
        onChange(proxyUrl, refresh);
        heap.addActionListener(ev -> refresh.run());
        pretouch.addActionListener(ev -> refresh.run());
        gc.addActionListener(ev -> refresh.run());
        uiScale.addActionListener(ev -> refresh.run());
        ipv6.addActionListener(ev -> refresh.run());
        refresh.run();

        JPanel form = new JPanel(new GridBagLayout());
        Row r = new Row(form);
        r.add("Game memory", heap,
              "How much memory the game is given. It is set aside when the game starts, so keep it below what your PC has free.");
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
              "Off, the launcher never looks for a release and offers whatever is installed.");
        r.preview("The game will be started as:", new JScrollPane(preview));

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(ev -> {
            String h = heapValue(String.valueOf(heap.getEditor().getItem()));
            if(!h.matches("\\d+[mg]")) {
                JOptionPane.showMessageDialog(d, "Game memory is a number of GB or MB: 2 GB, 512 MB.", "Options", JOptionPane.ERROR_MESSAGE);
                return;
            }
            settings.set("heap", h);
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

    /** <code>2g</code> as <code>2 GB</code>, <code>512m</code> as <code>512 MB</code>; anything else as it is. */
    static String heapLabel(String value) {
        String v = value.trim().toLowerCase();
        if(v.matches("\\d+g"))
            return v.substring(0, v.length() - 1) + " GB";
        if(v.matches("\\d+m"))
            return v.substring(0, v.length() - 1) + " MB";
        return value;
    }

    /** <code>2 GB</code> (or <code>2gb</code>, <code>2g</code>) as <code>2g</code>; the trimmed text otherwise. */
    static String heapValue(String label) {
        String v = label.trim().toLowerCase().replace(" ", "");
        if(v.matches("\\d+gb?"))
            return v.replaceAll("[^0-9]", "") + "g";
        if(v.matches("\\d+mb?"))
            return v.replaceAll("[^0-9]", "") + "m";
        return v;
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
