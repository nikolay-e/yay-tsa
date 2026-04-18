package dev.yaytsa.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(
    scanBasePackages = ["dev.yaytsa"],
)
@EntityScan("dev.yaytsa")
@EnableJpaRepositories("dev.yaytsa")
@EnableScheduling
class YaytsaApplication

fun main(args: Array<String>) {
    runApplication<YaytsaApplication>(*args)
}
