package fi.samppa.server.sql;


import fi.samppa.server.config.Config;
import fi.samppa.server.sql.drivers.LocalSQLLiteDriver;
import fi.samppa.server.sql.drivers.RemoteHikariDriver;
import fi.samppa.server.sql.drivers.StorageDriver;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;


public abstract class SQLStorage implements ISQLStorage {
    protected HashMap<String, SQLTableManager> tables = new HashMap<>();

    protected DatabaseType type;

    public static boolean connected = false;

    protected File dataFolder;

    protected final Config sqlSettings;

    protected StorageDriver storageDriver;

    protected AsyncDatabaseThread asyncThread;
    
    public SQLStorage(Config sqlSettings){
        this.sqlSettings = sqlSettings;
        
        this.asyncThread = new AsyncDatabaseThread(this);
        this.asyncThread.start();
        determineDriver();
    }

    public void onTableCreate(String table) {

    }


    public StorageDriver getStorageDriver() {
        return storageDriver;
    }

    public Config getSqlSettings() {
        return sqlSettings;
    }

    public AsyncDatabaseThread getAsyncThread() {
        return asyncThread;
    }

    private void determineDriver(){
        String type = sqlSettings.getProperty("db-type", "local");
        if(type.equalsIgnoreCase("local")){
            this.storageDriver = new LocalSQLLiteDriver(this, sqlSettings);
        }
        else {
            this.storageDriver = new RemoteHikariDriver(this, sqlSettings);
        }
    }
    public SQLTableManager getTableManager(String table){
        return tables.get(table);
    }

    public HashMap<String, SQLTableManager> getTables() {
        return tables;
    }

    public boolean tableExists(String table){
        Connection connection = null;
        try {
            connection = getConnection();
            return getStorageDriver().tableExists(connection, table);
        }
        catch (Exception e){
            e.printStackTrace();
        }
        finally {
            try { if(connection != null) closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
        }
        return false;
    }

    public void addTable(Connection connection, SQLTableManager sqlTableManager) throws SQLException {
        sqlTableManager.setSqlStorage(this);
        tables.put(sqlTableManager.getTable(), sqlTableManager);
        storageDriver.addTable(connection, sqlTableManager);
    }

    @Override
    public abstract void createTables() throws SQLException;

    public void connectToDatabase() {
        try {
            storageDriver.connectToDatabase();
        }
        catch (SQLException e){
            e.printStackTrace();
        }
    }

    public void truncateTable(String table){
        getAsyncThread().newTask(()->{
            Connection connection = null;
            try {
                connection = getConnection();
                PreparedStatement statement = connection.prepareStatement(String.format("TRUNCATE TABLE %s", table));
                statement.executeUpdate();
            }
            catch (Exception e){
                e.printStackTrace();
            }
            finally {
                try { if(connection != null) closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
            }
        });
    }

    public Connection getConnection() throws SQLException {
        return storageDriver.getConnection();
    }

    public void closeConnection(Connection connection) throws SQLException{
        storageDriver.closeConnection(connection);
    }

    public void insertOrUpdate(String table, String primaryKey, List<String> columnNames, List<Object> columnValues,
                               List<String> onDuplicateColumnNames, List<Object> onDuplicateColumnValues, String operation){

        storageDriver.insertOrUpdate(table, primaryKey, columnNames, columnValues, onDuplicateColumnNames, onDuplicateColumnValues, operation);
    }

    public void insertOrUpdate(String table, String primaryKey, List<String> columnNames, List<Object> columnValues,
                               List<String> onDuplicateColumnNames, List<Object> onDuplicateColumnValues){

        storageDriver.insertOrUpdate(table, primaryKey, columnNames, columnValues, onDuplicateColumnNames, onDuplicateColumnValues, " = ?");
    }

    public void insert(String table, List<String> columnNames, List<Object> columnValues){
        storageDriver.insert(table, columnNames, columnValues);
    }

    public void update(String primaryKey, String primaryKeyValue, String table, List<String> columnNames, List<Object> columnValues){
        storageDriver.update(primaryKey, primaryKeyValue, table, columnNames, columnValues);
    }
//
//    public JSONObject getJSONObject(String column, String table, String lookup, Object lookupValue){
//        Connection connection = null;
//        try {
//            connection = getConnection();
//            PreparedStatement statement = connection.prepareStatement(String.format("SELECT %s FROM %s WHERE %s=?", column,table,lookup));
//            statement.setObject(1, lookupValue);
//            ResultSet results = statement.executeQuery();
//            results.next();
//            String keys = results.getString(column);
//            JSONParser parser = new JSONParser();
//            return (JSONObject) parser.parse(keys);
//        }
//        catch (Exception e){
//            e.printStackTrace();
//        }
//        finally {
//            try { if(connection != null) closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
//        }
//        return null;
//    }


    public Object getValue(String column, String table , String lookupColumn, String lookupValue){
        return getValue(column,table,lookupColumn,lookupValue,true);
    }

    public Object getValue(String column, String table , String lookupColumn, String lookupValue, boolean requireLookup){
        Connection connection = null;
        PreparedStatement statement = null;
        try {
            connection = getConnection();
            if(requireLookup){
                statement = connection.prepareStatement(String.format("SELECT %s FROM %s WHERE %s=?", column, table, lookupColumn));
                statement.setString(1, lookupValue);
            }
            else {
                statement = connection.prepareStatement(String.format("SELECT %s FROM %s", column, table));
            }
            ResultSet rs = statement.executeQuery();
            if(!rs.next()) {
                return null;
            }
            return rs.getObject(column);
        }
        catch (Exception e){
            e.printStackTrace();
        }
        finally {
            try { if(connection != null) closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
        }
        return null;
    }

    public Object getValue(String column, String table){
        return getValue(column, table, null, null, false);
    }

    public void updateValue(String table, String column, String lookupColumn, Object lookupValue, Object value){
        getAsyncThread().newTask(()->{
           updateValueSync(table,column,lookupColumn,lookupValue,value, true);
        });
    }


    public void updateValue(String table, String column, Object value){
        getAsyncThread().newTask(()->{
            updateValueSync(table,column,value);
        });
    }


    public void updateValueSync(String table, String column, Object value){
        updateValueSync(table,column, null, null, value, false);
    }

    public void updateValueSync(String table, String column, String lookupColumn, Object lookupValue, Object value, boolean requireLookup){
        Connection connection = null;
        try {
            connection = getConnection();
            PreparedStatement statement = null;
            if(requireLookup){
                statement = connection.prepareStatement(String.format("UPDATE %s SET %s=? WHERE %s=?", table,column,lookupColumn));
                statement.setObject(1, value);
                statement.setObject(2, lookupValue);
            }
            else {
                statement = connection.prepareStatement(String.format("UPDATE %s SET %s=?", table,column));
                statement.setObject(1, value);
            }

            statement.executeUpdate();
        }
        catch (Exception e){
            e.printStackTrace();
        }
        finally {
            try { if(connection != null) closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
        }
    }
    public void deleteValue(String table, String lookupColumn, Object lookupValue){
        getAsyncThread().newTask(()->{
            Connection connection = null;
            try {
                connection = getConnection();
                PreparedStatement statement = connection.prepareStatement(String.format("DELETE FROM %s WHERE %s=?", table,lookupColumn));
                statement.setObject(1, lookupValue);
                statement.executeUpdate();
            }
            catch (Exception e){
                e.printStackTrace();
            }
            finally {
                try { if(connection != null) closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
            }
        });
    }

}
