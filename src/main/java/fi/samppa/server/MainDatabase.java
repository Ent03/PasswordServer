package fi.samppa.server;

import fi.samppa.server.clienthandling.Client;
import fi.samppa.server.config.Config;
import fi.samppa.server.sql.SQLStorage;
import fi.samppa.server.sql.SQLTableManager;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.keygen.KeyGenerators;

import javax.annotation.Nullable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class MainDatabase extends SQLStorage {
    public MainDatabase(Config sqlSettings) {
        super(sqlSettings);
    }

    @Override
    public void createTables() throws SQLException {
        Connection connection = getConnection();
        addTable(connection, new SQLTableManager("users", "PRIMARY KEY(uuid)", "username text", "hash VARCHAR(256)", "salt text", "uuid VARCHAR(36)"));
        addTable(connection, new SQLTableManager("sessions", "PRIMARY KEY(sessionhash)", "uuid VARCHAR(36)", "sessionhash text(256)", "enc text"));
        addTable(connection, new SQLTableManager("passwords", "PRIMARY KEY(pwid)", "pwenc text", "userenc text", "user VARCHAR(36)", "site text", "pwid VARCHAR(36)"));
        closeConnection(connection);
    }

    public UserData saveUser(String name, String hash){
        String salt = KeyGenerators.string().generateKey();
        UUID uuid = UUID.randomUUID();
        List<String> cNames = Arrays.asList("username", "hash", "salt", "uuid");
        List<Object> cValues = Arrays.asList(name, hash, salt, uuid.toString());
        insertOrUpdate("users", "uuid", cNames, cValues, cNames, cValues);
        return new UserData(uuid, new Client.CryptographyData(salt, hash));
    }

    public void deletePassword(UUID password){
        getTableManager("passwords").deleteValueFromPrimaryKey(password.toString());
    }

    public PasswordData savePassword(UUID user, String password, String username, String site){
        UUID uuid = UUID.randomUUID();
        List<String> cNames = Arrays.asList("pwenc", "userenc", "user", "site", "pwid");
        List<Object> cValues = Arrays.asList(password, username, user.toString(), site, uuid);
        insertOrUpdate("passwords", "pwid", cNames, cValues, cNames, cValues);
        return new PasswordData(username, password, site, uuid);
    }
//
//    public @Nullable PasswordData getPasswordBySite(UUID user, String site){
//        Connection connection = null;
//        try {
//            connection = getConnection();
//            PreparedStatement statement = connection.prepareStatement("SELECT * FROM passwords WHERE uuid = ? AND site = ?");
//            statement.setString(1, user.toString());
//            statement.setString(2, site);
//            ResultSet rs = statement.executeQuery();
//            if(!rs.next()) return null;
//            return new PasswordData(rs.getString("userenc"), rs.getString("pwenc"), rs.getString("site"));
//        }
//        catch (SQLException e){
//            e.printStackTrace();
//        }
//        finally {
//            if(connection != null) {
//                try {
//                    closeConnection(connection);
//                } catch (SQLException e) {
//                    e.printStackTrace();
//                }
//            }
//        }
//        return null;
//    }

    public List<PasswordData> getAllUserPasswords(UUID user){
        Connection connection = null;
        List<PasswordData> list = new ArrayList<>();
        try {
            connection = getConnection();
            PreparedStatement statement = connection.prepareStatement("SELECT * FROM passwords WHERE user = ?");
            statement.setString(1, user.toString());
            ResultSet rs = statement.executeQuery();
            while (rs.next()){
                list.add(new PasswordData(rs.getString("userenc"), rs.getString("pwenc"), rs.getString("site"),
                        UUID.fromString(rs.getString("pwid"))));
            }
            return list;
        }
        catch (SQLException e){
            e.printStackTrace();
        }
        finally {
            if(connection != null) {
                try {
                    closeConnection(connection);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
        return list;
    }

    public String saveSession(UUID user, String hash){
        String key = KeyGenerators.string().generateKey();
        List<String> cNames = Arrays.asList("uuid", "sessionhash", "enc");
        List<Object> cValues = Arrays.asList(user, hash, key);
        insertOrUpdate("sessions", "sessionhash", cNames, cValues,cNames, cValues);
        return key;
    }

    public @Nullable String getSessionEncKey(UUID user, String receivedID, Argon2PasswordEncoder encoder){
        Connection connection = null;
        try {
            connection = getConnection();
            PreparedStatement statement = connection.prepareStatement("SELECT * FROM sessions WHERE uuid = ?");
            statement.setString(1, user.toString());
            ResultSet rs = statement.executeQuery();
            while (rs.next()){
                if(encoder.matches(receivedID, rs.getString("sessionhash"))) return rs.getString("enc");
            }
            return null;
        }
        catch (SQLException e){
            e.printStackTrace();
        }
        finally {
            if(connection != null) {
                try {
                    closeConnection(connection);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    public @Nullable UserData fetchUserData(String username){

        Connection connection = null;
        try {
            connection = getConnection();
            PreparedStatement statement = connection.prepareStatement("SELECT * FROM users WHERE username = ?");
            statement.setString(1, username);
            ResultSet rs = statement.executeQuery();
            if(!rs.next()) return null;
            UUID uuid = UUID.fromString(rs.getString("uuid"));
            Client.CryptographyData data = new Client.CryptographyData(rs.getString("salt"), rs.getString("hash"));
            return new UserData(uuid, data);
        }
        catch (SQLException e){
            e.printStackTrace();
        }
        finally {
            if(connection != null) {
                try {
                    closeConnection(connection);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;

    }

    public static class UserData{
        private UUID uuid;
        private Client.CryptographyData cryptographyData;

        public UserData(UUID uuid, Client.CryptographyData cryptographyData) {
            this.uuid = uuid;
            this.cryptographyData = cryptographyData;
        }

        public UUID getUuid() {
            return uuid;
        }

        public Client.CryptographyData getCryptographyData() {
            return cryptographyData;
        }
    }

    public static class PasswordData{
        public String username, password, site;
        public UUID uuid;

        public PasswordData(String username, String password, String site, UUID uuid) {
            this.username = username;
            this.uuid = uuid;
            this.password = password;
            this.site = site;
        }
    }

}
