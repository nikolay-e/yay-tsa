package dev.yaytsa.persistence.auth

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.TestPropertySource

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ComponentScan(basePackages = ["dev.yaytsa.persistence.auth"])
@TestPropertySource(
    properties = [
        "spring.flyway.locations=classpath:db/auth",
        "spring.flyway.schemas=core_v2_auth",
        "spring.flyway.default-schema=core_v2_auth",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
abstract class AbstractPersistenceTest : dev.yaytsa.testkit.persistence.AbstractPersistenceTest()
