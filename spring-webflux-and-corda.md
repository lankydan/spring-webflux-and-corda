It's been a while since my last post but I'm finally back! Since I am still on my project, I will be writing about using Corda again. This time, rather than focusing on Corda, we'll look at using Spring with Corda. More specifically Spring WebFlux. Why do this? One, because we can. Two, because it allows us to stream events coming out of the Corda node. This provides us with the possibility to track the progress or flows or retrieve updates to the vault and send them to any clients registered to the relevant endpoints. Using WebFlux with Corda did introduce a few problems, some originating from Corda and some from Spring, although the Spring issues were to do with me expecting the Spring Boot + WebFlux combo to auto-configure more for me.

In this post, I'm going to assume you have some experience with Corda but if you do need some extra information on the subject I recommend reading through my previous posts: [What is Corda](URL) and [Developing with Corda](URL). Furthermore, I also suggest taking a look at [Doing stuff with Spring WebFlux](https://lankydanblog.com/2018/03/15/doing-stuff-with-spring-webflux/) as an introduction to WebFlux.

The `3.1` Open Source version of Corda will be used for the contents of this tutorial.

We will also be implementing everything in Kotlin but the contents of this post can be easily implemented in Java as well.

## Introduction to the example application

We will be modelling a really simple application that doesn't really provide much use and is just something I botched together for the sake of this post. The application will consist of one party sending a message (represented by the `MessageState`) to another party. To do this the `SendMessageFlow` will run and once it does, both parties will have a copy of the message and that's it. Short and simple but should provide us with enough to demonstrate how WebFlux can work with Corda.

## Structure

Normally I start with looking at the required dependencies but since I have split the code into separate modules I think it would be best to first view the structure of the little example application that we will be implementing in this post.
```
.
+-- app
|   +-- <spring code>
|   +-- build.gradle
+-- cordapp
|   +-- <corda flow code>
|   +-- build.gradle
+-- contracts-and-states
|   +-- <contract and states code>
|   +-- build.gradle
+-- build.gradle
```
That's a quick view of the structure of the application. `app` will contain all of the Spring code and will delegate to the Corda node via RPC. The `cordapp` module is where all the flow logic lives and `contracts-and-states` does what the name suggests and contains the contract and state code. Both the `cordapp` and `contracts-and-states` modules will be packaged up into Cordapp Jars later on and dumped into the Corda node.

Each of these modules contains a `build.gradle` file containing its relevant build information and dependencies. Since this post is not directly focusing on writing Corda code, we will not go on and look through every module and their build files in detail and instead will only brush over the code to provide enough information to allow us to look at the Spring implementation.

## Dependencies for Spring module

Below is the `build.gradle` file of the `app` module (containing the Spring code):
```groovy
buildscript {
    ext.spring_boot_version = '2.0.3.RELEASE'

    repositories {
        mavenCentral()
    }

    dependencies {
        classpath "org.jetbrains.kotlin:kotlin-allopen:$kotlin_version"
    }
}

// needed to resolve dependency error
// Constructor threw exception; nested exception is java.lang.NoSuchMethodError: io.netty.util.AsciiString.cached(Ljava/lang/String;)Lio/netty/util/AsciiString;
configurations.all {
    resolutionStrategy {
        force 'io.netty:netty-all:4.1.25.Final'
    }
}

repositories {
    mavenLocal()
    jcenter()
    mavenCentral()
    maven { url 'https://ci-artifactory.corda.r3cev.com/artifactory/corda-releases' }
}

apply plugin: 'kotlin'
apply plugin: 'net.corda.plugins.cordapp'
apply plugin: "kotlin-spring"

dependencies {
    compile "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version"
    testCompile "org.jetbrains.kotlin:kotlin-test:$kotlin_version"
    testCompile "junit:junit:$junit_version"

    cordaCompile "$corda_release_group:corda-core:$corda_release_version"
    cordaCompile "$corda_release_group:corda-jackson:$corda_release_version"
    cordaCompile "$corda_release_group:corda-rpc:$corda_release_version"
    cordaRuntime "$corda_release_group:corda:$corda_release_version"

    compile group: 'org.springframework.boot', name: 'spring-boot-starter-webflux', version: "$spring_boot_version"

    // added since rxjava 1.xx does not implement publisher so cant be used by Spring WebFlux
    compile group: 'io.reactivex', name: 'rxjava-reactive-streams', version: '1.2.1'

    cordapp project(":cordapp-contracts-states")
    cordapp project(":cordapp")
}

// tasks to run web servers

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).all {
    kotlinOptions {
        languageVersion = "1.1"
        apiVersion = "1.1"
        jvmTarget = "1.8"
        javaParameters = true   // Useful for reflection.
    }
}
```
I'm no expert in using Gradle so there is probably some things in this snippet that code be done better but it does what it needs to.

