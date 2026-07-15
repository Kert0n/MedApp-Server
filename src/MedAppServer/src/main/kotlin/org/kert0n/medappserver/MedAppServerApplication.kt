package org.kert0n.medappserver


import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication


@SpringBootApplication
@ConfigurationPropertiesScan

class MedAppServerApplication

fun main(args: Array<String>) {
    runApplication<MedAppServerApplication>(*args)

}
