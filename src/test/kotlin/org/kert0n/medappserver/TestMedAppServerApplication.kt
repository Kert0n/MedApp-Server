package org.kert0n.medappserver

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<MedAppServerApplication>().with(TestcontainersConfiguration::class).run(*args)
}
