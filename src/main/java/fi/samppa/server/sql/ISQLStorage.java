package fi.samppa.server.sql;


import java.sql.Connection;
import java.sql.SQLException;

public interface ISQLStorage {

     void createTables() throws SQLException;
     void connectToDatabase();
     Connection getConnection() throws SQLException;
}
