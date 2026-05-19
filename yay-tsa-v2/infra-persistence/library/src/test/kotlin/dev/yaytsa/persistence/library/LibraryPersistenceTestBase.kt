package dev.yaytsa.persistence.library

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.TestPropertySource

@SpringBootApplication
@EntityScan(basePackages = ["dev.yaytsa.persistence.library.entity"])
@EnableJpaRepositories(basePackages = ["dev.yaytsa.persistence.library.repository"])
class LibraryTestApplication

@SpringBootTest(classes = [LibraryTestApplication::class])
@TestPropertySource(
    properties = [
        "spring.flyway.locations=classpath:db/library",
        "spring.flyway.schemas=core_v2_library",
        "spring.flyway.default-schema=core_v2_library",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=core_v2_library",
    ],
)
abstract class LibraryPersistenceTestBase : dev.yaytsa.testkit.persistence.AbstractPersistenceTest()
