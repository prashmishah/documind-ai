
package com.documind.backend;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class BackendApplicationTests {

    private final HealthController controller =
            new HealthController();

    @Test
    void healthEndpointReturnsSuccessMessage() {

        String response = controller.health();

        assertEquals(
                "DocuMind AI Backend is running!",
                response
        );
    }

    @Test
    void healthEndpointReturnsNonEmptyResponse() {

        String response = controller.health();

        assertNotNull(response);
        assertFalse(response.isBlank());
    }
}
