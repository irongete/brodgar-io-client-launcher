package io.brodgar.launcher;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
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
 * The launcher's one window: a status line, a progress bar, the channel dropdown, the resource-proxy checkbox
 * and one button — <b>Play</b> once the client is ready, <b>Retry</b> when nothing is installed and the
 * download failed. Every method may be called from any thread; the button's action runs off the event
 * thread, so it may block. The dropdown is held while work is going on, since changing the channel starts
 * work of its own.
 */
final class Ui {
    private final JFrame frame;
    private final JLabel status;
    private final JProgressBar bar;
    private final JButton button;
    private final JCheckBox proxy;
    private final JComboBox<Channel> channel;
    private Runnable action;

    private Ui(String title, Channel ch, Consumer<Channel> onChannel, boolean proxyOn, Consumer<Boolean> onProxy) {
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
        button.setPreferredSize(new Dimension(120, 40));
        button.setEnabled(false);
        button.addActionListener(ev -> {
            Runnable a = action;
            if(a != null)
                new Thread(a, "launcher-action").start();
        });
        proxy = new JCheckBox("Use brodgar.io resource cache proxy", proxyOn);
        proxy.addActionListener(ev -> onProxy.accept(proxy.isSelected()));
        channel = new JComboBox<>(Channel.values());
        channel.setSelectedItem(ch);
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
        JPanel options = new JPanel(new BorderLayout(16, 0));
        options.add(proxy, BorderLayout.CENTER);
        JPanel pick = new JPanel(new BorderLayout(6, 0));
        pick.add(new JLabel("Channel:"), BorderLayout.WEST);
        pick.add(channel, BorderLayout.CENTER);
        options.add(pick, BorderLayout.EAST);
        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.add(status, BorderLayout.NORTH);
        middle.add(bar, BorderLayout.CENTER);
        middle.add(options, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
        right.add(button, BorderLayout.CENTER);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.add(middle, BorderLayout.CENTER);
        panel.add(right, BorderLayout.EAST);
        frame.setContentPane(panel);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setMinimumSize(new Dimension(520, 0));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** Open the window; <code>ch</code> and <code>proxyOn</code> are the dropdown's and the checkbox's first
     *  states, and <code>onChannel</code> and <code>onProxy</code> hear every change, on the event thread. */
    static Ui open(String title, Channel ch, Consumer<Channel> onChannel, boolean proxyOn, Consumer<Boolean> onProxy) {
        Ui[] out = new Ui[1];
        try {
            SwingUtilities.invokeAndWait(() -> out[0] = new Ui(title, ch, onChannel, proxyOn, onProxy));
        } catch(InterruptedException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
        return out[0];
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

    /** Offer the one action there is: the button reads <code>label</code>, is enabled, and runs
     *  <code>action</code> when pressed. The bar stops moving. */
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
