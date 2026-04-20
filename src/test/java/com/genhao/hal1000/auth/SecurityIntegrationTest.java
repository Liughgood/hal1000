package com.genhao.hal1000.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "hal1000.auth.jwt-secret=test_secret_1234567890",
        "hal1000.auth.jwt-issuer=hal1000-test"
})
class SecurityIntegrationTest {

    @Autowired
    WebApplicationContext wac;

    @Test
    void apiRequiresAuth() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(springSecurity())
                .build();

        mvc.perform(get("/api/conversations"))
                .andExpect(status().isUnauthorized());
    }
}

