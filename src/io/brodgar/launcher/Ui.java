package io.brodgar.launcher;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The main window, in JavaFX (the trailer is JavaFX Media, and a Swing window would copy every frame out of the
 * GPU). Rows: the trailer ({@link Trailer}) and its YouTube link; then a grid: status; progress bar and console
 * checkbox with the main button (Play / Retry / disabled) beside them, spanning both; Options and Open
 * client folder, with the channel dropdown at the right. The console checkbox writes <code>settings</code>
 * directly. All methods are thread-safe
 * (<code>Platform.runLater</code>); the main button's action runs on a thread of its own. The dropdown is
 * disabled while work runs. Full screen (a double click on the trailer) fills the screen with the trailer
 * alone, on black; Esc brings the window back.
 *
 * <p>Everything is drawn at {@link #SCALE}: the base font size times it, which sizes the controls (Modena
 * measures them in em), and every margin and the trailer likewise.
 *
 * <p>Options is still a Swing dialog ({@link OptionsDialog}): {@link #swingDialog} shows it beside this window.
 *
 * <p>The window icon is {@link Launcher#ICON}.
 */
final class Ui {
    /** The window's size, as a factor on the design at 1: a 640x360 trailer, 12 px text. */
    static final double SCALE = 1.3;
    private static final String FONT = "-fx-font-size: " + (Font.getDefault().getSize() * SCALE) + "px;";

    private final Stage stage;
    private final VBox root;
    private final Trailer trailer;
    private final HBox watch;
    private final GridPane controls;
    private final Label status;
    private final ProgressBar bar;
    private final Button button;
    private final CheckBox console;
    private final ComboBox<Channel> channel;
    private volatile Runnable action;

    private Ui(String title, Settings settings, Path trailerDir, Consumer<Channel> onChannel, Runnable onOptions, Runnable onClientFolder) {
        stage = new Stage();
        stage.setTitle(title);
        try(InputStream icon = Launcher.class.getResourceAsStream(Launcher.ICON)) {
            if(icon != null)
                stage.getIcons().add(new Image(icon));
        } catch(IOException e) {
            // no icon then
        }
        trailer = new Trailer(trailerDir);
        Hyperlink link = new Hyperlink("Watch the trailer on YouTube");
        // a quiet blue, no focus ring, flush with the video's left edge; the row around it keeps it there
        link.setStyle("-fx-text-fill: #1e6fd0; -fx-padding: 0; -fx-border-color: transparent;");
        link.setFocusTraversable(false);
        link.setOnAction(ev -> browse(Trailer.YOUTUBE));
        watch = new HBox(link);
        status = new Label("Starting...");
        status.setMaxWidth(Double.MAX_VALUE);
        bar = new ProgressBar(-1);
        bar.setMaxWidth(Double.MAX_VALUE);
        button = new Button("Play");
        // a light green; focused (it is, whenever it is offered) it keeps its plain border instead of the blue ring
        button.setStyle("-fx-font-weight: bold; -fx-font-size: " + (15 * SCALE) + "px; -fx-base: #9be09b;"
                        + " -fx-focus-color: -fx-outer-border; -fx-faint-focus-color: transparent;");
        button.setPrefWidth(130 * SCALE);
        button.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        button.setDisable(true);
        button.setOnAction(ev -> {
            Runnable a = action;
            if(a != null)
                new Thread(a, "launcher-action").start();
        });
        Button options = new Button("Options...");
        options.setOnAction(ev -> onOptions.run());
        Button folder = new Button("Open client folder");
        folder.setTooltip(new Tooltip("The client's folder in the file manager: hafen.jar, savedata, addons"));
        folder.setOnAction(ev -> onClientFolder.run());
        console = new CheckBox("Start the client with a console window");
        console.setSelected(settings.console());
        console.setTooltip(new Tooltip("The client runs in a command window that shows what it prints and stays open when it ends in an error"));
        console.setOnAction(ev -> settings.console(console.isSelected()));
        channel = new ComboBox<>();
        channel.getItems().addAll(Channel.values());
        channel.setConverter(new StringConverter<Channel>() {
            @Override public String toString(Channel c) {
                return (c == null) ? "" : c.label;
            }
            @Override public Channel fromString(String s) {
                return null;
            }
        });
        channel.setValue(settings.channel());
        channel.setDisable(true);
        channel.setOnAction(ev -> {
            Channel picked = channel.getValue();
            if(!channel.isDisabled() && (picked != null))
                onChannel.accept(picked);
        });

        double gap = 8 * SCALE, column = 16 * SCALE;
        controls = new GridPane();
        ColumnConstraints grow = new ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);
        controls.getColumnConstraints().addAll(grow, new ColumnConstraints(), new ColumnConstraints());
        // row 0, columns 0-1: status
        controls.add(status, 0, 0, 2, 1);
        GridPane.setMargin(status, new Insets(0, 0, gap, 0));
        // row 1, columns 0-1: bar
        controls.add(bar, 0, 1, 2, 1);
        GridPane.setMargin(bar, new Insets(0, 0, gap, 0));
        // row 2, columns 0-1: console checkbox
        controls.add(console, 0, 2, 2, 1);
        // rows 1-2, column 2: main button
        controls.add(button, 2, 1, 1, 2);
        GridPane.setMargin(button, new Insets(0, 0, 0, column));
        // row 3: Options and Open client folder; channel at the right
        HBox buttons = new HBox(gap, options, folder);
        controls.add(buttons, 0, 3, 2, 1);
        GridPane.setMargin(buttons, new Insets(gap, 0, 0, 0));
        HBox pick = new HBox(6 * SCALE, new Label("Channel:"), channel);
        pick.setAlignment(Pos.CENTER_RIGHT);
        controls.add(pick, 2, 3);
        GridPane.setHalignment(pick, HPos.RIGHT);
        GridPane.setMargin(pick, new Insets(gap, 0, 0, column));

        root = new VBox(gap, trailer, watch, controls);
        root.setPadding(new Insets(20 * SCALE));
        root.setAlignment(Pos.TOP_CENTER);
        root.setStyle(FONT);
        VBox.setVgrow(trailer, Priority.ALWAYS);
        stage.setScene(new Scene(root));
        stage.setResizable(false);
        stage.fullScreenProperty().addListener((o, was, on) -> layout(on));
        stage.setOnCloseRequest(ev -> System.exit(0));
        stage.show();
    }

    /** Full screen: the trailer alone, filling the screen, on black; else the window as it was, its size
     *  restored by the platform. */
    private void layout(boolean full) {
        watch.setVisible(!full);
        watch.setManaged(!full);
        controls.setVisible(!full);
        controls.setManaged(!full);
        root.setPadding(full ? Insets.EMPTY : new Insets(20 * SCALE));
        root.setStyle(full ? FONT + "-fx-background-color: black;" : FONT);
    }

    /** Build and show the window on the JavaFX thread, starting JavaFX; controls start from
     *  <code>settings</code>, the trailer's files are in <code>trailerDir</code>. The callbacks run on the JavaFX
     *  thread. */
    static Ui open(String title, Settings settings, Path trailerDir, Consumer<Channel> onChannel, Runnable onOptions, Runnable onClientFolder) {
        Ui[] out = new Ui[1];
        RuntimeException[] failed = new RuntimeException[1];
        CountDownLatch built = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                out[0] = new Ui(title, settings, trailerDir, onChannel, onOptions, onClientFolder);
            } catch(RuntimeException e) {
                failed[0] = e;
            } finally {
                built.countDown();
            }
        });
        await(built);
        if(failed[0] != null)
            throw new IllegalStateException(failed[0]);
        return out[0];
    }

    void status(String s) {
        Platform.runLater(() -> status.setText(s));
    }

    /** Fraction in 0..1; negative = indeterminate. */
    void progress(double fraction) {
        Platform.runLater(() -> bar.setProgress((fraction < 0) ? -1 : fraction));
    }

    /** Main button: <code>label</code>, enabled, runs <code>action</code>. Bar determinate, dropdown enabled. */
    void ready(String label, Runnable action) {
        this.action = action;
        Platform.runLater(() -> {
            if(bar.getProgress() < 0)
                bar.setProgress(0);
            button.setText(label);
            button.setDisable(false);
            channel.setDisable(false);
            button.requestFocus();
        });
    }

    /** Main button disabled, dropdown enabled. */
    void idle() {
        action = null;
        Platform.runLater(() -> {
            bar.setProgress(0);
            button.setText("Play");
            button.setDisable(true);
            channel.setDisable(false);
        });
    }

    /** The trailer paused, if it plays: the game is starting. */
    void pauseTrailer() {
        Platform.runLater(trailer::pause);
    }

    /** Main button and dropdown disabled. */
    void busy() {
        action = null;
        Platform.runLater(() -> {
            button.setDisable(true);
            channel.setDisable(true);
        });
    }

    /** Modal error dialog; blocks until dismissed. From any thread, the JavaFX one included. */
    void error(String message) {
        Runnable show = () -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message);
            alert.initOwner(stage);
            alert.setTitle(stage.getTitle());
            alert.setHeaderText(null);
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            alert.showAndWait();
        };
        if(Platform.isFxApplicationThread()) {
            show.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                show.run();
            } finally {
                done.countDown();
            }
        });
        await(done);
    }

    /** A modal Swing dialog, from the JavaFX thread: <code>dialog</code> runs on the Swing thread, the controls
     *  disabled meanwhile (Swing's modality does not reach this window); then <code>after</code>, back on the
     *  JavaFX thread. */
    void swingDialog(Runnable dialog, Runnable after) {
        controls.setDisable(true);
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch(Exception e) {
                // cross-platform look and feel then
            }
            try {
                dialog.run();
            } finally {
                Platform.runLater(() -> {
                    controls.setDisable(false);
                    after.run();
                });
            }
        });
    }

    void close() {
        Platform.runLater(stage::close);
    }

    /** Open <code>url</code> in the browser; nothing if there is none. */
    private static void browse(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch(IOException | UnsupportedOperationException | IllegalArgumentException e) {
            // no browser to open it in
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
