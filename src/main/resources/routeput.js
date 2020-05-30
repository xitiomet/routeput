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
    channel;
    wsProtocol;
    wsUrl;
    reconnectTimeout;
    connection;
    chunkBuffer;
    connectionId;
    onmessage;
    onblob;
    onconnect;
    constructor(channel)
    {
        this.host = location.host;
        this.channel = channel;
        this.wsProtocol = 'ws';
        this.wsUrl = '';
        this.reconnectTimeout = null;
        this.connection  = null;
        this.chunkBuffer = new Map();
    }
    
    connect()
    {
        var protocol = location.protocol;
        if (protocol.startsWith('https'))
        {
            this.wsProtocol = 'wss';
        }
        
        try
        {
            this.wsUrl = this.wsProtocol + '://' + this.host + '/channel/' + this.channel + '/';
            this.connection = new WebSocket(this.wsUrl);
            this.connection.onopen = () => {
                console.log("routeput connected - " + this.wsUrl);
                if (this.onconnect != undefined)
                {
                    this.onconnect();
                }
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
                if (jsonObject.hasOwnProperty("__response"))
                {
                    var commandResponse = jsonObject.__commandResponse;
                    if (commandResponse == "blob" && jsonObject.hasOwnProperty("i"))
                    {
                        if (jsonObject.i == 1)
                        {
                            this.chunkBuffer[jsonObject.name] = jsonObject.data;
                        } else if (jsonObject.i == jsonObject.of) {
                            this.chunkBuffer[jsonObject.name] += jsonObject.data;
                            if (this.onblob != undefined)
                            {
                                var blob = dataURItoBlob(this.chunkBuffer[jsonObject.name]);
                                this.onblob(jsonObject.name, blob);
                                this.chunkBuffer.delete(jsonObject.name);
                            }
                        } else {
                            this.chunkBuffer[jsonObject.name] += jsonObject.data;
                        }
                    } else if (commandResponse == "connectionId") {
                        this.connectionId = jsonObject.connectionId;
                    }
                } else {
                    if (this.onmessage != undefined)
                    {
                        this.onmessage(jsonObject);
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
                var mm = {"__request": "blob", "name": name ,"i": i+1, "of": sz, "data": chunks[i]};
                this.transmit(mm);
            }
        };
    }
    
    requestBlob(name)
    {
        var mm = {"__request": "blob", "name": name};
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
        this.transmit({"__request": "subscribe", "channel": channel});
    }
    
    unsubscribe(channel)
    {
        this.transmit({"__request": "unsubscribe", "channel": channel});
    }
}
