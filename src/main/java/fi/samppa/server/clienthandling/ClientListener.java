package fi.samppa.server.clienthandling;


import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import com.ning.compress.lzf.LZFDecoder;
import fi.samppa.server.*;
import fi.samppa.server.encryption.AESSecurityCap;
import org.checkerframework.checker.units.qual.A;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;


import javax.activation.MimetypesFileTypeMap;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

public class ClientListener extends Thread{
    //used to encrypt the files
    private BytesEncryptor bytesEncryptor;

    private Client client;
    private Server server;

    private boolean disconnected = false;

    private final DataInputStream dIn;

    private Argon2PasswordEncoder pwHasher = new Argon2PasswordEncoder();

    public ClientListener(Server server, Client client) throws Exception {
        this.client = client;
        this.server = server;
        this.dIn = new DataInputStream(client.getClientSocket().getInputStream());
        client.setHandler(this);
    }

    private void disconnectUser(String reason){
        if(disconnected) return;
        disconnected = true;
        server.removeUser(client.getUsername());
        client.closeSocket();
        System.out.println(String.format("User (%s) disconnected. (%s)", client.getUsername(), reason));
    }

    public void sendKey(){
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("key");
        out.writeUTF(AESSecurityCap.keyToString(client.getAesSecurityCap().getPublickey()));
        client.sendData(out.toByteArray());
    }

    public void sendAuthenticationStatus(AuthStatus status, String password, String salt){
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("authentication");
        output.writeUTF(status.name());
        if(status == AuthStatus.OK){
            output.writeUTF(salt);
            output.writeUTF(password);
        }
        client.sendEncrypted(output.toByteArray());
    }

    public void authenticateUser(String username, String password, MainDatabase.UserData data){
        client.setUsername(username);
        client.setAuthenticated();
        client.setCryptographyData(data.getCryptographyData());
        client.setUuid(data.getUuid());
        server.addUser(username, client);
        bytesEncryptor = Encryptors.standard(password, data.getCryptographyData().salt);
    }

    public void sendFileList(){
        File file = new File("user_files/"+client.getUsername());
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("host-list");
        String[] list = file.list((dir, name) -> !new File(dir, name).isDirectory());
        String result = list == null ? "" : String.join("\n", list);
        output.writeUTF(result);
        client.sendEncrypted(output.toByteArray());
    }

    public void sendFileInfo(File file){
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("file-info");
        output.writeUTF(file.getName());
        output.writeLong(file.length());
        client.sendEncrypted(output.toByteArray());
    }

    public void sendThumbnail(File file) throws IOException {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("host-file-thumbnail");
        output.writeUTF(file.getName());

        byte[] data = bytesEncryptor.decrypt(Files.readAllBytes(file.toPath()));
        output.writeInt(data.length);
        output.write(data);
        client.sendEncrypted(output.toByteArray());
    }

    public void sendFileListThumbnails() throws IOException {
        File folder = new File("user_files/"+client.getUsername()+"/thumbs");
        File[] list = folder.listFiles();
        if(list == null) return;
        for(File file : list){
            sendThumbnail(file);
        }
    }

    private BufferedImage scale(BufferedImage source, double ratio) {
        int w = (int)(source.getWidth() * ratio);
        int h = (int)(source.getHeight() * ratio);
        BufferedImage bi = getCompatibleImage(w, h);
        Graphics2D g2d = bi.createGraphics();
        double xScale = w / source.getWidth();
        double yScale = h / source.getHeight();
        AffineTransform at = AffineTransform.getScaleInstance(xScale, yScale);
        g2d.drawRenderedImage(source, at);
        g2d.dispose();
        return bi;
    }