So, there are a few things I want to highlight. Spring Boot `2.0.3.RELEASE` is being used and to go along with this the `kotlin-spring` plugin is used to add `open` to all Kotlin classes marked with certain Spring annotations. This is needed for quite a lot of situations since Spring requires some classes to be non-final which isn't a problem by default in Java but is problematic with Kotlin since all classes are final by default. More information on the plugin can be found on [kotlinlang.org](https://kotlinlang.org/docs/reference/compiler-plugins.html#spring-support).

`spring-boot-starter-webflux` pulls in the WebFlux dependencies along with general Spring web server code that we need to get everything up and running.

`rxjava-reactive-streams`, this is an interesting one that we will see come into play later on. Since Corda uses RxJava `1.x.x` rather than the newer RxJava2, it's `Observable`s do not implement the Java 8 `Publisher` interface that is used by Spring WebFlux to return reactive streams. This dependency allows these older `Observable`s to be converted into a `Publisher` so it is compatible with what WebFlux needs. We will touch on this again later when we look at the code to do this conversion.

Finally, the `netty-all` version needed to be forced to `4.1.25.Final` to resolve a dependency issue.

## Routing functions

WebFlux introduces a functional approach to routing requests to the functions that handle them. More information on this can be found in [Doing stuff with Spring WebFlux](https://lankydanblog.com/2018/03/15/doing-stuff-with-spring-webflux/). I don't want to jump deep into how WebFlux is working but we will take a quick look at defining the routing functions. The main reason for this is due to using Kotlin instead of Java as it provides a different way to define the functions by instead using a DSL.

Below is the code to define the routing for this tutorial:
```kotlin
@Configuration
class MessageRouter {
    @Bean
    fun routes(handler: MessageHandler): RouterFunction<ServerResponse> = router {
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
```
The `routes` bean takes in the `MessageHandler` bean (which we will look at later) and maps two URI's to functions found in `MessageHandler`. The DSL allows for a slightly shorter version when compared to the Java implementation. There are a few parts to focus on in this snippet.

`("/messages")` defines the base request path of the two routing functions. The DSL allows the functions themselves to be nested from this path and helps with the conveying the structure of the routes.

One function accepts `TEXT_EVENT_STREAM` (`text/event-stream`) in the response returned from sending the request while also specifying `APPLICATION_JSON` (`application/stream+json`) as the contents of the body. Since we have defined the `Content-Type` in most cases we can assume that we will be sending a `POST` request (which we are). `POST` is further nested from the previous configuration and further adds which `MessageHandler` function will accept requests along with the specific path to it.

The second function receives updates from the Corda node. To do this it returns `APPLICATION_STREAM_JSON` and expects a `GET` request to be sent to `/messages/updates`.

## Handler functions

In this section, we will look at the `MessageHandler` that was mentioned quite a few times in the previous section. This class contains all the functions that perform the actual business logic, the routing was just a means to reach this point.

Any my previous post, [Doing stuff with Spring WebFlux](https://lankydanblog.com/2018/03/15/doing-stuff-with-spring-webflux/) will explain the more WebFlux specific parts of these examples in more depth than I will in this post.

Below is the handler code:
```kotlin
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
```
First, we should highlight the `NodeRPCConnection` class and it's property `proxy` of type `CordaRPCOps`. `NodeRPCConnection` was stolen from a [example Corda and Spring](https://github.com/joeldudleyr3/spring-observable-stream) application. Long story short `NodeRPCConnection` creates the RPC connection to the Corda node and `proxy` returns a `CordaRPCOps` which contains all the RPC operations that are available to use. This is the way that Spring will interact with the Corda node.

Let take a closer look at the `updates` function:
```kotlin
fun updates(request: ServerRequest): Mono<ServerResponse> {
    return ok().contentType(APPLICATION_STREAM_JSON)
        .body(trackNewMessages(), ParameterizedTypeReference.forType(Vault.Update::class.java))
}

private fun trackNewMessages(): Publisher<Vault.Update<MessageState>> =
    toPublisher(proxy.vaultTrackBy<MessageState>().updates)
```
This function returns new messages as they are saved to the vault. This sort of endpoint would be nice if you had an application that monitored updates coming from your Corda node.

The Corda related code in this snippet is all contained within the `trackNewMessages` function. It uses `CordaRPCOps`'s `vaultTrackBy` to access the vault service via RPC and start tracking updates to any `MessageStates`. Since we have not passed any arguments to the function it will be tracking `UNCONSUMED` states only. `vaultTrackBy` returns a `DataFeed` object that can be used to either retrieve a snapshot of the vault via the `snapshot` property or by accessing the `updates` property an `Observable` will be returned allowing it's update events to be subscribed to. This RxJava `Observable` is what we will use to stream data back to the caller.

This is the first instance where we need to use the `rxjava-reactive-streams` that I mentioned earlier. The `toPublisher` method takes in the `Observable` and converts it into a `Publisher` since WebFlux requires Java 8 compatible reactive streaming libraries which must implement `Publisher`. For example, Spring tends to make use of [Reactor](https://projectreactor.io/) which provides the `Mono` and `Flux` classes.

Once the `Publisher` has been created it simply needs to be fed into a `ServerResponse`. As everything has gone well at this point we will return a `200` response via the `ok` method and set the `Content-Type` to `APPLICATION_STREAM_JSON`. Finally, the `Publisher` from `trackNewMessages` is put into the body of the response ready to be subscribed to by the requesting client.

The functionality to stream updates from the node to a client is complete what about actually saving a new message? Furthermore, is there any information that we can pass back to the sender about the executing flow? So let's answer those two questions. Yes, we can save a new message using WebFlux and yes, there is information about the progress of the flow can be returned.

Below is the code for the `post` function that saves a new message to both the sender's node and to the recipient while streaming the flow's progress:
```kotlin
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
```
`proxy.startTrackedFlow` starts a flow whose progress can be tracked by any `ProgressTracker`s added to the flow. The `startTrackedFlow` defined in this class simply delegates to the aforementioned function and returns its `progress` property; an `Observable<String>` whose events consist of the `ProgressTracker`'s progress.

The `MessageState` that's passed to the flow is created from the `Message` object passed in from the request. This is to allow easier input of the message data to the endpoint since it contains less information than the `MessageState` itself. `parse` converts the string X500 name passed in the `Message` into a `CordaX500Name` and then into a `Party` within the network, assuming one exists.

This is then packaged into a response via the `created` method. The `Content-Type` is specified to tell the client that it contains `text/event-stream` and returned. The path to the message is defined via the `UUID` that was created before the flow was executed. This could, for example, be used to retrieve a specific message but you'll need to implement that yourself since I was too lazy to do that for this post.

## Creating a client

I think now that the endpoints are set up we should create a client that can send requests and consume the streams sent back to it. Later on, we will briefly look at the flow code to get a fuller understanding of whats going on.

To send requests to a reactive back-end Spring WebFlux provides the `WebClient` class provides a way to both send and handle the returned stream. The `MessageClient` below does just that:
```kotlin
@Component
class MessageClient(
    @Value("\${server.host}") private val host: String,
    @Value("\${server.port}") private val port: Int,
    private val decoder: Jackson2JsonDecoder
) {

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
```
The `MessageClient` wraps a `WebClient` that is used by the class to send requests to the address specified in the `WebClient`'s builder. There is some extra configuration but going on in this class around serialisation but I want to brush over that for now as there is a section further down covering that topic.

As before [Doing stuff with Spring WebFlux](https://lankydanblog.com/2018/03/15/doing-stuff-with-spring-webflux/) provides in-depth explanations into the WebFlux specific methods.

So let's look at each request individually, first up the `POST` request to the `/messages` endpoint:
```kotlin
val message = Message("O=PartyB,L=London,C=GB", "hello there")
client
    .post()
    .uri("/messages")
    .body(Mono.just(message), Message::class.java)
    .accept(TEXT_EVENT_STREAM)
    .exchange()
    .flatMapMany { it.bodyToFlux(String::class.java) }
    .subscribe { println("STEP: $it") }
```
The `post` method creates a builder that allows the contents of the request to be specified. This should match up to an endpoint that we defined earlier. Once the request has been built, call the `exchange` method to send it to the server. The body of the response is then mapped to a `Flux<String>` allowing it to be subscribed to. That is the essence of using Reactive Streams. Once the response has been subscribed to, it is up to the client to perform whatever processing they wish to do on each value sent it. In this scenario, it simply just prints out the current step of the `ProgressTracker`.

If we sent a request via this piece of code we would receive the following:
```
STEP: Verifying
STEP: Signing
STEP: Sending to Counterparty
STEP: Collecting signatures from counterparties.
STEP: Verifying collected signatures.
STEP: Done
STEP: Finalising
STEP: Requesting signature by notary service
STEP: Broadcasting transaction to participants
STEP: Done
STEP: Done
```
These are the steps that were defined in the `SendMessageFlow`'s `ProgressTracker`, yes I know I haven't shown you that code yet but just trust me on this. Not much else to this one really. As you can see "STEP" has been attached to the string values being streamed back to the client.

Now onto the `GET` request to the `/messages/update` endpoint:
```kotlin
client
    .get()
    .uri("/messages/updates")
    .accept(APPLICATION_STREAM_JSON)
    .exchange()
    .flatMapMany { it.bodyToFlux(Vault.Update::class.java) }
    .subscribe { println("UPDATE: $it") }
```
Again there isn't really much to show at this point. But, behind the scenes there is actually quite a bit of work required to get this to work. All the issues that I faced to get this call to work all revolved around serialisation and deserialisation. We will get into that in a later section.

The response to this request is as follows:
```
UPDATE: 0 consumed, 1 produced

Consumed:

Produced:
56781DF3CEBF2CDAFACE1C5BF04D4962B5483FBCD2C2E428352AD82BC951C686(0): TransactionState(data=MessageState(sender=O=PartyA, L=London, C=GB, recipient=O=PartyB, L=London, C=GB, contents=hello there, linearId=1afc6144-32b1-4265-a06e-73b6bb81aef3_b0fa8491-c9b9-418c-ba6e-8b7840faaf30, participants=[O=PartyA, L=London, C=GB, O=PartyB, L=London, C=GB]), contract=com.lankydanblog.tutorial.contracts.MessageContract, notary=O=Notary, L=London, C=GB, encumbrance=null, constraint=net.corda.core.contracts.WhitelistedByZoneAttachmentConstraint@4a1febb5)
```
The nice thing about this endpoint is that it now maintains a connection to the node which will keep sending any related updates back to this client. The above request was the update for the original `POST` message but if another was sent it would output a new update on the client. This is what makes this sort of endpoint ideal for triggering a new event or simply displaying up to date data on a front-end separate from the Corda node itself.

## Serialisation and Deserialisation

Before we move on to look at the flow that is actually executing on the node. I wanted to focus on setting up serialisation and deserialisation correctly so that the data that is retrieved be the `/messages/updates` endpoint is able to serialise its data correct to pass to the client, who also need to be able to deserialise the response data.

Normally Spring does a lot of this for you, and it still does, but it seems with WebFlux there are some extra steps required to get it set up properly. Disclaimer, this is from my experience and if you know of better ways to do this I would be interested to hear from you.

### Corda JacksonSupport

Spring tends to use Jackson by default and very handily Corda provides a lot of Jackson setup itself. The `JacksonSupport.cordaModule` provides some serialisation and deserialisation for classes such as `Party` and `SecureHash`. If you have some basic situations where you need to serialise or deserialise a Corda class this will probably suit your needs. In Spring you could create a bean that will be added to the default `ObjectMapper`.
```kotlin
@Bean
fun cordaModule() = JacksonSupport.cordaModule
```
But, this route has a few caveats. Some classes cannot be deserialised since the module relies on the `ObjectMapper` having access to node information, for example via the RPC client `CordaRPCOps`. Without this deserialising a `Party`, `AbstractParty` or `AnonymousParty` will not only fail, from my experience, it actually hangs and required me stepping into the code to check that this was indeed where the error was occurring.

That being said, the sky isn't falling down yet. Below is the exception that disappears into the void, which was received via the `MessageClient` posting retrieving updates from the `/messages/updates` endpoint (for the rest of this section the same endpoint will be used):
```
com.fasterxml.jackson.databind.ObjectMapper cannot be cast to net.corda.client.jackson.JacksonSupport$PartyObjectMapper
```
From this, we can determine that it is simply that our `ObjectMapper` is of the wrong type and actually needs to be the subtype `PartyObjectMapper`. Taking this a bit further we can see that this mapper is found in the `JacksonSupport` class as well. Now all there is left to do is create this mapper and use that instead of the default `ObjectMapper`.

So lets see how to do that:
```kotlin
@Bean
fun rpcObjectMapper(rpc: NodeRPCConnection): ObjectMapper {
    return JacksonSupport.createDefaultMapper(rpc.proxy)
}
```
This will create a `RpcObjectMapper` which implements `PartyObjectMapper` and makes use of RPC to retrieve node information to make it possible to deserialise the various party classes. Inside the `createDefaultMapper,` the `cordaModule` from before is added and thanks to Spring, this object mapper will now be used by default for most (note the most for later) instances where serialisation or deserialisation is needed.

### Some more serialisation and deserialisation configuration

Now... I'm actually in quite a weird position. I wanted to go through all the other steps to get the endpoint working but no matter what I do, I cannot seem to recreate all the errors I used to get before getting it to work. I don't know what to say... But somewhere my exceptions are being swallowed and stopping me from seeing what is going on. Anyway, we must continue on. Thankfully I know why I added the rest of the code but I cannot provide you with the exception that each changed fixed...

Soooo, let's look at the end product of the `rpcObjectMapper` that we started working on earlier:
```kotlin
@Bean
fun rpcObjectMapper(rpc: NodeRPCConnection): ObjectMapper {
    val mapper = JacksonSupport.createDefaultMapper(rpc.proxy)
    mapper.registerModule(jsonComponentModule())
    mapper.registerModule(MixinModule())
    return mapper
}

@Bean
fun jsonComponentModule() = JsonComponentModule()

class MixinModule : SimpleModule() {
    init {
        setMixInAnnotation(Vault.Update::class.java, VaultUpdateMixin::class.java)
    }
}

abstract class VaultUpdateMixin {
    @JsonIgnore
    abstract fun isEmpty()
}
```
There are a few additions here. The `JsonComponentModule` is added as a bean so that it picks up the `@JsonSerializer` and `@JsonDeserializer` custom components that are defined (in other classes). It seems that even if it is added to the mapper as a module, it still requires the bean itself to be created if it is going to find and register the custom JSON components.

Next is the `MixinModule`. This is needed due to `Vault.Update` having a method called `isEmpty`, which doesn't play nicely with Jackson who gets confused and thinks that `isEmpty` matches to a boolean field called `empty` on the property and tries to pass it's value when deserialising the JSON back into an object. The Mixin allows us to add Jackson annotations onto the class without actually having access to the class itself, which we obviously don't control since this an object from within Corda's codebase. The other option is that this is added to the `cordaModule` we discussed earlier but that is a different conversation.

The `MixinModule` itself is simply a class whose constructor adds the `VaultUpdateMixin` to itself. Then this module is added back into the mapper like any other module. Job done. 

The Jackson annotation that was added to the Mixin was `@JsonIgnore` which speaks for itself really when serialising or deserialising the `isEmpty` function will be ignored.

### Custom Serialisers and Deserialisers

To serialise the `Vault.Update` only the `AttachmentConstraint` interface needs it's own custom serialiser:
```kotlin
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
```
Not much to talk about since only the `HashAttachmentConstraint` actually has any fields. This matches up to the deserialiser later on which reads the `type` JSON field to determine which object is created.

The last two classes that need custom deserialisers are `ContractState` and `AttachmentContract` (matching the serialiser from before):
```kotlin
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
```
The `ContractStateDeserialiser` is a pretty lazy implementation since I only have one state that is being used in this tutorial. The `AttachmentConstraintDeserialiser` uses the `type` field defined in the serialiser to determine which implementation of `AttachmentConstraint` it should be converted into.

### WebFlux specific configuration

This subsection goes over the extra configuration that is required due to using WebFlux. You have already seen some of the configuration within the `MessageClient` but there is a bit extra that needs to be done. We will go over just that here.
```kotlin
@Bean
fun decoder(rpcObjectMapper: ObjectMapper): Jackson2JsonDecoder =
    Jackson2JsonDecoder(rpcObjectMapper, MediaType.APPLICATION_JSON, MediaType.APPLICATION_STREAM_JSON)
```
This bean is needed to allow the client to deserialise `application/stream+json`. It is then passed into the `MessageClient`:
```kotlin
@Component
class MessageClient(
    @Value("\${server.host}") private val host: String,
    @Value("\${server.port}") private val port: Int,
    private val decoder: Jackson2JsonDecoder
) {

    private val strategies = ExchangeStrategies
        .builder()
        .codecs { clientCodecConfigurer ->
            clientCodecConfigurer.defaultCodecs().jackson2JsonDecoder(decoder)
        }.build()

    private val client = WebClient.builder()
        .exchangeStrategies(strategies)
        .baseUrl("http://$host:$port")
        .build()

    // do stuff
}
```
To make use of the `Jackson2JsonDecoder` that was defined in the configuration the `ExchangeStrategies` of the `WebClient` must be defined. Unfortunately, the `ExchangeStrategies` are not written to pick up the `Jackson2JsonDecoder` that we already created. I was hoping that this sort of configuration would work by default but oh well. To add the `ExchangeStrategies` the `WebClient` builder must be used and passed in. Once that is done, we are finally there, all serialisation to package up the response and the deserialisation to use it from the client is complete.

That sums up all the Spring related code that I wish to go over in this post. 

## A quick look at the Flow code

Before concluding, I will briefly show the flow that I quickly put together for the purpose of this tutorial:
```kotlin
@InitiatingFlow
@StartableByRPC
class SendMessageFlow(private val message: MessageState) : FlowLogic<SignedTransaction>() {

    private companion object {
        object CREATING : ProgressTracker.Step("Creating")
        object VERIFYING : ProgressTracker.Step("Verifying")
        object SIGNING : ProgressTracker.Step("Signing")
        object COUNTERPARTY : ProgressTracker.Step("Sending to Counterparty")
        object FINALISING : ProgressTracker.Step("Finalising")

        private fun tracker() = ProgressTracker(
            CREATING,
            VERIFYING,
            SIGNING,
            COUNTERPARTY,
            FINALISING
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val stx = collectSignature(verifyAndSign(transaction()))
        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(stx))
    }

    @Suspendable
    private fun collectSignature(
        transaction: SignedTransaction
    ): SignedTransaction {
        val session = initiateFlow(message.recipient)
        progressTracker.currentStep = COUNTERPARTY
        return subFlow(CollectSignaturesFlow(transaction, listOf(session)))
    }

    private fun verifyAndSign(transaction: TransactionBuilder): SignedTransaction {
        progressTracker.currentStep = VERIFYING
        transaction.verify(serviceHub)
        progressTracker.currentStep = SIGNING
        return serviceHub.signInitialTransaction(transaction)
    }

    private fun transaction() =
        TransactionBuilder(notary()).apply {
            progressTracker.currentStep = CREATING
            addOutputState(message, MessageContract.CONTRACT_ID)
            addCommand(
                Command(
                    MessageContract.Commands.Send(),
                    message.participants.map(Party::owningKey)
                )
            )
        }

    private fun notary() = serviceHub.networkMapCache.notaryIdentities.first()
}

@InitiatedBy(SendMessageFlow::class)
class SendMessageResponder(val session: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(object : SignTransactionFlow(session) {
            override fun checkTransaction(stx: SignedTransaction) {}
        })
    }
}
```
It's a pretty simple flow with the addition of a `ProgressTracker` that was used in the `/messages` request to follow the current state of the flow. Long story short, this flow takes the `MessageState` passed into it and sends it to the counterparty. While moving through the flow the `ProgressTracker` is updated to the relevant step. Further documentation on using a `ProgressTracker` can be found in the [Corda docs](https://docs.corda.net/flow-state-machines.html#progress-tracking).

## Closing time

That was honestly a lot longer than I thought it would be and has taken me much longer to write than I hoped. 

In conclusion, Spring WebFlux provides the capability to use reactive streams to handle response events whenever they arrive. When used with Corda, the progress of a flow can be tracked on a persistent stream of vault updates can be maintained and acted upon as they arrive. To fully make use of WebFlux with Corda, we also had to look into ensuring that objects were serialised correctly by the server and then deserialised by the client so they can be made use of. Lucky Corda does provide some of this but one or two classes or features are missing and we need to make sure that the object mapper they provide is used. Unfortunately, WebFlux requires a bit more configuration than I'm normally accustomed to when using Spring modules but nothing that can't be fixed.

The rest of the code for this post can be found on my [GitHub](https://github.com/lankydan/spring-webflux-and-corda)