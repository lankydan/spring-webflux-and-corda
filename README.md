![Corda](https://www.corda.net/wp-content/uploads/2016/11/fg005_corda_b.png)

# Spring webserver

This project defines a simple Spring webserver that connects to a Corda node via RPC.

# Structure:

The Spring web server is defined in the `server` module, and has two parts:

* `src/main/resources/static`, which defines the webserver's frontend
* `src/main/kotlin/net/corda/server`, which defines the webserver's backend

The backend has two controllers, defined in `server/src/main/kotlin/net/corda/server/Controller.kt`:

* `StandardController`, which provides generic (non-CorDapp specific) REST endpoints
* `CustomController`, which the user can extend to provide CorDapp-specific REST endpoints

# Pre-requisites:

See https://docs.corda.net/getting-set-up.html.

# Usage

## Running the nodes:

See https://docs.corda.net/tutorial-cordapp.html#running-the-example-cordapp.

## Running the webservers:

Once the nodes are running, you can start the node webserver from the command line:

* Windows: `gradlew.bat runPartyAServer`
* Unix: `./gradlew runPartyAServer`

You can also start the webservers using the `Run PartyA Server` IntelliJ run configuration.

Both approaches use environment variables to set:

* `server.port`, which defines the HTTP port the webserver listens on
* `config.rpc.*`, which define the RPC settings the webserver uses to connect to the node

## Interacting with the nodes:

Once the nodes are started, you can access the node's frontend at the following address:

    `localhost:10011`

And you can access the REST endpoints at:

    `localhost:10011/[ENDPOINT]`

For example, you can check the node's status using:

    `localhost:10011/status`
    
 ```
 java -Xmx512m -Dlog4j.configurationFile=../../../src/main/resources/log4j2.xml -javaagent:drivers/jolokia-jvm-1.3.7-agent.jar=port=7006 -jar corda.jar
 ```