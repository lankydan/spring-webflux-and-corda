package net.corda.server

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import net.corda.client.jackson.JacksonSupport
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.Vault
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.server.client.MessageClient
import net.corda.states.MessageState
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
import java.math.BigDecimal
import java.security.PublicKey
import java.util.*


// dont use @EnableWebFlux since it disables all spring boot webflux autoconfiguration
// this prevented the jackson module from being picked up
@SpringBootApplication
//@EnableWebFlux
private class Starter : CommandLineRunner {

    @Bean
    fun cordaModule() = JacksonSupport.cordaModule

    // doesnt seem to need the modules
    @Bean
    fun rpcObjectMapper(rpc: NodeRPCConnection): ObjectMapper {
        val mapper = JacksonSupport.createDefaultMapper(rpc.proxy)
//        mapper.registerModule(cordaModule())
//        mapper.registerModule(CordaJacksonSupportModule())
        mapper.registerModule(jsonComponentModule())
//        mapper.registerModule(JacksonSupport.cordaModule)
        mapper.registerModule(MyModule(rpc))
        return mapper
    }

    class MyModule(rpc: NodeRPCConnection) : SimpleModule() {
        init {
//            addDeserializer(ContractState::class.java, ContractStateDeserialiser(rpc))
//            addSerializer(AttachmentConstraint::class.java, AttachmentConstraintSerialiser)
//            addDeserializer(AttachmentConstraint::class.java, AttachmentConstraintDeserialiser)
//            addDeserializer(Vault.Update::class.java, VaultUpdateDeserialiser)
            setMixInAnnotation(Vault.Update::class.java, VaultUpdateMixin::class.java)
        }
    }

//    class CordaJacksonSupportModule : SimpleModule() {
//        init {
//            addSerializer(AnonymousParty::class.java, JacksonSupport.AnonymousPartySerializer)
//            addDeserializer(AnonymousParty::class.java, JacksonSupport.AnonymousPartyDeserializer)
//            addSerializer(Party::class.java, JacksonSupport.PartySerializer)
//            addDeserializer(Party::class.java, JacksonSupport.PartyDeserializer)
//            addDeserializer(AbstractParty::class.java, JacksonSupport.PartyDeserializer)
//            addSerializer(BigDecimal::class.java, JacksonSupport.ToStringSerializer)
//            addDeserializer(BigDecimal::class.java, NumberDeserializers.BigDecimalDeserializer())
//            addSerializer(SecureHash::class.java, JacksonSupport.SecureHashSerializer)
//            addSerializer(SecureHash.SHA256::class.java, JacksonSupport.SecureHashSerializer)
//            addDeserializer(SecureHash::class.java, JacksonSupport.SecureHashDeserializer())
//            addDeserializer(SecureHash.SHA256::class.java, JacksonSupport.SecureHashDeserializer())
//
//            // Public key types
//            addSerializer(PublicKey::class.java, JacksonSupport.PublicKeySerializer)
//            addDeserializer(PublicKey::class.java, JacksonSupport.PublicKeyDeserializer)
//
//            // For NodeInfo
//            // TODO this tunnels the Kryo representation as a Base58 encoded string. Replace when RPC supports this.
//            addSerializer(NodeInfo::class.java, JacksonSupport.NodeInfoSerializer)
//            addDeserializer(NodeInfo::class.java, JacksonSupport.NodeInfoDeserializer)
//
//            // For Amount
//            addSerializer(Amount::class.java, JacksonSupport.AmountSerializer)
//            addDeserializer(Amount::class.java, JacksonSupport.AmountDeserializer)
//
//            // For OpaqueBytes
//            addDeserializer(OpaqueBytes::class.java, JacksonSupport.OpaqueBytesDeserializer)
//            addSerializer(OpaqueBytes::class.java, JacksonSupport.OpaqueBytesSerializer)
//
//            // For X.500 distinguished names
//            addDeserializer(CordaX500Name::class.java, JacksonSupport.CordaX500NameDeserializer)
//            addSerializer(CordaX500Name::class.java, JacksonSupport.CordaX500NameSerializer)
//
//            // Mixins for transaction types to prevent some properties from being serialized
//            setMixInAnnotation(SignedTransaction::class.java, JacksonSupport.SignedTransactionMixin::class.java)
//            setMixInAnnotation(WireTransaction::class.java, JacksonSupport.WireTransactionMixin::class.java)
//        }
//    }

    abstract class VaultUpdateMixin {
        @JsonIgnore
        abstract fun isEmpty()
    }

