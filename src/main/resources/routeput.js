var connection;
var reconnectTimeout;

var routeputHost = "mywebsite.com";
var routeputChannel = "general";

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

