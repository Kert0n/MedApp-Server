package org.kert0n.medappserver


import org.springframework.boot.autoconfigure.SpringBootApplication
import org.kert0n.medappserver.services.security.RsaKeyProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication


@SpringBootApplication
@EnableConfigurationProperties(RsaKeyProperties::class)

class MedAppServerApplication

fun main(args: Array<String>) {
    runApplication<MedAppServerApplication>(*args)

}
