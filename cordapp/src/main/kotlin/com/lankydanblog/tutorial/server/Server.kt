package com.lankydanblog.tutorial.server

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import net.corda.client.jackson.JacksonSupport
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.node.services.Vault
import com.lankydanblog.tutorial.server.client.MessageClient
import com.lankydanblog.tutorial.states.MessageState
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.jackson.JsonComponent
import org.springframework.boot.jackson.JsonComponentModule
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.config.WebFluxConfigurer


// dont use @EnableWebFlux since it disables all spring boot webflux autoconfiguration
// this prevented the jackson module from being picked up
@SpringBootApplication
//@EnableWebFlux
private class Starter : CommandLineRunner {

    @Bean
    fun webFluxConfigurer(encoder: Jackson2JsonEncoder, decoder: Jackson2JsonDecoder): WebFluxConfigurer {
        return object : WebFluxConfigurer {
            override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer?) {
                configurer!!.defaultCodecs().jackson2JsonEncoder(encoder)
                configurer.defaultCodecs().jackson2JsonDecoder(decoder)
            }
        }

    }

    @Autowired
    lateinit var encoder: Jackson2JsonEncoder;

    @Autowired
    lateinit var decoder: Jackson2JsonDecoder;

    override fun run(vararg args: String?) {
        val client = MessageClient(encoder, decoder)
        client.doStuff()
    }
}

fun main(args: Array<String>) {
    SpringApplication.run(Starter::class.java)
}