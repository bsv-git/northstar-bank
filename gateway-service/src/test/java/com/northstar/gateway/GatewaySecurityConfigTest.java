package com.northstar.gateway;

import com.northstar.common.ServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GatewayController.class)
@Import(GatewaySecurityConfig.class)
class GatewaySecurityConfigTest {
    @Autowired
    MockMvc mvc;

    @MockBean
    SessionAuthenticationFilter sessionAuthenticationFilter;

    @MockBean
    ServiceClient serviceClient;

    @Test
    void protectedBankingEndpointRejectsAnonymousRequest() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotAccessAdminEndpoint() throws Exception {
        mvc.perform(get("/api/operations"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicSignupStillRequiresCsrfTokenForStateChangingRequests() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
