package io.brodgar.launcher;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * The main window. GridBag rows: status + progress bar with the main button (Play / Retry / disabled) spanning
 * both; console checkbox; proxy checkbox, channel dropdown, Options; Open client folder. The console checkbox
 * writes <code>settings</code> directly; the proxy checkbox goes through <code>onProxy</code> (it also writes the
 * client's file). All methods are thread-safe (<code>invokeLater</code>); the main button's action runs on a
 * thread of its own. The dropdown is disabled while work runs.
 */
final class Ui {
    private final JFrame frame;
    private final JLabel status;
    private final JProgressBar bar;
    private final JButton button;
    private final JCheckBox console;
    private final JCheckBox proxy;
    private final JComboBox<Channel> channel;
    private Runnable action;

    private Ui(String title, Settings settings, Consumer<Channel> onChannel, Consumer<Boolean> onProxy, Runnable onOptions, Runnable onClientFolder) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch(Exception e) {
            // cross-platform look and feel then
        }
        frame = new JFrame(title);
        status = new JLabel("Starting...");
        bar = new JProgressBar(0, 1000);
        bar.setIndeterminate(true);
        button = new JButton("Play");
        button.setFont(button.getFont().deriveFont(Font.BOLD, 15f));
        button.setPreferredSize(new Dimension(130, 44));
        button.setEnabled(false);
        button.addActionListener(ev -> {
            Runnable a = action;
            if(a != null)
                new Thread(a, "launcher-action").start();
        });
        JButton options = new JButton("Options...");
        options.addActionListener(ev -> onOptions.run());
        JButton folder = new JButton("Open client folder");
        folder.setToolTipText("The client's folder in the file manager: hafen.jar, savedata, addons");
        folder.addActionListener(ev -> onClientFolder.run());
        console = new JCheckBox("Start the client with a console window", settings.console());
        console.setToolTipText("The client runs in a command window that shows what it prints and stays open when it ends in an error");
        console.addActionListener(ev -> settings.console(console.isSelected()));
        proxy = new JCheckBox("Use brodgar.io resource cache proxy", settings.resourceProxy());
        proxy.addActionListener(ev -> onProxy.accept(proxy.isSelected()));
        channel = new JComboBox<>(Channel.values());
        channel.setSelectedItem(settings.channel());
        channel.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, (value instanceof Channel c) ? c.label : value, index, selected, focus);
            }
        });
        channel.setEnabled(false);
        channel.addActionListener(ev -> {
            Channel picked = (Channel)channel.getSelectedItem();
            if(channel.isEnabled() && (picked != null))
                onChannel.accept(picked);
        });

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 8, 0);
        // row 0, columns 0-1: status
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.weightx = 1;
        c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(status, c);
        // row 1, columns 0-1: bar
        c.gridy = 1;
        panel.add(bar, c);
        // rows 0-1, column 2: main button
        c.gridx = 2; c.gridy = 0; c.gridwidth = 1; c.gridheight = 2; c.weightx = 0;
        c.fill = GridBagConstraints.BOTH; c.insets = new Insets(0, 16, 8, 0);
        panel.add(button, c);
        // row 2, columns 0-1: console checkbox
        c.gridheight = 1; c.insets = new Insets(0, 0, 0, 0);
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(console, c);
        // row 3: proxy checkbox, channel, Options
        c.gridy = 3; c.gridwidth = 1;
        panel.add(proxy, c);
        JPanel pick = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        pick.add(new JLabel("Channel:"));
        pick.add(channel);
        c.gridx = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.EAST;
        panel.add(pick, c);
        c.gridx = 2; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 0, 0);
        panel.add(options, c);
        // row 4, column 2: Open client folder
        c.gridy = 4; c.insets = new Insets(8, 16, 0, 0);
        panel.add(folder, c);

        frame.setContentPane(panel);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setMinimumSize(new Dimension(580, 0));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** Build and show the window on the event thread; controls start from <code>settings</code>. The callbacks
     *  run on the event thread. */
    static Ui open(String title, Settings settings, Consumer<Channel> onChannel, Consumer<Boolean> onProxy, Runnable onOptions, Runnable onClientFolder) {
        Ui[] out = new Ui[1];
        try {
            SwingUtilities.invokeAndWait(() -> out[0] = new Ui(title, settings, onChannel, onProxy, onOptions, onClientFolder));
        } catch(InterruptedException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
        return out[0];
    }

    JFrame frame() {
        return frame;
    }

    void title(String t) {
        SwingUtilities.invokeLater(() -> frame.setTitle(t));
    }

    void status(String s) {
        SwingUtilities.invokeLater(() -> status.setText(s));
    }

    /** Fraction in 0..1; negative = indeterminate. */
    void progress(double fraction) {
        SwingUtilities.invokeLater(() -> {
            bar.setIndeterminate(fraction < 0);
            if(fraction >= 0)
                bar.setValue((int)Math.round(fraction * 1000));
        });
    }

    /** Main button: <code>label</code>, enabled, runs <code>action</code>. Bar determinate, dropdown enabled. */
    void ready(String label, Runnable action) {
        this.action = action;
        SwingUtilities.invokeLater(() -> {
            bar.setIndeterminate(false);
            button.setText(label);
            button.setEnabled(true);
            channel.setEnabled(true);
            button.requestFocusInWindow();
        });
    }

    /** Main button disabled, dropdown enabled. */
    void idle() {
        action = null;
        SwingUtilities.invokeLater(() -> {
            bar.setIndeterminate(false);
            bar.setValue(0);
            button.setText("Play");
            button.setEnabled(false);
            channel.setEnabled(true);
        });
    }

    /** Main button and dropdown disabled. */
    void busy() {
        action = null;
        SwingUtilities.invokeLater(() -> {
            button.setEnabled(false);
            channel.setEnabled(false);
        });
    }

    /** Modal error dialog; blocks until dismissed. */
    void error(String message) {
        try {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(frame, message, frame.getTitle(), JOptionPane.ERROR_MESSAGE));
        } catch(InterruptedException | InvocationTargetException e) {
            // event thread gone
        }
    }

    void close() {
        SwingUtilities.invokeLater(frame::dispose);
    }
}
