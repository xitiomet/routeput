## Routeput Websocket Server ##

This project is my attempt at making a simple Websocket Server for my many-to-one, and many-to-many communications needs. This server was written in java using GraalVM and native-image.

**How to use**

Simply form a websocket connection to /channel/ANYTHING/ and start sending json objects. Anyone else connected to /channel/ANYTHING/ will receive those objects, except for the sender. 

(also some special fields are added to the object, more on that later).

ANYTHING can be anything! In the example above the word ANYTHING represents the name of the channel your websocket clients are talking on. This is the many-to-many functionality of Routeput anything transmitted to a channel is received by all in the channel, unless a collector connection is formed.

Collectors are special clients that receive all events transmitted by anyone on the channel, if a collector is connected to a channel, none of the other clients will see each others messages and all messages are delivered to the collector, who then may respond to each client individually or together as a whole. Any Messages transmitted by the collector are sent to all members of the channel

**Special fields**

So about those special fields. Internally the server adds two fields to every packet when it arrives, the sourceId which represents the connection it came in on, and the eventChannel which represents the channel it came in on. These fields are for debugging and responding to the sender directly.

Example Transmitted message:

```json
{
  "hello": "world"
}
```

Other Clients Receive:

```json
{
  "hello": "world",
  "__routeput": {
    "srcId": "PXpoTFSfxnAqzjoEyyMrLWuD",
    "channel": "ANYTHING"
  }
}
```

As you can see an additional field called "__routeput" was added to the object. This special field contains routing information for packets.

**Targeted Messages**

In order to send a message directly to another client you must know it's clientId (which is the same as the "srcId" field in received messages)

Example Targeted Message:
```json
{
  "__routeput": {
    "dstId": "PXpoTFSfxnAqzjoEyyMrLWuD"
  }
  "hello": "world"
}
```

**Implementation with javascript**

Routeput is designed to work with a javascript powered front-end, below is a simple implementation example. Please note that all transmitted messages must be serialized json. routeput.js is provided in the root of your routput server. (ex: http://127.0.0.1:6144/routeput.js) this library provides the RouteputConnection class

```javascript
var routeput = new RouteputConnection("myChannel");

routeput.onblob = function(name, blob) {
    console.log("Recieved File: " + name);
};

routeput.onmessage = function (jsonObject) {
    var evChannel = jsonObject.__eventChannel;
	console.log("Received Message on " + evChannel);
};

routeput.onconnect = function () {
	routeput.transmit({"hello": "world"});
};

```