package com.onboarding.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PlatformApplicationTests {

    @Test
    void contextLoads() {
        // If the application context fails to wire (bad bean config, broken
        // security setup, missing datasource, etc), this test fails --
        // cheap but genuinely useful as a first line of CI defense.
    }
}
