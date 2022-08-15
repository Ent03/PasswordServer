package fi.samppa.server.sql.drivers;



import fi.samppa.server.Server;
import fi.samppa.server.config.Config;
import fi.samppa.server.sql.SQLStorage;
import fi.samppa.server.sql.SQLTableManager;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class LocalSQLLiteDriver extends StorageDriver{
    protected File dataFolder;
    protected Connection connection;

    public LocalSQLLiteDriver(SQLStorage sqlStorage, Config sqlSettings) {
        super(sqlStorage, sqlSettings);
    }


    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    @Override
    public boolean tableExists(Connection connection, String table) throws SQLException {
        PreparedStatement statement = connection.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name=?");
        statement.setString(1, table);
        ResultSet resultSet = statement.executeQuery();
        boolean exists = resultSet.next();
        resultSet.close();
        return exists;
    }

    @Override
    public void addTable(Connection connection, SQLTableManager tableManager) throws SQLException {
        tables.put(tableManager.getTable(), tableManager);
        ArrayList<String> columns = tableManager.getColumns();
        if(tableExists(connection, tableManager.getTable())) {


            PreparedStatement checkColumn = connection.prepareStatement(String.format("pragma table_info('%s')", tableManager.getTable()));
            //checkColumn.setString(1, tableManager.getTable()); //throws syntax error for some reason if i dont format it directly
            ResultSet rs = checkColumn.executeQuery();
            HashMap<String, String> columnList = new HashMap<>();
            while (rs.next()) columnList.put(rs.getString("name"), rs.getString("type"));
            for(String columnFull : columns){
                String[] columnSplit = columnFull.split(" ");
                String column = columnSplit[0];
                String type = columnSplit[1];


                if(!columnList.containsKey(column)){
                    PreparedStatement addColumn = connection.prepareStatement(String.format("ALTER TABLE %s ADD %s", tableManager.getTable(), columnFull));
                    addColumn.executeUpdate();
                    Server.logger.info("[SQL] Added missing column §6" + column);
                }
                else {
                    String typeInDB = columnList.get(column);
                    if(!typeInDB.equalsIgnoreCase(type)){
                        Server.logger.info("[SQL] Changed column §6" + column + " to type " + type + "in table " + tableManager.getTable());

                        //SQLite does not support table MODIFY

                        PreparedStatement createColumn = connection.prepareStatement((String.format("ALTER TABLE %s ADD COLUMN %s", tableManager.getTable(), column+"temp")));
                        createColumn.executeUpdate();

                        PreparedStatement copyData = connection.prepareStatement(String.format("UPDATE %s SET %s=%s", tableManager.getTable(), column, column+"temp"));
                        copyData.executeUpdate();

                        PreparedStatement delOld = connection.prepareStatement((String.format("ALTER TABLE %s DROP COLUMN %s", tableManager.getTable(), column)));
                        delOld.executeUpdate();

                        PreparedStatement rename = connection.prepareStatement((String.format("ALTER TABLE %s RENAME COLUMN %s TO %s", tableManager.getTable(), column+"temp", column)));
                        rename.executeUpdate();
                    }
                }
            }
            rs.close();
            return;
        };
        StringBuilder tableBuilder = new StringBuilder();
        tableBuilder.append("CREATE TABLE ");
        tableBuilder.append(tableManager.getTable());
        tableBuilder.append("(");
        for(int i = 0; i < columns.size(); i++) {
            String c = columns.get(i);
            tableBuilder.append(c);
            if(i != columns.size()-1) tableBuilder.append(",");
        }
        if(!tableManager.getPrimaryKey().isEmpty()) tableBuilder.append(",");
        tableBuilder.append(tableManager.getPrimaryKey());
        tableBuilder.append(")");
        PreparedStatement statement = connection.prepareStatement(tableBuilder.toString());
        statement.execute();
        statement.close();
        Server.logger.info("[MySQL] §aTable " + tableManager.getTable() + " created!");
        sqlStorage.onTableCreate(tableManager.getTable());
    }

    @Override
    public void insertOrUpdate(String table, String primaryKey, List<String> columnNames, List<Object> columnValues,
                               List<String> onDuplicateColumnNames, List<Object> onDuplicateColumnValues, String operation){
        try {
            Connection connection = getConnection();
            String columns = "(" + String.join(",", columnNames) + ")";
            String values = "(" + StringUtils.repeat( "?,", columnValues.size()-1) + "?)";

            String insertPart = String.format("INSERT INTO %s %s VALUES %s ", table, columns, values);

            String replaceValues = String.join(operation+",", onDuplicateColumnNames) + operation;

            String replacePart = String.format("ON CONFLICT(%s) DO UPDATE SET %s", primaryKey, replaceValues);

            PreparedStatement statement = connection.prepareStatement(insertPart +
                    replacePart);

            for(int i = 1; i < columnNames.size()+1; i++){
                statement.setObject(i, columnValues.get(i-1));
            }

            for(int i = 1; i < onDuplicateColumnNames.size() + 1; i++){
                statement.setObject(columnNames.size() + i, onDuplicateColumnValues.get(i-1));
            }
            statement.executeUpdate();
        }
        catch (SQLException e){
            e.printStackTrace();
        }
    }

    @Override
    public boolean databaseIsEmpty() {
        try {
            Connection connection = getConnection();
            PreparedStatement statement = connection.prepareStatement("SELECT name FROM sqlite_master");

            ResultSet resultSet = statement.executeQuery();
            if(!resultSet.next()){
                return true;
            }

            return resultSet.getInt(1) == 0;
        }
        catch (SQLException e){
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void initDatabase() throws SQLException {
        String name = sqlSettings.getProperty("db-name") + ".db";
        Server.logger.info("Initializing local database " + name);
        this.dataFolder = new File(Server.DATA_FOLDER, "db/"+ name);
        if(!dataFolder.exists()){
            dataFolder.getParentFile().mkdirs();
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder);
    }

    @Override
    public void closeConnection(Connection connection) throws SQLException {
        //closeConnection(connection);
    }
}
