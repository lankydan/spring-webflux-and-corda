It's been a while since my last post but I'm finally back! Since I am still on my project, I will be writing about using Corda again. This time, rather than focusing on Corda, we'll look at using Spring with Corda. More specifically, Spring WebFlux. Why do this? One, because we can. Two, because it allows us to stream events coming out of the Corda node. This provides us with the possibility to track the progress of flows or retrieve updates to the vault and send them to any clients registered to the relevant endpoints. Using WebFlux with Corda did introduce a few problems. Some originating from Corda and some from Spring. Although, the Spring issues were to do with me expecting the Spring Boot + WebFlux combo to do more by default for me.

In this post, I'm going to assume you have some experience with Corda but if you do need some extra information on the subject I recommend reading through my previous posts: [What is Corda](https://lankydanblog.com/2018/06/05/what-is-corda/) and [Developing with Corda](https://lankydanblog.com/2018/06/05/developing-with-corda/). Furthermore, I also suggest taking a look at [Doing stuff with Spring WebFlux](https://lankydanblog.com/2018/03/15/doing-stuff-with-spring-webflux/) as an introduction to WebFlux.

The <code>3.2</code> Open Source version of Corda will be used for the contents of this tutorial. I actually started writing this post based on <code>3.1</code> but the newer version was released during this time. Due to this there are a few comments based on moving between these versions.

We will also be implementing everything in Kotlin but the contents of this post can be implemented in Java as well.

## Introduction to the example application

We will be modelling a really simple application that doesn't provide much use and is something I botched together for the sake of this post. The application will consist of one party sending a message (represented by the <code>MessageState</code>) to another party. To do this the <code>SendMessageFlow</code> will run and once it does, both parties will have a copy of the message and that's it. Short and simple but should provide us with enough to demonstrate how WebFlux can work with Corda.

## Structure

Normally I start by looking at the dependencies. Although, since I have split the code into separate modules, it would be best to first view the structure of the little example application.
<pre>
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
</pre>
That's a quick view of the structure of the application. <code>app</code> will contain all the Spring code and will delegate to the Corda node via RPC. The <code>cordapp</code> module houses the flow logic and <code>contracts-and-states</code> does what the name suggests and contains the contract and state code. Both the <code>cordapp</code> and <code>contracts-and-states</code> modules are packaged up into Cordapp Jars and dumped into the Corda node.

Each of these modules contains a <code>build.gradle</code> file containing its relevant build information and dependencies. Since this post is not directly focusing on writing Corda code, we will not go on and look through every module and their build files in detail. Instead, we will only brush over the flow code at the end of the post so we can focus on the Spring implementation.

## Dependencies for Spring module

Below is the <code>build.gradle</code> file of the <code>app</code> module (containing the Spring code):

