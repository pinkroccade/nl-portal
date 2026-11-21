package nl.nlportal.proto

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ProtoApplication

fun main(args: Array<String>) {
    runApplication<ProtoApplication>(*args)
}
