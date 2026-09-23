package io.brodgar.launcher;

import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * The first-start setup, before the main window: a page for each choice the launcher makes on the player's
 * behalf — the resource pack, the resource cache, the portable client, the game's memory — with what it buys in two
 * or three plain sentences ending in the recommendation ({@link #recommendedGb} for the memory) above its control,
 * which starts at the setting's current value; Back and Next, Finish
 * on the last page. Finish writes the four settings and <code>firstrun=false</code> to
 * <code>launcher.properties</code>: the launcher then opens. Closing the window quits the launcher with nothing
 * written, so the setup returns at the next start. {@link Settings#firstRun} says whether it is shown
 * ({@link Launcher#main}). The labels are the Options dialog's, so the player finds each again there; the
 * window is drawn like it, at {@link Ui#SCALE} ({@link Ui#dialog}).
 */
final class FirstRunDialog {
    /** Width of a page's text, at 1; it wraps there. */
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
        "The map you explore is saved on your disk. Portable client keeps it in one SQLite file beside the client: "
        + "x10 faster, and easy to back up or move. The map you already have in the game's cache can be "
        + "imported. Recommended.";

    /** A page: its heading, its text, its control. */
    private record Page(String heading, String text, Node control) {}

    private final Settings settings;
    private final Stage stage;
    /** The pages, one visible at a time: as tall as the tallest, so no resize between pages. */
    private final StackPane deck = new StackPane();
    private final Label heading = new Label();
    private final Label step = new Label();
    private final Button back = new Button("Back");
    private final Button next = new Button("Next");
    private final CheckBox pack, proxy, sqlite, importMap;
    private final Slider heap;
    private final List<Page> pages;
    private int at;

    private FirstRunDialog(Settings settings) {
        this.settings = settings;
        long totalGb = OptionsDialog.totalMemoryGb();
        pack = new CheckBox("Download the brodgar.io resource pack");
        pack.setSelected(settings.resourcePack());
        proxy = new CheckBox("Use brodgar.io resource cache");
        proxy.setSelected(settings.resourceProxy());
        sqlite = new CheckBox("Portable client");
        sqlite.setSelected(settings.store().equals("sqlite"));
        importMap = new CheckBox("Import the map and the minimap icons from the cache");
        importMap.setSelected(DataMigrator.hasCache());
        importMap.disableProperty().bind(sqlite.selectedProperty().not());
        importMap.setVisible(DataMigrator.hasCache());     // no cache, nothing to import
        importMap.setManaged(importMap.isVisible());
        sqlite.setStyle("-fx-font-weight: bold;");
        VBox store = new VBox(8 * Ui.SCALE, sqlite, importMap);
        heap = OptionsDialog.heapSlider(settings.heap(), totalGb);
        pages = List.of(new Page("The resource pack", PACK_TEXT, pack),
                        new Page("The resource cache", CACHE_TEXT, proxy),
                        new Page("Portable client", STORE_TEXT, store),
                        new Page("Game memory", memoryText(totalGb, recommendedGb(totalGb, (int)heap.getMax())), OptionsDialog.heapRow(heap)));
        deck.setAlignment(Pos.TOP_LEFT);
        for(Page p : pages)
            deck.getChildren().add(page(p));

        heading.setStyle("-fx-font-size: 1.33em; -fx-font-weight: bold;");
        step.setStyle("-fx-font-size: 0.92em; -fx-text-fill: #6e6e6e;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(heading, gap, step);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 12 * Ui.SCALE, 0));

        back.setOnAction(ev -> go(at - 1));
        next.setDefaultButton(true);
        next.setOnAction(ev -> {
            if(at < pages.size() - 1)
                go(at + 1);
            else
                finish();
        });

        VBox root = new VBox(top, deck, OptionsDialog.buttons(back, next));
        root.setPadding(new Insets(16 * Ui.SCALE, 20 * Ui.SCALE, 16 * Ui.SCALE, 20 * Ui.SCALE));
        go(0);
        stage = Ui.dialog(null, Launcher.TITLE + " — first start", root);
        stage.setOnCloseRequest(ev -> System.exit(0));    // nothing written: the setup returns at the next start
    }

    /** Show modally on the JavaFX thread and return when Finish has written the settings; from any other
     *  thread, after {@link Ui#startup}. Closing the window exits the process. */
    static void show(Settings settings) {
        Ui.runAndWait(() -> new FirstRunDialog(settings).stage.showAndWait());
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
    private static VBox page(Page p) {
        Label text = new Label(p.text());
        text.setWrapText(true);
        text.setPrefWidth(TEXT_WIDTH * Ui.SCALE);
        if(p.control() instanceof CheckBox)
            p.control().setStyle("-fx-font-weight: bold;");
        VBox panel = new VBox(16 * Ui.SCALE, text, p.control());
        panel.setAlignment(Pos.TOP_LEFT);
        return panel;
    }

    /** Page <code>i</code>: heading, step, card, Back enabled after the first, Next reads Finish on the last. */
    private void go(int i) {
        at = i;
        Page p = pages.get(i);
        heading.setText(p.heading());
        step.setText((i + 1) + " of " + pages.size());
        for(int k = 0; k < pages.size(); k++)
            deck.getChildren().get(k).setVisible(k == i);
        back.setDisable(i == 0);
        next.setText((i == pages.size() - 1) ? "Finish" : "Next");
        next.requestFocus();
    }

    /** Write the four settings, the import the portable client page asked for, and <code>firstrun=false</code>; close. */
    private void finish() {
        settings.set("resource.pack", String.valueOf(pack.isSelected()));
        settings.set("resource.proxy", String.valueOf(proxy.isSelected()));
        settings.set("store", sqlite.isSelected() ? "sqlite" : "files");
        settings.set("store.import", String.valueOf(sqlite.isSelected() && importMap.isSelected()));
        settings.set("heap", OptionsDialog.gb(heap) + "g");
        settings.set("firstrun", "false");
        stage.close();
    }
}
