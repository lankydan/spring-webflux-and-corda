package com.lankydanblog.tutorial.server.client

import com.lankydanblog.tutorial.server.dto.Message
import net.corda.core.node.services.Vault
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType.APPLICATION_STREAM_JSON
import org.springframework.http.MediaType.TEXT_EVENT_STREAM
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@Component
class MessageClient(
    @Value("\${server.host}") private val host: String,
    @Value("\${server.port}") private val port: Int,
    private val decoder: Jackson2JsonDecoder
) {

    // cannot be done DefaultExchangeStrategiesBuilder does not allow this
    // plus WebClient.create() inits a new webclient with the default builders properties which wont
    // include any config ive done
    private val strategies = ExchangeStrategies
        .builder()
        .codecs { clientCodecConfigurer ->
            clientCodecConfigurer.defaultCodecs().jackson2JsonDecoder(decoder)
        }.build()

    private val client = WebClient.builder()
        .exchangeStrategies(strategies)
        .baseUrl("http://$host:$port")
        .build()

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
            .subscribe { println("UPDATE: $it") }

    }
}