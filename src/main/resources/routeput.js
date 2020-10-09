function randomId()
{
    var chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
    var result = '';
    for (var i = 10; i > 0; --i) result += chars[Math.floor(Math.random() * chars.length)];
    return result;
}

function chunkSubstr(str, size)
{
  const numChunks = Math.ceil(str.length / size)
  const chunks = new Array(numChunks)

  for (let i = 0, o = 0; i < numChunks; ++i, o += size) {
    chunks[i] = str.substr(o, size)
  }

  return chunks
}

function setCookie(cname, cvalue)
{
    var d = new Date();
    d.setTime(d.getTime() + (365*24*60*60*1000));
    var expires = "expires="+ d.toUTCString();
    document.cookie = cname + "=" + cvalue + ";" + expires + ";path=/";
}

function getCookie(cname, defaultValue)
{
    var name = cname + "=";
    var decodedCookie = decodeURIComponent(document.cookie);
    var ca = decodedCookie.split(';');
    for(var i = 0; i <ca.length; i++) {
      var c = ca[i];
      while (c.charAt(0) == ' ') {
        c = c.substring(1);
      }
      if (c.indexOf(name) == 0) {
        return c.substring(name.length, c.length);
      }
    }
    return defaultValue;
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
    routeputConnection;
    onjoin;
    onleave;
    onchannelpropertychange;
    onmemberpropertychange;
    constructor(name, routeputConnection)
    {
        this.name = name;
        this.members = new Map();
        this.properties = {};
        this.routeputConnection = routeputConnection;
    }

    setProperty(k, v)
    {
        var mm = {"__routeput": {"channel": this.name,"setChannelProperty": { [k]: ("__routeput." + k) }, [k]: v}};
        this.transmit(mm);
    }

    getMembers()
    {
        return [ ...this.members.values() ];
    }

    filterMembers(expr)
    {
        return this.getMembers().filter(expr);
    }

    transmitBlob(blobName, blob)
    {
        let reader = new FileReader();
        reader.readAsDataURL(blob);
        reader.onload = () => {
            var chunks = chunkSubstr(reader.result, 4096);
            var sz = chunks.length;
            for (let i = 0; i < sz; i++)
            {
                var mm = {"__routeput": {"type": "blob", "channel": this.name, "name": blobName ,"i": i+1, "of": sz, "data": chunks[i]}};
                this.routeputConnection.transmit(mm);
            }
        };
    }
}

class RouteputConnection
{
    host;
    properties;
    defaultChannel;
    wsProtocol;
    wsUrl;
    reconnectTimeout;
    connection;
    debug;
    sessions;
    channels;
    chunkBuffer;
    requests;
    connectionId;

    onmessage;
    onblob;
    onconnect;
    
    constructor(channelName)
    {
        this.host = location.host;
        this.sessions = new Map();
        this.channels = new Map();
        this.requests = new Map();
        this.defaultChannel = new RouteputChannel(channelName);
        this.channels.set(channelName, this.defaultChannel);
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
            var channel = new RouteputChannel(channelName, this);
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
                console.log("Routeput connected - " + this.wsUrl);
                var mm = {"__routeput": {
                                "type": "connectionId",
                                "channel": this.defaultChannel.name,
                                "properties": this.properties,
                                "connectionId": this.connectionId
                            }
                         };
                this.transmit(mm);
            };
            
