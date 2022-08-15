package fi.samppa.server.sql.drivers;



import com.zaxxer.hikari.HikariDataSource;
import fi.samppa.server.Server;
import fi.samppa.server.config.Config;
import fi.samppa.server.sql.SQLStorage;
import fi.samppa.server.sql.SQLTableManager;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class RemoteHikariDriver extends StorageDriver{
    private HikariDataSource hikari;

    public RemoteHikariDriver(SQLStorage sqlStorage, Config sqlSettings) {
        super(sqlStorage, sqlSettings);
    }


    @Override
    public Connection getConnection() throws SQLException {
        return hikari.getConnection();
    }

    @Override
    public boolean tableExists(Connection connection, String table) throws SQLException{
        PreparedStatement statement = connection.prepareStatement("SHOW TABLES LIKE ?");
        statement.setString(1, table);
        ResultSet resultSet = statement.executeQuery();
        boolean exists = resultSet.next();
        resultSet.close();
        return exists;
    }

    @Override
    public void insertOrUpdate(String table, String primaryKey, List<String> columnNames, List<Object> columnValues, List<String> onDuplicateColumnNames, List<Object> onDuplicateColumnValues, String operation) {

        try(Connection connection = getConnection()) { //auto-closeable
            String columns = "(" + String.join(",", columnNames) + ")";
            String values = StringUtils.repeat("(" + "?,", columnValues.size()-1) + "?)";

            String insertPart = String.format("INSERT INTO %s %s VALUES %s ", table, columns, values);

            String replaceValues = String.join(operation+",", onDuplicateColumnNames) + operation;

            String replacePart = String.format("ON DUPLICATE KEY UPDATE %s", replaceValues);

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
    public void addTable(Connection connection, SQLTableManager tableManager) throws SQLException {
        tables.put(tableManager.getTable(), tableManager);
        ArrayList<String> columns = tableManager.getColumns();
        if(tableExists(connection, tableManager.getTable())) {


            PreparedStatement checkColumn = connection.prepareStatement(String.format("SHOW COLUMNS FROM %s", tableManager.getTable()));
            ResultSet rs = checkColumn.executeQuery();
            HashMap<String, String> columnList = new HashMap<>();
            while (rs.next()) columnList.put(rs.getString("Field"), rs.getString("Type"));
            for(String columnFull : columns){
                String[] columnSplit = columnFull.split(" ");
                String column = columnSplit[0];
                String type = columnSplit[1];


                if(!columnList.containsKey(column)){
                    PreparedStatement addColumn = connection.prepareStatement(String.format("ALTER TABLE %s ADD %s", tableManager.getTable(), columnFull));
                    addColumn.executeUpdate();
                    Server.logger.info("[MySQL] §cAdded missing column §6" + column);
                }
                else {
                    String typeInDB = columnList.get(column);
                    if(!typeInDB.equalsIgnoreCase(type)){
                        Server.logger.info("[MySQL] §cChanged column §6" + column + " to type " + type + "in table " + tableManager.getTable());
                        PreparedStatement changeType = connection.prepareStatement((String.format("ALTER TABLE %s MODIFY %s %s", tableManager.getTable(), column, type)));
                        changeType.executeUpdate();
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
        Server.logger.info("§6[MySQL] §aTable " + tableManager.getTable() + " created!");
        sqlStorage.onTableCreate(tableManager.getTable());
    }

    @Override
    public boolean databaseIsEmpty() {
        try {
            String database = sqlSettings.getProperty("database");
            Connection connection = getConnection();
            PreparedStatement statement = connection.prepareStatement("SELECT COUNT(DISTINCT `table_name`) FROM `information_schema`.`columns` WHERE `table_schema` = ?");
            statement.setString(1, database);

            ResultSet resultSet = statement.executeQuery();
            resultSet.next();
            closeConnection(connection);
            return resultSet.getInt(1) == 0;
        }
        catch (SQLException e){
            e.printStackTrace();
        }

        return false;
    }

    protected void setupParameters(HikariDataSource hikari){
        hikari.addDataSourceProperty("cachePrepStmts", true);
        hikari.addDataSourceProperty("prepStmtCacheSize", 250);
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", 2048);
        hikari.addDataSourceProperty("useServerPrepStmts", true);
        hikari.addDataSourceProperty("useLocalSessionState", true);
        hikari.addDataSourceProperty("rewriteBatchedStatements", true);
        hikari.addDataSourceProperty("cacheResultSetMetadata", true);
        hikari.addDataSourceProperty("cacheServerConfiguration", true);
        hikari.addDataSourceProperty("elideSetAutoCommits", true);
        hikari.addDataSourceProperty("maintainTimeStats", false);
        hikari.setConnectionTimeout(5000);
    }

    @Override
    public void initDatabase() {
        String database = sqlSettings.getProperty("database");
        if(database.equals("notset")) {
            Server.logger.info("Cannot connect to a remote database before configuration is done!");
            return;
        }
        String host = sqlSettings.getProperty("host");
        String port = sqlSettings.getProperty("port");
        String username = sqlSettings.getProperty("username");
        String password = sqlSettings.getProperty("password");
        hikari = new HikariDataSource();
        hikari.setJdbcUrl(String.format("jdbc:mysql://%s:%s/%s?characterEncoding=UTF-8", host, port, database));
        setupParameters(hikari);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(20);

    }

    @Override
    public void closeConnection(Connection connection) throws SQLException {
        connection.close(); //return it to the connection pool
    }
}
