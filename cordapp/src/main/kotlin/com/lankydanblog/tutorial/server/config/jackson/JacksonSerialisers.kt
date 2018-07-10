package com.lankydanblog.tutorial.server.config.jackson

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import net.corda.core.contracts.AlwaysAcceptAttachmentConstraint
import net.corda.core.contracts.AttachmentConstraint
import net.corda.core.contracts.HashAttachmentConstraint
import net.corda.core.contracts.WhitelistedByZoneAttachmentConstraint
import org.springframework.boot.jackson.JsonComponent

@JsonComponent
class AttachmentConstraintSerialiser : JsonSerializer<AttachmentConstraint>() {
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
}