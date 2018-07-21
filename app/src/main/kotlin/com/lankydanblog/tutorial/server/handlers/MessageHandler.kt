package com.lankydanblog.tutorial.server.handlers

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultTrackBy
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import com.lankydanblog.tutorial.flows.SendMessageFlow
import com.lankydanblog.tutorial.server.NodeRPCConnection
import com.lankydanblog.tutorial.server.dto.Message
import com.lankydanblog.tutorial.states.MessageState
import net.corda.core.messaging.CordaRPCOps
import org.reactivestreams.Publisher
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType.APPLICATION_STREAM_JSON
import org.springframework.http.MediaType.TEXT_EVENT_STREAM
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.ServerResponse.created
import org.springframework.web.reactive.function.server.ServerResponse.ok
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import rx.RxReactiveStreams.toPublisher
import java.util.*


@Component
class MessageHandler(rpc: NodeRPCConnection) {

    private val proxy: CordaRPCOps = rpc.proxy

    fun updates(request: ServerRequest): Mono<ServerResponse> {
        return ok().contentType(APPLICATION_STREAM_JSON)
            .body(trackNewMessages(), ParameterizedTypeReference.forType(Vault.Update::class.java))
    }

    private fun trackNewMessages(): Publisher<Vault.Update<MessageState>> =
        toPublisher(proxy.vaultTrackBy<MessageState>().updates)

    fun post(request: ServerRequest): Mono<ServerResponse> {
        val message = request.bodyToMono(Message::class.java)
        val id = UUID.randomUUID()
        return message.flatMap {
            created(UriComponentsBuilder.fromPath("messages/$id").build().toUri())
                .contentType(TEXT_EVENT_STREAM)
                .body(startTrackedFlow(it, id), String::class.java)
        }
    }

    private fun startTrackedFlow(message: Message, id: UUID): Publisher<String> =
        toPublisher(
            proxy.startTrackedFlow(
                ::SendMessageFlow, state(message, id)
            ).progress
        )

    private fun state(message: Message, id: UUID) =
        MessageState(
            sender = proxy.nodeInfo().legalIdentities.first(),
            recipient = parse(message.recipient),
            contents = message.contents,
            linearId = UniqueIdentifier(id.toString())
        )

    private fun parse(party: String) = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(party))
            ?: throw IllegalArgumentException("Unknown party name.")
}