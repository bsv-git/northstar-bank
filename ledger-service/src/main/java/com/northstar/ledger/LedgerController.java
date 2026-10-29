package com.northstar.ledger;
import com.northstar.common.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static com.northstar.common.ApiException.require;
@RestController
public class LedgerController {
    final JdbcTemplate db; final ObjectMapper json;
    public LedgerController(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    @GetMapping("/internal/accounts") List<Map<String,Object>> accounts(@RequestHeader("X-User-Id") String user){return db.queryForList("SELECT * FROM accounts WHERE owner_id=? ORDER BY created_at,id",user);}
    record NewAccount(String label,String type){}
    @PostMapping("/internal/accounts") @Transactional Map<String,Object> open(@RequestHeader("X-User-Id") String user,@RequestBody NewAccount req){
        require(req.label()!=null&&!req.label().isBlank()&&req.label().length()<=60,400,"Choose an account name of 1–60 characters");
        require(Set.of("CHECKING","SAVINGS").contains(req.type()==null?"":req.type()),400,"Choose checking or savings");
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?)) IS NULL",Boolean.class,user);
        require(db.queryForObject("SELECT count(*) FROM accounts WHERE owner_id=?",Long.class,user)<8,400,"The demo allows up to eight accounts per customer");
        String id="NS"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
        db.update("INSERT INTO accounts(id,owner_id,label,type,balance) VALUES (?,?,?,?,0)",id,user,req.label().trim(),req.type());
        audit(user,"ACCOUNT_OPENED",id); return db.queryForMap("SELECT * FROM accounts WHERE id=?",id);
    }
    @GetMapping("/internal/transactions") List<Map<String,Object>> history(@RequestHeader("X-User-Id") String user){return db.queryForList("SELECT e.*,a.label,a.currency,t.from_account,t.to_account FROM entries e JOIN accounts a ON a.id=e.account_id LEFT JOIN transfers t ON t.id=e.transfer_id WHERE a.owner_id=? ORDER BY e.id DESC LIMIT 200",user);}
    @GetMapping("/internal/beneficiaries") List<Map<String,Object>> beneficiaries(@RequestHeader("X-User-Id") String user){return db.queryForList("SELECT b.*,a.currency FROM beneficiaries b JOIN accounts a ON a.id=b.account_id WHERE b.owner_id=? ORDER BY b.nickname",user);}
    record Beneficiary(String accountId,String nickname){}
    @PostMapping("/internal/beneficiaries") @Transactional Map<String,Object> addBeneficiary(@RequestHeader("X-User-Id") String user,@RequestBody Beneficiary req){
        require(req.nickname()!=null&&!req.nickname().isBlank()&&req.nickname().length()<=60,400,"A recipient name is required (up to 60 characters)");
        require(db.queryForObject("SELECT count(*) FROM accounts WHERE id=? AND owner_id NOT IN (?, 'system')",Long.class,req.accountId(),user)>0,400,"Recipient account not found. Use another demo customer's account.");
        var id=UUID.randomUUID();
        db.update("INSERT INTO beneficiaries VALUES (?,?,?,?) ON CONFLICT(owner_id,account_id) DO UPDATE SET nickname=excluded.nickname",id,user,req.accountId(),req.nickname().trim());
        audit(user,"RECIPIENT_SAVED",req.accountId()); return Map.of("ok",true);
    }
    @DeleteMapping("/internal/beneficiaries/{id}") Map<String,Boolean> remove(@RequestHeader("X-User-Id") String user,@PathVariable UUID id){db.update("DELETE FROM beneficiaries WHERE id=? AND owner_id=?",id,user);return Map.of("ok",true);}
    @GetMapping("/internal/cards") List<Map<String,Object>> cards(@RequestHeader("X-User-Id") String user){return db.queryForList("SELECT c.*,a.label FROM cards c JOIN accounts a ON a.id=c.account_id WHERE a.owner_id=?",user);}
    record CardStatus(String status){}
    @PatchMapping("/internal/cards/{id}") @Transactional Map<String,Boolean> card(@RequestHeader("X-User-Id") String user,@PathVariable UUID id,@RequestBody CardStatus req){
        require(Set.of("ACTIVE","FROZEN").contains(req.status()==null?"":req.status()),400,"Invalid card status");
        require(db.update("UPDATE cards SET status=? WHERE id=? AND account_id IN (SELECT id FROM accounts WHERE owner_id=?)",req.status(),id,user)==1,404,"Card not found");
        audit(user,"CARD_"+req.status(),id.toString());return Map.of("ok",true);
    }
    public record Transfer(UUID id,String fromAccount,String toAccount,BigDecimal amount,String description){}
    @PostMapping("/internal/transfers") @Transactional Map<String,Object> transfer(@RequestHeader("X-User-Id") String user,@RequestBody Transfer req) throws Exception {
        require(req.id()!=null && req.fromAccount()!=null && req.toAccount()!=null && req.amount()!=null,400,"Transfer fields are required");
        require(!req.fromAccount().equals(req.toAccount()),400,"Choose two different accounts");
        require(req.amount().signum()>0&&req.amount().compareTo(new BigDecimal("10000"))<=0&&req.amount().stripTrailingZeros().scale()<=2,400,"Enter $0.01–$10,000.00 with at most two decimal places");
        String description=req.description()==null?"Transfer":req.description().trim();
        require(!description.isBlank()&&description.length()<=140,400,"Description must contain 1–140 characters");
        // All postings for a request lock the same key; account rows are then locked in a stable order.
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?)) IS NULL",Boolean.class,req.id().toString());
        var existing=db.queryForList("SELECT * FROM transfers WHERE id=?",req.id());
        if(!existing.isEmpty()){
            var old=existing.getFirst();
            require(old.get("user_id").equals(user)&&old.get("from_account").equals(req.fromAccount())&&old.get("to_account").equals(req.toAccount())&&((BigDecimal)old.get("amount")).compareTo(req.amount())==0&&old.get("description").equals(description),409,"Request ID was already used for a different transfer");
            return old;
        }
        var locked=db.queryForList("SELECT * FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE",req.fromAccount(),req.toAccount());
        require(locked.size()==2,404,"Account not found");
        var from=locked.stream().filter(a->a.get("id").equals(req.fromAccount())).findFirst().orElseThrow();
        var to=locked.stream().filter(a->a.get("id").equals(req.toAccount())).findFirst().orElseThrow();
        require(from.get("owner_id").equals(user),403,"You can only transfer from your own accounts");
        require(!to.get("owner_id").equals("system"),400,"Invalid destination");
        require(to.get("owner_id").equals(user)||db.queryForObject("SELECT count(*) FROM beneficiaries WHERE owner_id=? AND account_id=?",Long.class,user,req.toAccount())>0,400,"Save this recipient before sending money");
        require(((BigDecimal)from.get("balance")).compareTo(req.amount())>=0,422,"Insufficient available balance");
        db.update("UPDATE accounts SET balance=balance-? WHERE id=?",req.amount(),req.fromAccount());
        db.update("UPDATE accounts SET balance=balance+? WHERE id=?",req.amount(),req.toAccount());
        db.update("INSERT INTO transfers(id,user_id,from_account,to_account,amount,description) VALUES (?,?,?,?,?,?)",req.id(),user,req.fromAccount(),req.toAccount(),req.amount(),description);
        db.update("INSERT INTO entries(transfer_id,account_id,amount,kind,description) VALUES (?,?,?,'DEBIT',?),(?,?,?,'CREDIT',?)",req.id(),req.fromAccount(),req.amount().negate(),description,req.id(),req.toAccount(),req.amount(),description);
        var event=new LinkedHashMap<String,Object>();
        event.put("id",req.id());event.put("type","TRANSFER_COMPLETED");event.put("sender",user);event.put("receiver",to.get("owner_id"));event.put("amount",req.amount());event.put("fromAccount",req.fromAccount());event.put("toAccount",req.toAccount());event.put("description",description);event.put("createdAt",java.time.Instant.now().toString());
        db.update("INSERT INTO outbox(id,payload) VALUES (?,?)",req.id(),json.writeValueAsString(event));
        audit(user,"TRANSFER_POSTED",req.id()+" · "+req.amount()+" USD");
        return db.queryForMap("SELECT * FROM transfers WHERE id=?",req.id());
    }
    @GetMapping("/internal/operations") Map<String,Object> operations(){return Map.of("accounts",db.queryForObject("SELECT count(*) FROM accounts WHERE owner_id!='system'",Long.class),"transfers",db.queryForObject("SELECT count(*) FROM transfers",Long.class),"pendingEvents",db.queryForObject("SELECT count(*) FROM outbox WHERE NOT published",Long.class),"ledgerNet",db.queryForObject("SELECT coalesce(sum(amount),0) FROM entries",BigDecimal.class),"audit",db.queryForList("SELECT * FROM audit ORDER BY id DESC LIMIT 50"));}
    void audit(String actor,String action,String detail){db.update("INSERT INTO audit(actor,action,detail) VALUES (?,?,?)",actor,action,detail);}
}
