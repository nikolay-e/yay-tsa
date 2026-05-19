package dev.yaytsa.persistence.playback

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.TestPropertySource

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ComponentScan(basePackages = ["dev.yaytsa.persistence.playback"])
@TestPropertySource(
    properties = [
        "spring.flyway.locations=classpath:db/playback",
        "spring.flyway.schemas=core_v2_playback",
        "spring.flyway.default-schema=core_v2_playback",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
abstract class AbstractPersistenceTest : dev.yaytsa.testkit.persistence.AbstractPersistenceTest()
