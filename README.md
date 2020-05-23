# Route.put Websocket Server #

This project is my attempt at making a simple Websocket Server for my many-to-one, and many-to-many communications needs.

**How to use**

Simply form a websocket connection to /channel/ANYTHING/ and start sending json objects. Anyone else connected to /channel/ANYTHING/ will receive those objects, except for the sender. 

(also some special fields are added to the object, more on that later).

ANYTHING can be anything! In the example above the word ANYTHING represents the name of the channel your websocket clients are talking on. This is the many-to-many functionality of Route.put anything transmitted to a channel is received by all in the channel, unless a collector connection is formed.

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
  "__sourceId": "PXpoTFSfxnAqzjoEyyMrLWuD",
  "__eventChannel": "ANYTHING"
}
```

The fields are prefixed with a double underscore in hopes that they will not collide with any existing fields in your packet. In future versions the names of these fields will be definable.

**Targeted Messages**

In order to send a message directly to another client you must know it's clientId (which is the same as the "__sourceId" field in received messages)

Example Targeted Message:
```json
{
  "__targetId": "PXpoTFSfxnAqzjoEyyMrLWuD",
  "hello": "world"
}
```

**Implementation with javascript**

Route.put is designed to work with your javascript powered front-end, below is a simple implementation example. Please note that all transmitted messages must be serialized json.

```javascript

var connection;
var reconnectTimeout;

var routeputHost = "mywebsite.com";
var routeputChannel = "general";

// A Message was received from the server
function routeputReceived(sourceId, jsonObject)
{
	console.log("Object received from " + sourceId);
    console.log(jsonObject);
}

function routeputConnect()
{
    try
    {
        connection = new WebSocket('ws://" + routeputHost + "/channel/' + routeputChannel + '/');
        connection.onopen = function () {
            console.log("routeput connected");
        };
        
        connection.onerror = function (error) {
          console.log("WebSocket error!");
        };

        //Code for handling incoming Websocket messages from the server
        connection.onmessage = function (e) {
            var jsonObject = JSON.parse(e.data);
            if (jsonObject.hasOwnProperty('__sourceId'))
            {
                var sourceId = jsonObject.__sourceId;
				routeputReceived(sourceId, jsonObject);
            }
        };
        
        connection.onclose = function () {
          reconnectTimeout = setTimeout(routeputConnect, 3000);
        };
    } catch (err) {
        console.log(err);
    }
}

function routeputTransmit(wsEvent)
{
    var out_event = JSON.stringify(wsEvent);
    console.log("Transmit: " + out_event);
    try
    {
        connection.send(out_event);
    } catch (err) {
        console.log(err);
    }
}


```