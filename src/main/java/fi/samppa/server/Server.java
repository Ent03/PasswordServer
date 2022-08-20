package fi.samppa.server;


import fi.samppa.server.clienthandling.Client;
import fi.samppa.server.clienthandling.ClientListener;
import fi.samppa.server.config.Config;
import fi.samppa.server.sql.SQLStorage;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.logging.Logger;

public class Server extends Thread{
    private ServerSocket serverSocket;
    public static Logger logger = Logger.getLogger("fi.samppa");

    public static String DATA_FOLDER = "data/";

    public MainDatabase database;

    public Config config;

    private HashMap<String, Client> users = new HashMap<>();

    public Server(MainDatabase mainDatabase){
        config = Config.initConfig(DATA_FOLDER, "config.properties");
        this.database = mainDatabase;
    }

    public void addUser(String username, Client client){
        users.put(username, client);
    }

    public Client getUser(String user){
        return users.get(user);
    }

    public void removeUser(String user){
        users.remove(user);
    }

    public void bind(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        System.out.println("Listening on port " + port);
    }

    public Set<String> getUsers(){
        return users.keySet();
    }

    public Collection<Client> getClients(){
        return users.values();
    }

    @Override
    public void run() {
        try {
            while (true){
                Socket clientSocket = serverSocket.accept();
                Client client = new Client(clientSocket);
                ClientListener clientListener = new ClientListener(this, client);
                clientListener.start();
                System.out.println("Client connected from " + client.getClientSocket().getInetAddress().getHostAddress());
            }
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }
}
