function randomId()
{
    var chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
    var result = '';
    for (var i = 10; i > 0; --i) result += chars[Math.floor(Math.random() * chars.length)];
    return result;
}

function removeRouteputMeta(obj) {
    if (obj != null && obj != undefined && typeof obj === 'object')
    {
        var newObj = {};
        for(const [key, value] of Object.entries(obj)) {
            if (key != '__routeput')
            {
                newObj[key] = removeRouteputMeta(value);
            }
        }
        return newObj;
    } else {
        return obj;
    }
}

function setCookie(cname, cvalue, exdays) {
    var d = new Date();
    d.setTime(d.getTime() + (exdays*24*60*60*1000));
    var expires = "expires="+ d.toUTCString();
    document.cookie = cname + "=" + cvalue + ";" + expires + ";path=/";
}

function getCookie(cname, defaultValue) {
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

function chunkSubstr(str, size)
{
  const numChunks = Math.ceil(str.length / size)
  const chunks = new Array(numChunks)

  for (let i = 0, o = 0; i < numChunks; ++i, o += size) {
    chunks[i] = str.substr(o, size)
  }

  return chunks
}

function blobToHTML(fileName, blob)
  {
      var type = blob.type;
      if (type.startsWith("image/"))
      {
          var newImage = document.createElement('img');
          newImage.src = URL.createObjectURL(blob);
          return newImage.outerHTML;
      } else if (type.startsWith("audio/") && !type.startsWith("audio/mid")) {
          return "<audio controls src=\"" + URL.createObjectURL(blob) + "\" />";
      } else if (type.startsWith("video/")) {
          return  "<video controls><source type=\"" + type + "\" src=\"" + URL.createObjectURL(blob) + "\"></video>";
      } else {
          return "<a download=\"" + fileName + "\" href=\"" + URL.createObjectURL(blob) + "\"><b>" + fileName + "</b></a>";    
      }
  }

function setCookie(cname, cvalue, exdays)
{
    var d = new Date();
    d.setTime(d.getTime() + (exdays*24*60*60*1000));
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
            if (pointer != undefined)
            {
                if (pointer.hasOwnProperty(currentValue))
                {
                    ro = pointer[currentValue];
                    if (ro instanceof Object)
                        pointer = ro;
                } else {
                    ro = undefined;
                    pointer = undefined;
                }
            }
        })
    } else {
        ro = object;
    }
    if (ro instanceof Object)
        ro = removeRouteputMeta(ro);
    return ro;
}

class RouteputRemoteSession
{
    srcId;
    channelName;
    connected;
    properties;
    onpropertychange;
    onmessage;
    routeputConnection;
    constructor(srcId, channelName, properties, routeputConnection)
    {
        this.srcId = srcId;
        this.properties = properties;
        this.routeputConnection = routeputConnection;
        this.channelName = channelName;
    }

