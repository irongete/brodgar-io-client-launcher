package io.brodgar.launcher;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Copies the map and the minimap icon settings of the worlds in {@link #WORLDS} from the game's own store
 * (<code>HashDirCache</code>, <code>%APPDATA%\Haven and Hearth\data</code>) into the portable client's SQLite store,
 * <code>map.sqlite</code> in the client's savedata folder. Offered when Options switches the store from files
 * to SQLite ({@link #offer}); run once the client is installed when the first-start setup asked for it
 * ({@link #copy}, <code>store.import</code>). Resources are not copied: the resource pack, the resource server or the proxy give them
 * back. Worlds not listed are left out: their servers are closed.
 *
 * <p>Rows follow the client's <code>SqliteCache</code>: <code>map/&lt;genus&gt;/…</code> in the table
 * <code>map-&lt;genus&gt;</code>, the icon settings (<code>data/mm-icons-2/&lt;genus&gt;/…</code>) in
 * <code>entries</code>, schema 2. On a name already there the newer file wins.
 */
final class DataMigrator {
    private DataMigrator() {}

    /** The worlds whose data is copied, by genus, with the name the dialog shows. */
    private static final Map<String, String> WORLDS = Map.of(
        "earth10", "World 16");

    /** The layout the client writes, its <code>SqliteCache.SCHEMA</code>. */
    private static final int SCHEMA = 2;
    /** The columns of every table, the client's. */
    private static final String COLUMNS = " (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, data BLOB NOT NULL,"
        + " mtime INTEGER NOT NULL)";
    /** Rows per transaction. */
    private static final int BATCH = 500;

    /**
     * Ask, then copy; on the JavaFX thread, over <code>owner</code>. <code>clientDir</code> is the client's
     * folder (its working directory); <code>savedataDir</code> the override, blank for <code>savedata</code>.
     */
    static void offer(Stage owner, Path clientDir, String savedataDir) {
        if(!hasCache())
            return;
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
            "You selected Portable client. Do you want to import the map and the minimap icons from the cache?"
            + " Recommended. Close the game first.");
        ask.initOwner(owner);
        ask.setTitle("Portable client");
        ask.setHeaderText(null);
        ask.getDialogPane().setStyle(Ui.FONT);
        ask.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        if(ask.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK)
            copy(owner, clientDir, savedataDir);
    }

    /** Whether the game's own store is there to copy from. */
    static boolean hasCache() {
        Path source = oldCacheDir();
        return (source != null) && Files.isDirectory(source);
    }

    /** The copy window, without asking: scan, pick the worlds, copy; on the JavaFX thread, over
     *  <code>owner</code>, returning when it closes. Nothing when there is no cache. Arguments as {@link #offer}. */
    static void copy(Stage owner, Path clientDir, String savedataDir) {
        if(!hasCache())
            return;
        Path source = oldCacheDir();
        Path dir = savedataDir.isBlank() ? clientDir.resolve("savedata") : clientDir.resolve(savedataDir.trim());
        Label status = new Label("Scanning " + source + "…");
        status.setWrapText(true);
        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setMaxWidth(Double.MAX_VALUE);
        VBox worlds = new VBox(4 * Ui.SCALE);
        Button copy = new Button("Copy");
        copy.setDefaultButton(true);
        copy.setVisible(false);
        copy.setManaged(false);
        Button close = new Button("Close");
        close.setCancelButton(true);
        VBox root = new VBox(8 * Ui.SCALE, status, bar, worlds, OptionsDialog.buttons(copy, close));
        root.setAlignment(Pos.TOP_LEFT);
        root.setPadding(new Insets(12 * Ui.SCALE, 16 * Ui.SCALE, 12 * Ui.SCALE, 16 * Ui.SCALE));   // Ui.dialog sets the style: no padding there
        root.setPrefWidth(440 * Ui.SCALE);
        Stage d = Ui.dialog(owner, "Portable client", root);
        close.setOnAction(ev -> d.close());

        new Thread(() -> {
            Map<String, List<File>> found = scan(source, (n, of) -> Platform.runLater(() -> {
                status.setText("Scanning: " + n + " / " + of);
                bar.setProgress((double)n / of);
            }));
            Platform.runLater(() -> {
                if(found.isEmpty()) {
                    status.setText("Nothing to copy: no map of " + String.join(", ", WORLDS.values()) + " in " + source + ".");
                    bar.setProgress(1);
                    return;
                }
                status.setText("Worlds to copy:");
                bar.setVisible(false);
                bar.setManaged(false);
                List<CheckBox> boxes = new ArrayList<>();
                found.forEach((genus, files) -> {
                    CheckBox box = new CheckBox(WORLDS.get(genus) + " (" + files.size() + " entries)");
                    box.setSelected(true);
                    box.setUserData(genus);
                    boxes.add(box);
                });
                worlds.getChildren().setAll(boxes);
                copy.setVisible(true);
                copy.setManaged(true);
                d.sizeToScene();
                copy.setOnAction(ev -> {
                    List<File> files = new ArrayList<>();
                    for(CheckBox box : boxes)
                        if(box.isSelected())
                            files.addAll(found.get((String)box.getUserData()));
                    worlds.getChildren().clear();
                    copy.setVisible(false);
                    copy.setManaged(false);
                    close.setDisable(true);
                    bar.setVisible(true);
                    bar.setManaged(true);
                    bar.setProgress(0);
                    d.sizeToScene();
                    new Thread(() -> {
                        String done;
                        try {
                            int n = migrate(files, clientDir, dir, (c, of) -> Platform.runLater(() -> {
                                status.setText("Copying: " + c + " / " + of);
                                bar.setProgress((double)c / of);
                            }));
                            done = n + " entries copied into " + dir.resolve("map.sqlite") + ".";
                        } catch(Exception e) {
                            done = "The copy failed: " + e.getMessage();
                        }
                        String text = done;
                        Platform.runLater(() -> {
                            status.setText(text);
                            bar.setProgress(1);
                            close.setDisable(false);
                        });
                    }, "sqlite-copy").start();
                });
            });
        }, "sqlite-scan").start();
        d.showAndWait();
    }

    /** Progress: <code>n</code> of <code>of</code> done. */
    private interface Progress {
        void at(int n, int of);
    }

    /** The client's <code>Config.localdir()</code> + <code>data</code>; null when there is none. */
    private static Path oldCacheDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if(os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            return (appdata == null) ? null : Paths.get(appdata, "Haven and Hearth", "data");
        }
        if(os.contains("mac"))
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Haven and Hearth", "data");
        return Paths.get(System.getProperty("user.home"), ".haven", "data");
    }

    /** The cache files of <code>dir</code> — <code>HashDirCache</code>'s names, 16 hex digits, a dot, a number —
     *  whose entry belongs to a world in {@link #WORLDS}, by genus, in the order found. */
    private static Map<String, List<File>> scan(Path dir, Progress progress) {
        File[] files = dir.toFile().listFiles((d, n) -> n.matches("[0-9a-f]{16}\\.\\d+"));
        Map<String, List<File>> found = new LinkedHashMap<>();
        if(files == null)
            return found;
        for(int i = 0; i < files.length; i++) {
            String name = name(files[i]);
            String genus = (name == null) ? null : genus(name);
            if(genus != null && WORLDS.containsKey(genus))
                found.computeIfAbsent(genus, g -> new ArrayList<>()).add(files[i]);
            if((i + 1) % 1000 == 0)
                progress.at(i + 1, files.length);
        }
        return found;
    }

    /** The entry name in a cache file's header (version 1, cache id, name); null for anything else. */
    private static String name(File f) {
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 512))) {
            if(in.readByte() != 1)
                return null;
            in.readUTF();
            return in.readUTF();
        } catch(IOException e) {
            return null;
        }
    }

    /** The world of an entry: <code>map/&lt;genus&gt;/…</code> or <code>data/mm-icons-2/&lt;genus&gt;[/…]</code>;
     *  null for any other entry (resources above all). */
    private static String genus(String name) {
        for(String prefix : new String[] {"map/", "data/mm-icons-2/"}) {
            if(name.startsWith(prefix)) {
                int end = name.indexOf('/', prefix.length());
                if(end < 0)
                    end = prefix.equals("map/") ? -1 : name.length();
                return (end > prefix.length()) ? name.substring(prefix.length(), end) : null;
            }
        }
        return null;
    }

    /** The table of an entry, the client's rule: <code>map-&lt;genus&gt;</code> for the map, else <code>entries</code>. */
    private static String table(String name) {
        return name.startsWith("map/") ? "map-" + genus(name) : "entries";
    }

    /** Copy <code>files</code> into <code>dir/map.sqlite</code>; returns the entries written. The driver is the
     *  client's, <code>lib/sqlite-jdbc-*.jar</code>. */
    private static int migrate(List<File> files, Path clientDir, Path dir, Progress progress) throws Exception {
        Path jar;
        try(Stream<Path> s = Files.list(clientDir.resolve("lib"))) {
            jar = s.filter(p -> p.getFileName().toString().matches("sqlite-jdbc.*\\.jar")).findFirst()
                .orElseThrow(() -> new IOException("no sqlite-jdbc driver in " + clientDir.resolve("lib")));
        }
        Files.createDirectories(dir);
        Path db = dir.resolve("map.sqlite");
        try(URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, DataMigrator.class.getClassLoader())) {
            Driver driver = (Driver)Class.forName("org.sqlite.JDBC", true, loader).getDeclaredConstructor().newInstance();
            try(Connection conn = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties())) {
                try(Statement st = conn.createStatement()) {
                    int have;
                    try(ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                        have = rs.next() ? rs.getInt(1) : 0;
                    }
                    if(have != 0 && have != SCHEMA)
                        throw new IOException(db + " has schema " + have + ", this copy writes " + SCHEMA + ": not touched");
                    st.execute("PRAGMA busy_timeout = 3000");
                    st.execute("CREATE TABLE IF NOT EXISTS entries" + COLUMNS);
                    if(have == 0)
                        st.execute("PRAGMA user_version = " + SCHEMA);
                }
                conn.setAutoCommit(false);
                Map<String, PreparedStatement> inserts = new LinkedHashMap<>();
                int n = 0;
                try {
                    for(File f : files) {
                        try(DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                            if(in.readByte() == 1) {
                                in.readUTF();
                                String name = in.readUTF();
                                PreparedStatement ps = inserts.get(table(name));
                                if(ps == null)
                                    inserts.put(table(name), ps = insert(conn, table(name)));
                                ps.setString(1, name);
                                ps.setBytes(2, in.readAllBytes());
                                ps.setLong(3, f.lastModified());
                                ps.addBatch();
                            }
                        } catch(IOException e) {
                            // a truncated file: skipped, as the client would
                        }
                        if(++n % BATCH == 0) {
                            for(PreparedStatement ps : inserts.values())
                                ps.executeBatch();
                            conn.commit();
                            progress.at(n, files.size());
                        }
                    }
                    for(PreparedStatement ps : inserts.values())
                        ps.executeBatch();
                    conn.commit();
                } finally {
                    for(PreparedStatement ps : inserts.values())
                        ps.close();
                }
                return n;
            }
        }
    }

    /** The UPSERT into <code>table</code>, made first when it is new; the newer row wins. */
    private static PreparedStatement insert(Connection conn, String table) throws Exception {
        String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
        try(Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + quoted + COLUMNS);
        }
        return conn.prepareStatement("INSERT INTO " + quoted + " (name, data, mtime) VALUES (?, ?, ?)"
            + " ON CONFLICT (name) DO UPDATE SET data = excluded.data, mtime = excluded.mtime"
            + " WHERE excluded.mtime > " + quoted + ".mtime");
    }
}
