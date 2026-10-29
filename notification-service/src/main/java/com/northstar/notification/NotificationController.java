package com.northstar.notification;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
@RestController
public class NotificationController {
    final JdbcTemplate db;final ObjectMapper json;
    public NotificationController(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    @KafkaListener(topics="bank.transactions.v1") @Transactional
    public void receive(String payload) throws Exception {
        var e=json.readTree(payload);var id=UUID.fromString(e.path("id").asText());
        String sender=e.path("sender").asText(),receiver=e.path("receiver").asText(),amount=e.path("amount").decimalValue().setScale(2).toPlainString();
        add(id,sender,"Transfer completed","$"+amount+" sent to "+e.path("toAccount").asText()+" · "+e.path("description").asText());
        if(!sender.equals(receiver))add(id,receiver,"Money received","$"+amount+" received in "+e.path("toAccount").asText()+" · "+e.path("description").asText());
    }
    void add(UUID id,String user,String title,String message){db.update("INSERT INTO notifications(event_id,user_id,title,message) VALUES (?,?,?,?) ON CONFLICT(event_id,user_id) DO NOTHING",id,user,title,message);}
    @GetMapping("/internal/notifications") List<Map<String,Object>> list(@RequestHeader("X-User-Id") String user){return db.queryForList("SELECT * FROM notifications WHERE user_id=? ORDER BY sequence DESC LIMIT 50",user);}
    @PostMapping("/internal/notifications/read") Map<String,Boolean> read(@RequestHeader("X-User-Id") String user){db.update("UPDATE notifications SET read_at=now() WHERE user_id=? AND read_at IS NULL",user);return Map.of("ok",true);}
}
