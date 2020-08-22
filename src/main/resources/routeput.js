function chunkSubstr(str, size)
{
  const numChunks = Math.ceil(str.length / size)
  const chunks = new Array(numChunks)

  for (let i = 0, o = 0; i < numChunks; ++i, o += size) {
    chunks[i] = str.substr(o, size)
  }

  return chunks
}

function dataURItoBlob(dataURI)
{
  var byteString = atob(dataURI.split(',')[1]);
  var mimeString = dataURI.split(',')[0].split(':')[1].split(';')[0]
  var ab = new ArrayBuffer(byteString.length);
  var ia = new Uint8Array(ab);
  for (var i = 0; i < byteString.length; i++) {
      ia[i] = byteString.charCodeAt(i);
  }
  var blob = new Blob([ab], {type: mimeString});
  return blob;
}

class RouteputConnection
{
    host;
    properties;
    channel;
    channelProperties;
    wsProtocol;
    wsUrl;
    reconnectTimeout;
    connection;
    chunkBuffer;
    connectionId;
    onmessage;
    onblob;
    onconnect;
    onresponse;
    constructor(channel)
    {
        this.host = location.host;
        this.channel = channel;
        
        this.wsProtocol = 'ws';
        var protocol = location.protocol;
        if (protocol.startsWith('https'))
        {
            this.wsProtocol = 'wss';
        }

        this.reconnectTimeout = null;
        this.connection  = null;
        this.chunkBuffer = new Map();
        this.properties = {};
        this.wsUrl = this.wsProtocol + '://' + this.host + '/channel/' + this.channel + '/';
    }
    
    connect()
    {   
        try
        {
            this.connection = new WebSocket(this.wsUrl);
            this.connection.onopen = () => {
                console.log("routeput connected - " + this.wsUrl);
            };
            
            this.connection.onerror = (error) => {
              console.log("routeput error! - " + this.wsUrl);
              console.log(error);
            };

            //Code for handling incoming Websocket messages from the server
            this.connection.onmessage = (e) => {
                var rawData = e.data;
                var jsonObject = JSON.parse(rawData);
                console.log("Route.put Receive: " + rawData);
                if (jsonObject.hasOwnProperty("__routeput"))
                {
                    var routePutMeta = jsonObject.__routeput
                    if (routePutMeta.hasOwnProperty("type"))
                    {
                        var messageType = routePutMeta.type;
                        if (messageType == "blob" && jsonObject.hasOwnProperty("i"))
                        {
                            if (jsonObject.i == 1)
                            {
                                this.chunkBuffer[routePutMeta.name] = routePutMeta.data;
                            } else if (jsonObject.i == jsonObject.of) {
                                this.chunkBuffer[routePutMeta.name] += routePutMeta.data;
                                if (this.onblob != undefined)
                                {
                                    var blob = dataURItoBlob(this.chunkBuffer[routePutMeta.name]);
                                    this.onblob(routePutMeta.name, blob);
                                    this.chunkBuffer.delete(routePutMeta.name);
                                }
                            } else {
                                this.chunkBuffer[routePutMeta.name] += routePutMeta.data;
                            }
                        } else if (messageType == "connectionId") {
                            this.connectionId = routePutMeta.connectionId;
                            this.properties = routePutMeta.properties;
                            this.channelProperties = routePutMeta.channelProperties;
                            if (this.onconnect != undefined)
                            {
                                this.onconnect();
                            }
                        } else if (messageType == "ping") {
                            var mm = {"__routeput": {"type": "pong", "pingTimestamp": routePutMeta.timestamp}};
                            this.transmit(mm);
                        } else if (messageType == "response") {
                            if (this.onresponse != undefined)
                            {
                                this.onresponse(routePutMeta.response, routePutMeta);
                            }
                        }
                    } else {
                        if (this.onmessage != undefined)
                        {
                            this.onmessage(jsonObject);
                        }
                    }
                }
            };
            
            this.connection.onclose = () => {
              this.reconnectTimeout = setTimeout(() => { this.connect() }, 3000);
            };
        } catch (err) {
            console.log(err);
        }
    }
    
    transmitFile(file)
    {
        this.transmitBlob(file.name, file);
    }
    
    transmitBlob(name, blob)
    {
        let reader = new FileReader();
        reader.readAsDataURL(blob);
        reader.onload = () => {
            var chunks = chunkSubstr(reader.result, 4096);
            var sz = chunks.length;
            for (let i = 0; i < sz; i++)
            {
                var mm = {"__routeput": {"type": "blob", "name": name ,"i": i+1, "of": sz, "data": chunks[i]}};
                this.transmit(mm);
            }
        };
    }

    setSessionProperty(k, v)
    {
        var mm = {"__routeput": {"type": "request", "request": "setSessionProperty", "key": k, "value": v}};
        this.transmit(mm);
    }
    
    setChannelProperty(k, v)
    {
        var mm = {"__routeput": {"type": "request", "request": "setChannelProperty", "key": k, "value": v}};
        this.transmit(mm);
    }

    requestBlob(name)
    {
        var mm = {"__routeput": {"type": "blob", "name": name}};
        this.transmit(mm);
    }

    transmit(wsEvent)
    {
        var out_event = JSON.stringify(wsEvent);
        console.log("Route.put Transmit: " + out_event);
        try
        {
            this.connection.send(out_event);
        } catch (err) {
            console.log(err);
        }
    }
    
    subscribe(channel)
    {
        this.transmit({"__routeput": {"type": "request", "request":"subscribe", "channel": channel}});
    }
    
    unsubscribe(channel)
    {
        this.transmit({"__routeput": {"type": "request", "request":"subscribe", "channel": channel}});
    }
}
