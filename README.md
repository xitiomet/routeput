# Routeput Websocket Messaging and Property Change Server

## Table of Contents
1. [Introduction](#introduction)
2. [Basic Usage](#usage)
3. [Javascript Library](#javascript)

## Introduction

I found myself constantly needing a Websocket server that handles JSON packets and distributes them to other clients, or specific clients. Solutions like pusher and pubnub just seemed like overkill, and i'm not a fan of relying on too many outside services. This project is my attempt at making a simple, lightweight and fast Websocket Server for my many-to-one, and many-to-many messaging needs.

Every message contains its an object field called "__routeput" which provides all the routing information. This makes it easy for objects to traverse other protocols and services without losing the message's source, destination, and channel.

Another way to think of the message is like a piece of mail, "__routeput" is the envelope with the return address and destination.

**What about the name?**

Routeput got it's name from the idea of PUTting the ROUTE information directly into the JSON object.

**Under the hood**
1. This server was written in Java using GraalVM and native-image.
2. Jetty is used for all websocket management and API interfaces
3. org.json is used for JSON object management

## Usage

What do routeput messaages look like? You decide! The server is designed to route JSON objects as messages. These messages can be any custom json object you need, the server just adds its own little routing field called "__routeput"

Imagine the following simple object
```json
{
  "hello": "world"
}
```

After entering the routeput server it will be delievered to recipients slightly modified.
```json
{
  "hello": "world",
  "__routeput": {
    "srcId": "VWbFlzwFVa",
    "channel": "myChannel"
  }
}
```

On the recieving end the "__routeput" field lets the recipient know more information about how the packet was handled by routeput. The "__routeput" field is always added to the root of the object, and can easily be ignored by your code unless you would like to take advantage of some of the advanced features of routeput.

**__routeput fields**

Inside the "__routeput" field there are two fields you can always expect
 * channel - The channel the packet was sent on, think of this as a way to seperate different applications or groups on a single server
 * srcId - Who transmitted this packet

**Lets try something**

After installing Routeput the server will launch by default on port 6144. by default all websockets should be formed to http://127.0.0.1:6144/channel/channelName/ (replacing channelName with your custom channel)

Once you connect you will receive a packet from the server that looks like this:
```json
{
  "__routeput": {
      "srcId": "VWbFlzwFVa",
      "channel": "channelName",
      "type": "connectionId",
      "channelProperties": {},
      "connectionId": "VWbFlzwFVa",
      "properties": {}
    }
}
```
this is a "connectionId" packet (denoted by the "type" field inside "__routeput") and will be received by any client connecting to the Websocket. Whenever the "type" field is present in a message, chances are this is a special message. In all client libraries these packets are usually absorbed and managed internally.

Upon connecting every client is assigned a ten-digit case-sensitive alphabetic key to identify the connection. This key is important it will be the mailing address for your connection inside the network. These IDs are also not persistent, upon every reconnection it should be assumed that this id changed. 

The important thing to note is your connectionId which is "VWbFlzwFVa" in the provided example.

**Multiple Connections**

OK! So now lets form a second connection as well to the same endpoint. As soon as this happens, the second connection receives the same welcome packet, and another packet.

```json
{
  "__routeput": {
      "srcId":"VWbFlzwFVa",
      "channel":"channelName",
      "type":"ConnectionStatus",
      "connected":true,
      "properties":{}
    }
}

```
"ConnectionStatus" messages are another type of message generated by the server. This packet lets you know whenever someone joins or exits the channel. When a member joins they will receive a "ConnectionStatus" packet for every other member already joined, and the other members will receive a packet for the user that just joined. 

If you go back to connection 1 you will see they received a message that connection 2 joined. This eliminates the need to implement any of your own connection tracking logic. "srcId" will always represent the "connectionId" of the client joining or leaving.

Now that we have two connections lets try sending some of our own messages....

From the first connection lets sent the message:

```json
{
  "hello": "world"
}
```

The Second Connection Receives:

```json
{
  "hello": "world",
  "__routeput": {
    "srcId": "VWbFlzwFVagMUldXGhZFABlH",
    "channel": "ANYTHING"
  }
}
```

As you can see an additional field called "__routeput" was added to the object. The server always knows your connectionId and channel, so there is no need to add this information to packets sent directly to the server. Also note the lack of a "type" field, one of the main ways to tell your messages from ones auto-generated by the server is the presence of the "type" field.

## Targeted Messages

In order to send a message directly to another client (instead of the entire channel) you must know it's connectionId which can be obtained from "ConnectionStatus" messages

Example Targeted Message:
```json
{
  "__routeput": {
    "dstId": "PXpoTFSfxnAqzjoEyyMrLWuD"
  }
  "hello": "world"
}
```

## JavaScript

Routeput is designed to work with a javascript powered front-end, below is a simple implementation example. Please note that all transmitted messages must be objects. routeput.js is provided in the root of your routput server. (ex: http://127.0.0.1:6144/routeput.js)

this library provides the following classes:
  * RouteputConnection - represents your connection to the routeput server
  * RouteputChannel - represents a channel object (most interaction will be using this class.)
  * RouteputRemoteSession - represents another user/connection occupying a channel.


Simple Example
```javascript
var routeput = new RouteputConnection("myChannel");

// Turn on console debugging
routeput.debug = true;

// Listen for any messages on the default channel you connected with ("myChannel" above)
routeput.defaultChannel.onmessage = function (member, jsonObject) {
  var routeputMeta = jsonObject.__routeput;
  var channel = routeputMeta.channel;
	console.log("Received Message on " + channel);
};


// Fired after the connection is negotiated with the routeput server
routeput.onconnect = function () {
	routeput.defaultChannel.transmit({"hello": "world"});
};

```
In the example above, we form a connection to the channel "myChannel" and listen for any messages. After connecting we transmit the object {"hello": "world"}

