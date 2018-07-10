package com.lankydanblog.tutorial.server.config.jackson

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import com.lankydanblog.tutorial.states.MessageState
import org.springframework.boot.jackson.JsonComponent

@JsonComponent
class ContractStateDeserialiser : JsonDeserializer<ContractState>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ContractState {
        return ctxt.readValue(p, MessageState::class.java)
    }
}

@JsonComponent
class AttachmentConstraintDeserialiser : JsonDeserializer<AttachmentConstraint>() {
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