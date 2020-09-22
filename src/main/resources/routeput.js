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

function getPathValue(object, path)
{
    var ro = undefined;
    var pointer = object;
    if (path != undefined && path != "")
    {
        var st = path.split(".");
        st.forEach(function(currentValue)
        {
            if (pointer.hasOwnProperty(currentValue))
            {
                ro = pointer[currentValue];
                if (ro instanceof Object)
                    pointer = ro;
            }
        })
    } else {
        return object;
    }
    return ro;
}

class RouteputRemoteSession
{
    srcId;
    connected;
    properties;
    onpropertychange;
    constructor(srcId, properties)
    {
        this.srcId = srcId;
        this.properties = properties;
    }
}

class RouteputChannel
{
    name;
    properties;
    members;
    onjoin;
    onleave;
    onchannelpropertychange;
    onmemberpropertychange;
    constructor(name)
    {
        this.name = name;
        this.members = new Map();
        this.properties = {};
    }

    getMembers()
    {
        return [ ...therapyChat.members.values() ];
    }

    filterMembers(expr)
    {
        return this.getMembers().filter(expr);
    }
}

class RouteputConnection
{
    host;
    properties;
    channel;
    wsProtocol;
    wsUrl;
    reconnectTimeout;
    connection;
    debug;
    sessions;
    channels;
    chunkBuffer;
    connectionId;
    onmessage;
    onblob;
    onconnect;
    onresponse;
    
    constructor(channelName)
    {
        this.host = location.host;
        this.sessions = new Map();
        this.channels = new Map();
        this.channel = new RouteputChannel(channelName);
        this.channels.set(channelName, this.channel);
        if (this.host == undefined || this.host == "")
        {
            this.host = "127.0.0.1:6144";
        }
        this.wsProtocol = 'ws';
        var protocol = location.protocol;
        if (protocol.startsWith('https'))
        {
            this.wsProtocol = 'wss';
        }
        this.debug = false;
        this.reconnectTimeout = null;
        this.connection  = null;
        this.chunkBuffer = new Map();
        this.properties = {};
        this.wsUrl = this.wsProtocol + '://' + this.host + '/channel/';
    }

    getChannel(channelName)
    {
        if (this.channels.has(channelName))
        {
            return this.channels.get(channelName);
        } else {
            var channel = new RouteputChannel(channelName);
            this.channels.set(channelName, channel);
            return channel;
        }
    }
    
    connect()
    {   
        try
        {
            this.connection = new WebSocket(this.wsUrl);
            this.connection.onopen = () => {
                console.log("routeput connected - " + this.wsUrl);
                var mm = {"__routeput": {
                                "type": "connectionId",
                                "channel": this.channel.name,
                                "properties": this.properties,
                                "connectionId": this.connectionId
                            }
                         };
                this.transmit(mm);
            };
            
            this.connection.onerror = (error) => {
              if (this.debug)
              {
                console.log("routeput error! - " + this.wsUrl);
                console.log(error);
              }
              this.connection.close();
            };

            //Code for handling incoming Websocket messages from the server
            this.connection.onmessage = (e) => {
                var rawData = e.data;
                var jsonObject = JSON.parse(rawData);
                if (this.debug)
                {
                    console.log("Route.put Receive: " + rawData);
                }
                if (jsonObject.hasOwnProperty("__routeput"))
                {
                    var routePutMeta = jsonObject.__routeput;
                    var srcId = routePutMeta.srcId;
                    var channel = this.getChannel(routePutMeta.channel);
                    var messageType = undefined;
                    if (routePutMeta.hasOwnProperty("type"))
                    {
                        messageType = routePutMeta.type;
                    }
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
                        channel.properties = routePutMeta.channelProperties;
                        if (this.onconnect != undefined)
                        {
                            this.onconnect();
                        }
                    } else if (messageType == "ping") {
                        var mm = {"__routeput": {"type": "pong", "pingTimestamp": routePutMeta.timestamp}};
                        this.transmit(mm);
                    } else if (messageType == "ConnectionStatus") {
                        var c = routePutMeta.connected;
                        var member;
                        if (this.sessions.has(srcId))
                        {
                            member = this.sessions.get(srcId);
                        } else {
                            member = new RouteputRemoteSession(routePutMeta.srcId, routePutMeta.properties);
                            this.sessions.set(srcId, member);
                        }
                        member.connected = c;
                        if (c)
                        {
                            channel.members.set(srcId, member);
                            if (channel.onjoin != undefined)
                            {
                                channel.onjoin(member);
                            }
                        } else {
                            channel.members.delete(srcId);
                            if (channel.onleave != undefined)
                            {
                                channel.onleave(member);
                            }
                        }
                    } else if (messageType == "response") {
                        if (this.onresponse != undefined)
                        {
                            this.onresponse(routePutMeta.response, routePutMeta);
                        }
                    } else {
                        if (routePutMeta.hasOwnProperty('setSessionProperty'))
                        {
                            var member = this.sessions.get(srcId);
                            if (member != undefined)
                            {
                                var storeRequest = routePutMeta.setSessionProperty;
                                for(const [key, value] of Object.entries(storeRequest))
                                {
                                    var realValue = getPathValue(jsonObject, value);
                                    member.properties[key] = realValue;
                                    if (this.debug)
                                    {
                                        console.log("setSessionProperty(" + srcId + "): " + key + " = " + realValue);
                                    }
                                    if(member.onpropertychange != undefined)
                                    {
                                        member.onpropertychange(key, realValue);
                                    }
                                    if(channel.onmemberpropertychange != undefined)
                                    {
                                        channel.onmemberpropertychange(member, key, realValue);
                                    }
                                }
                            } else {
                                if (this.debug)
                                {
                                    console.log("setSessionProperty(" + srcId + "): UNKNOWN Session " + routePutMeta.setSessionProperty);
                                }
                            }
                        }
                        if (routePutMeta.hasOwnProperty('setChannelProperty'))
                        {
                            var storeRequest = routePutMeta.setChannelProperty;
                            for(const [key, value] of Object.entries(storeRequest))
                            {
                                var realValue = getPathValue(jsonObject, value);
                                channel.properties[key] = realValue;
                                if (this.debug)
                                {
                                    console.log("setChannelProperty(" + channel.name + "): " + key + " = " + realValue);
                                }
                                if(channel.onchannelpropertychange != undefined)
                                {
                                    channel.onchannelpropertychange(key, realValue);
                                }
                            }
                        }
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
            if (this.debug)
            {
                console.log(err);
            }
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
        var mm = {"__routeput": {"setSessionProperty": { [k]: ("__routeput." + k) }, [k]: v}};
        this.transmit(mm);
    }
    
    setChannelProperty(k, v)
    {
        var mm = {"__routeput": {"setChannelProperty": { [k]: ("__routeput." + k) }, [k]: v}};
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
        if (this.debug)
        {
            console.log("Route.put Transmit: " + out_event);
        }
        try
        {
            this.connection.send(out_event);
        } catch (err) {
            if (this.debug)
            {
                console.log(err);
            }
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

    logError(text)
    {
        this.transmit({"__routeput": {"type": "error"}, "text": text});
    }

    logInfo(text)
    {
        this.transmit({"__routeput": {"type": "info"}, "text": text});
    }

    logWarning(text)
    {
        this.transmit({"__routeput": {"type": "warning"}, "text": text});
    }
}
