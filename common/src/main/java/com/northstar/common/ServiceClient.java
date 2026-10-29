package com.northstar.common;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
@Component
public class ServiceClient {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;
    @Value("${bank.internal-secret}") String secret;
    @Value("${bank.services.identity-url:http://127.0.0.1:8081}") String identityUrl;
    @Value("${bank.services.ledger-url:http://127.0.0.1:8082}") String ledgerUrl;
    @Value("${bank.services.payment-url:http://127.0.0.1:8083}") String paymentUrl;
    @Value("${bank.services.notification-url:http://127.0.0.1:8084}") String notificationUrl;
    public ServiceClient(ObjectMapper mapper) {this.mapper=mapper;}
    public JsonNode call(int port,String method,String path,String user,Object body) {
        try {
            var b=HttpRequest.newBuilder(URI.create(baseUrl(port)+path)).timeout(Duration.ofSeconds(8))
                .header("X-Internal-Key",secret).header("Content-Type","application/json");
            if(user!=null) b.header("X-User-Id",user);
            b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var r=client.send(b.build(),HttpResponse.BodyHandlers.ofString());
            JsonNode result=mapper.readTree(r.body());
            if(r.statusCode()>=400) throw new ApiException(r.statusCode(),result.path("error").asText("Downstream service rejected the request"));
            return result;
        } catch(ApiException e) {throw e;} catch(Exception e) {throw new ApiException(503,"A banking service is unavailable. Your request can be safely retried.");}
    }
    private String baseUrl(int port) {
        return switch(port) {
            case 8081 -> identityUrl;
            case 8082 -> ledgerUrl;
            case 8083 -> paymentUrl;
            case 8084 -> notificationUrl;
            default -> throw new ApiException(503,"Unknown downstream service.");
        };
    }
}
