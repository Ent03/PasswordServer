package fi.samppa.server;


import lombok.Data;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/")
public class RestAPIController {
    private HashMap<String, SessionData> tokens = new HashMap<>();

    private boolean isAuthorized(HttpHeaders headers){
        String token = headers.getFirst("authorization");
        return token != null && tokens.containsKey(token);
    }

    @GetMapping("/logout")
    public ResponseEntity<?> logUserOut(@RequestHeader HttpHeaders headers){
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        tokens.remove(headers.getFirst("authorization"));
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @GetMapping("/passwords")
    public ResponseEntity<?> getPasswords(@RequestHeader HttpHeaders headers, @RequestParam(value = "search", required = false) String search){
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        SessionData sessionData = tokens.get(headers.getFirst("authorization"));
        MainDatabase.UserData userData = tokens.get(headers.getFirst("authorization")).getUserData();
        List<MainDatabase.PasswordData> passwordData = Main.database.getAllUserPasswords(userData.getUuid());

        //encrypting it here because doing client-side encryption on top of https is pointless

        passwordData.forEach(p -> {
            p.password = sessionData.getEncryptor().decrypt(p.password);
            p.site = sessionData.getEncryptor().decrypt(p.site);
            p.username = sessionData.getEncryptor().decrypt(p.username);
        });
        if(search != null){
            passwordData.removeIf(p -> !((p.site.startsWith(search) || p.site.endsWith(search))
            || (p.username.startsWith(search) || p.username.endsWith(search))));
        }

        final HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Access-Control-Expose-Headers", "X-Total-Count");
        responseHeaders.add("X-Total-Count", String.valueOf(passwordData.size()));
        return new ResponseEntity<>(passwordData, responseHeaders, HttpStatus.OK);
    }

    @PostMapping("/authenticate")
    public ResponseEntity<?> authenticateUser(@RequestBody AuthData user){
        MainDatabase.UserData data = Main.database.fetchUserData(user.getUsername());

        if(data == null || !Main.pwHasher.matches(user.password, data.getCryptographyData().hash)){
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        String token = UUID.randomUUID().toString();
        tokens.put(token, new SessionData(data, Encryptors.text(user.getPassword(), data.getCryptographyData().salt)));
        return ResponseEntity.ok(token);
    }

    @DeleteMapping("/passwords/{id}")
    public ResponseEntity<?> deletePassword(@RequestHeader HttpHeaders headers, @PathVariable("id") String id){
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        UUID uuid = UUID.fromString(id);
        MainDatabase.PasswordData passwordData = Main.database.getPassword(uuid);

        Main.database.deletePassword(uuid);

        return ResponseEntity.ok(passwordData);
    }

    @PostMapping("/passwords")
    public ResponseEntity<?> addPassword(@RequestHeader HttpHeaders headers, @RequestBody PasswordBody data){
        if(!isAuthorized(headers)) return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        SessionData sessionData = tokens.get(headers.getFirst("authorization"));

        MainDatabase.PasswordData passwordData = Main.database.savePassword(sessionData.getUserData().getUuid(),
                sessionData.getEncryptor().encrypt(data.password),
                sessionData.getEncryptor().encrypt(data.username),
                sessionData.getEncryptor().encrypt(data.site));

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
    static class PasswordBody{
        private String username, password, site;

        public PasswordBody(String username, String password, String site) {
            this.username = username;
            this.password = password;
            this.site = site;
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

        public SessionData(MainDatabase.UserData userData, TextEncryptor encryptor) {
            this.userData = userData;
            this.encryptor = encryptor;
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
