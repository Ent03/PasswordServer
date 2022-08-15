package fi.samppa.server.clienthandling;



import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import fi.samppa.server.encryption.AESSecurityCap;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;

public class Client {
    private final Socket clientSocket;

    private UUID uuid;
    private String username;

    private boolean authenticated = false;
    private boolean connectionSecured = false;
    private boolean inSession = false;

    private CryptographyData cryptographyData;

    private ClientListener handler;

    private int ping = 0;

    private DataOutputStream dOut;

    private AESSecurityCap aesSecurityCap = new AESSecurityCap();

    public Client(Socket clientSocket) throws IOException {
        this.clientSocket = clientSocket;
        this.dOut = new DataOutputStream(clientSocket.getOutputStream());
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }


    public UUID getUuid() {
        return uuid;
    }

    public void setCryptographyData(CryptographyData cryptographyData) {
        this.cryptographyData = cryptographyData;
    }

    public CryptographyData getCryptographyData() {
        return cryptographyData;
    }

    public boolean isConnectionSecured() {
        return connectionSecured;
    }

    public void setConnectionSecured(boolean connectionSecured) {
        this.connectionSecured = connectionSecured;
    }

    public AESSecurityCap getAesSecurityCap() {
        return aesSecurityCap;
    }

    public void setHandler(ClientListener handler) {
        this.handler = handler;
    }

    public ClientListener getHandler() {
        return handler;
    }


    public int getPing() {
        return ping;
    }

    public void setPing(int ping) {
        this.ping = ping;
    }

    public void sendData(byte[] data) {
        try {
            dOut.writeInt(data.length); //writing length
            dOut.write(data);
        } catch (IOException e) {
            e.printStackTrace();
            closeSocket();
        }
    }

    public void sendEncrypted(byte[] data){
        sendData(aesSecurityCap.encryptBytes(data));
    }


    public void sendMessageRaw(String message)  {
        if(isClosed()) return;
        sendData(message.getBytes());
    }

    public void sendChannelMessage(String message){
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("message");
        out.writeUTF(message);
        sendEncrypted(out.toByteArray());
    }

    public void sendFileData(byte[] data){
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("file-data");
        out.writeInt(data.length);
        out.write(data);
        sendEncrypted(out.toByteArray());
    }

    public void sendCustomData(String channel, byte[] data){
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(channel);
        out.writeInt(data.length);
        out.write(data);
        sendEncrypted(out.toByteArray());
    }

    public boolean isInSession() {
        return inSession;
    }

    public void setInSession(boolean inSession) {
        this.inSession = inSession;
    }

    public void setUsername(String username){
        this.username = username;
    }

    public String getUsername() {
        if(!authenticated) return "Unauthenticated";
        return username;
    }

    public void setAuthenticated(){
        this.authenticated = true;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void closeSocket(){
        try {
            clientSocket.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Socket getClientSocket() {
        return clientSocket;
    }

    public boolean isClosed(){
        return clientSocket.isClosed();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Client client = (Client) o;
        return client.getClientSocket().equals(this.clientSocket);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientSocket);
    }

    public static class CryptographyData{
        public String salt, hash;

        public CryptographyData(String salt, String hash) {
            this.salt = salt;
            this.hash = hash;
        }
    }
}
