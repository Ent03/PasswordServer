package fi.samppa.server.sql;



import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

public class SQLTableManager {
    private final LinkedHashMap<String, String> columns = new LinkedHashMap<>();
    private final String table;
    private final String primaryKeyName;
    private final String primaryKey;
    private SQLStorage storage;
    public SQLTableManager(String table, String primaryKey, String... columns){
        this.table = table;
        this.primaryKey = primaryKey;
        this.primaryKeyName = !primaryKey.isEmpty() ? primaryKey.split("\\(")[1].replace(")", "").trim() : "";
        for(String column : columns){
            String[] columnSplit = column.split(" ");
            String columnName = columnSplit[0].trim();
            this.columns.put(columnName, column);
        }
    }

    public void setSqlStorage(SQLStorage sqlStorage) {
        this.storage = sqlStorage;
    }

    public void updateValueFromPrimaryKey(String column, Object newValue, String primaryKey){
        if(this.primaryKeyName.isEmpty()) throw new IllegalArgumentException("Primary key must be specified.");
        storage.getAsyncThread().newTask(()->{
            Connection connection = null;
            try {
                connection = storage.getConnection();
                PreparedStatement statement = connection.prepareStatement(String.format("UPDATE %s SET %s = ? WHERE %s = ?", table, column, primaryKeyName));
                statement.setObject(1, newValue);
                statement.setString(2, primaryKey);
                statement.executeUpdate();
            }
            catch (SQLException e){
                e.printStackTrace();
            }
            finally {
                try { if(connection != null) storage.closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
            }
        });
    }

    public void updateValuesFromPrimaryKey(List<String> columns, List<Object> newValues, String primaryKeyValue){
        if(this.primaryKeyName.isEmpty()) throw new IllegalArgumentException("Primary key must be specified.");
        storage.update(table, primaryKeyName, primaryKeyValue, columns, newValues);
    }

    public void getValuesFromPrimaryKey(String primaryKeyLookup, Consumer<HashMap<String, Object>> values){
        if(this.primaryKeyName.isEmpty()) throw new IllegalArgumentException("Primary key must be specified.");
        Connection connection = null;
        HashMap<String, Object> objectHashMap = new HashMap<>();
        try {
            connection = storage.getConnection();
            PreparedStatement statement = connection.prepareStatement(String.format("SELECT * FROM %s WHERE %s=?", table, this.primaryKeyName));
            statement.setString(1, primaryKeyLookup);
            ResultSet resultSet = statement.executeQuery();
            if(!resultSet.next()){
                values.accept(objectHashMap);
                return;
            }
            for(String column : columns.keySet()){
                objectHashMap.put(column, resultSet.getObject(column));
            }
            values.accept(objectHashMap);
        }
        catch (SQLException e){
            e.printStackTrace();
            values.accept(objectHashMap);
        }
        finally {
            try { if(connection != null) storage.closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
        }
    }

    public HashMap<String, Object> getValuesFromPrimaryKey(String primaryKeyLookup){
        if(this.primaryKeyName.isEmpty()) throw new IllegalArgumentException("Primary key must be specified.");
        Connection connection = null;
        HashMap<String, Object> objectHashMap = new HashMap<>();
        try {
            connection = storage.getConnection();
            PreparedStatement statement = connection.prepareStatement(String.format("SELECT * FROM %s WHERE %s=?", table, this.primaryKeyName));
            statement.setString(1, primaryKeyLookup);
            ResultSet resultSet = statement.executeQuery();
            if(!resultSet.next()){
                return objectHashMap;
            }
            for(String column : columns.keySet()){
                objectHashMap.put(column, resultSet.getObject(column));
            }
            return objectHashMap;
        }
        catch (SQLException e){
            e.printStackTrace();
            return objectHashMap;
        }
        finally {
            try { if(connection != null) storage.closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
        }
    }

    public void deleteValueFromPrimaryKey(String primaryKeyLookup){
        if(this.primaryKeyName.isEmpty()) throw new IllegalArgumentException("Primary key must be specified.");
        storage.getAsyncThread().newTask(()->{
            Connection connection = null;
            try {
                connection = storage.getConnection();
                PreparedStatement statement = connection.prepareStatement(String.format("DELETE FROM %s WHERE %s=?", table, this.primaryKeyName));
                statement.setString(1, primaryKeyLookup);
                statement.executeUpdate();
            }
            catch (SQLException e){
                e.printStackTrace();
            }
            finally {
                try { if(connection != null) storage.closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
            }
        });
    }

    public void getValueFromPrimaryKey(String primaryKeyLookup, String column, Consumer<Object> value){
        if(this.primaryKeyName.isEmpty()) throw new IllegalArgumentException("Primary key must be specified.");
        storage.getAsyncThread().newTask(()->{
            Connection connection = null;
            try {
                connection = storage.getConnection();
                PreparedStatement statement = connection.prepareStatement(String.format("SELECT %s FROM %s WHERE %s=?", column, table, this.primaryKeyName));
                statement.setString(1, primaryKeyLookup);
                ResultSet resultSet = statement.executeQuery();
                if(!resultSet.next()) {
                    value.accept(null);
                    return;
                }
                Object result = resultSet.getObject(column);
                value.accept(result);
            }
            catch (SQLException e){
                e.printStackTrace();
            }
            finally {
                try { if(connection != null) storage.closeConnection(connection); } catch (SQLException e){ e.printStackTrace(); }
            }
        });
    }

    public String getTable() {
        return table;
    }

    public ArrayList<String> getColumns(){
        return new ArrayList<>(columns.values());
    }

    public String getPrimaryKey() {
        return primaryKey;
    }
}
