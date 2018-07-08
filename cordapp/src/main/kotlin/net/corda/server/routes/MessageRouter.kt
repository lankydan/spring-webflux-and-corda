package net.corda.server.routes

import net.corda.server.handlers.MessageHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.router

@Configuration
class MessageRouter {
    @Bean
    fun routes(handler: MessageHandler) = router {
        ("/messages").nest {
            (accept(MediaType.TEXT_EVENT_STREAM) and contentType(MediaType.APPLICATION_JSON)).nest {
                POST("/", handler::post)
            }
            accept(MediaType.APPLICATION_STREAM_JSON).nest {
                GET("/updates", handler::updates)
            }
        }
    }
}