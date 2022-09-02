package fi.samppa.server;


import com.fasterxml.jackson.annotation.JsonIgnore;
import fi.samppa.server.logs.LogType;
import lombok.Data;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.tomcat.jni.FileInfo;
import org.apache.tomcat.util.http.fileupload.FileItemIterator;
import org.apache.tomcat.util.http.fileupload.FileItemStream;
import org.apache.tomcat.util.http.fileupload.servlet.ServletFileUpload;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/")
public class RestAPIController {
    private final int BLOCK_SIZE = 1024;

    private HashMap<String, SessionData> sessions = new HashMap<>();
    private HashMap<String, LoginAttempt> loginAttempts = new HashMap<>();

    private HashMap<String, ShareLink> shareLinks = new HashMap<>();

    private HttpHeaders getResponseHeaders(){
        HttpHeaders headers = new HttpHeaders();
        return headers;
    }

    private boolean isAuthorized(HttpHeaders headers){
        String token = headers.getFirst("authorization");
        SessionData sessionData = sessions.get(token);
        if(sessionData == null) {
            return false;
        }
        String ip = headers.getFirst("X-Real-IP");
        return !Main.server.config.getBoolean("production") || sessionData.ipAddr.equals(ip);
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<?> getFileInfo(@RequestHeader HttpHeaders headers, @PathVariable("id") String fileName) throws IOException {
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = getSessionData(headers);

        //File file = new File("user_files/"+sessionData.getUserData().getUuid().toString()+"/"+fileName);

        return new ResponseEntity<>(new FileInfo(fileName,"/api/v1/files/download?name="+fileName), getResponseHeaders(), HttpStatus.OK);
    }

    @DeleteMapping("/files/{id}")
    public ResponseEntity<?> delFile(@RequestHeader HttpHeaders headers, @PathVariable("id") String fileName) throws IOException {
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = getSessionData(headers);
        File file = new File("user_files/"+sessionData.getUserData().getUuid().toString()+"/"+fileName);
        long len = file.length();
        if(!file.exists()){
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        file.delete();

        return new ResponseEntity<>(new FileData(null, sessionData.getEncryptor().decrypt(file.getName()), len, fileName),getResponseHeaders(), HttpStatus.OK);
    }

    @RequestMapping(value = "/files/share", method = RequestMethod.GET)
    public ResponseEntity<String> getShareLink(HttpServletResponse response, @RequestHeader HttpHeaders headers,
                                                                    @RequestParam("id") String encryptedName) throws IOException {

        if (!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = getSessionData(headers);
        File file = new File("user_files/"+sessionData.getUserData().getUuid().toString()+"/"+encryptedName);
        String id = KeyGenerators.string().generateKey();
        shareLinks.put(id, new ShareLink(file, sessionData));
        return new ResponseEntity<>(id, getResponseHeaders(),HttpStatus.OK);
    }

    @RequestMapping(value = "/files/shared", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> getSharedFile(HttpServletResponse response,
                                                               @RequestParam("id") String shareId,
                                                               @RequestParam("download") boolean download) throws IOException  {
        if(!shareLinks.containsKey(shareId)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        ShareLink shareLink = shareLinks.get(shareId);
        File file = shareLink.file;

        String fileName = shareLink.sessionData.getEncryptor().decrypt(file.getName());

        if(download) response.addHeader("Content-Disposition", String.format("attachment; filename=\"%s\"", fileName));

        FileInputStream inputStream = new FileInputStream(file);
        StreamingResponseBody stream = out ->{
            byte[] data=new byte[BLOCK_SIZE+32];
            int length;
            while ((length=inputStream.read(data)) >= 0){
                byte[] actual = new byte[length];
                for(int i = 0; i < length; i++){
                    actual[i] = data[i];
                }

                byte[] decrypted = shareLink.sessionData.getFileEncryptor().decrypt(actual);
                out.write(decrypted);
            }
        };
        return new ResponseEntity<>(stream, getResponseHeaders(), HttpStatus.OK);
    }


    @RequestMapping(value = "/files/download", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> downloadFileChunks(HttpServletResponse response, @RequestHeader HttpHeaders headers,
                                                                    @RequestParam("id") String encryptedName, @RequestParam("token") String token,
                                                                    @RequestParam("download") boolean download) throws IOException  {
        headers.add("authorization", token);
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = getSessionData(token);
        File file = new File("user_files/"+sessionData.getUserData().getUuid().toString()+"/"+encryptedName);

        String fileName = sessionData.getEncryptor().decrypt(file.getName());

        if(download) response.addHeader("Content-Disposition", String.format("attachment; filename=\"%s\"", fileName));
//        responseHeaders.add("Cache-Control", "no-cache, no-store, must-revalidate");
//        responseHeaders.add("Pragma", "no-cache");
//        responseHeaders.add("Expires", "0");


        FileInputStream inputStream = new FileInputStream(file);
        StreamingResponseBody stream = out ->{
            byte[] data=new byte[BLOCK_SIZE+32];
            int length;
            while ((length=inputStream.read(data)) >= 0){
                byte[] actual = new byte[length];
                for(int i = 0; i < length; i++){
                    actual[i] = data[i];
                }

                byte[] decrypted = sessionData.getFileEncryptor().decrypt(actual);
                out.write(decrypted);
            }
        };
        return  new ResponseEntity<>(stream, getResponseHeaders(), HttpStatus.OK);
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> fileUploadNew(HttpServletRequest request, @RequestHeader HttpHeaders headers) throws IOException {
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = getSessionData(headers);
        ServletFileUpload upload = new ServletFileUpload();

        FileItemIterator iterator = upload.getItemIterator(request);

        if(!iterator.hasNext()) return new ResponseEntity<>(HttpStatus.BAD_REQUEST);

        FileItemStream item = iterator.next();
        String encryptedName = sessionData.getEncryptor().encrypt(item.getName());
        InputStream inputStream = item.openStream();

        encryptFile(inputStream, sessionData, encryptedName);

        return new ResponseEntity<>(new FileData(null, item.getName(), request.getContentLength(), encryptedName), getResponseHeaders(),HttpStatus.OK);
    }

    private void encryptFile(InputStream inputStream, SessionData sessionData, String encryptedName) throws IOException {
        File tempFile = new File("temp_files/"+sessionData.getUserData().getUuid()+"/"+encryptedName);
        if(!tempFile.exists()){
            tempFile.getParentFile().mkdirs();
        }
        FileOutputStream fileOutputStream = new FileOutputStream(tempFile);

        byte[] data=new byte[BLOCK_SIZE];
        int length;
        while ((length = inputStream.read(data)) >= 0){
            fileOutputStream.write(data, 0, length);
        }
        fileOutputStream.close();
        FileInputStream fileInputStream = new FileInputStream(tempFile);
        //encrypting it, it's not possible to do it above because the actual data read might not be exact
        File file = new File("user_files/"+sessionData.getUserData().getUuid()+"/"+encryptedName);
        if(!file.exists()) file.getParentFile().mkdirs();
        fileOutputStream = new FileOutputStream(file);
        byte[] data2 = new byte[BLOCK_SIZE];
        while ((fileInputStream.read(data2)) >= 0){
            byte[] encrypted = sessionData.getFileEncryptor().encrypt(data2);
            fileOutputStream.write(encrypted);
        }
        fileOutputStream.close();
        tempFile.delete();
    }

    @GetMapping("/logout")
    public ResponseEntity<?> logUserOut(@RequestHeader HttpHeaders headers){
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        sessions.remove(headers.getFirst("authorization"));
        return new ResponseEntity<>(getResponseHeaders(),HttpStatus.OK);
    }

    private boolean searchContains(String search, String query){
        if(query == null) return false;
        return query.toLowerCase().contains(search.toLowerCase());
    }

    private List<FileData> getUserFiles(SessionData sessionData){
        File file = new File("user_files/"+sessionData.getUserData().getUuid().toString());
        File[] list = file.listFiles((dir, name) -> !new File(dir, name).isDirectory());
        if(list == null) return new ArrayList<>();
        return Arrays.stream(list).map(f -> new FileData(f, f.getName(), f.length(), f.getName())).collect(Collectors.toList());
    }

    @GetMapping("/files")
    public ResponseEntity<?> getFiles(@RequestHeader HttpHeaders headers, @RequestParam(value = "search", required = false) String search,
                                     @RequestParam(value = "_end", defaultValue = "10") int end,
                                     @RequestParam(value = "_start", defaultValue = "0") int start) throws IOException {
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        SessionData sessionData = getSessionData(headers);

        List<FileData> fileData = getUserFiles(sessionData);


        for(FileData file : fileData){
            try {
                file.name = sessionData.getEncryptor().decrypt(file.name);
            }
            catch (Exception e){
                //it was not encrypted, migrating
                String newName = sessionData.getEncryptor().encrypt(file.name);
                file.id = newName;
                FileUtils.copyFile(file.file, new File(file.file.getParentFile(), newName));
                file.file.delete();
            }

            FileInputStream inputStream = null;
            try {
                inputStream = new FileInputStream(file.file);
                int size = (int) (file.file.length() < BLOCK_SIZE ? file.file.length() : BLOCK_SIZE+32);
                byte[] data = new byte[size];
                inputStream.read(data);
                sessionData.getFileEncryptor().decrypt(data); //if this throws bad padding error, start migration
            }
            catch (Exception e){
                //migrating, encrypted the old way
                try {
                    ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(sessionData.fileEncryptor.decrypt(Files.readAllBytes(file.file.toPath())));
                    encryptFile(byteArrayInputStream, sessionData, file.id);
                }
                catch (Exception ignored){
                }
            }
        }

        int ogSize = fileData.size();
        Collections.reverse(fileData);

        Iterator<FileData> dataIterator = fileData.iterator();
        int index = 0;
        int removed = 0;
        while (dataIterator.hasNext()){
            FileData data = dataIterator.next();
            if(search != null && !(searchContains(search, data.getName()))) {
                dataIterator.remove();
                removed++;
            }
            else if(index++ < start || index > end) dataIterator.remove();
        }
        ogSize = ogSize - removed;
        final HttpHeaders responseHeaders = getResponseHeaders();
        responseHeaders.add("Access-Control-Expose-Headers", "X-Total-Count");
        responseHeaders.add("X-Total-Count", String.valueOf(ogSize));
        return new ResponseEntity<>(fileData, responseHeaders, HttpStatus.OK);
    }

    @GetMapping("/logs")
    public ResponseEntity<?> getLogs(@RequestHeader HttpHeaders headers, @RequestParam(value = "search", required = false) String search,
                                     @RequestParam(value = "_end", defaultValue = "10") int end,
                                     @RequestParam(value = "_start", defaultValue = "0") int start){
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        SessionData sessionData = getSessionData(headers);

        List<MainDatabase.LogData> logData = Main.database.getUserLogs(sessionData.getUserData().getUuid());
        int ogSize = logData.size();
        Collections.reverse(logData);


        Iterator<MainDatabase.LogData> dataIterator = logData.iterator();
        int index = 0;
        int removed = 0;
        while (dataIterator.hasNext()){
            MainDatabase.LogData data = dataIterator.next();
            if(search != null && !(searchContains(search, data.getType()) || searchContains(search, data.getInfo())
                    || searchContains(search, data.getTime()) || searchContains(search, data.getIp()))) {
                dataIterator.remove();
                removed++;
            }
            else if(index++ < start || index > end) dataIterator.remove();
        }
        ogSize = ogSize - removed;
        final HttpHeaders responseHeaders = getResponseHeaders();
        responseHeaders.add("Access-Control-Expose-Headers", "X-Total-Count");
        responseHeaders.add("X-Total-Count", String.valueOf(ogSize));

        return new ResponseEntity<>(logData, responseHeaders, HttpStatus.OK);
    }

    @GetMapping("/passwords")
    public ResponseEntity<?> getPasswords(HttpServletRequest request, @RequestHeader HttpHeaders headers, @RequestParam(value = "search", required = false) String search,
                                          @RequestParam(value = "_end", defaultValue = "10") int end,
                                          @RequestParam(value = "_start", defaultValue = "0") int start){
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        SessionData sessionData = sessions.get(headers.getFirst("authorization"));
        MainDatabase.UserData userData = sessions.get(headers.getFirst("authorization")).getUserData();
        List<MainDatabase.PasswordData> passwordData = Main.database.getAllUserPasswords(userData.getUuid());


        Collections.reverse(passwordData);
        //decrypting it here because doing client-side encryption on top of https is pointless

        int ogSize = passwordData.size();
        passwordData.forEach(p -> {
            p.password = sessionData.getEncryptor().decrypt(p.password);
            p.site = sessionData.getEncryptor().decrypt(p.site);
            p.username = sessionData.getEncryptor().decrypt(p.username);
        });
        Iterator<MainDatabase.PasswordData> dataIterator = passwordData.iterator();
        int index = 0;
        int removed = 0;
        while (dataIterator.hasNext()){
            MainDatabase.PasswordData data = dataIterator.next();
            if(search != null && !(searchContains(search, data.username) || searchContains(search, data.site))) {
                dataIterator.remove();
                removed++;
            }
            else if(index++ < start || index > end) dataIterator.remove();
        }
        ogSize = ogSize - removed;

        Main.database.addLog(getSessionData(headers).getUserData().getUuid(), LogType.PASSWORD_REQUEST,
                request.getRequestURI() + "?search="+search, request.getHeader("X-Real-IP"));

        final HttpHeaders responseHeaders = getResponseHeaders();
        responseHeaders.add("Access-Control-Expose-Headers", "X-Total-Count");
        responseHeaders.add("X-Total-Count", String.valueOf(ogSize));
        return new ResponseEntity<>(passwordData, responseHeaders, HttpStatus.OK);
    }

    @PostMapping("/authenticate")
    public ResponseEntity<?> authenticateUser(HttpServletRequest request, @RequestHeader HttpHeaders headers, @RequestBody AuthData user){
        MainDatabase.UserData data = Main.database.fetchUserData(user.getUsername());
        if(data == null) return new ResponseEntity<>(getResponseHeaders(), HttpStatus.UNAUTHORIZED);

        String ip = headers.getFirst("X-Real-IP");
        if(ip == null) ip = request.getRemoteAddr();
        if(!loginAttempts.containsKey(user.getUsername())){
            loginAttempts.put(user.getUsername(), new LoginAttempt());
        }
        else {
            int tries = loginAttempts.get(user.getUsername()).getAndIncrement();
            if(tries > Main.server.config.getInt("login-attempt-limit")){
                Main.database.addLog(data.getUuid(), LogType.AUTHENTICATION, String.format("Maximum login attempts reached (%s)", tries), ip);
                //locking it
                Server.logger.warning("Someone is trying to brute force account " +  user.getUsername() + ", tries = " + tries);
                return new ResponseEntity<>(getResponseHeaders(),HttpStatus.UNAUTHORIZED);
            }
        }

        if(!Main.pwHasher.matches(user.password, data.getCryptographyData().hash)){
            Main.database.addLog(data.getUuid(), LogType.AUTHENTICATION, "Failed Login", ip);
            return new ResponseEntity<>(getResponseHeaders(),HttpStatus.UNAUTHORIZED);
        }
        Main.database.addLog(data.getUuid(), LogType.AUTHENTICATION, "Successful Login", ip);
        loginAttempts.remove(user.getUsername());

        String token = UUID.randomUUID().toString();
        sessions.put(token, new SessionData(data, Encryptors.text(user.getPassword(), data.getCryptographyData().salt),
                Encryptors.standard(user.getPassword(), data.getCryptographyData().salt), ip));
        return new ResponseEntity<>(token, getResponseHeaders(), HttpStatus.OK);
    }

    private SessionData getSessionData(HttpHeaders headers){
        return getSessionData(headers.getFirst("authorization"));
    }
    private SessionData getSessionData(String token){
        return sessions.get(token);
    }

    @DeleteMapping("/passwords/{id}")
    public ResponseEntity<?> deletePassword(@RequestHeader HttpHeaders headers, @PathVariable("id") String id){
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        UUID uuid = UUID.fromString(id);
        MainDatabase.PasswordData passwordData = Main.database.getPassword(uuid);

        Main.database.deletePassword(uuid);
        SessionData sessionData = getSessionData(headers);
        Main.database.addLog(getSessionData(headers).getUserData().getUuid(), LogType.PASSWORD_DELETE,
                "Site: " + sessionData.getEncryptor().decrypt(passwordData.site), headers.getFirst("X-Real-IP"));

        return new ResponseEntity<>(passwordData, getResponseHeaders(), HttpStatus.OK);
    }

    @PostMapping("/passwords")
    public ResponseEntity<?> addPassword(@RequestHeader HttpHeaders headers, @RequestBody PasswordBody data){
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = sessions.get(headers.getFirst("authorization"));

        MainDatabase.PasswordData passwordData = Main.database.savePassword(sessionData.getUserData().getUuid(),
                sessionData.getEncryptor().encrypt(data.password),
                sessionData.getEncryptor().encrypt(data.username),
                sessionData.getEncryptor().encrypt(data.site));

        Main.database.addLog(getSessionData(headers).getUserData().getUuid(), LogType.PASSWORD_CREATE,
                "Site: " + data.site, headers.getFirst("X-Real-IP"));

        return new ResponseEntity<>(passwordData, getResponseHeaders(), HttpStatus.OK);
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody AuthData user){
        MainDatabase.UserData data = Main.database.fetchUserData(user.getUsername());
        if(data != null){
            return new ResponseEntity<>(HttpStatus.CONFLICT);
        }
        Main.database.saveUser(user.getUsername(), Main.pwHasher.encode(user.getPassword()));
        return new ResponseEntity<>(Arrays.asList(user), getResponseHeaders(), HttpStatus.OK);
    }


    static class ShareLink{
        private File file;
        private SessionData sessionData;

        public ShareLink(File file, SessionData sessionData) {
            this.file = file;
            this.sessionData = sessionData;
        }
    }

    @Data
    static class LoginAttempt{
        private int tries = 0;
        private long lastTime = System.currentTimeMillis();

        public int getAndIncrement(){
            // 10 minutes
            int configReset = Main.server.config.getInt("login-attempts-reset-time");
            long secondsSince = ((System.currentTimeMillis() - lastTime) / 1000);
            lastTime = System.currentTimeMillis();
            if(secondsSince > configReset * 60L){
                tries = 0;
            }
            else {
                tries++;
            }
            return tries;
        }
    }

    @Data
    static class FileInfo{
        private String downloadUrl;
        private String id;

        public FileInfo(String fileName, String downloadUrl) {
            this.downloadUrl = downloadUrl;
            this.id = fileName;
        }
    }

    @Data
    static class PasswordBody{
        private String username, password, site;

        public PasswordBody(String username, String password, String site) {
            this.username = username;
            this.password = password;
            this.site = site;
        }
    }

    @Data
    static class FileData{
        @JsonIgnore
        private File file;

        private String name;
        private long size;
        private String id;

        public FileData(File file, String name, long size, String encryptedName) {
            this.name = name;
            this.size = size;
            this.id = encryptedName;
            this.file = file;
        }
    }

    @Data
    static class AuthData{
        private String username, password;

        public AuthData(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    @Data
    public abstract static class TokenBody{
        private String token;

        public TokenBody(String token) {
            this.token = token;
        }
    }

    @Data
    public static class SessionData{
        private MainDatabase.UserData userData;
        private TextEncryptor encryptor;
        private BytesEncryptor fileEncryptor;

        private String ipAddr;

        public SessionData(MainDatabase.UserData userData, TextEncryptor encryptor, BytesEncryptor fileEncryptor, String ipAddr) {
            this.userData = userData;
            this.encryptor = encryptor;
            this.fileEncryptor = fileEncryptor;
            this.ipAddr = ipAddr;
        }
    }

    @Data
    static class TestObj{
        String name;
        UUID id;
        public TestObj(String name){
            this.name = name;
            this.id = UUID.randomUUID();
        }
    }
}
