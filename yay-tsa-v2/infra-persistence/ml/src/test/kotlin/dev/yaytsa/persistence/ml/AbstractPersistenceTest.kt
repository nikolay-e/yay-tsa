package dev.yaytsa.persistence.ml

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.TestPropertySource

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ComponentScan(basePackages = ["dev.yaytsa.persistence.ml"])
@TestPropertySource(
    properties = [
        "spring.flyway.locations=classpath:db/ml",
        "spring.flyway.schemas=core_v2_ml",
        "spring.flyway.default-schema=core_v2_ml",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
abstract class AbstractPersistenceTest : dev.yaytsa.testkit.persistence.AbstractPersistenceTest()
