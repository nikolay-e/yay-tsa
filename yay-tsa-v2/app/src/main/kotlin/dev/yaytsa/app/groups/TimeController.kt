package dev.yaytsa.app.groups

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class TimeController {
    // ServerClock samples this to estimate clock offset/RTT; it reads the body as a bare number.
    @GetMapping("/v1/time", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun serverTime(): String = Instant.now().toEpochMilli().toString()
}