    private BufferedImage getCompatibleImage(int w, int h) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();
        GraphicsConfiguration gc = gd.getDefaultConfiguration();
        BufferedImage image = gc.createCompatibleImage(w, h);
        return image;
    }

    public void sendSessionEncKey(String salt, String encKey){
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("session-key");
        output.writeUTF(salt);
        output.writeUTF(encKey);
        client.sendEncrypted(output.toByteArray());
    }

    public void sendPasswordData(MainDatabase.PasswordData data){
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("password-data");
        output.writeUTF(data.password);
        output.writeUTF(data.username);
        output.writeUTF(data.site);
        output.writeUTF(data.uuid.toString());
        client.sendEncrypted(output.toByteArray());
    }

    @Override
    public void run() {
        //queryTask();
        while (!client.isClosed()){
            try {
                int length = dIn.readInt();
                byte[] message = new byte[length];
                dIn.readFully(message);

                ByteArrayDataInput in;

                String subchannel;
                if(!client.isConnectionSecured()){
                    in = ByteStreams.newDataInput(message);
                    subchannel = in.readUTF();
                    if(subchannel.equals("sendkey")){
                        sendKey();
                        System.out.println("sent server side public key");
                    }
                    else if(subchannel.equals("receivekey")){
                        String key = in.readUTF();
                        client.getAesSecurityCap().setReceiverPublicKey(AESSecurityCap.keyFromString(key));
                        System.out.println("received client side public key");
                        client.setConnectionSecured(true);
                        client.sendChannelMessage("Key exchange completed");
                    }
                    continue;
                }
                in = ByteStreams.newDataInput(client.getAesSecurityCap().decryptBytes(message));
                subchannel = in.readUTF();

                if(subchannel.equals("session-key")){
                    String sessionID = in.readUTF();
                    String username = in.readUTF();

                    MainDatabase.UserData data = server.database.fetchUserData(username);
                    if(data == null) continue;

                    String key = server.database.getSessionEncKey(data.getUuid(), sessionID, pwHasher);

                    if(key != null){
                        //used to decrypt the encrypted password on the clients side so it can authenticate
                        sendSessionEncKey(data.getCryptographyData().salt, key);
                    }
                }

                if(!client.isAuthenticated()){
                    if(subchannel.equals("authentication")){
                        String name = in.readUTF();
                        String password = in.readUTF();
                        MainDatabase.UserData data = server.database.fetchUserData(name);
                        if(data == null){
                            client.sendChannelMessage("Invalid credentials. (User not found)");
                            sendAuthenticationStatus(AuthStatus.FAILED, password, "");
                            continue;
                        }
                        if(pwHasher.matches(password, data.getCryptographyData().hash)){
                            authenticateUser(name, password, data);
                            System.out.println("client authenticated as user " + name);
                            sendAuthenticationStatus(AuthStatus.OK, password, data.getCryptographyData().salt);
                            client.sendChannelMessage("Successfully authenticated");
                        }
                        else {
                            client.sendChannelMessage("Invalid credentials. (Wrong password)");
                            sendAuthenticationStatus(AuthStatus.FAILED, password, "");
                        }
                    }
                    else if(subchannel.equals("registration")){
                        String name = in.readUTF();
                        String password = in.readUTF();
                        String hash = pwHasher.encode(password);

                        MainDatabase.UserData data = server.database.fetchUserData(name);
                        if(data != null){
                            sendAuthenticationStatus(AuthStatus.ALREADY_REGISTERED, "", "");
                            continue;
                        }

                        data = server.database.saveUser(name, hash);

                        authenticateUser(name, password, data);
                        sendAuthenticationStatus(AuthStatus.OK, password, data.getCryptographyData().salt);
                        client.sendChannelMessage("Registered");
                    }
                }
                else if(subchannel.equals("message")){
                    String targetName = in.readUTF();
                    String targetMsg = in.readUTF();
                    Client target = server.getUser(targetName);
                    if(target == null) continue;
                    target.sendChannelMessage(targetMsg);
                }
                else if(subchannel.equals("create-session")){
                    String sessionID = in.readUTF();

                    sessionID = pwHasher.encode(sessionID); //hashing it
                    String newKey = server.database.saveSession(client.getUuid(), sessionID);
                    sendSessionEncKey(client.getCryptographyData().salt, newKey);
                }
                else if(subchannel.equals("save-password")){
                    String username = in.readUTF();
                    String password = in.readUTF();
                    String site = in.readUTF();
                    MainDatabase.PasswordData passwordData = server.database.savePassword(client.getUuid(), password, username, site);
                    sendPasswordData(passwordData);
                }
                else if(subchannel.equals("instruction")){
                    String instruction = in.readUTF();
                    if(instruction.equals("send-passwords")){
                        List<MainDatabase.PasswordData> passwords = server.database.getAllUserPasswords(client.getUuid());
                        for(MainDatabase.PasswordData passwordData : passwords){
                            sendPasswordData(passwordData);
                        }
                    }
                }
                else if(subchannel.equals("delete-password")){
                    UUID uuid = UUID.fromString(in.readUTF());
                    server.database.deletePassword(uuid);
                }
            } catch (IOException e) {
                try {
                    e.printStackTrace();
                    client.getClientSocket().close();
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            }
        }
        disconnectUser("Disconnected");
    }

}