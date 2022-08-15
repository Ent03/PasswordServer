package fi.samppa.server;


import java.io.IOException;

public class Main {
    public static Server server;
    public static int port = 5002;

    public static void main(String[] args) throws IOException {
        server = new Server();

        server.bind(port);
        server.start();
    }
}
