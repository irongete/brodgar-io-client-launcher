package io.brodgar.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * The game's trailer, 640x360 at {@link Ui#SCALE} at the top of the window: <code>trailer.mp4</code> (720p H.264 + AAC) played by
 * JavaFX Media, under <code>trailer.jpg</code> until it plays. Both are beside <code>launcher.jar</code> (the
 * Steam item's folder, or home). A click on the video plays or pauses it; a double click fills the screen with
 * it alone ({@link Ui}) and back (as does Esc) — the click's pause waits a moment, in case a second one comes.
 * A bar along the bottom, there while the mouse is over the video, has the position (drag or click to seek)
 * and the time. At the end it stops: the poster again, a click plays it from the start. Without the video: the
 * poster alone; without that either, a dark pane.
 */
final class Trailer extends StackPane {
    static final double WIDTH = 640 * Ui.SCALE, HEIGHT = 360 * Ui.SCALE;
    static final String VIDEO = "trailer.mp4", POSTER = "trailer.jpg";
    /** The same trailer on YouTube: the link under the video. */
    static final String YOUTUBE = "https://www.youtube.com/watch?v=_HbQlAjre2Q";
    /** Null without the video. */
    private MediaPlayer player;

    /** <code>dir</code>: the folder of the two files. */
    Trailer(Path dir) {
        setPrefSize(WIDTH, HEIGHT);
        // the video and the poster take the pane's size, and a MediaView's minimum is its current size: without
        // this, the pane could never shrink back from full screen
        setMinSize(0, 0);
        setStyle("-fx-background-color: #181818;");
        Path video = dir.resolve(VIDEO), image = dir.resolve(POSTER);
        ImageView poster = new ImageView();
        if(Files.isRegularFile(image))
            poster.setImage(new Image(image.toUri().toString(), true));
        poster.setPreserveRatio(true);
        poster.fitWidthProperty().bind(widthProperty());
        poster.fitHeightProperty().bind(heightProperty());
        // play sign: a translucent disc with a white triangle
        double s = Ui.SCALE;
        Polygon triangle = new Polygon(-12 * s, -18 * s, -12 * s, 18 * s, 18 * s, 0);
        triangle.setFill(Color.WHITE);
        Group sign = new Group(new Circle(36 * s, Color.rgb(0, 0, 0, 0.55)), triangle);
        sign.setMouseTransparent(true);
        getChildren().addAll(poster, sign);
        if(!Files.isRegularFile(video)) {
            sign.setVisible(false);
            return;
        }

        player = new MediaPlayer(new Media(video.toUri().toString()));
        player.setOnEndOfMedia(player::stop);           // a pause and a seek here would start it over
        MediaView view = new MediaView(player);
        view.setPreserveRatio(true);
        view.fitWidthProperty().bind(widthProperty());
        view.fitHeightProperty().bind(heightProperty());
        // STALLED (buffering, which a start passes through, and may stay reported in) counts as playing: the poster
        // must come off then, and the sign must not sit on a moving video
        var playing = player.statusProperty().isEqualTo(MediaPlayer.Status.PLAYING)
                      .or(player.statusProperty().isEqualTo(MediaPlayer.Status.STALLED));
        poster.visibleProperty().bind(playing.or(player.statusProperty().isEqualTo(MediaPlayer.Status.PAUSED)).not());
        sign.visibleProperty().bind(playing.not());
        PauseTransition click = new PauseTransition(Duration.millis(250));
        click.setOnFinished(ev -> {
            if(playing.get())
                player.pause();
            else
                player.play();
        });
        setCursor(Cursor.HAND);
        setOnMouseClicked(ev -> {
            if(ev.getClickCount() == 1) {
                click.playFromStart();
            } else {
                click.stop();
                Stage stage = (Stage)getScene().getWindow();
                stage.setFullScreen(!stage.isFullScreen());
            }
        });

        // the bar: position, time
        Slider position = new Slider();
        position.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(position, Priority.ALWAYS);
        position.maxProperty().bind(Bindings.createDoubleBinding(() -> seconds(player.getTotalDuration()), player.totalDurationProperty()));
        boolean[] syncing = {false};
        player.currentTimeProperty().addListener((o, was, now) -> {
            if(!position.isValueChanging()) {
                syncing[0] = true;
                position.setValue(now.toSeconds());
                syncing[0] = false;
            }
        });
        position.valueProperty().addListener((o, was, now) -> {
            if(!syncing[0])
                player.seek(Duration.seconds(now.doubleValue()));
        });
        Label time = new Label();
        time.setStyle("-fx-text-fill: white;");
        time.textProperty().bind(Bindings.createStringBinding(() -> clock(player.getCurrentTime()) + " / " + clock(player.getTotalDuration()),
                                                              player.currentTimeProperty(), player.totalDurationProperty()));
        HBox bar = new HBox(8 * s, position, time);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6 * s, 12 * s, 6 * s, 12 * s));
        bar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.6);");
        bar.setMaxHeight(Region.USE_PREF_SIZE);
        bar.setCursor(Cursor.DEFAULT);
        bar.visibleProperty().bind(hoverProperty());
        bar.addEventHandler(MouseEvent.MOUSE_CLICKED, Event::consume);      // not the video's click
        StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
        getChildren().addAll(view, bar);
        poster.toFront();
        sign.toFront();
        bar.toFront();
    }

    /** Pause, if playing. */
    void pause() {
        if((player != null) && ((player.getStatus() == MediaPlayer.Status.PLAYING) || (player.getStatus() == MediaPlayer.Status.STALLED)))
            player.pause();
    }

    /** Seconds of <code>d</code>; 0 while unknown. */
    private static double seconds(Duration d) {
        return ((d == null) || d.isUnknown() || d.isIndefinite()) ? 0 : d.toSeconds();
    }

    /** <code>m:ss</code>. */
    private static String clock(Duration d) {
        int s = (int)Math.round(seconds(d));
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }
}
