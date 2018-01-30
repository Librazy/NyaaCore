package cat.nyaa.nyaacore.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class SQLiteDatabase extends BaseDatabase implements Cloneable, RelationalDB {
    protected SQLiteDatabase() {
        super();
    }

    protected Connection dbConn;

    protected abstract String getFileName();

    protected abstract JavaPlugin getPlugin();

    protected void connect() {
        File dbFile = new File(getPlugin().getDataFolder(), getFileName());
        try {
            Class.forName("org.sqlite.JDBC");
            String connStr = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            getPlugin().getLogger().info("Connecting database: " + connStr);
            dbConn = DriverManager.getConnection(connStr);
            dbConn.setAutoCommit(true);
            createTables();
        } catch (ClassNotFoundException | SQLException ex) {
            dbConn = null;
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void close() {
        try {
            dbConn.close();
            dbConn = null;
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    final protected Connection getConnection() {
        return dbConn;
    }

    /**
     * Remember to close the new connection cloned.
     */
    @Override
    protected Object clone() throws CloneNotSupportedException {
        Object obj = super.clone();
        ((SQLiteDatabase) obj).connect();
        return obj;
    }

    /**
     * Execute a SQL file bundled with the plugin
     *
     * @param filename       full file name, including extension, in resources/sql folder
     * @param replacementMap {{key}} in the file will be replaced by value. Ignored if null. NOTE: sql injection will happen
     * @param cls            class of desired object
     * @param parameters     JDBC's positional parametrized query.
     * @return the result set, null if cls is null.
     */
    public <T> List<T> queryBundledAs(String filename, Map<String, String> replacementMap, Class<T> cls, Object... parameters) {
        String sql;
        try (
                InputStream inputStream = getPlugin().getResource("sql/" + filename);
                BufferedInputStream bis = new BufferedInputStream(inputStream);
                ByteArrayOutputStream buf = new ByteArrayOutputStream()
        ) {
            int result = bis.read();
            while (result != -1) {
                buf.write((byte) result);
                result = bis.read();
            }
            sql = buf.toString(StandardCharsets.UTF_8.name());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        try (PreparedStatement stat = buildStatement(sql, replacementMap, parameters)) {
            boolean hasResult = stat.execute();
            if (cls == null) {
                return null;
            } else if (hasResult) {
                return parseResultSet(stat.getResultSet(), cls);
            } else {
                return new ArrayList<>();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void queryBundled(String filename, Map<String, String> replacementMap, Object... parameters) {
        queryBundledAs(filename, replacementMap, null, parameters);
    }
}
