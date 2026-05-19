package dev.yaytsa.persistence.preferences

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.TestPropertySource

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ComponentScan(basePackages = ["dev.yaytsa.persistence.preferences"])
@TestPropertySource(
    properties = [
        "spring.flyway.locations=classpath:db/preferences",
        "spring.flyway.schemas=core_v2_preferences",
        "spring.flyway.default-schema=core_v2_preferences",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
abstract class AbstractPersistenceTest : dev.yaytsa.testkit.persistence.AbstractPersistenceTest()
