# Spring WebFlux and Corda

This project uses Spring WebFlux to stream the progress of a simple flow and updates of states that are saved to the vault.

# Structure:

* app: Spring code
* cordapp: Corda application code
* contracts-and-states: Contracts and states

# Pre-requisites:

See https://docs.corda.net/getting-set-up.html.

# Usage

## Running the nodes:

Run the following gradle task to build the Corda nodes

* Windows: `gradlew.bat deployNodes`
* Unix: `./gradlew deployNodes`

Once built, go to the `build/nodes` directory and run `./runnodes` 

## Running the webservers:

Once the nodes are running, you can start the node webserver from the command line:

* Windows: `gradlew.bat runPartyAServer`
* Unix: `./gradlew runPartyAServer`

Both approaches use environment variables to set:

* `server.port`, which defines the HTTP port the webserver listens on
* `config.rpc.*`, which define the RPC settings the webserver uses to connect to the node

## Interacting with the nodes:

Send a message via a post request

    `localhost:10011/messages`

with a body like
```json
{
    "recipient": "O=PartyB,L=London,C=GB",
    "contents": "this is a temporary message"
}
```

Most importantly my blog can be found at [www.lankydanblog.com](https://lankydanblog.com) and you can follow me on Twitter at [@LankyDanDev](https://twitter.com/LankyDanDev)