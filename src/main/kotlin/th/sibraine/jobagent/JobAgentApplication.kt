package th.sibraine.jobagent

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class JobAgentApplication

fun main(args: Array<String>) {
    runApplication<th.sibraine.jobagent.JobAgentApplication>(*args)
}
