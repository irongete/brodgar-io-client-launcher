package io.brodgar.launcher;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * The first-start setup, before the main window: a page for each choice the launcher makes on the player's
 * behalf — the resource pack, the resource cache, the SQLite store, the game's memory — with what it buys in two
 * or three plain sentences ending in the recommendation ({@link #recommendedGb} for the memory) above its control,
 * which starts at the setting's current value; Back and Next, Finish
 * on the last page. Finish writes the four settings and <code>firstrun=false</code> to
 * <code>launcher.properties</code>: the launcher then opens. Closing the window quits the launcher with nothing
 * written, so the setup returns at the next start. {@link Settings#firstRun} says whether it is shown
 * ({@link Launcher#main}). The labels are the Options dialog's, so the player finds each again there.
 */
final class FirstRunDialog {
    /** Width of a page's text, in the HTML that wraps it. */
    private static final int TEXT_WIDTH = 520;
    /** The recommended heap is a quarter of the machine's memory, kept between these. */
    private static final int RECOMMENDED_MIN_GB = 2, RECOMMENDED_MAX_GB = 8;

    private static final String PACK_TEXT =
        "The game fetches its graphics and sounds one by one as you play, and the first days are spent waiting "
        + "for them. The resource pack downloads all of them at once, about 250 MB, and keeps them up to date. Recommended.";
    private static final String CACHE_TEXT =
        "What the resource pack does not have yet comes from the game's server, which is slow from far away. The "
        + "brodgar.io cache serves the same files x2.5 faster. Recommended.";
    private static final String STORE_TEXT =
        "The map you explore is saved on your disk. The SQLite store keeps it in one file beside the client: "
        + "x10 faster, and easy to back up or move. A map recorded by another client stays there; "
        + "Export and Import in the map window carry it over. Recommended.";

    /** A page: its heading, its text, its control. */
    private record Page(String heading, String text, JComponent control) {}

    private final Settings settings;
    private final JDialog dialog;
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel heading = new JLabel();
    private final JLabel step = new JLabel();
    private final JButton back = new JButton("Back");
    private final JButton next = new JButton("Next");
    private final JCheckBox pack, proxy, sqlite;
    private final JSlider heap;
    private final List<Page> pages;
    private int at;

    private FirstRunDialog(Settings settings) {
        this.settings = settings;
        dialog = new JDialog((Frame)null, Launcher.TITLE + " — first start", true);
        dialog.setIconImage(Launcher.icon());
        long totalGb = OptionsDialog.totalMemoryGb();
        pack = new JCheckBox("Download the brodgar.io resource pack", settings.resourcePack());
        proxy = new JCheckBox("Use brodgar.io resource cache", settings.resourceProxy());
        sqlite = new JCheckBox("Use the SQLite store", settings.store().equals("sqlite"));
        heap = OptionsDialog.heapSlider(settings.heap(), totalGb);
        pages = List.of(new Page("The resource pack", PACK_TEXT, pack),
                        new Page("The resource cache", CACHE_TEXT, proxy),
                        new Page("The SQLite store", STORE_TEXT, sqlite),
                        new Page("Game memory", memoryText(totalGb, recommendedGb(totalGb, heap.getMaximum())), OptionsDialog.heapRow(heap)));
        for(int i = 0; i < pages.size(); i++)
            deck.add(page(pages.get(i)), String.valueOf(i));

        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize() + 4f));
        step.setFont(step.getFont().deriveFont(Font.PLAIN, Math.max(10f, step.getFont().getSize() - 1)));
        Color fg = UIManager.getColor("Label.disabledForeground");
        step.setForeground((fg != null) ? fg : Color.GRAY);
        JPanel top = new JPanel(new BorderLayout());
        top.add(heading, BorderLayout.WEST);
        top.add(step, BorderLayout.EAST);
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        back.addActionListener(ev -> go(at - 1));
        next.addActionListener(ev -> {
            if(at < pages.size() - 1)
                go(at + 1);
            else
                finish();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(back);
        buttons.add(next);
        buttons.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        panel.add(top, BorderLayout.NORTH);
        panel.add(deck, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.getRootPane().setDefaultButton(next);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                System.exit(0);                     // nothing written: the setup returns at the next start
            }
        });
        go(0);
        dialog.pack();                              // the deck is as tall as its tallest page: no resize between pages
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(null);
    }

    /** Show modally on the Swing thread and return when Finish has written the settings; from any other
     *  thread. Closing the window exits the process. */
    static void show(Settings settings) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                OptionsDialog.systemLookAndFeel();
                new FirstRunDialog(settings).dialog.setVisible(true);
            });
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch(InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** A quarter of the machine's memory, from {@link #RECOMMENDED_MIN_GB} to {@link #RECOMMENDED_MAX_GB} and
     *  within the slider's range: 8 GB installed gives 2, 16 gives 4, 32 and up give 8; unknown gives 2. */
    static int recommendedGb(long totalGb, int maxGb) {
        return (int)Math.min(maxGb, Math.max(RECOMMENDED_MIN_GB, Math.min(RECOMMENDED_MAX_GB, totalGb / 4)));
    }

    /** The memory page's text: the machine's memory when it is known, and the recommendation. */
    private static String memoryText(long totalGb, int recommendedGb) {
        return "How much memory the game may use while it runs; the rest stays free for everything else."
            + ((totalGb > 0) ? " This computer has " + totalGb + " GB." : "") + " Recommended: " + recommendedGb + " GB.";
    }

    /** A page's panel: the text, wrapped at {@link #TEXT_WIDTH}, and the control under it, a checkbox in bold as
     *  in Options. */
    private static JPanel page(Page p) {
        JLabel text = new JLabel("<html><div style='width:" + TEXT_WIDTH + "px'>" + p.text() + "</div></html>");
        if(p.control() instanceof JCheckBox b)
            b.setFont(b.getFont().deriveFont(Font.BOLD));
        JPanel body = new JPanel(new BorderLayout());
        body.add(p.control(), BorderLayout.NORTH);
        JPanel panel = new JPanel(new BorderLayout(0, 16));
        panel.add(text, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    /** Page <code>i</code>: heading, step, card, Back enabled after the first, Next reads Finish on the last. */
    private void go(int i) {
        at = i;
        Page p = pages.get(i);
        heading.setText(p.heading());
        step.setText((i + 1) + " of " + pages.size());
        cards.show(deck, String.valueOf(i));
        back.setEnabled(i > 0);
        next.setText((i == pages.size() - 1) ? "Finish" : "Next");
        next.requestFocusInWindow();
    }

    /** Write the four settings and <code>firstrun=false</code>; close. */
    private void finish() {
        settings.set("resource.pack", String.valueOf(pack.isSelected()));
        settings.set("resource.proxy", String.valueOf(proxy.isSelected()));
        settings.set("store", sqlite.isSelected() ? "sqlite" : "files");
        settings.set("heap", heap.getValue() + "g");
        settings.set("firstrun", "false");
        dialog.dispose();
    }
}