    @Bean
    fun encoder(rpcObjectMapper: ObjectMapper): Jackson2JsonEncoder {
        val encoder = Jackson2JsonEncoder(rpcObjectMapper, MediaType.APPLICATION_JSON)
        encoder.streamingMediaTypes = listOf(
            MediaType.APPLICATION_STREAM_JSON
        )
        return encoder
    }

    @Bean
    fun decoder(rpcObjectMapper: ObjectMapper): Jackson2JsonDecoder {
        val decoder =
            Jackson2JsonDecoder(rpcObjectMapper, MediaType.APPLICATION_JSON, MediaType.APPLICATION_STREAM_JSON)
        return decoder
    }

//    @Bean
//    fun myModule(objectMapper: ObjectMapper, rpc: NodeRPCConnection) : Module {
////        val mapper = ObjectMapper()
//        val module = SimpleModule()
//        module.addDeserializer(ContractState::class.java, ContractStateDeserialiser(rpc))
//        objectMapper.registerModule(module)
//        objectMapper.registerModule(cordaModule())
//        objectMapper.registerModule(jsonComponentModule())
//        return module
//    }

    @Bean
    fun jsonComponentModule() = JsonComponentModule()

    @JsonComponent
    object AttachmentConstraintSerialiser : JsonSerializer<AttachmentConstraint>() {
        override fun serialize(value: AttachmentConstraint, generator: JsonGenerator, provider: SerializerProvider) {
            when (value) {
                is AlwaysAcceptAttachmentConstraint -> {
                    generator.writeStartObject()
                    generator.writeStringField("type", "AlwaysAcceptAttachmentConstraint")
                    generator.writeEndObject()
                }
                is HashAttachmentConstraint -> {
                    generator.writeStartObject()
                    generator.writeStringField("type", "HashAttachmentConstraint")
                    provider.defaultSerializeField("attachmentId", value, generator)
                    generator.writeEndObject()
                }
                is WhitelistedByZoneAttachmentConstraint -> {
                    generator.writeStartObject()
                    generator.writeStringField("type", "WhitelistedByZoneAttachmentConstraint")
                    generator.writeEndObject()
                }
                else -> {
                    generator.writeStartObject()
                    generator.writeStringField("type", "AutomaticHashConstraint")
                    generator.writeEndObject()
                }
            }
        }
//        override fun serialize(value: AttachmentConstraint, generator: JsonGenerator, provider: SerializerProvider) {
//           generator.writeString(value.toString())
//        }
    }

    @JsonComponent
    object AttachmentConstraintDeserialiser : JsonDeserializer<AttachmentConstraint>() {

        init {
            println("Contract state deserialiser created")
        }

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): AttachmentConstraint? {
            val tree = p.readValueAsTree<JsonNode>()
            val type = tree["type"]
            return when (type.asText()) {
                "AlwaysAcceptAttachmentConstraint" -> AlwaysAcceptAttachmentConstraint
                "HashAttachmentConstraint" -> HashAttachmentConstraint(
                    SecureHash.parse(tree["attachmentId"].asText())
                )
                "WhitelistedByZoneAttachmentConstraint" -> WhitelistedByZoneAttachmentConstraint
                else -> AutomaticHashConstraint
            }
        }
    }

    //    @JsonComponent
    object VaultUpdateDeserialiser : JsonDeserializer<Vault.Update<ContractState>>() {

        init {
            println("Contract state deserialiser created")
        }

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Vault.Update<ContractState>? {

            val tree = p.readValueAsTree<JsonNode>()
            tree["produced"]
//            val consumed = ctxt.config.findTypeDeserializer(
//                ctxt.typeFactory.constructType(
//                    ContractState::class.java
//                )
//            ).deserializeTypedFromObject(p, ctxt) as Set<StateAndRef<ContractState>>
//            val produced = ctxt.config.findTypeDeserializer(
//                ctxt.typeFactory.constructType(
//                    Set::class.java
//                )
//            ).deserializeTypedFromObject(p, ctxt) as Set<StateAndRef<ContractState>>
//            val flowId = ctxt.config.findTypeDeserializer(
//                ctxt.typeFactory.constructType(
//                    UUID::class.java
//                )
//            ).deserializeTypedFromObject(p, ctxt) as UUID
            val consumed = ctxt.config.findTypeDeserializer(
                ctxt.typeFactory.constructType(
                    ContractState::class.java
                )
            ).deserializeTypedFromObject(p, ctxt) as Set<StateAndRef<ContractState>>
            val produced = ctxt.config.findTypeDeserializer(
                ctxt.typeFactory.constructType(
                    Set::class.java
                )
            ).deserializeTypedFromObject(p, ctxt) as Set<StateAndRef<ContractState>>
            val flowId = ctxt.config.findTypeDeserializer(
                ctxt.typeFactory.constructType(
                    UUID::class.java
                )
            ).deserializeTypedFromObject(p, ctxt) as UUID
            val updateType = Vault.UpdateType.valueOf(tree["updateType"].asText())
            return Vault.Update(consumed, produced, flowId, updateType)
        }
    }

