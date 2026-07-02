package dev.yaytsa.worker.ml

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@EntityScan(basePackages = ["dev.yaytsa.persistence.library.entity"])
@EnableJpaRepositories(basePackages = ["dev.yaytsa.persistence.library.repository"])
class MlWorkerTestApplication
