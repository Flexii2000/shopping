package com.fherrmann.shopping;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "shopping.data-file=build/tmp/context-test/shopping.json")
class ShoppingApplicationTests {

    @Test
    void contextLoads() {
    }
}
