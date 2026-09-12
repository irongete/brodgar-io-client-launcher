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
 * The launcher's one window, a grid of four rows: the status line and the progress bar with the big button
 * beside them — <b>Play</b> once the channel's newest release is installed, <b>Retry</b> when nothing is
 * installed and the download failed, greyed out while the channel has nothing — then the console checkbox, and
 * under it the resource-proxy checkbox, the channel dropdown and <b>Options...</b>, in the button's column. The
 * two checkboxes are settings, remembered the moment they are ticked. Every method may be called from any
 * thread; the big button's action runs off the event thread, so it may block. The dropdown is held while work
 * is going on, since changing the channel starts work of its own.
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

    private Ui(String title, Settings settings, Consumer<Channel> onChannel, Runnable onOptions) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch(Exception e) {
            // the cross-platform look is fine too
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
        console = new JCheckBox("Start the client with a console window", settings.console());
        console.setToolTipText("The client runs in a command window that shows what it prints and stays open when it ends in an error");
        console.addActionListener(ev -> settings.console(console.isSelected()));
        proxy = new JCheckBox("Use brodgar.io resource cache proxy", settings.resourceProxy());
        proxy.addActionListener(ev -> settings.resourceProxy(proxy.isSelected()));
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
        // row 0: the status, over both left columns
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.weightx = 1;
        c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(status, c);
        // row 1: the bar
        c.gridy = 1;
        panel.add(bar, c);
        // rows 0-1, right column: the big button, filling both
        c.gridx = 2; c.gridy = 0; c.gridwidth = 1; c.gridheight = 2; c.weightx = 0;
        c.fill = GridBagConstraints.BOTH; c.insets = new Insets(0, 16, 8, 0);
        panel.add(button, c);
        // row 2: the console checkbox, over both left columns
        c.gridheight = 1; c.insets = new Insets(0, 0, 0, 0);
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(console, c);
        // row 3: the proxy checkbox, the channel, and Options in the button's column
        c.gridy = 3; c.gridwidth = 1;
        panel.add(proxy, c);
        JPanel pick = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        pick.add(new JLabel("Channel:"));
        pick.add(channel);
        c.gridx = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.EAST;
        panel.add(pick, c);
        c.gridx = 2; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 0, 0);
        panel.add(options, c);

        frame.setContentPane(panel);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setMinimumSize(new Dimension(580, 0));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** Open the window. The dropdown and the checkboxes start as <code>settings</code> has them, and the checkboxes
     *  write themselves back into it; <code>onChannel</code> hears every change of the dropdown and
     *  <code>onOptions</code> the Options button, both on the event thread. */
    static Ui open(String title, Settings settings, Consumer<Channel> onChannel, Runnable onOptions) {
        Ui[] out = new Ui[1];
        try {
            SwingUtilities.invokeAndWait(() -> out[0] = new Ui(title, settings, onChannel, onOptions));
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

    /** A fraction in 0..1, or a negative number while the size is unknown. */
    void progress(double fraction) {
        SwingUtilities.invokeLater(() -> {
            bar.setIndeterminate(fraction < 0);
            if(fraction >= 0)
                bar.setValue((int)Math.round(fraction * 1000));
        });
    }

    /** Offer the one action there is: the big button reads <code>label</code>, is enabled, and runs
     *  <code>action</code> when pressed. The bar stops moving and the dropdown is free again. */
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

    /** Nothing to play and nothing to retry: the big button is greyed out, the dropdown is free — picking
     *  another channel is the way out. */
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

    /** Nothing to press while work is going on. */
    void busy() {
        action = null;
        SwingUtilities.invokeLater(() -> {
            button.setEnabled(false);
            channel.setEnabled(false);
        });
    }

    /** Show a message and wait for the click; the window stays. */
    void error(String message) {
        try {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(frame, message, frame.getTitle(), JOptionPane.ERROR_MESSAGE));
        } catch(InterruptedException | InvocationTargetException e) {
            // nothing left to show it in
        }
    }

    void close() {
        SwingUtilities.invokeLater(frame::dispose);
    }
}
