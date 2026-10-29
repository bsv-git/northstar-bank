package com.northstar.payment;
import com.northstar.common.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.math.BigDecimal;
import java.util.*;
import static com.northstar.common.ApiException.require;
@RestController
public class PaymentController {
    final JdbcTemplate db;final ServiceClient client;
    public PaymentController(JdbcTemplate db,ServiceClient client){this.db=db;this.client=client;}
    record Request(String fromAccount,String toAccount,BigDecimal amount,String description){}
    @GetMapping("/internal/payments") List<Map<String,Object>> list(@RequestHeader("X-User-Id") String user){return db.queryForList("SELECT * FROM payments WHERE user_id=? ORDER BY created_at DESC LIMIT 100",user);}
    @PostMapping("/internal/payments") Map<String,Object> create(@RequestHeader("X-User-Id") String user,@RequestHeader("Idempotency-Key") String key,@RequestBody Request req){
        require(key.matches("[A-Za-z0-9-]{8,80}"),400,"Provide an 8–80 character request ID");
        require(req.fromAccount()!=null && req.toAccount()!=null && req.fromAccount().length()<=24 && req.toAccount().length()<=24 && req.amount()!=null,400,"Source, destination and amount are required");
        require(req.amount().signum()>0 && req.amount().compareTo(new BigDecimal("10000"))<=0 && req.amount().stripTrailingZeros().scale()<=2,400,"Enter $0.01–$10,000.00 with at most two decimal places");
        String desc=req.description()==null?"Transfer":req.description().trim();
        require(!desc.isBlank()&&desc.length()<=140,400,"Description must contain 1–140 characters");
        db.update("INSERT INTO payments(id,user_id,request_key,from_account,to_account,amount,description) VALUES (?,?,?,?,?,?,?) ON CONFLICT(user_id,request_key) DO NOTHING",UUID.randomUUID(),user,key,req.fromAccount(),req.toAccount(),req.amount(),desc);
        var payment=db.queryForMap("SELECT * FROM payments WHERE user_id=? AND request_key=?",user,key);
        require(payment.get("from_account").equals(req.fromAccount())&&payment.get("to_account").equals(req.toAccount())&&((BigDecimal)payment.get("amount")).compareTo(req.amount())==0&&payment.get("description").equals(desc),409,"This request ID belongs to a different payment. Start a new transfer.");
        process(payment);return db.queryForMap("SELECT * FROM payments WHERE id=?",payment.get("id"));
    }
    @Scheduled(fixedDelay=5000) void recover(){for(var p:db.queryForList("SELECT * FROM payments WHERE status='PENDING' ORDER BY created_at LIMIT 20"))process(p);}
    void process(Map<String,Object> p){
        if(!p.get("status").equals("PENDING"))return;
        try{
            client.call(8082,"POST","/internal/transfers",p.get("user_id").toString(),Map.of("id",p.get("id"),"fromAccount",p.get("from_account"),"toAccount",p.get("to_account"),"amount",p.get("amount"),"description",p.get("description")));
            db.update("UPDATE payments SET status='COMPLETED',error=NULL,updated_at=now() WHERE id=?",p.get("id"));
        }catch(ApiException e){
            if(e.status>=400&&e.status<500)db.update("UPDATE payments SET status='FAILED',error=?,updated_at=now() WHERE id=? AND status='PENDING'",e.getMessage(),p.get("id"));
            // Unknown outcome remains PENDING. Retrying the same ledger ID cannot post twice.
        }
    }
}
