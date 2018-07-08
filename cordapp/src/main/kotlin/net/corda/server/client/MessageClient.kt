package net.corda.server.client

import com.fasterxml.jackson.databind.ObjectMapper
import net.corda.core.node.services.Vault
import net.corda.server.dto.Message
import net.corda.states.MessageState
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.MediaType.*
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.function.client.ExchangeStrategies


class MessageClient(val rpcObjectMapper: ObjectMapper) {

    var strategies = ExchangeStrategies
        .builder()
        .codecs { clientDefaultCodecsConfigurer ->
            val encoder = Jackson2JsonEncoder(rpcObjectMapper, MediaType.APPLICATION_JSON)
            encoder.streamingMediaTypes = listOf(
                MediaType.APPLICATION_STREAM_JSON
            )
            clientDefaultCodecsConfigurer.defaultCodecs()
                .jackson2JsonEncoder(encoder)
            clientDefaultCodecsConfigurer.defaultCodecs()
                .jackson2JsonDecoder(Jackson2JsonDecoder(rpcObjectMapper, MediaType.APPLICATION_JSON, MediaType.APPLICATION_STREAM_JSON))

        }.build()

//    private val client = WebClient.create("http://localhost:10011")

    private val client = WebClient.builder().exchangeStrategies(strategies).baseUrl("http://localhost:10011").build()

    fun doStuff() {
        val message = Message("O=PartyB,L=London,C=GB", "hello there")
        client
            .post()
            .uri("/messages")
            .body(Mono.just(message), Message::class.java)
            .accept(TEXT_EVENT_STREAM)
            .exchange()
            .flatMapMany { it.bodyToFlux(String::class.java) }
            .subscribe { println("STEP: $it") }

        client
            .get()
            .uri("/messages/updates")
            .accept(APPLICATION_STREAM_JSON)
            .exchange()
            .flatMapMany { it.bodyToFlux(Vault.Update::class.java) }
            .subscribe { println("STEP: $it") }

//        client
//            .get()
//            .uri("/messages/updates")
//            .accept(APPLICATION_STREAM_JSON)
//            .exchange()
//            .flatMapMany { it.bodyToFlux(MessageState::class.java) }
//            .subscribe { println("STEP: $it") }

    }
}