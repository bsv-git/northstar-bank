package com.northstar.gateway;
import com.northstar.common.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.northstar.common.ApiException.require;
@RestController
public class GatewayController {
    final ServiceClient client;final ObjectMapper json;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    @org.springframework.beans.factory.annotation.Value("${bank.services.identity-url}") String identityUrl;
    @org.springframework.beans.factory.annotation.Value("${bank.services.ledger-url}") String ledgerUrl;
    @org.springframework.beans.factory.annotation.Value("${bank.services.payment-url}") String paymentUrl;
    @org.springframework.beans.factory.annotation.Value("${bank.services.notification-url}") String notificationUrl;
    public GatewayController(ServiceClient client,ObjectMapper json){this.client=client;this.json=json;}
    @PostMapping("/api/login") ResponseEntity<?> login(@RequestBody JsonNode body,HttpServletRequest request){
        mutation(request);
        JsonNode result=client.call(8081,"POST","/internal/login",null,body);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie(result.path("token").asText(),28800)).body(result.path("user"));
    }
    @PostMapping("/api/users") JsonNode createUser(HttpServletRequest request,@RequestBody JsonNode body){
        mutation(request);
        return client.call(8081,"POST","/internal/users",null,body);
    }
    @PostMapping("/api/v1/auth/signup") JsonNode signup(HttpServletRequest request,@RequestBody JsonNode body){
        mutation(request);
        return client.call(8081,"POST","/api/v1/auth/signup",null,body);
    }
    @PostMapping("/api/logout") ResponseEntity<?> logout(HttpServletRequest request){mutation(request);client.call(8081,"POST","/internal/logout",null,Map.of("token",token(request)));return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie("",0)).body(Map.of("ok",true));}
    @GetMapping("/api/me") JsonNode me(HttpServletRequest request){return user(request);}
    @RequestMapping(value={"/api/accounts","/api/transactions","/api/beneficiaries","/api/beneficiaries/{id}","/api/cards","/api/cards/{id}","/api/notifications","/api/notifications/read"},method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PATCH,RequestMethod.DELETE})
    JsonNode route(HttpServletRequest request,@RequestBody(required=false) JsonNode body){
        if(!request.getMethod().equals("GET"))mutation(request);
        var user=requireRole(request,"CUSTOMER");
        String path=request.getRequestURI().replaceFirst("/api/","/internal/");
        int port=path.startsWith("/internal/notifications")?8084:8082;
        return client.call(port,request.getMethod(),path,user.path("id").asText(),body);
    }
    @GetMapping("/api/payments") JsonNode payments(HttpServletRequest request){var user=requireRole(request,"CUSTOMER");return client.call(8083,"GET","/internal/payments",user.path("id").asText(),null);}
    @PostMapping("/api/payments") JsonNode pay(HttpServletRequest request,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key) throws Exception {
        mutation(request);String user=requireRole(request,"CUSTOMER").path("id").asText();
        require(key.matches("[A-Za-z0-9-]{8,80}"),400,"Invalid request ID");
        // Preserve the caller's idempotency key across the service boundary.
        var r=http.send(HttpRequest.newBuilder(URI.create(paymentUrl+"/internal/payments")).timeout(Duration.ofSeconds(12)).header("Content-Type","application/json").header("X-Internal-Key",secret).header("X-User-Id",user).header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        var result=json.readTree(r.body());if(r.statusCode()>=400)throw new ApiException(r.statusCode(),result.path("error").asText("Payment service unavailable"));return result;
    }
    @org.springframework.beans.factory.annotation.Value("${bank.internal-secret}") String secret;
    @GetMapping("/api/operations") Map<String,Object> operations(HttpServletRequest request){
        require(user(request).path("role").asText().equals("ADMIN"),403,"Administrator access required");
        var result=new LinkedHashMap<String,Object>();result.put("ledger",client.call(8082,"GET","/internal/operations",null,null));result.put("services",health());return result;
    }
    @GetMapping("/api/health") List<Map<String,Object>> health(){
        var services=new ArrayList<Map<String,Object>>();
        for(var s:Map.of("Identity",8081,"Ledger",8082,"Payments",8083,"Notifications",8084).entrySet()){
            boolean up=false;try{up=http.send(HttpRequest.newBuilder(URI.create(serviceUrl(s.getValue())+"/actuator/health")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(Exception ignored){}
            services.add(Map.of("name",s.getKey(),"port",s.getValue(),"status",up?"UP":"DOWN"));
        }
        services.sort(Comparator.comparing(a->a.get("port").toString()));return services;
    }
    @GetMapping("/api/csrf") Map<String,String> csrf(org.springframework.security.web.csrf.CsrfToken token){
        return Map.of("token",token.getToken());
    }
    @GetMapping(value="/api/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter events(HttpServletRequest request){
        String session=token(request);String user=requireRole(request,"CUSTOMER").path("id").asText();
        var emitter=new SseEmitter(1800000L);var closed=new java.util.concurrent.atomic.AtomicBoolean(false);
        emitter.onCompletion(()->closed.set(true));emitter.onTimeout(()->closed.set(true));emitter.onError(e->closed.set(true));
        Thread.startVirtualThread(()->{
            String last="";int tick=0;
            try {while(!closed.get()){
                if(tick++%15==0)client.call(8081,"POST","/internal/session",null,Map.of("token",session));
                JsonNode notes=client.call(8084,"GET","/internal/notifications",user,null);
                String signature=notes.toString();
                if(!signature.equals(last)){emitter.send(SseEmitter.event().name("activity").data(notes));last=signature;}
                else emitter.send(SseEmitter.event().comment("heartbeat"));
                Thread.sleep(1500);
            }}catch(Exception ignored){emitter.complete();}
        });return emitter;
    }
    JsonNode user(HttpServletRequest r){return client.call(8081,"POST","/internal/session",null,Map.of("token",token(r)));}
    JsonNode requireRole(HttpServletRequest request,String role){
        JsonNode current=user(request);
        require(role.equals(current.path("role").asText()),403,"This action requires the "+role.toLowerCase(Locale.ROOT)+" role");
        return current;
    }
    String token(HttpServletRequest r){if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("bank_session"))return c.getValue();throw new ApiException(401,"Please sign in");}
    String cookie(String value,int maxAge){return ResponseCookie.from("bank_session",value).httpOnly(true).sameSite("Strict").path("/").maxAge(maxAge).build().toString();}
    String serviceUrl(int port){return switch(port){case 8081->identityUrl;case 8082->ledgerUrl;case 8083->paymentUrl;case 8084->notificationUrl;default->throw new ApiException(503,"Unknown downstream service.");};}
    void mutation(HttpServletRequest r){
        require("Northstar".equals(r.getHeader("X-Requested-With")),403,"Missing request protection header");
        String origin=r.getHeader("Origin");
        String requestOrigin=r.getScheme()+"://"+r.getServerName()+":"+r.getServerPort();
        require(origin==null||origin.equals(requestOrigin)||Set.of("http://localhost:8080","http://127.0.0.1:8080").contains(origin),403,"Origin is not allowed");
    }
}
