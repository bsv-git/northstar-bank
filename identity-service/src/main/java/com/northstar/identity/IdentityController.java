package com.northstar.identity;
import com.northstar.common.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.scheduling.annotation.Scheduled;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.northstar.common.ApiException.require;
@RestController
public class IdentityController {
    final JdbcTemplate db;
    final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(11);
    final SecureRandom random=new SecureRandom();
    public IdentityController(JdbcTemplate db){this.db=db;}
    @Bean CommandLineRunner seed(){return args->{
        String[] names={"Maya Thompson","Alex Morgan","Olivia Carter","Liam Bennett","Sophia Reed","Noah Foster","Emma Brooks","Oliver Hayes","Ava Mitchell","Elijah Turner","Isabella Parker","James Collins","Mia Edwards","Lucas Stewart","Amelia Morris","Henry Cooper","Harper Ward","Benjamin Gray","Evelyn Bell","Theodore Murphy","Luna Bailey","William Rivera","Camila Cook","Michael Rogers","Gianna Morgan","Alexander Peterson","Elizabeth Cooper","Daniel Richardson","Ella Cox","Matthew Howard","Scarlett Ward","Jackson Torres","Victoria Peterson","Sebastian Gray","Aria Ramirez","David James","Chloe Watson","Joseph Brooks","Nora Kelly","Samuel Sanders","Riley Price","Owen Bennett","Layla Wood","Gabriel Barnes","Ellie Ross","Carter Henderson","Hazel Coleman","Wyatt Jenkins","Lily Perry","John Powell","Aurora Long"};
        for(int i=0;i<50;i++){
            String id=i==0?"maya":i==1?"alex":String.format(Locale.ROOT,"demo%02d",i+1);
            String email=i==0?"maya@northstar.demo":i==1?"alex@northstar.demo":String.format(Locale.ROOT,"user%02d@northstar.demo",i+1);
            db.update("INSERT INTO users(id,name,email,password_hash,role) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",id,names[i],email,passwords.encode("BankDemo!2026"),"CUSTOMER");
        }
        db.update("INSERT INTO users(id,name,email,password_hash,role) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING","admin","Jordan Lee","admin@northstar.demo",passwords.encode("BankDemo!2026"),"ADMIN");
    };}
    record CreateUser(String name,String email,String password){}
    @PostMapping("/internal/users") synchronized Map<String,Object> create(@RequestBody CreateUser input){
        require(input.name()!=null&&!input.name().isBlank()&&input.name().trim().length()<=100,400,"Name is required and must be at most 100 characters");
        require(input.email()!=null&&input.email().trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")&&input.email().trim().length()<=160,400,"Provide a valid email address");
        require(input.password()!=null&&input.password().length()>=8&&input.password().length()<=72,400,"Password must be 8–72 characters");
        String email=input.email().trim().toLowerCase(Locale.ROOT);
        String name=input.name().trim();
        for(int attempt=0;attempt<3;attempt++){
            String id="usr_"+UUID.randomUUID().toString().replace("-","");
            int inserted=db.update("INSERT INTO users(id,name,email,password_hash,role) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",id,name,email,passwords.encode(input.password()),"CUSTOMER");
            if(inserted==1)return profile(db.queryForMap("SELECT * FROM users WHERE id=?",id));
            require(db.queryForObject("SELECT count(*) FROM users WHERE email=?",Long.class,email)==0,409,"An account with this email already exists");
        }
        throw new IllegalStateException("Could not allocate a unique user ID");
    }
    record Login(String email,String password){}
    @PostMapping("/internal/login") synchronized Map<String,Object> login(@RequestBody Login input){
        require(input.email()!=null && input.password()!=null && input.password().length()<=72,400,"Email and password are required");
        var rows=db.queryForList("SELECT * FROM users WHERE email=?",input.email().trim().toLowerCase(Locale.ROOT));
        require(!rows.isEmpty(),401,"Invalid email or password");
        var user=rows.getFirst();
        var locked=(java.sql.Timestamp)user.get("locked_until");
        require(locked==null || locked.toInstant().isBefore(java.time.Instant.now()),429,"Too many attempts. Try again in five minutes.");
        if(!passwords.matches(input.password(),(String)user.get("password_hash"))){
            db.update("UPDATE users SET failures=failures+1,locked_until=CASE WHEN failures>=4 THEN now()+interval '5 minutes' ELSE locked_until END WHERE id=?",user.get("id"));
            throw new ApiException(401,"Invalid email or password");
        }
        db.update("UPDATE users SET failures=0,locked_until=NULL WHERE id=?",user.get("id"));
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO sessions VALUES (?,?,now()+interval '8 hours')",hash(token),user.get("id"));
        return Map.of("token",token,"user",profile(user));
    }
    record Token(String token){}
    @PostMapping("/internal/session") Map<String,Object> session(@RequestBody Token input){
        require(input.token()!=null,401,"Please sign in");
        var rows=db.queryForList("SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>now()",hash(input.token()));
        require(!rows.isEmpty(),401,"Your session has expired. Please sign in."); return profile(rows.getFirst());
    }
    @PostMapping("/internal/logout") Map<String,Boolean> logout(@RequestBody Token input){ if(input.token()!=null)db.update("DELETE FROM sessions WHERE token_hash=?",hash(input.token()));return Map.of("ok",true);}
    @Scheduled(fixedDelay=600000) void expire(){db.update("DELETE FROM sessions WHERE expires_at<now()");}
    Map<String,Object> profile(Map<String,Object> u){return Map.of("id",u.get("id"),"name",u.get("name"),"email",u.get("email"),"role",u.get("role"));}
    String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