//    @JsonComponent
//    object ContractStateSerialiser : JsonSerializer<ContractState>() {
//        override fun serialize(value: ContractState, generator: JsonGenerator, provider: SerializerProvider) {
//            if (value is MessageState) {
////                generator.writeObjectField("sender", value.sender)
////                generator.writeObjectField("recipient", value.recipient)
////                generator.writeStringField("contents", value.contents)
////                generator.writeObjectField("linearId", value.linearId)
////                generator.writeObjectField("participants", value.participants)
////                generator.writeTypeId("MessageState")
//                generator.writeString(value.toString())
//            }
////            generator.writeString(value.toBase58String())
//        }
//    }

//    @JsonComponent
//    object ContractStateDeserialiser : JsonDeserializer<ContractState>() {
//
//        init {
//            println("Contract state deserialiser created")
//        }
//
////                private val proxy = rpc.proxy
//        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ContractState? {
//            val tree = p.readValueAsTree<JsonNode>()
//            val sender = tree["sender"]
//            return MessageState(
//                proxy.partiesFromName("PartyA", true).single(),
//                proxy.partiesFromName("PartyB", true).single(),
//                "message", UniqueIdentifier()
//            )
////            return null
//        }
//    }

    @JsonComponent
    class ContractStateDeserialiser(rpc: NodeRPCConnection) : JsonDeserializer<ContractState>() {

        init {
            println("Contract state deserialiser created")
        }

        private val proxy = rpc.proxy
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ContractState {
//            val tree = p.readValueAsTree<JsonNode>()
//            val sender = tree["sender"]
//            val recipient = tree["recipient"]
//            val contents = tree["contents"]
            return ctxt.readValue(p, MessageState::class.java)
//            if(!sender.isNull && !recipient.isNull && !contents.isNull) {
//                val mapper = p.codec as JacksonSupport.PartyObjectMapper
//                return MessageState(
//                    proxy.partiesFromName(sender.asText(), true).single(),
//                    proxy.partiesFromName(recipient.asText(), true).single(),
//                    contents.asText(), UniqueIdentifier()
//                )
//            }
//            throw JsonMappingException(p, "ContractState could not be deserialised")
        }
    }

//    @JsonComponent
//    class MessageContractStateDeserialiser(rpc: NodeRPCConnection) : JsonDeserializer<MessageState>() {
//
//        init {
//            println("Message state deserialiser created")
//        }
//
//        private val proxy = rpc.proxy
//        override fun deserialize(p: JsonParser, ctxt: DeserializationContext?): MessageState {
////            val tree = p.readValueAsTree<JsonNode>()
////            val sender = tree["sender"]
//            return MessageState(
//                proxy.partiesFromName("PartyA", true).single(),
//                proxy.partiesFromName("PartyB", true).single(),
//                "message", UniqueIdentifier()
//            )
//        }
//    }

    //    @Bean
//    fun jackson2ObjectMapperBuilderCustomizer(): Jackson2ObjectMapperBuilderCustomizer {
//        return Jackson2ObjectMapperBuilderCustomizer {
//            it.featuresToEnable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
//                .mixIn(ContractState::class.java, MessageState::class.java)
//        }
//    }
//
//    @Bean
//    fun jackson2JsonEncoder(mapper: ObjectMapper): Jackson2JsonEncoder {
//        return Jackson2JsonEncoder(mapper)
//    }
//
//    @Bean
//    fun jackson2JsonDecoder(mapper: ObjectMapper): Jackson2JsonDecoder {
//        return Jackson2JsonDecoder(mapper)
//    }
//
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
    lateinit var rpcObjectMapper: ObjectMapper
//    lateinit var objectMapper: ObjectMapper

    override fun run(vararg args: String?) {
        val client = MessageClient(rpcObjectMapper)
        client.doStuff()
    }
}

fun main(args: Array<String>) {
    SpringApplication.run(Starter::class.java)
}

//@JsonComponent
//class PublicKeySerializer : JsonSerializer<PublicKey>() {
//    override fun serialize(value: PublicKey, generator: JsonGenerator, provider: SerializerProvider) {
//        generator.writeString(value.toBase58String())
//    }
//}
