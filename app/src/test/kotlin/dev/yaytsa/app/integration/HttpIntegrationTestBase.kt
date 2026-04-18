package dev.yaytsa.app.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest
@AutoConfigureMockMvc
abstract class HttpIntegrationTestBase {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("yaytsa_test")
                .withUsername("test")
                .withPassword("test")
                .withInitScript("init-extensions.sql")

        init {
            postgres.start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.enabled") { "false" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "none" }
            registry.add("yaytsa.mpd.enabled") { "false" }
            registry.add("yaytsa.llm.enabled") { "false" }
            registry.add("yaytsa.ml.enabled") { "false" }
            registry.add("yaytsa.karaoke.enabled") { "false" }
        }
    }

    protected fun post(
        url: String,
        body: Any,
        token: String? = null,
    ): MvcResult {
        val builder =
            MockMvcRequestBuilders
                .post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return mockMvc.perform(builder).andReturn()
    }

    protected fun get(
        url: String,
        token: String? = null,
    ): MvcResult {
        val builder = MockMvcRequestBuilders.get(url)
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return mockMvc.perform(builder).andReturn()
    }

    protected fun delete(
        url: String,
        token: String? = null,
    ): MvcResult {
        val builder = MockMvcRequestBuilders.delete(url)
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return mockMvc.perform(builder).andReturn()
    }
}