[gist https://gist.github.com/lankydan/007a8a2956fd1c714b4017c5e5675c8a /]

I'm not an expert in Gradle, so there are probably some things in this snippet that could be done better, but it does what it needs to.

So, there are a few things I want to highlight. Spring Boot <code>2.0.3.RELEASE</code> is being used and to go along with this the <code>kotlin-spring</code> plugin is used to add <code>open</code> to all Kotlin classes marked with certain Spring annotations. This is needed for quite a lot of situations since Spring requires some classes to be non-final. This isn't a problem in Java but is problematic for Kotlin since all classes are final by default. More information on the plugin can be found at [kotlinlang.org](https://kotlinlang.org/docs/reference/compiler-plugins.html#spring-support).

<code>spring-boot-starter-webflux</code> pulls in the WebFlux dependencies along with general Spring web server code to get everything up and running.

<code>rxjava-reactive-streams</code>, this is an interesting one that we will see come into play later on. Since Corda uses RxJava <code>1.x.x</code> rather than the newer RxJava2, its <code>Observable</code>s do not implement the Java 8 <code>Publisher</code> interface that Spring WebFlux uses to return reactive streams. This dependency converts these older <code>Observable</code>s into <code>Publisher</code>s so they are compatible with WebFlux. We will touch on this again later when we look at the code to do this conversion.

Finally, the <code>netty-all</code> version is forced to <code>4.1.25.Final</code> to resolve a dependency issue.

## Routing functions

WebFlux introduces a functional approach for routing requests to the functions that handle them. More information on this can be found in [Doing stuff with Spring WebFlux](https://lankydanblog.com/2018/03/15/doing-stuff-with-spring-webflux/). I don't want to jump deep into how WebFlux is working but we will take a quick look at defining the routing functions. The main reason for this is due to using Kotlin instead of Java. Kotlin provides a different way to define the functions by using a DSL.

Below is the code to define the routing for this tutorial:

[gist https://gist.github.com/lankydan/578a020202f6562fd19b184b671de2d9 /]

The <code>routes</code> bean takes in the <code>MessageHandler</code> bean (which we will look at later) and maps two URI's to functions found in that <code>MessageHandler</code>. The DSL allows for a slightly shorter version compared to the Java implementation. There are a few parts to focus on in this snippet.

<code>("/messages")</code> defines the base request path of the two routing functions. The DSL allows the functions to nest themselves from this base path and helps with the conveying the structure of the routes.

One function accepts <code>TEXT_EVENT_STREAM</code> (<code>text/event-stream</code>) in the response returned from sending the request while also specifying <code>APPLICATION_JSON</code> (<code>application/stream+json</code>) as the contents of the body. Since we have defined the <code>Content-Type</code>, in most cases we can assume that we will be sending a <code>POST</code> request (which we are). <code>POST</code> is further nested from the previous configuration and adds another <code>MessageHandler</code> function to accept requests.

The second function receives updates from the Corda node. To do this it returns <code>APPLICATION_STREAM_JSON</code> and expects a <code>GET</code> request to be sent to <code>/messages/updates</code>.

## Handler functions

In this section, we will look at the <code>MessageHandler</code> that was mentioned a few times in the previous section. This class contains all the functions that perform the actual business logic. The routing was just a means to reach this point.

My previous post, [Doing stuff with Spring WebFlux](https://lankydanblog.com/2018/03/15/doing-stuff-with-spring-webflux/) will explain the more WebFlux specific parts of these examples in more depth than I will in this post.

Below is the handler code:

[gist https://gist.github.com/lankydan/7fe2c7a06ce5988c876b4fb3ca607e8f /]

First, we should highlight the <code>NodeRPCConnection</code> class and it's property <code>proxy</code> of type <code>CordaRPCOps</code>. I stole <code>NodeRPCConnection</code> from an [example Corda and Spring](https://github.com/joeldudleyr3/spring-observable-stream) application (written by a R3 employee). Long story short, <code>NodeRPCConnection</code> creates the RPC connection to the Corda node and <code>proxy</code> returns a <code>CordaRPCOps</code>. <code>CordaRPCOps</code> contains all the RPC operations that are available to use. This is the way that Spring will interact with the Corda node.

Let take a closer look at the <code>updates</code> function:

[gist https://gist.github.com/lankydan/b6ad5da98618d795398f9700c7b1b54b /]

This function returns new messages as they are saved to the vault. This sort of endpoint would be nice if you had an application that monitored updates coming from your Corda node.

The Corda related code in this snippet is all contained within the <code>trackNewMessages</code> function. It uses <code>CordaRPCOps</code>'s <code>vaultTrackBy</code> to access the vault service and starts tracking updates to any <code>MessageState</code>s. Since we have not passed any arguments to the function it will be tracking <code>UNCONSUMED</code> states only. <code>vaultTrackBy</code> returns a <code>DataFeed</code> object that can be used to either retrieve a snapshot of the vault via the <code>snapshot</code> property or by accessing the <code>updates</code> property an <code>Observable</code> will be returned allowing it's update events to be subscribed to. This RxJava <code>Observable</code> is what we will use to stream data back to the caller.

This is the first instance where we need to use the <code>rxjava-reactive-streams</code> that I mentioned earlier. The <code>toPublisher</code> method takes in an <code>Observable</code> and converts it into a <code>Publisher</code>. Remember, WebFlux requires Java 8 compatible reactive streaming libraries that must implement <code>Publisher</code>. For example, Spring tends to make use of [Reactor](https://projectreactor.io/) which provides the <code>Mono</code> and <code>Flux</code> classes.

After creating the <code>Publisher</code> it needs to be fed into a <code>ServerResponse</code>. As everything has gone well at this point we will return a <code>200</code> response via the <code>ok</code> method. The <code>Content-Type</code> is then set to <code>APPLICATION_STREAM_JSON</code> since it contains streaming data. Finally, the body of the response takes in the <code>Publisher</code> from <code>trackNewMessages</code>. The endpoint is now ready to be subscribed to by a requesting client.

The functionality to stream updates from the node to a client is now complete. What about actually saving a new message? Furthermore, is there any information that we can pass back to the sender about the executing flow? So let's answer those two questions. Yes, we can save a new message using WebFlux. And yes, a flow can return its current progress.

Below is the code for the <code>post</code> function that saves a new message to both the sender's and the recipient's nodes while streaming the flow's progress:

[gist https://gist.github.com/lankydan/0630d3151aa4088cf967552582fc65c5 /]

<code>proxy.startTrackedFlow</code> starts a flow whose progress can be tracked by any <code>ProgressTracker</code>s added to the flow. The <code>startTrackedFlow</code> defined in this class delegates to the aforementioned function and returns its <code>progress</code> property; an <code>Observable&lt;String&gt;</code> whose events consist of the <code>ProgressTracker</code>'s progress.

The <code>MessageState</code> that's passed into the flow is created from the <code>Message</code> object passed in from the request. This is to allow easier input of the message data to the endpoint since it contains less information than the <code>MessageState</code> itself. <code>parse</code> converts the string X500 name passed in the <code>Message</code> into a <code>CordaX500Name</code> and then into a <code>Party</code> within the network, assuming one exists.

This is then packaged into a response via the <code>created</code> method. The <code>Content-Type</code> is specified to tell the client that it contains <code>text/event-stream</code>. The path to the message uses the <code>UUID</code> that was created before the flow was executed. This could, for example, be used to retrieve a specific message but you'll need to implement that yourself since I am too lazy to do that for this post.

## Creating a client

Now that the endpoints are set up we should create a client that can send requests and consume the streams sent back to it. Later on, we will briefly look at the flow code to get a fuller understanding of whats going on.

To send requests to a reactive back-end, Spring WebFlux provides the <code>WebClient</code> class. After sending a request, the <code>WebClient</code> can react to each event sent in the response. The <code>MessageClient</code> below does just that:

[gist https://gist.github.com/lankydan/ec00bbe3fd347e1765b6c2f525b506fc /]

The <code>MessageClient</code> wraps and uses a <code>WebClient</code> to send requests to the address specified in the <code>WebClient</code>'s builder. There is some extra configuration going on in this class around deserialisation, but I want to brush over that for now as there is a section further down covering that topic.

As before [Doing stuff with Spring WebFlux](https://lankydanblog.com/2018/03/15/doing-stuff-with-spring-webflux/) provides in-depth explanations into the WebFlux specific methods.

So let's look at each request individually, first up the <code>POST</code> request to the <code>/messages</code> endpoint:

[gist https://gist.github.com/lankydan/8c73791c6686f19a5cd19968da1766dc /]

The <code>post</code> method creates a builder that specifies the contents of the request. This should match up to an endpoint that we defined earlier. Once the request has been built, call the <code>exchange</code> method to send it to the server. The body of the response is then mapped to a <code>Flux&lt;String&gt;</code> allowing it to be subscribed to. That is the essence of using Reactive Streams. Once subscribing to the response, it is up to the client to perform whatever processing they wish to do on each event. In this scenario, it simply prints out the current step of the <code>ProgressTracker</code>.

If we sent a request via this piece of code we would receive the following:
<pre>
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
</pre>
These are the steps that the <code>SendMessageFlow</code>'s <code>ProgressTracker</code> defines. Yes, I know I haven't shown you that code yet but just trust me on this. Not much else to this one really. As you can see, each string value returned from the stream attaches "STEP" to itself

Now onto the <code>GET</code> request to the <code>/messages/update</code> endpoint:

[gist https://gist.github.com/lankydan/33beec186082ceccbbc892462fa4aee7 /]

Again there isn't much to show at this point. But, behind the scenes there is actually quite a bit of work required to get this to work. All the issues that I faced to get this call to work all revolved around serialisation and deserialisation. We will get into that in the next section.

The response to this request is as follows:
<pre>
UPDATE: 0 consumed, 1 produced

Consumed:

Produced:
56781DF3CEBF2CDAFACE1C5BF04D4962B5483FBCD2C2E428352AD82BC951C686(0): TransactionState(data=MessageState(sender=O=PartyA, L=London, C=GB, recipient=O=PartyB, L=London, C=GB, contents=hello there, linearId=1afc6144-32b1-4265-a06e-73b6bb81aef3_b0fa8491-c9b9-418c-ba6e-8b7840faaf30, participants=[O=PartyA, L=London, C=GB, O=PartyB, L=London, C=GB]), contract=com.lankydanblog.tutorial.contracts.MessageContract, notary=O=Notary, L=London, C=GB, encumbrance=null, constraint=net.corda.core.contracts.WhitelistedByZoneAttachmentConstraint@4a1febb5)
</pre>
The nice thing about this endpoint is that it now maintains a connection to the node which will keep sending any related updates back to this client. The above request was the update for the original <code>POST</code> message. Any new events received by the client would output an update on the client. This is what makes this sort of endpoint ideal for triggering a process or simply displaying up to date data on a front-end separate from the Corda node itself.

## Serialisation and Deserialisation

In this section, I wanted to focus on setting up serialisation and deserialisation correctly. The data retrieved from the <code>/messages/updates</code> endpoint needs to serialise its data correctly to pass to the client, who also needs to be able to deserialise the response data.

Normally Spring does a lot of this for you, and it still does, but it seems with WebFlux there are some extra steps required to get it set up properly. Disclaimer, this is from my experience and if you know of better ways to do this I would be interested to hear from you.

### Corda JacksonSupport

Spring tends to use Jackson by default and, very handily, Corda provides a lot of Jackson setup itself. The <code>JacksonSupport.cordaModule</code> provides some serialisation and deserialisation for classes such as <code>Party</code> and <code>CordaX500Name</code>. If you have some basic situations where you need to serialise or deserialise a Corda class this will probably suit your needs. In Spring you could create a bean that the default <code>ObjectMapper</code> will retrieve and add to itself.

[gist https://gist.github.com/lankydan/3a07dbdfb74421d71951a132c12f137d /]

But, this route has a few caveats. Some classes cannot be deserialised since the module relies on the <code>ObjectMapper</code> having access to node information, for example via the RPC client <code>CordaRPCOps</code>. Without this, deserialising a <code>Party</code>, <code>AbstractParty</code> or <code>AnonymousParty</code> will fail. Not only that, but this has now been deprecated from Corda <code>3.2</code> due to not being thread safe. <code>JacksonSupport.cordaModule</code> has also been moved into its own class (<code>CordaModule</code>). 

The solution I give below is also the solution that Corda recommends taking from now on.

Below is the exception thrown when the <code>MessageClient</code> retrieves updates from the <code>/messages/updates</code> endpoint (for the rest of this section the same endpoint will be used):
<pre>
com.fasterxml.jackson.databind.ObjectMapper cannot be cast to net.corda.client.jackson.JacksonSupport$PartyObjectMapper
</pre>
From this, we can determine that our <code>ObjectMapper</code> is of the wrong type and actually needs to be the subtype <code>PartyObjectMapper</code>. Taking this a bit further we can see that this mapper is found in the <code>JacksonSupport</code> class as well. Now, all there is left to do is to create this mapper and use that instead of the default <code>ObjectMapper</code>.

So lets see how to do that:

[gist https://gist.github.com/lankydan/71077a750f0c10387e0785a86f83d487/]

This will create a <code>RpcObjectMapper</code> which implements <code>PartyObjectMapper</code> and makes use of RPC to retrieve node information to make it possible to deserialise the various party classes. Inside the <code>createDefaultMapper,</code> the <code>CordaModule</code> from before is added and thanks to Spring, this will now be the default object mapper for most (note the most for later) instances where serialisation or deserialisation is needed.

### Some more serialisation and deserialisation configuration

Now... I'm actually in quite a weird position. I wanted to go through all the other steps to get the endpoint working. But, no matter what I do, I cannot seem to recreate all the errors I used to run into before getting it to work. I don't know what to say... Somewhere my exceptions are being swallowed and stopping me from seeing what is going on. Anyway, we must continue on. Thankfully I know why I added the rest of the code but I can no longer provide you with the exception that each change fixed...

Soooo, let's look at the end product of the <code>rpcObjectMapper</code> that we started working on earlier:

[gist https://gist.github.com/lankydan/63f83e6278216eb2748746a1034c13eb /]

There are a few additions here. The <code>JsonComponentModule</code> is added as a bean so that it picks up the defined <code>@JsonSerializer</code> and <code>@JsonDeserializer</code> custom components (in other classes). It seems that even if it is added to the mapper as a module, it still requires the bean itself to be created if it is going to find and register the custom JSON components.

Next is the <code>MixinModule</code>. <code>Vault.Update</code> needs this due to having a method called <code>isEmpty</code>, which doesn't play nicely with Jackson who gets confused and thinks that <code>isEmpty</code> matches to a boolean field called <code>empty</code>. So when deserialising the JSON back into an object it tries to pass in a value for the field.

The Mixin allows us to add Jackson annotations onto a class without actually having access to the class itself which we obviously don't control since this an object from within Corda's codebase. The other option is that this is added to the <code>CordaModule</code> we discussed earlier but that is a different conversation.

The <code>MixinModule</code> itself is simply a class whose constructor adds the <code>VaultUpdateMixin</code> to itself. The mapper then adds the module just like any other module. Job done. 

The Jackson annotation that was added to the Mixin was <code>@JsonIgnore</code> which speaks for itself. When serialising or deserialising the <code>isEmpty</code> function will be ignored.

Before we continue, I want to comment on the <code>SecureHashMixin</code>:

[gist https://gist.github.com/lankydan/62f2bc57bc46998404efdd6efbf1d178 /]

I have added this in after moving from <code>3.1</code> to <code>3.2</code>. To me it looks like adding a Mixin for <code>SecureHash</code> has been forgotten. The <code>CordaModule</code> includes serialisation and deserialisation for <code>SecureHash.SHA256</code> but not <code>SecureHash</code>. The above code is copy and paste from <code>CordaModule</code> with a different class being tied to the Mixin.

Once this is included, the differences between <code>3.1</code> and <code>3.2</code> will be resolved. 

I think I'll raise an issue for this!

### Custom Serialisers and Deserialisers

To serialise <code>Vault.Update</code> only the <code>AttachmentConstraint</code> interface needs it's own custom serialiser:

[gist https://gist.github.com/lankydan/c52b342f741d595ee59ecd3cbe2ef952 /]

Not much to talk about since only the <code>HashAttachmentConstraint</code> actually has any fields. This matches up to the deserialiser later on which reads the <code>type</code> JSON field to determine which object is created.

The last two classes that need custom deserialisers are <code>ContractState</code> and <code>AttachmentContract</code> (matching the serialiser from before):

[gist https://gist.github.com/lankydan/c7e71dd88a8f0cd6af47d78bc54bbb9a /]

The <code>ContractStateDeserialiser</code> is a pretty lazy implementation since only one state is being used in this tutorial. The <code>AttachmentConstraintDeserialiser</code> uses the <code>type</code> field defined in the serialiser to determine which implementation of <code>AttachmentConstraint</code> it should be converted into.

### WebFlux specific configuration

This subsection goes over the extra required configuration due to using WebFlux. You have already seen some of the configuration within the <code>MessageClient</code> but there is a bit extra that needs to be done:

[gist https://gist.github.com/lankydan/6dd6d3cc7211cdf4f00c0b5c5181103e /]

The client needs this bean to be able to deserialise <code>application/stream+json</code> along with the objects returned in the response.

[gist https://gist.github.com/lankydan/b01cc927b1f0d890f0c20639a81cbe07 /]

To make use of the <code>Jackson2JsonDecoder</code> defined in the configuration, the <code>ExchangeStrategies</code> of the <code>WebClient</code> must be specified. Unfortunately, the <code>ExchangeStrategies</code> class is not written to pick up the <code>Jackson2JsonDecoder</code> that we already created. I was hoping that this sort of configuration would work by default, but oh well. To add the <code>ExchangeStrategies</code> the <code>WebClient</code> builder must be used. Once that is done, we are finally there. All serialisation to package up the response and the deserialisation to use it from the client is complete.

That sums up all the Spring related code that I wish to go over in this post. 

## A quick look at the Flow code

Before concluding, I will briefly show the flow that I put together for the purpose of this tutorial:

[gist https://gist.github.com/lankydan/02b18be48da6258c095c7d0450b1ea7e /]

It's a pretty simple flow with the addition of a <code>ProgressTracker</code> that the <code>/messages</code> request used to follow the current state of the flow. Long story short, this flow takes the <code>MessageState</code> passed into it and sends it to the counterparty. While moving through the flow the <code>ProgressTracker</code> is updated to the relevant step. Further documentation on using a <code>ProgressTracker</code> can be found in the [Corda docs](https://docs.corda.net/flow-state-machines.html#progress-tracking).

## Closing time

That was honestly a lot longer than I thought it would be and has taken me much longer to write than I hoped. 

In conclusion, Spring WebFlux provides the capability to use reactive streams to handle response events whenever they arrive. When used with Corda, the progress of a flow can be tracked and a persistent stream of vault updates can be maintained ready to be acted upon as they arrive. To fully make use of WebFlux with Corda, we also had to look into ensuring that objects were serialised correctly by the server and then deserialised by the client so they can be made use of. Lucky Corda does provide some of this, but one or two classes or features are missing and we need to make sure that we use their provided the object mapper. Unfortunately, WebFlux requires a bit more configuration than I'm normally accustomed to when using Spring modules, but nothing that can't be fixed.

The rest of the code for this post can be found on my [GitHub](https://github.com/lankydan/spring-webflux-and-corda)