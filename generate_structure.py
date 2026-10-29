from pathlib import Path
root=Path(__file__).parent
modules=['common','identity-service','ledger-service','payment-service','notification-service','gateway-service']
for module in modules:
    base=root/module
    (base/'src/main/java/com/northstar'/module.split('-')[0]).mkdir(parents=True,exist_ok=True)
    (base/'src/main/resources').mkdir(parents=True,exist_ok=True)
    deps=['<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>']
    if module!='common':
        deps += ['<dependency><groupId>com.northstar</groupId><artifactId>common</artifactId><version>${project.version}</version></dependency>', '<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>']
    if module not in ['common','gateway-service']:
        deps += ['<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>','<dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>','<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>','<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>']
    if module=='identity-service': deps += ['<dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-crypto</artifactId></dependency>']
    if module in ['ledger-service','notification-service']: deps += ['<dependency><groupId>org.springframework.kafka</groupId><artifactId>spring-kafka</artifactId></dependency>']
    build='' if module=='common' else '<build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>'
    (base/'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>com.northstar</groupId><artifactId>bank-demo</artifactId><version>1.0.0</version></parent><artifactId>'+module+'</artifactId><dependencies>'+''.join(deps)+'</dependencies>'+build+'</project>')
    if module=='common': continue
    name=module.split('-')[0]
    port=8080 if name=='gateway' else {'identity':8081,'ledger':8082,'payment':8083,'notification':8084}[name]
    properties=f'''spring.application.name={module}
server.address=127.0.0.1
server.port={port}
server.error.include-message=never
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=10s
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=never
bank.internal-secret=${{BANK_INTERNAL_SECRET}}
spring.threads.virtual.enabled=true
'''
    if name!='gateway': properties+=f'''spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/bank_{name}
spring.datasource.username=bank_{name}
spring.datasource.password=${{BANK_DB_PASSWORD}}
spring.datasource.hikari.maximum-pool-size=6
spring.flyway.enabled=true
'''
    if name in ['ledger','notification']: properties+='''spring.kafka.bootstrap-servers=127.0.0.1:19092
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.acks=all
spring.kafka.producer.properties.enable.idempotence=true
spring.kafka.producer.properties.delivery.timeout.ms=15000
spring.kafka.producer.properties.request.timeout.ms=5000
spring.kafka.producer.properties.max.block.ms=5000
spring.kafka.consumer.group-id=bank-notifications-v1
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.listener.ack-mode=record
'''
    (base/'src/main/resources/application.properties').write_text(properties)
    (base/f'src/main/java/com/northstar/{name}/Application.java').write_text(f'''package com.northstar.{name};
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication(scanBasePackages={{"com.northstar.{name}","com.northstar.common"}})
@EnableScheduling
public class Application {{ public static void main(String[] args) {{ SpringApplication.run(Application.class,args); }} }}
''')
