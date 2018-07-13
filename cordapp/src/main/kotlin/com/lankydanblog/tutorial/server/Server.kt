package com.lankydanblog.tutorial.server

import com.lankydanblog.tutorial.server.client.MessageClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder


// dont use @EnableWebFlux since it disables all spring boot webflux autoconfiguration
// this prevented the jackson module from being picked up
@SpringBootApplication
private class Starter : CommandLineRunner {

    @Autowired
    lateinit var encoder: Jackson2JsonEncoder

    @Autowired
    lateinit var decoder: Jackson2JsonDecoder

    override fun run(vararg args: String?) {
        val client = MessageClient(encoder, decoder)
        client.doStuff()
    }
}

fun main(args: Array<String>) {
    SpringApplication.run(Starter::class.java)
}