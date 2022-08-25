package fi.samppa.server.sql.drivers;



import fi.samppa.server.Server;
import fi.samppa.server.config.Config;
import fi.samppa.server.sql.SQLStorage;
import fi.samppa.server.sql.SQLTableManager;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;

public abstract class StorageDriver {
    protected static HashMap<String, SQLTableManager> tables = new HashMap<>();
    protected final Config sqlSettings;
    protected SQLStorage sqlStorage;

    public StorageDriver(SQLStorage sqlStorage, Config sqlSettings) {
        this.sqlSettings = sqlSettings;
        this.sqlStorage = sqlStorage;
    }


    public void createDatabase() throws SQLException {
        if (databaseIsEmpty()) {
            sqlStorage.createTables();
        } else {
            //Check for columns and such
            sqlStorage.createTables();
        }
    }

    public void insertOrUpdate(String table, String primaryKey, List<String> columnNames, List<Object> columnValues,
                               List<String> onDuplicateColumnNames, List<Object> onDuplicateColumnValues, String operation) {

    }

    public void insert(String table, List<String> columnNames, List<Object> columnValues){
        try {
            Connection connection = getConnection();
            String columns = "(" + String.join(",", columnNames) + ")";
            String values = "(" + StringUtils.repeat( "?,", columnValues.size()-1) + "?)";


            String insertPart = String.format("INSERT INTO %s %s VALUES %s ", table, columns, values);
            PreparedStatement statement = connection.prepareStatement(insertPart);
            for (int i = 1; i < columnNames.size() + 1; i++) {
                statement.setObject(i, columnValues.get(i - 1));
            }
            statement.executeUpdate();
        }
        catch (SQLException e){
            e.printStackTrace();
        }
    }

    public void update(String table, String primaryKey, String primaryKeyValue,
                       List<String> columnNames, List<Object> columnValues){
        try {
            Connection connection = getConnection();

            String replaceValues = String.join("=?"+",", columnNames) + "=?";

            String insertPart = String.format("UPDATE %s SET %s WHERE %s=?", table, replaceValues, primaryKey);
            Server.logger.info("final update " + insertPart);
            PreparedStatement statement = connection.prepareStatement(insertPart);
            statement.setString(1, primaryKeyValue);
            for (int i = 1; i < columnNames.size() + 1; i++) {
                statement.setObject(i+1, columnValues.get(i - 1));
            }
            statement.executeUpdate();
        }
        catch (SQLException e){
            e.printStackTrace();
        }
    }

    public abstract Connection getConnection() throws SQLException;

    public abstract boolean tableExists(Connection connection, String table) throws SQLException;

    public abstract void addTable(Connection connection, SQLTableManager tableManager) throws SQLException;

    public abstract boolean databaseIsEmpty();

    public void connectToDatabase() throws SQLException {
        initDatabase();
        try {
            createDatabase();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }

    }

    public abstract void initDatabase() throws SQLException;

    public abstract void closeConnection(Connection connection) throws SQLException;
}
