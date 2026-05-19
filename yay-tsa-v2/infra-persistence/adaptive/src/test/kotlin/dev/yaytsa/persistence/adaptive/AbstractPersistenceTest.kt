package dev.yaytsa.persistence.adaptive

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.TestPropertySource

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ComponentScan(basePackages = ["dev.yaytsa.persistence.adaptive"])
@TestPropertySource(
    properties = [
        "spring.flyway.locations=classpath:db/adaptive",
        "spring.flyway.schemas=core_v2_adaptive",
        "spring.flyway.default-schema=core_v2_adaptive",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
abstract class AbstractPersistenceTest : dev.yaytsa.testkit.persistence.AbstractPersistenceTest()
