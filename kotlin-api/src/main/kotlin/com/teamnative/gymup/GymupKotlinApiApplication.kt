package com.teamnative.gymup

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GymupKotlinApiApplication

fun main(args: Array<String>) {
    runApplication<GymupKotlinApiApplication>(*args)
}
