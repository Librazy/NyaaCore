package cat.nyaa.nyaacore.database;

import org.apache.commons.lang.Validate;

import java.sql.*;
import java.util.*;

public abstract class BaseDatabase implements Cloneable {

    /* TableName to TableStructure */
    protected final Map<String, TableStructure<?>> tables;
    /* TableClass to TableName */
    protected final Map<Class<?>, String> tableName;

    /**
     * The return value should be constant
     * and returned classes should be annotated by @DataTable().
     *
     * @return Table classes in this database.
     */
    protected abstract Class<?>[] getTables();

    /* auto commit should be set to `true` for the returned connection */
    protected abstract Connection getConnection();

    public abstract void close();

    /**
     * Scan & construct all table structures.
     */
    protected BaseDatabase() {
        tables = new HashMap<>();
        tableName = new HashMap<>();
        for (Class<?> tableClass : getTables()) {
            TableStructure<?> tableStructure = TableStructure.fromClass(tableClass);
            if (tableStructure == null) throw new RuntimeException();
            tables.put(tableStructure.getTableName(), tableStructure);
            tableName.put(tableClass, tableStructure.getTableName());
        }
    }

    protected void createTables() {
        for (TableStructure<?> c : tables.values()) {
            createTable(c);
        }
    }

    protected void createTable(String name) {
        Validate.notNull(name);
        createTable(tables.get(name));
    }

    public void createTable(Class<?> cls) {
        Validate.notNull(cls);
        createTable(tableName.get(cls));
    }

