var connection;
var debugMode = true;
var reconnectTimeout;

function changeView(radio)
{
    if (radio.value == "console")
    {
        document.getElementById('console').style.display = 'block';
    }
}

function sendEvent(wsEvent)
{
    var out_event = JSON.stringify(wsEvent);
    if (debugMode)
        logIt("<- <b>(" + wsEvent.__eventChannel + ")</b>: " + out_event);
    try
    {
        connection.send(out_event);
    } catch (err) {
        console.log(err);
    }
}

function logIt(message)
{
    var console = document.getElementById('console');
    var d = new Date();
    var dString = d.toLocaleTimeString();
    if (console.innerHTML != "") console.innerHTML += "<br />";
    console.innerHTML += "&lt;" + dString + "&gt; " + message;
    window.scrollTo(0,document.body.scrollHeight);
}

function setupWebsocket()
{
    try
    {
        logIt("Attempting to connect to WebSocket backend");
        var hostname = location.hostname;
        var protocol = location.protocol;
        var port = location.port;
        var wsProtocol = 'ws';
        if (hostname == '')
        {
            debugMode = true;
            hostname = '127.0.0.1';
            protocol = 'http';
            port = 6144;
        }
        if (protocol.startsWith('https'))
        {
            wsProtocol = 'wss';
        }
        connection = new WebSocket(wsProtocol + '://' + hostname + ':' + port + '/channel/');
        
        connection.onopen = function () {
            logIt("Connected to WebSocket backend!");
        };
        
        connection.onerror = function (error) {
          logIt("WebSocket error!");
        };

        //Code for handling incoming Websocket messages from the server
        connection.onmessage = function (e) {
            var jsonObject = JSON.parse(e.data);
            var evChannel = jsonObject.__eventChannel;
            if (debugMode)
                logIt("-> <b>(" + evChannel + ")</b>: " + JSON.stringify(jsonObject));
        };
        
        connection.onclose = function () {
          logIt('WebSocket connection closed');
          reconnectTimeout = setTimeout(setupWebsocket, 10000);
        };
    } catch (err) {
        console.log(err);
    }
}

window.onload = function() {
    setupWebsocket();
};