    transmit(routeputMessage)
    {
        if (routeputMessage.hasOwnProperty("__routeput"))
        {
            var routePutMeta = routeputMessage.__routeput;
            routePutMeta['channel'] = this.channelName;
            routePutMeta['dstId'] = this.srcId;
        } else {
            routeputMessage['__routeput'] = { "channel": this.channelName, "dstId": this.srcId }
        }
        this.routeputConnection.transmit(routeputMessage);
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
    onmessage;
    constructor(name, routeputConnection)
    {
        this.name = name;
        this.members = new Map();
        this.properties = {};
        this.routeputConnection = routeputConnection;
    }

    setProperty(k, v)
    {
        var old = undefined;
        if (this.properties.hasOwnProperty(k))
        {
            old = this.properties[k];
        }
        if (old === v)
        {
            //console.log("Ignoring value, already set " + v);
        } else {
            this.properties[k] = v;
            var mm = {"__routeput": 
                            {"type": "propertyChange", 
                            "updates" : [ { "type":"channel", "id": this.name, "key": k, "old": old , "new": v } ]
                            }
                    };
            this.transmit(mm);
        }
    }

    setSessionProperty(k, v)
    {
        this.routeputConnection.setProperty(k, v);
    }

    getMembers()
    {
        return [ ...this.members.values() ];
    }

    filterMembers(expr)
    {
        return this.getMembers().filter(expr);
    }

    transmit(routeputMessage)
    {
        if (routeputMessage.hasOwnProperty("__routeput"))
        {
            var routePutMeta = routeputMessage.__routeput;
            routePutMeta['channel'] = this.name;
            routePutMeta['srcId'] = this.routeputConnection.connectionId;
        } else {
            routeputMessage['__routeput'] = { "channel": this.name, "srcId": this.routeputConnection.connectionId }
        }
        this.routeputConnection.transmit(routeputMessage);
    }

    transmitBlob(blobName, blob)
    {
        var self = this;
        return new Promise((resolve, reject) => {
            let reader = new FileReader();
            reader.onload = () => {
                var chunks = chunkSubstr(reader.result, 4096);
                var sz = chunks.length;
                var outboundMessageQueue = [];
                for (let i = 0; i < sz; i++)
                {
                    var ipo = i+1;
                    var mm = {"__routeput": {"type": "blob", "channel": this.name, "name": blobName ,"i": ipo, "of": sz, "data": chunks[i]}};
                    if (ipo == sz)
                    {
                        var finishMsgId = randomId();
                        mm.__routeput['msgId'] = finishMsgId;
                        var promHooks = {"resolve": resolve, "reject": reject, "request": mm};
                        self.routeputConnection.requests.set(finishMsgId, promHooks);
                    }
                    outboundMessageQueue.push(mm);
                }
                self.routeputConnection.noLockTransmit(outboundMessageQueue, 0);
            };
            reader.onerror = () => {
                reject();
            };
            reader.readAsDataURL(blob);
        });
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
    channels;
    chunkBuffer;
    requests;
    connectionId;
    serverHostname;

    onmessage;
    onblob;
    onconnect;
    
    constructor(channelName)
    {
        this.host = location.host;
        this.channels = new Map();
        this.requests = new Map();
        this.defaultChannel = new RouteputChannel(channelName, this);
        this.channels.set(channelName, this.defaultChannel);
        if (this.host == undefined || this.host == "")
        {
            this.host = "openstatic.org:6144";
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

    getMembersMatching(connectionId)
    {
        //console.log("Searching for: " + connectionId);
        var rv = new Map();
        this.channels.forEach((value, key, map) => {
            //console.log("checking: " + key);
            if (value.members.has(connectionId))
            {
                //console.log("found: " + connectionId + " in " + value.name);
                rv.set(value, value.members.get(connectionId));
            }
        });
        return rv;
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
                        } else {
                            // Server has finished recieving a file!
                            if (routePutMeta.hasOwnProperty('ref'))
                            {
                                if (this.requests.has(routePutMeta.ref))
                                {
                                    var promHooks = this.requests.get(routePutMeta.ref);
                                    promHooks.resolve(routePutMeta);
                                    this.requests.delete(routePutMeta.ref);
                                }
                            }
                        }
                    } else if (messageType == "blob" && routePutMeta.hasOwnProperty("i")) {
                        var context = '';
                        if (routePutMeta.hasOwnProperty('context'))
                        {
                            context = routePutMeta.context;
                        }
                        var chunkBufferKey = context + ":" + routePutMeta.name;
                        if (routePutMeta.i == 1)
                        {
                            // Store chunks as they come in
                            this.chunkBuffer[chunkBufferKey] = routePutMeta.data;
                        } else if (routePutMeta.i == routePutMeta.of) {
                            // Final chunk of file
                            this.chunkBuffer[chunkBufferKey] += routePutMeta.data;
                            var blob = dataURItoBlob(this.chunkBuffer[chunkBufferKey]);
                            this.chunkBuffer.delete(chunkBufferKey);
                            if (this.onblob != undefined)
                            {
                                this.onblob(context, routePutMeta.name, blob);
                            }
                            // Check if there is a promise awaiting this blob
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
                            this.chunkBuffer[chunkBufferKey] += routePutMeta.data;
                        }
                    } else if (messageType == "connectionId") {
                        var channel = this.getChannel(routePutMeta.channel);
                        this.connectionId = routePutMeta.connectionId;
                        this.properties = routePutMeta.properties;
                        channel.properties = routePutMeta.channelProperties;
                        this.serverHostname = routePutMeta.serverHostname;
                        if (this.onconnect != undefined)
                        {
                            this.onconnect();
                        }
                        for(const [key, value] of Object.entries(channel.properties))
                        {
                            if (this.debug)
                            {
                                console.log("setChannelProperty at connect(" + channel.name + "): " + key + " = " + value);
                            }
                            if(channel.onchannelpropertychange != undefined)
                            {
                                channel.onchannelpropertychange(key, value);
                            }
                        }
                    } else if (messageType == "ping") {
                        var mm = {"__routeput": {"type": "pong", "pingTimestamp": routePutMeta.timestamp}};
                        this.transmit(mm);
                    } else if (messageType == "ConnectionStatus") {
                        var channel = this.getChannel(routePutMeta.channel);
                        var c = routePutMeta.connected;
                        var member;
                        if (c)
                        {
                            member = new RouteputRemoteSession(routePutMeta.srcId, routePutMeta.channel, routePutMeta.properties, this);
                            member.connected = c;
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
                            member = channel.members.get(srcId);
                            if (member != undefined)
                            {
                                member.connected = c;
                                channel.members.delete(srcId);
                                if (channel.onleave != undefined)
                                {
                                    channel.onleave(member);
                                }
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
                    } else if (messageType == "propertyChange") {
                        var updates = routePutMeta.updates;
                        updates.forEach(update => {
                            if (update.type == "channel")
                            {
                                var updateChannel = this.getChannel(update.id);
                                var key = update.key;
                                var newValue = update.new;
                                updateChannel.properties[key] = newValue;
                                if (this.debug)
                                {
                                    console.log("setChannelProperty(" + updateChannel.name + "): " + key + " = " + newValue);
                                }
                                if(updateChannel.onchannelpropertychange != undefined)
                                {
                                    updateChannel.onchannelpropertychange(key, newValue);
                                }
                            } else if (update.type = "session") {
                                var members = this.getMembersMatching(update.id);
                                var key = update.key;
                                var newValue = update.new;
                                if (this.debug)
                                {
                                    console.log("setSessionProperty(" + update.id + "): " + key + " = " + newValue);
                                }
                                members.forEach((member, channel, map) => {
                                    member.properties[key] = newValue;
                                    if(member.onpropertychange != undefined)
                                    {
                                        member.onpropertychange(key, newValue);
                                    }
                                    if(channel.onmemberpropertychange != undefined)
                                    {
                                        channel.onmemberpropertychange(member, key, newValue);
                                    }
                                });
                            }
                        });

                    } else {
                        // Ok now we are getting into messages not handled by routeput.js
                        var channel = this.getChannel(routePutMeta.channel);
                        var member = channel.members.get(srcId);
                        if (messageType == "error") {
                            if (routePutMeta.hasOwnProperty('ref'))
                            {
                                if (this.requests.has(routePutMeta.ref))
                                {
                                    var promHooks = this.requests.get(routePutMeta.ref)
                                    promHooks.reject(routePutMeta);
                                    this.requests.delete(routePutMeta.ref);
                                }
                            }
                        }
                        if (routePutMeta.hasOwnProperty('setCookie'))
                        {
                            if (member != undefined)
                            {
                                var storeRequest = routePutMeta.setCookie;
                                for(const [key, value] of Object.entries(storeRequest))
                                {
                                    var realValue = getPathValue(jsonObject, value);
                                    setCookie(key, value, 365);
                                    if (this.debug)
                                    {
                                        console.log("setCookie(" + srcId + "): " + key + " = " + realValue);
                                    }
                                }
                            } else {
                                if (this.debug)
                                {
                                    console.log("setCookie(" + srcId + "): UNKNOWN Session " + routePutMeta.setCookie);
                                }
                            }
                        }
                        if (this.onmessage != undefined)
                        {
                            this.onmessage(member, messageType, jsonObject);
                        }
                        if (channel.onmessage != undefined)
                        {
                            channel.onmessage(member, messageType, jsonObject);
                        }
                        if (member != undefined)
                        {
                            if (member.onmessage != undefined)
                            {
                                member.onmessage(member, messageType, jsonObject);
                            }
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
    
    transmitFile(context, file)
    {
        return this.transmitBlob(context, file.name, file);
    }

    // This is great if you have to transmit a large array of objects at once.
    noLockTransmit(array, index, finishCallback)
    {
        setTimeout(() => {
            this.transmit(array[index]);
            if (index < (array.length-1))
            {
                this.noLockTransmit(array, (index + 1), finishCallback);
            } else if (finishCallback instanceof Function) {
                finishCallback();
            }
        }, 100);
    }

    transmitBlob(context, name, blob)
    {
        return new Promise((resolve, reject) => {
            let reader = new FileReader();
            reader.onload = () => {
                var chunks = chunkSubstr(reader.result, 4096);
                var sz = chunks.length;
                var outboundMessageQueue = [];
                for (let i = 0; i < sz; i++)
                {
                    var ipo = i+1;
                    var mm = {"__routeput": {"type": "blob", "context": context, "name": name ,"i": ipo, "of": sz, "data": chunks[i]}};
                    if (ipo == sz)
                    {
                        var finishMsgId = randomId();
                        mm.__routeput['msgId'] = finishMsgId;
                        var promHooks = {"resolve": resolve, "reject": reject, "request": mm};
                        this.requests.set(finishMsgId, promHooks);
                    }
                    outboundMessageQueue.push(mm);
                }
                this.noLockTransmit(outboundMessageQueue, 0);
            };
            reader.onerror = () => {
                reject();
            };
            reader.readAsDataURL(blob);
        });
    }

    setProperty(k, v)
    {
        var old = undefined;
        if (this.properties.hasOwnProperty(k))
        {
            old = this.properties[k];
        }
        if (old === v)
        {
            //console.log("Ignoring value, already set " + v);
        } else if (this.connectionId != undefined) {
            this.properties[k] = v;
            var mm = {"__routeput": 
                            {"type": "propertyChange", 
                            "updates" : [ { "type":"session", "id": this.connectionId, "key": k, "old": old , "new": v } ]
                            }
                    };
            this.transmit(mm);
        }
    }
    
    makeRequest(routeputMessage)
    {
        return new Promise((resolve, reject) => {
            if (routeputMessage.hasOwnProperty("__routeput"))
            {
                var routePutMeta = routeputMessage.__routeput;
                if (routePutMeta.hasOwnProperty('msgId'))
                {
                    var promHooks = {"resolve": resolve, "reject": reject, "request": routeputMessage};
                    this.requests.set(routePutMeta.msgId, promHooks);
                }
                this.transmit(routeputMessage);
            } else {
                reject("No routeput META");
            }
        });
        
    }

    requestBlob(context, name)
    {
        var mm = {"__routeput": {"msgId": randomId(), "type": "request", "request": "blob", "name": name, "context": context}};
        return this.makeRequest(mm);
    }

    requestBlobInfo(context, name)
    {
        var mm = {"__routeput": {"msgId": randomId(), "type": "request", "request": "blobInfo", "name": name, "context": context}};
        return this.makeRequest(mm);
    }

    transmit(routeputMessage)
    {
        if (routeputMessage.hasOwnProperty("__routeput"))
        {
            var routePutMeta = routeputMessage.__routeput;
            if (!routePutMeta.hasOwnProperty("srcId"))
            {
                routePutMeta['srcId'] = this.connectionId;
            }
        } else {
            routeputMessage['__routeput'] = { "srcId": this.connectionId }
        }
        var out_event = JSON.stringify(routeputMessage);
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
        this.transmit({"__routeput": {"msgId": randomId(), "type": "request", "request":"subscribe", "channel": channel}});
    }
    
    unsubscribe(channel)
    {
        this.transmit({"__routeput": {"msgId": randomId(), "type": "request", "request":"subscribe", "channel": channel}});
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