            this.connection.onerror = (error) => {
              if (this.debug)
              {
                console.log("Routeput error! - " + this.wsUrl);
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
                    console.log("Routeput Receive: " + rawData);
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
                    if (messageType == "blob" && routePutMeta.hasOwnProperty("exists"))
                    {
                        // Server just wants to tell us the file doesnt exist, lets check for a request and reject the promise
                        if (!routePutMeta.exists)
                        {
                            if (routePutMeta.hasOwnProperty('ref'))
                            {
                                if (this.requests.has(routePutMeta.ref))
                                {
                                    var promHooks = this.requests.get(routePutMeta.ref);
                                    promHooks.reject(routePutMeta);
                                    this.requests.delete(routePutMeta.ref);
                                }
                            }
                        }
                    } else if (messageType == "blob" && routePutMeta.hasOwnProperty("i")) {
                        if (routePutMeta.i == 1)
                        {
                            // Store chunks as they come in
                            this.chunkBuffer[routePutMeta.name] = routePutMeta.data;
                        } else if (routePutMeta.i == routePutMeta.of) {
                            // Final chunk of file
                            this.chunkBuffer[routePutMeta.name] += routePutMeta.data;
                            var blob = dataURItoBlob(this.chunkBuffer[routePutMeta.name]);
                            this.chunkBuffer.delete(routePutMeta.name);
                            if (this.onblob != undefined)
                            {
                                this.onblob(routePutMeta.name, blob);
                            }
                            if (routePutMeta.hasOwnProperty('ref'))
                            {
                                if (this.requests.has(routePutMeta.ref))
                                {
                                    var promHooks = this.requests.get(routePutMeta.ref);
                                    promHooks.resolve(blob);
                                    this.requests.delete(routePutMeta.ref);
                                }
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
                            for(const [key, value] of Object.entries(member.properties))
                            {
                                if (this.debug)
                                {
                                    console.log("initSessionProperty(" + member.srcId + "): " + key + " = " + value);
                                }
                                if(member.onpropertychange != undefined)
                                {
                                    member.onpropertychange(key, value);
                                }
                                if(channel.onmemberpropertychange != undefined)
                                {
                                    channel.onmemberpropertychange(member, key, value);
                                }
                            }
                        } else {
                            channel.members.delete(srcId);
                            if (channel.onleave != undefined)
                            {
                                channel.onleave(member);
                            }
                        }
                    } else if (messageType == "response") {
                        if (routePutMeta.hasOwnProperty('ref'))
                        {
                            if (this.requests.has(routePutMeta.ref))
                            {
                                var promHooks = this.requests.get(routePutMeta.ref)
                                promHooks.resolve(routePutMeta);
                                this.requests.delete(routePutMeta.ref);
                            }
                        }
                    } else if (messageType == "error") {
                        if (routePutMeta.hasOwnProperty('ref'))
                        {
                            if (this.requests.has(routePutMeta.ref))
                            {
                                var promHooks = this.requests.get(routePutMeta.ref)
                                promHooks.reject(routePutMeta);
                                this.requests.delete(routePutMeta.ref);
                            }
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
    
    transmitFile(file, context)
    {
        this.transmitBlob(file.name, file, context);
    }
    
    

    transmitBlob(name, blob, context)
    {
        let reader = new FileReader();
        reader.readAsDataURL(blob);
        reader.onload = () => {
            var chunks = chunkSubstr(reader.result, 4096);
            var sz = chunks.length;
            for (let i = 0; i < sz; i++)
            {
                var mm = {"__routeput": {"type": "blob", "context": context, "name": name ,"i": i+1, "of": sz, "data": chunks[i]}};
                this.transmit(mm);
            }
        };
    }

    setProperty(k, v)
    {
        var mm = {"__routeput": {"setSessionProperty": { [k]: ("__routeput." + k) }, [k]: v}};
        this.transmit(mm);
    }
    
    makeRequest(routeputMessage)
    {
        return new Promise((resolve, reject) => {
            if (routeputMessage.hasOwnProperty("__routeput"))
            {
                var routePutMeta = routeputMessage.__routeput;
                var promHooks = {"resolve": resolve, "reject": reject, "request": routeputMessage};
                this.requests.set(routePutMeta.msgId, promHooks);
                this.transmit(routeputMessage);
            } else {
                reject("No routeput META");
            }
        });
        
    }

    requestBlob(name, context)
    {
        var mm = {"__routeput": {"msgId": randomId(), "type": "request", "request": "blob", "name": name, "context": context}};
        return this.makeRequest(mm);
    }

    requestBlobInfo(name, context)
    {
        var mm = {"__routeput": {"msgId": randomId(), "type": "request", "request": "blobInfo", "name": name, "context": context}};
        return this.makeRequest(mm);
    }

    transmit(wsEvent)
    {
        var out_event = JSON.stringify(wsEvent);
        if (this.debug)
        {
            console.log("Routeput Transmit: " + out_event);
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
        this.transmit({"__routeput": {"msgId": randomId(), "type": "request", "request":"subscribe", "channel": this.defaultChannel.name}});
    }
    
    unsubscribe(channel)
    {
        this.transmit({"__routeput": {"msgId": randomId(), "type": "request", "request":"subscribe", "channel": this.defaultChannel.name}});
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
