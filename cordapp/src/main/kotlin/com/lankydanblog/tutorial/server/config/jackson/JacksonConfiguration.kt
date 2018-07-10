package com.lankydanblog.tutorial.server.config.jackson

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.lankydanblog.tutorial.server.NodeRPCConnection
import net.corda.client.jackson.JacksonSupport
import net.corda.core.node.services.Vault
import org.springframework.boot.jackson.JsonComponentModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder

@Configuration
class JacksonConfiguration {

    @Bean
    fun cordaModule() = JacksonSupport.cordaModule

    @Bean
    fun jsonComponentModule() = JsonComponentModule()

    @Bean
    fun rpcObjectMapper(rpc: NodeRPCConnection): ObjectMapper {
        val mapper = JacksonSupport.createDefaultMapper(rpc.proxy)
        mapper.registerModule(jsonComponentModule())
        mapper.registerModule(MixinModule())
        return mapper
    }

    class MixinModule : SimpleModule() {
        init {
            setMixInAnnotation(Vault.Update::class.java, VaultUpdateMixin::class.java)
        }
    }

    abstract class VaultUpdateMixin {
        @JsonIgnore
        abstract fun isEmpty()
    }

    @Bean
    fun encoder(rpcObjectMapper: ObjectMapper): Jackson2JsonEncoder {
        val encoder = Jackson2JsonEncoder(rpcObjectMapper, MediaType.APPLICATION_JSON)
        encoder.streamingMediaTypes = listOf(MediaType.APPLICATION_STREAM_JSON)
        return encoder
    }

    @Bean
    fun decoder(rpcObjectMapper: ObjectMapper): Jackson2JsonDecoder =
        Jackson2JsonDecoder(rpcObjectMapper, MediaType.APPLICATION_JSON, MediaType.APPLICATION_STREAM_JSON)
}