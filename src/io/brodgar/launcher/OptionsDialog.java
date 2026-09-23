package io.brodgar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * Modal dialog over the settings {@link Launcher#command} and {@link Settings#clientConfig} use, each with the
 * flag or key it sets, plus a live preview of the command. OK writes all of them to
 * <code>launcher.properties</code>; Cancel writes nothing. Fixed flags (exports, native access, Unsafe) are not
 * shown. Drawn like the window, at {@link Ui#SCALE} ({@link Ui#dialog}).
 */
final class OptionsDialog {
    private OptionsDialog() {}

    /** Heap slider: <code>MIN_GB</code> to <code>max(MIN_GB + 1, min(CAP_GB, totalGb / 2))</code>. */
    static final int MIN_GB = 1, CAP_GB = 16;
    /** Width of a help line, at 1; longer ones wrap. */
    private static final int HELP_WIDTH = 600;
    /** The game icons the client ships, in the jar beside the classes ({@link Launcher#ICON}), in the order
     *  the <em>Game icon</em> dropdown lists them: copies of the client's <code>etc/icon.png</code> and
     *  <code>etc/icon-original.png</code>, shown beside each choice. */
    private static final String[] GAME_ICONS = {"icon-client.png", "icon-client-original.png"};
    /** Height of a game icon in the dropdown, at 1. */
    private static final int GAME_ICON_SIZE = 20;

    /** Show modally over <code>owner</code>, on the JavaFX thread; returns on close. <code>java</code> is the
     *  executable the preview starts with. */
    static void show(Stage owner, Settings settings, Path java) {
        long totalGb = totalMemoryGb();
        Slider heap = heapSlider(settings.heap(), totalGb);
        HBox heapRow = heapRow(heap);
        CheckBox pretouch = new CheckBox("Reserve it all at start");
        pretouch.setSelected(settings.pretouch());
        ComboBox<String> gc = new ComboBox<>();
        gc.getItems().addAll("Concurrent (ZGC): frees memory while the game keeps running",
                             "Standard (G1): frees memory in short stops");
        gc.getSelectionModel().select(settings.gc().equals("g1") ? 1 : 0);
        CheckBox uiScale = new CheckBox("Let Windows scale the game window");
        uiScale.setSelected(settings.uiScale());
        ComboBox<String> ipv6 = new ComboBox<>();
        ipv6.getItems().addAll("As Windows prefers", "IPv4 first", "IPv6 first");
        ipv6.getSelectionModel().select(switch(settings.ipv6()) { case "false" -> 1; case "true" -> 2; default -> 0; });
        CheckBox pack = new CheckBox("Download the brodgar.io resource pack");
        pack.setSelected(settings.resourcePack());
        CheckBox proxy = new CheckBox("Use brodgar.io resource cache");
        proxy.setSelected(settings.resourceProxy());
        CheckBox sqlite = new CheckBox("Portable client");
        sqlite.setSelected(settings.store().equals("sqlite"));
        TextField opts = new TextField(settings.javaOptsText());
        CheckBox addonsOverride = new CheckBox("Override addons folder");
        addonsOverride.setSelected(settings.addonsOverride());
        TextField addonsDir = new TextField(settings.addonsDir());
        addonsDir.disableProperty().bind(addonsOverride.selectedProperty().not());
        CheckBox savedataOverride = new CheckBox("Override savedata folder");
        savedataOverride.setSelected(settings.savedataOverride());
        TextField savedataDir = new TextField(settings.savedataDir());
        savedataDir.disableProperty().bind(savedataOverride.selectedProperty().not());
        CheckBox updates = new CheckBox("Look for a newer launcher and game when the launcher opens");
        updates.setSelected(settings.checkUpdates());
        ComboBox<String> icon = new ComboBox<>();
        icon.getItems().addAll("The brodgar.io dolmen, in blue", "The original Haven & Hearth icon");
        icon.getSelectionModel().select(settings.icon().equals("original") ? 1 : 0);
        Image[] icons = gameIcons();
        icon.setCellFactory(list -> iconCell(icon, icons));
        icon.setButtonCell(iconCell(icon, icons));

        TextArea preview = new TextArea();
        preview.setEditable(false);
        preview.setWrapText(true);
        preview.setPrefRowCount(4);
        preview.setStyle("-fx-font-family: Monospaced; -fx-font-size: 0.83em;");

        Runnable refresh = () -> {
            Settings.Launch l = new Settings.Launch(gb(heap) + "g", pretouch.isSelected(),
                (gc.getSelectionModel().getSelectedIndex() == 1) ? "g1" : "zgc", uiScale.isSelected(),
                switch(ipv6.getSelectionModel().getSelectedIndex()) { case 1 -> "false"; case 2 -> "true"; default -> "system"; },
                sqlite.isSelected() ? "sqlite" : "files", Settings.split(opts.getText()),
                proxy.isSelected() ? Settings.RESOURCE_PROXY_URL : null);
            preview.setText(String.join(" ", Launcher.command(java, l)));
            preview.positionCaret(0);
        };
        opts.textProperty().addListener((o, was, is) -> refresh.run());
        heap.valueProperty().addListener((o, was, is) -> refresh.run());
        pretouch.setOnAction(ev -> refresh.run());
        gc.setOnAction(ev -> refresh.run());
        uiScale.setOnAction(ev -> refresh.run());
        ipv6.setOnAction(ev -> refresh.run());
        proxy.setOnAction(ev -> refresh.run());
        sqlite.setOnAction(ev -> refresh.run());
        refresh.run();

        Rows r = new Rows();
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
              "Fetch the resource files from " + Settings.RESOURCE_PROXY_URL + " instead of the Haven server: about x2.5 faster (-U on the client's command line)");
        r.add(null, sqlite,
              "Keep the map and the resource files in SQLite: map reads about x10 faster");
        r.add("Extra Java options", opts,
              "Appended to the JVM command line, space-separated");
        r.labelled(addonsOverride, addonsDir,
              "haven.addondir in client/haven-config.properties; off: client/addons");
        r.labelled(savedataOverride, savedataDir,
              "haven.savedatadir in client/haven-config.properties; off: client/savedata");
        r.add(null, updates,
              "check.updates: GitHub release lookup for the launcher and the client at start");
        r.add("Game icon", icon,
              "haven.icon in client/haven-config.properties: the icon of the game window, in its title bar and in the taskbar");
        r.preview("The game will be started as:", preview);

        Button ok = new Button("OK");
        ok.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        // the form scrolls on a screen too short for the dialog (Ui.place), and nowhere else
        ScrollPane scroll = new ScrollPane(r.form);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        scroll.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        VBox root = new VBox(scroll, buttons(ok, cancel));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.setPadding(new Insets(12 * Ui.SCALE, 16 * Ui.SCALE, 12 * Ui.SCALE, 16 * Ui.SCALE));
        Stage d = Ui.dialog(owner, "Options", root);
        ok.setOnAction(ev -> {
            boolean toSqlite = sqlite.isSelected() && settings.store().equals("files");
            settings.set("heap", gb(heap) + "g");
            settings.set("heap.pretouch", String.valueOf(pretouch.isSelected()));
            settings.set("gc", (gc.getSelectionModel().getSelectedIndex() == 1) ? "g1" : "zgc");
            settings.set("ui.scale", String.valueOf(uiScale.isSelected()));
            settings.set("ipv6", switch(ipv6.getSelectionModel().getSelectedIndex()) { case 1 -> "false"; case 2 -> "true"; default -> "system"; });
            settings.set("resource.pack", String.valueOf(pack.isSelected()));
            settings.set("resource.proxy", String.valueOf(proxy.isSelected()));
            settings.set("store", sqlite.isSelected() ? "sqlite" : "files");
            settings.set("java.opts", opts.getText().trim());
            settings.set("addons.override", String.valueOf(addonsOverride.isSelected()));
            settings.set("addons.dir", addonsDir.getText().trim());
            settings.set("savedata.override", String.valueOf(savedataOverride.isSelected()));
            settings.set("savedata.dir", savedataDir.getText().trim());
            settings.set("check.updates", String.valueOf(updates.isSelected()));
            settings.set("icon", (icon.getSelectionModel().getSelectedIndex() == 1) ? "original" : "brodgar");
            d.close();
            if(toSqlite)    // the map and the minimap icons of the known worlds, from the game's own store
                DataMigrator.offer(owner, Launcher.home().resolve("client"),
                                   savedataOverride.isSelected() ? savedataDir.getText() : "");
        });
        cancel.setOnAction(ev -> d.close());
        d.showAndWait();
    }

    /** The heap slider: {@link #MIN_GB} to <code>max(MIN_GB + 1, min(CAP_GB, totalGb / 2))</code>, 8 when the
     *  memory is unknown, at <code>heap</code>'s value clamped into that range, a tick per GB. Also the setup's
     *  ({@link FirstRunDialog}). */
    static Slider heapSlider(String heap, long totalGb) {
        int maxGb = (totalGb <= 0) ? 8 : (int)Math.max(MIN_GB + 1, Math.min(CAP_GB, totalGb / 2));
        Slider s = new Slider(MIN_GB, maxGb, Math.max(MIN_GB, Math.min(maxGb, heapGb(heap))));
        boolean even = maxGb - MIN_GB > 8;              // label every GB up to 9 labels, else the even ones
        s.setMajorTickUnit(1);
        s.setMinorTickCount(0);
        s.setBlockIncrement(1);
        s.setShowTickMarks(true);
        s.setShowTickLabels(true);
        s.setSnapToTicks(true);
        s.setLabelFormatter(new StringConverter<Double>() {
            @Override public String toString(Double gb) {
                int n = (int)Math.round(gb);
                return (!even || (n % 2 == 0)) ? String.valueOf(n) : "";
            }
            @Override public Double fromString(String s) {
                return null;
            }
        });
        return s;
    }

    /** The slider with its value, <code>N GB</code> in bold, at its right, following it. */
    static HBox heapRow(Slider heap) {
        Label value = new Label(gb(heap) + " GB");
        value.setStyle("-fx-font-weight: bold;");
        value.setMinWidth(52 * Ui.SCALE);
        heap.valueProperty().addListener((o, was, is) -> value.setText(gb(heap) + " GB"));
        HBox row = new HBox(8 * Ui.SCALE, heap, value);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(heap, Priority.ALWAYS);
        return row;
    }

    /** The slider's value, in whole GB. */
    static int gb(Slider heap) {
        return (int)Math.round(heap.getValue());
    }

    /** {@link #GAME_ICONS} as images, in that order; an entry is null when the jar has not got it. */
    private static Image[] gameIcons() {
        Image[] images = new Image[GAME_ICONS.length];
        for(int i = 0; i < images.length; i++) {
            try(InputStream in = Launcher.class.getResourceAsStream(GAME_ICONS[i])) {
                images[i] = (in == null) ? null : new Image(in);
            } catch(IOException e) {
                // no icon then
            }
        }
        return images;
    }

    /** A dropdown cell drawing <code>images</code>[the item's place in <code>box</code>] before the text; the
     *  cell of the closed dropdown is one of these too, and it is not in the list, so the place is looked up by
     *  the item rather than taken from the index. */
    private static ListCell<String> iconCell(ComboBox<String> box, Image[] images) {
        return new ListCell<>() {
            private final ImageView view = new ImageView();
            {
                view.setFitHeight(GAME_ICON_SIZE * Ui.SCALE);
                view.setPreserveRatio(true);
                view.setSmooth(true);
            }

            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                int at = (item == null) ? -1 : box.getItems().indexOf(item);
                Image image = ((at < 0) || (at >= images.length)) ? null : images[at];
                view.setImage(image);
                setGraphic((image == null) ? null : view);
            }
        };
    }

    /** A dialog's button row: <code>buttons</code> at the right, a space above them. */
    static HBox buttons(Button... buttons) {
        HBox row = new HBox(8 * Ui.SCALE, buttons);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(12 * Ui.SCALE, 0, 0, 0));
        return row;
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

    /** Grid rows: label (a name, or a checkbox that governs the control) + control, then a help line
     *  spanning both columns. */
    private static final class Rows {
        final GridPane form = new GridPane();
        private int y = 0;

        Rows() {
            ColumnConstraints name = new ColumnConstraints(), field = new ColumnConstraints();
            field.setHgrow(Priority.ALWAYS);
            form.getColumnConstraints().addAll(name, field);
            form.setHgap(8 * Ui.SCALE);
        }

        void add(String name, Node field, String what) {
            labelled((name == null) ? null : new Label(name), field, what);
        }

        /** The row with <code>label</code> in the first column: a name, or the checkbox that governs the control. */
        void labelled(Node label, Node field, String what) {
            Insets above = new Insets(6 * Ui.SCALE, 0, 0, 0);
            if(label != null) {
                label.setStyle("-fx-font-weight: bold;");
                form.add(label, 0, y);
                GridPane.setMargin(label, above);
                form.add(field, 1, y++);
            } else {
                if(field instanceof CheckBox)
                    field.setStyle("-fx-font-weight: bold;");
                form.add(field, 0, y++, 2, 1);
            }
            GridPane.setMargin(field, above);
            Label help = new Label(what);
            help.setWrapText(true);
            help.setPrefWidth(HELP_WIDTH * Ui.SCALE);
            help.setStyle("-fx-font-size: 0.92em; -fx-text-fill: #6e6e6e;");
            form.add(help, 0, y++, 2, 1);
        }

        void preview(String caption, Node area) {
            Label label = new Label(caption);
            form.add(label, 0, y++, 2, 1);
            GridPane.setMargin(label, new Insets(16 * Ui.SCALE, 0, 0, 0));
            form.add(area, 0, y++, 2, 1);
            GridPane.setMargin(area, new Insets(4 * Ui.SCALE, 0, 0, 0));
        }
    }
}