    /**
     * Create a table in this database.
     * Note the table doesn't have to be defined in getTables().
     *
     * @param struct The table definition
     */
    protected void createTable(TableStructure<?> struct) {
        Validate.notNull(struct);
        String sql = struct.getCreateTableSQL();
        try {
            Statement smt = getConnection().createStatement();
            smt.executeUpdate(sql);
            smt.close();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * build a statement using provided parameters
     *
     * @param sql            SQL string
     * @param replacementMap {{key}} in the file will be replaced by value. Ignored if null. NOTE: sql injection will happen
     * @param parameters     JDBC's positional parametrized query.
     * @return statement
     */
    public PreparedStatement buildStatement(String sql, Map<String, String> replacementMap, Object... parameters) {
        if (replacementMap != null) {
            for (String key : replacementMap.keySet()) {
                sql = sql.replace("{{" + key + "}}", replacementMap.get(key));
            }
        }
        try {
            PreparedStatement stmt = getConnection().prepareStatement(sql);
            for (int i = 0; i < parameters.length; i++) {
                stmt.setObject(i + 1, parameters[i]);
            }
            return stmt;
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Convert a result set to a list of java objects
     *
     * @param rs  the result set
     * @param cls record type
     * @param <T> record type, do not have to be registered in getTables()
     * @return java object list
     */
    public <T> List<T> parseResultSet(ResultSet rs, Class<T> cls) {
        try {
            if (rs == null) return new ArrayList<>();
            TableStructure<T> table = TableStructure.fromClass(cls);
            List<T> results = new ArrayList<T>();
            while (rs.next()) {
                T obj = table.getObjectFromResultSet(rs);
                results.add(obj);
            }
            return results;
        } catch (SQLException | ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Return the Query object for specified table class.
     *
     * @return Query object
     */
    public <T> Query<T> query(Class<T> tableClass) {
        return new SQLiteQuery<>(tableClass);
    }

    public class SQLiteQuery<T> implements Query<T> {
        private TableStructure<T> table;
        /* NOTE: the values in the map must be SQL-type objects */
        private Map<String, Object> whereClause = new HashMap<>();

        public SQLiteQuery(Class<T> tableClass) {
            if (!tableName.containsKey(tableClass)) throw new IllegalArgumentException("Unknown Table");
            if (!tables.containsKey(tableName.get(tableClass))) throw new IllegalArgumentException("Unknown Table");
            table = (TableStructure<T>) tables.get(tableName.get(tableClass));
        }


        /**
         * clear the where clauses
         *
         * @return self
         */
        @Override
        public Query<T> clear() {
            whereClause.clear();
            return this;
        }

        @Override
        public Query<T> whereEq(String columnName, Object obj) {
            return where(columnName, "=", obj);
        }

        /**
         * comparator can be any SQL comparator.
         * e.g. =, >, <
         */
        @Override
        public Query<T> where(String columnName, String comparator, Object obj) {
            if (!table.hasColumn(columnName)) throw new IllegalArgumentException("Unknown DataColumn Name");
            obj = table.getColumn(columnName).toDatabaseType(obj);
            whereClause.put(columnName + comparator + "?", obj);
            return this;
        }

        /**
         * remove records matching the where clauses
         */
        @Override
        public void delete() {
            String sql = "DELETE FROM " + table.getTableName();
            List<Object> objects = new ArrayList<>();
            if (whereClause.size() > 0) {
                sql += " WHERE";
                for (Map.Entry e : whereClause.entrySet()) {
                    if (objects.size() > 0) sql += " AND";
                    sql += " " + e.getKey();
                    objects.add(e.getValue());
                }
            }
            try {
                PreparedStatement stmt = getConnection().prepareStatement(sql);
                int x = 1;
                for (Object obj : objects) {
                    stmt.setObject(x, obj);
                    //ColumnType.setPreparedStatement(stmt, x, obj);
                    x++;
                }
                stmt.execute();
                stmt.close();
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }

        /**
         * the where clauses are ignored
         *
         * @param object record to be inserted
         */
        @Override
        public void insert(T object) {
            try {
                String sql = String.format("INSERT INTO %s(%s) VALUES(?", table.getTableName(), table.getColumnNamesString());
                for (int i = 1; i < table.columns.size(); i++) sql += ",?";
                sql += ")";
                PreparedStatement stmt = getConnection().prepareStatement(sql);
                Map<String, Object> objMap = table.getColumnObjectMap(object);
                for (int i = 1; i <= table.orderedColumnName.size(); i++) {
                    String colName = table.orderedColumnName.get(i - 1);
                    if (!objMap.containsKey(colName) || objMap.get(colName) == null) {
                        stmt.setNull(i, Types.NULL);
                    } else {
                        stmt.setObject(i, objMap.get(colName));
                    }
                }
                stmt.execute();
                stmt.close();
            } catch (SQLException | ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }


        /**
         * SELECT * FROM this_table WHERE ...
         *
         * @return all select rows
         */
        @Override
        public List<T> select() {
            String sql = "SELECT " + table.getColumnNamesString() + " FROM " + table.tableName;
            List<Object> objects = new ArrayList<>();
            if (whereClause.size() > 0) {
                sql += " WHERE";
                for (Map.Entry e : whereClause.entrySet()) {
                    if (objects.size() > 0) sql += " AND";
                    sql += " " + e.getKey();
                    objects.add(e.getValue());
                }
            }
            try {
                PreparedStatement stmt = getConnection().prepareStatement(sql);
                int x = 1;
                for (Object obj : objects) {
                    stmt.setObject(x, obj);
                    x++;
                }
                List<T> results = new ArrayList<T>();
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    T obj = table.getObjectFromResultSet(rs);
                    results.add(obj);
                }
                stmt.close();
                return results;
            } catch (SQLException | ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }

        /**
         * Select only one record.
         *
         * @return the record, or throw exception if not unique
         */
        @Override
        public T selectUnique() {
            T result = selectUniqueUnchecked();
            if (result == null) throw new RuntimeException("SQL Selection has no result or not unique");
            return result;
        }

        /**
         * Select only one record.
         *
         * @return the record, or null if not unique.
         */
        public T selectUniqueUnchecked() {
            String sql = "SELECT " + table.getColumnNamesString() + " FROM " + table.tableName;
            List<Object> objects = new ArrayList<>();
            if (whereClause.size() > 0) {
                sql += " WHERE";
                for (Map.Entry e : whereClause.entrySet()) {
                    if (objects.size() > 0) sql += " AND";
                    sql += " " + e.getKey();
                    objects.add(e.getValue());
                }
            }
            try {
                PreparedStatement stmt = getConnection().prepareStatement(sql);
                int x = 1;
                for (Object obj : objects) {
                    stmt.setObject(x, obj);
                    x++;
                }
                T result = null;
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    result = table.getObjectFromResultSet(rs);
                    if (rs.next()) result = null;
                }
                stmt.close();
                return result;
            } catch (SQLException | ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }

        /**
         * A short hand for select().size();
         *
         * @return number of records to be selected.
         */
        @Override
        public int count() {
            String sql = "SELECT COUNT(*) AS C FROM " + table.tableName;
            List<Object> objects = new ArrayList<>();
            if (whereClause.size() > 0) {
                sql += " WHERE";
                for (Map.Entry e : whereClause.entrySet()) {
                    if (objects.size() > 0) sql += " AND";
                    sql += " " + e.getKey();
                    objects.add(e.getValue());
                }
            }
            try {
                PreparedStatement stmt = getConnection().prepareStatement(sql);
                int x = 1;
                for (Object obj : objects) {
                    stmt.setObject(x, obj);
                    x++;
                }
                List<T> results = new ArrayList<T>();
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    int count = rs.getInt("C");
                    stmt.close();
                    return count;
                } else {
                    stmt.close();
                    throw new RuntimeException("COUNT() returns empty result");
                }
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }

        /**
         * Update record according to the where clauses
         *
         * @param obj     new values for columns
         * @param columns columns need to be updated, update all columns if empty
         */
        @Override
        public void update(T obj, String... columns) {
            try {
                List<String> updatedColumns = new ArrayList<>();
                Map<String, Object> newValues = table.getColumnObjectMap(obj, columns);
                if (columns == null || columns.length <= 0) {
                    updatedColumns.addAll(table.orderedColumnName);
                } else {
                    for (String col : columns) {
                        if (!table.columns.containsKey(col))
                            throw new IllegalArgumentException("Unknown Column Name: " + col);
                    }
                    updatedColumns.addAll(Arrays.asList(columns));
                }

                List<Object> parameters = new ArrayList<>();
                String sql = "UPDATE " + table.tableName + " SET ";
                for (int i = 0; i < updatedColumns.size(); i++) {
                    if (i > 0) sql += ",";
                    sql += updatedColumns.get(i) + "=?";
                    parameters.add(newValues.get(updatedColumns.get(i)));
                }

                boolean firstClause = true;
                if (whereClause.size() > 0) {
                    sql += " WHERE";
                    for (Map.Entry e : whereClause.entrySet()) {
                        if (!firstClause) sql += " AND";
                        firstClause = false;
                        sql += " " + e.getKey();
                        parameters.add(e.getValue());
                    }
                }

                PreparedStatement stmt = getConnection().prepareStatement(sql);
                int idx = 1;
                for (Object o : parameters) {
                    if (o == null) {
                        stmt.setNull(idx, Types.NULL);
                    } else {
                        stmt.setObject(idx, o);
                    }
                    idx++;
                }
                stmt.execute();
                stmt.close();
            } catch (ReflectiveOperationException | SQLException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
