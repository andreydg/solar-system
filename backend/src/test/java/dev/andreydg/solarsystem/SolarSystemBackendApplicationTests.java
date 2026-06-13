package dev.andreydg.solarsystem;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "solar-system.storage=in-memory",
    "solar-system.jpl.async-validation-enabled=false"
})
class SolarSystemBackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
