package net.corda.server.dto

data class Message(val recipient: String, val contents: String) {
}