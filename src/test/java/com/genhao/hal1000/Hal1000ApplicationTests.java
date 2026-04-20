package com.genhao.hal1000;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "hal1000.auth.jwt-secret=test_secret_1234567890",
        "hal1000.auth.jwt-issuer=hal1000-test"
})
class Hal1000ApplicationTests {

    @Test
    void contextLoads() {
    }

}
