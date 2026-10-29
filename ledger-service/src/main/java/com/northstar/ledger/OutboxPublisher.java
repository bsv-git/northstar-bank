package com.northstar.ledger;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.admin.NewTopic;
import java.util.concurrent.TimeUnit;
@Component
public class OutboxPublisher {
    final JdbcTemplate db;final KafkaTemplate<String,String> kafka;
    public OutboxPublisher(JdbcTemplate db,KafkaTemplate<String,String> kafka){this.db=db;this.kafka=kafka;}
    @Bean NewTopic bankEvents(){return new NewTopic("bank.transactions.v1",3,(short)1);}
    @Scheduled(fixedDelay=1000) public void publish(){
        for(var row:db.queryForList("SELECT id,payload FROM outbox WHERE NOT published ORDER BY created_at LIMIT 50")){
            try{
                // A crash after Kafka acknowledges can redeliver; consumers deduplicate by event ID.
                kafka.send("bank.transactions.v1",row.get("id").toString(),row.get("payload").toString()).get(18,TimeUnit.SECONDS);
                db.update("UPDATE outbox SET published=true WHERE id=?",row.get("id"));
            }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Kafka delivery deferred; durable outbox retained: {}",e.getMessage());break;}
        }
    }
}
