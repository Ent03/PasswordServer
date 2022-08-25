package fi.samppa.server;


import com.fasterxml.jackson.annotation.JsonIgnore;
import fi.samppa.server.logs.LogType;
import lombok.Data;
import org.apache.commons.io.FileUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/")
public class RestAPIController {
    private HashMap<String, SessionData> sessions = new HashMap<>();
    private HashMap<String, LoginAttempt> loginAttempts = new HashMap<>();

    private boolean isAuthorized(HttpHeaders headers){
        String token = headers.getFirst("authorization");
        SessionData sessionData = sessions.get(token);
        if(sessionData == null) return false;
        String ip = headers.getFirst("X-Real-IP");
        return !Main.server.config.getBoolean("production") || sessionData.ipAddr.equals(ip);
    }


    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> fileUpload(@RequestHeader HttpHeaders headers, @RequestParam("file") MultipartFile file) throws IOException {
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = getSessionData(headers);
        String encryptedName = sessionData.getEncryptor().encrypt(file.getOriginalFilename());
        File convertFile = new File("user_files/"+sessionData.getUserData().getUuid().toString()+"/"+encryptedName);
        if(!convertFile.exists()){
            convertFile.getParentFile().mkdirs();
        }
        FileOutputStream fout = new FileOutputStream(convertFile);
        fout.write(sessionData.getFileEncryptor().encrypt(file.getBytes()));
        fout.close();
        return new ResponseEntity<>(new FileData(null, file.getName(), file.getSize(), encryptedName), HttpStatus.OK);
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<?> getFileInfo(@RequestHeader HttpHeaders headers, @PathVariable("id") String fileName) throws IOException {
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = getSessionData(headers);

        //File file = new File("user_files/"+sessionData.getUserData().getUuid().toString()+"/"+fileName);

        return new ResponseEntity<>(new FileInfo(fileName,"/api/v1/files/download?name="+fileName), HttpStatus.OK);
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

        return new ResponseEntity<>(new FileData(null, sessionData.getEncryptor().decrypt(file.getName()), len, fileName), HttpStatus.OK);
    }

    @RequestMapping(value = "/files/download2", method = RequestMethod.GET)
    public ResponseEntity<Object> downloadFile(@RequestHeader HttpHeaders headers, @RequestParam("id") String encryptedName, @RequestParam("token") String token) throws IOException  {
        headers.add("authorization", token);
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = getSessionData(token);
        File file = new File("user_files/"+sessionData.getUserData().getUuid().toString()+"/"+encryptedName);
        byte[] bytes = new FileInputStream(file).readAllBytes();
        bytes = sessionData.getFileEncryptor().decrypt(bytes);
        HttpHeaders responseHeaders = new HttpHeaders();

        ByteArrayResource resource = new ByteArrayResource(bytes);

        responseHeaders.add("Content-Disposition", String.format("attachment; filename=\"%s\"", file.getName()));
//        responseHeaders.add("Cache-Control", "no-cache, no-store, must-revalidate");
//        responseHeaders.add("Pragma", "no-cache");
//        responseHeaders.add("Expires", "0");

            return ResponseEntity.ok()
                .headers(responseHeaders)
                .contentLength(bytes.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @RequestMapping(value = "/files/download", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> downloadFileChunks(HttpServletResponse response, @RequestHeader HttpHeaders headers,
                                                                    @RequestParam("id") String encryptedName, @RequestParam("token") String token) throws IOException  {
        headers.add("authorization", token);
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = getSessionData(token);
        File file = new File("user_files/"+sessionData.getUserData().getUuid().toString()+"/"+encryptedName);
        byte[] bytes = new FileInputStream(file).readAllBytes();
        bytes = sessionData.getFileEncryptor().decrypt(bytes);

        String fileName = sessionData.getEncryptor().decrypt(file.getName());
        ByteArrayResource resource = new ByteArrayResource(bytes);

        response.addHeader("Content-Disposition", String.format("attachment; filename=\"%s\"", fileName));
//        responseHeaders.add("Cache-Control", "no-cache, no-store, must-revalidate");
//        responseHeaders.add("Pragma", "no-cache");
//        responseHeaders.add("Expires", "0");


        InputStream inputStream = new ByteArrayInputStream(bytes);
        StreamingResponseBody stream = out ->{
            byte[] data=new byte[16384];
            int length;
            while ((length=inputStream.read(data)) >= 0){
                out.write(data, 0, length);
            }
        };
        return ResponseEntity.ok().contentLength(bytes.length).body(stream);
    }

    @RequestMapping(value = "/files/view", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> viewFile(@RequestHeader HttpHeaders headers,
            @RequestParam("id") String encryptedName, @RequestParam("token") String token) throws IOException  {
        headers.add("authorization", token);
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = getSessionData(token);
        File file = new File("user_files/"+sessionData.getUserData().getUuid().toString()+"/"+encryptedName);
        byte[] bytes = new FileInputStream(file).readAllBytes();
        bytes = sessionData.getFileEncryptor().decrypt(bytes);

        InputStream inputStream = new ByteArrayInputStream(bytes);
        StreamingResponseBody stream = out ->{
            byte[] data=new byte[16384];
            int length;
            while ((length=inputStream.read(data)) >= 0){
                out.write(data, 0, length);
            }
        };
        return ResponseEntity.ok().contentLength(bytes.length).body(stream);
    }

    @GetMapping("/logout")
    public ResponseEntity<?> logUserOut(@RequestHeader HttpHeaders headers){
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        sessions.remove(headers.getFirst("authorization"));
        return new ResponseEntity<>(HttpStatus.OK);
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
        final HttpHeaders responseHeaders = new HttpHeaders();
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
        final HttpHeaders responseHeaders = new HttpHeaders();
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

        final HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Access-Control-Expose-Headers", "X-Total-Count");
        responseHeaders.add("X-Total-Count", String.valueOf(ogSize));
        return new ResponseEntity<>(passwordData, responseHeaders, HttpStatus.OK);
    }

    @PostMapping("/authenticate")
    public ResponseEntity<?> authenticateUser(@RequestHeader HttpHeaders headers, @RequestBody AuthData user){
        MainDatabase.UserData data = Main.database.fetchUserData(user.getUsername());
        if(data == null) return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);

        String ip = headers.getFirst("X-Real-IP");
        if(!loginAttempts.containsKey(user.getUsername())){
            loginAttempts.put(user.getUsername(), new LoginAttempt());
        }
        else {
            int tries = loginAttempts.get(user.getUsername()).getAndIncrement();
            if(tries > Main.server.config.getInt("login-attempt-limit")){
                Main.database.addLog(data.getUuid(), LogType.AUTHENTICATION, String.format("Maximum login attempts reached (%s)", tries), ip);
                //locking it
                Server.logger.warning("Someone is trying to brute force account " +  user.getUsername() + ", tries = " + tries);
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
        }

        if(!Main.pwHasher.matches(user.password, data.getCryptographyData().hash)){
            Main.database.addLog(data.getUuid(), LogType.AUTHENTICATION, "Failed Login", ip);
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Main.database.addLog(data.getUuid(), LogType.AUTHENTICATION, "Successful Login", ip);
        loginAttempts.remove(user.getUsername());

        String token = UUID.randomUUID().toString();
        sessions.put(token, new SessionData(data, Encryptors.text(user.getPassword(), data.getCryptographyData().salt),
                Encryptors.standard(user.getPassword(), data.getCryptographyData().salt), ip));
        return ResponseEntity.ok(token);
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

        return ResponseEntity.ok(passwordData);
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

        return new ResponseEntity<>(passwordData, HttpStatus.OK);
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody AuthData user){
        MainDatabase.UserData data = Main.database.fetchUserData(user.getUsername());
        if(data != null){
            return new ResponseEntity<>(HttpStatus.CONFLICT);
        }
        Main.database.saveUser(user.getUsername(), Main.pwHasher.encode(user.getPassword()));
        return new ResponseEntity<>(Arrays.asList(user), HttpStatus.OK);
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
