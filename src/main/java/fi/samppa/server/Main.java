package fi.samppa.server;

import fi.samppa.server.config.Config;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import javax.swing.*;
import java.io.IOException;

@SpringBootApplication
public class Main {
    public static Server server;
    public static int port = 5002;
    public static MainDatabase database;
    public static Argon2PasswordEncoder pwHasher = new Argon2PasswordEncoder();

    public static void main(String[] args) throws IOException {
        database = new MainDatabase(Config.initConfig("data/", "sql.properties"));
        database.connectToDatabase();

        server = new Server(database);
        server.bind(port);
        server.start();

        //Starting the REST API
        SpringApplication.run(Main.class, args);

    }
}
