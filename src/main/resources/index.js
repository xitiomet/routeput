var connection;
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
    //logIt("<- <b>(" + wsEvent.__eventChannel + ")</b>: " + out_event);
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
    console.innerHTML += "&lt;" + dString + "&gt; " + message + "<br />";
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
        connection = new WebSocket(wsProtocol + '://' + hostname + ':' + port + '/channel/routeputDebug/');
        
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
            if (jsonObject.hasOwnProperty('logIt'))
            {
                logIt(jsonObject.logIt);
            } else if (jsonObject.hasOwnProperty('channelStats')) {
                var channelStats = jsonObject.channelStats;
                var channelStatsTable = document.getElementById('channelStatsTable');
                var i;
                for (i = 0; i < channelStatsTable.children.length; i++)
                {
                    var child = channelStatsTable.children[i];
                    var cName = child.id.slice(0, -2);
                    if (!channelStats.hasOwnProperty(cName) && child.id.endsWith("TR"))
                    {
                        console.log("Removing missing channel: " + cName);
                        channelStatsTable.removeChild(child);
                    }
                }

                
                for (var key in channelStats)
                {
                    //console.log(key);
                    var value = channelStats[key];
                    //console.log(value)
                    var channelTR = document.getElementById(key + "TR");
                    if (channelTR == undefined)
                    {
                        channelTR = document.createElement("tr");
                        channelTR.id = key + "TR";
                        channelStatsTable.appendChild(channelTR);
                    }
                    channelTR.innerHTML = "<td>" + key + "</td><td>" + value.members + "</td>";
                }
            }
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

