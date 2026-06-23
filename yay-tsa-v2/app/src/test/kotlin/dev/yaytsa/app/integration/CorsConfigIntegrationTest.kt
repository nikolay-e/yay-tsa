package dev.yaytsa.app.integration

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class CorsConfigIntegrationTest : HttpIntegrationTestBase() {
    @Test
    fun `explicit allowed origin is echoed with credentials allowed`() {
        mockMvc
            .perform(
                MockMvcRequestBuilders
                    .options("/Items")
                    .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()),
            ).andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
    }

    @Test
    fun `origin outside the allowed list is rejected`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .options("/Items")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()),
                ).andReturn()

        assertNull(result.response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
    }
}
