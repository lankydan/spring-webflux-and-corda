package com.lankydanblog.tutorial.server

import com.lankydanblog.tutorial.server.client.MessageClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication


// dont use @EnableWebFlux since it disables all spring boot webflux autoconfiguration
// this prevented the jackson module from being picked up
@SpringBootApplication
private class Starter : CommandLineRunner {

    @Autowired
    private lateinit var client: MessageClient

    override fun run(vararg args: String?) {
        client.doStuff()
    }
}

fun main(args: Array<String>) {
    SpringApplication.run(Starter::class.java)
}