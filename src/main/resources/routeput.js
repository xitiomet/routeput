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

// MD5 (public domain, Joseph Myers) — operates on a Uint8Array, returns lowercase hex.
function md5Bytes(bytes)
{
    function add32(a, b) { return (a + b) & 0xFFFFFFFF; }
    function cmn(q, a, b, x, s, t) {
        a = add32(add32(a, q), add32(x, t));
        return add32((a << s) | (a >>> (32 - s)), b);
    }
    function ff(a, b, c, d, x, s, t) { return cmn((b & c) | ((~b) & d), a, b, x, s, t); }
    function gg(a, b, c, d, x, s, t) { return cmn((b & d) | (c & (~d)), a, b, x, s, t); }
    function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
    function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | (~d)), a, b, x, s, t); }
    function md5cycle(x, k) {
        var a = x[0], b = x[1], c = x[2], d = x[3];
        a = ff(a, b, c, d, k[0], 7, -680876936);
        d = ff(d, a, b, c, k[1], 12, -389564586);
        c = ff(c, d, a, b, k[2], 17, 606105819);
        b = ff(b, c, d, a, k[3], 22, -1044525330);
        a = ff(a, b, c, d, k[4], 7, -176418897);
        d = ff(d, a, b, c, k[5], 12, 1200080426);
        c = ff(c, d, a, b, k[6], 17, -1473231341);
        b = ff(b, c, d, a, k[7], 22, -45705983);
        a = ff(a, b, c, d, k[8], 7, 1770035416);
        d = ff(d, a, b, c, k[9], 12, -1958414417);
        c = ff(c, d, a, b, k[10], 17, -42063);
        b = ff(b, c, d, a, k[11], 22, -1990404162);
        a = ff(a, b, c, d, k[12], 7, 1804603682);
        d = ff(d, a, b, c, k[13], 12, -40341101);
        c = ff(c, d, a, b, k[14], 17, -1502002290);
        b = ff(b, c, d, a, k[15], 22, 1236535329);
        a = gg(a, b, c, d, k[1], 5, -165796510);
        d = gg(d, a, b, c, k[6], 9, -1069501632);
        c = gg(c, d, a, b, k[11], 14, 643717713);
        b = gg(b, c, d, a, k[0], 20, -373897302);
        a = gg(a, b, c, d, k[5], 5, -701558691);
        d = gg(d, a, b, c, k[10], 9, 38016083);
        c = gg(c, d, a, b, k[15], 14, -660478335);
        b = gg(b, c, d, a, k[4], 20, -405537848);
        a = gg(a, b, c, d, k[9], 5, 568446438);
        d = gg(d, a, b, c, k[14], 9, -1019803690);
        c = gg(c, d, a, b, k[3], 14, -187363961);
        b = gg(b, c, d, a, k[8], 20, 1163531501);
        a = gg(a, b, c, d, k[13], 5, -1444681467);
        d = gg(d, a, b, c, k[2], 9, -51403784);
        c = gg(c, d, a, b, k[7], 14, 1735328473);
        b = gg(b, c, d, a, k[12], 20, -1926607734);
        a = hh(a, b, c, d, k[5], 4, -378558);
        d = hh(d, a, b, c, k[8], 11, -2022574463);
        c = hh(c, d, a, b, k[11], 16, 1839030562);
        b = hh(b, c, d, a, k[14], 23, -35309556);
        a = hh(a, b, c, d, k[1], 4, -1530992060);
        d = hh(d, a, b, c, k[4], 11, 1272893353);
        c = hh(c, d, a, b, k[7], 16, -155497632);
        b = hh(b, c, d, a, k[10], 23, -1094730640);
        a = hh(a, b, c, d, k[13], 4, 681279174);
        d = hh(d, a, b, c, k[0], 11, -358537222);
        c = hh(c, d, a, b, k[3], 16, -722521979);
        b = hh(b, c, d, a, k[6], 23, 76029189);
        a = hh(a, b, c, d, k[9], 4, -640364487);
        d = hh(d, a, b, c, k[12], 11, -421815835);
        c = hh(c, d, a, b, k[15], 16, 530742520);
        b = hh(b, c, d, a, k[2], 23, -995338651);
        a = ii(a, b, c, d, k[0], 6, -198630844);
        d = ii(d, a, b, c, k[7], 10, 1126891415);
        c = ii(c, d, a, b, k[14], 15, -1416354905);
        b = ii(b, c, d, a, k[5], 21, -57434055);
        a = ii(a, b, c, d, k[12], 6, 1700485571);
        d = ii(d, a, b, c, k[3], 10, -1894986606);
        c = ii(c, d, a, b, k[10], 15, -1051523);
        b = ii(b, c, d, a, k[1], 21, -2054922799);
        a = ii(a, b, c, d, k[8], 6, 1873313359);
        d = ii(d, a, b, c, k[15], 10, -30611744);
        c = ii(c, d, a, b, k[6], 15, -1560198380);
        b = ii(b, c, d, a, k[13], 21, 1309151649);
        a = ii(a, b, c, d, k[4], 6, -145523070);
        d = ii(d, a, b, c, k[11], 10, -1120210379);
        c = ii(c, d, a, b, k[2], 15, 718787259);
        b = ii(b, c, d, a, k[9], 21, -343485551);
        x[0] = add32(a, x[0]);
        x[1] = add32(b, x[1]);
        x[2] = add32(c, x[2]);
        x[3] = add32(d, x[3]);
    }
    function md5blk(bytes, offset) {
        var blk = new Array(16);
        for (var i = 0; i < 16; i++) {
            blk[i] = bytes[offset + i*4]
                   | (bytes[offset + i*4 + 1] << 8)
                   | (bytes[offset + i*4 + 2] << 16)
                   | (bytes[offset + i*4 + 3] << 24);
        }
        return blk;
    }
    var n = bytes.length;
    var state = [1732584193, -271733879, -1732584194, 271733878];
    var i;
    for (i = 64; i <= n; i += 64) {
        md5cycle(state, md5blk(bytes, i - 64));
    }
    var tail = [0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0];
    var tailStart = i - 64;
    var remaining = n - tailStart;
    for (var j = 0; j < remaining; j++) {
        tail[j >> 2] |= bytes[tailStart + j] << ((j & 3) << 3);
    }
    tail[remaining >> 2] |= 0x80 << ((remaining & 3) << 3);
    if (remaining > 55) {
        md5cycle(state, tail);
        for (var k = 0; k < 16; k++) tail[k] = 0;
    }
    // 64-bit length in bits; safe for practical sizes.
    tail[14] = n * 8;
    md5cycle(state, tail);
    var hexChars = '0123456789abcdef';
    var result = '';
    for (var wi = 0; wi < 4; wi++) {
        var v = state[wi];
        for (var bi = 0; bi < 4; bi++) {
            var b = (v >> (bi*8)) & 0xFF;
            result += hexChars[(b >> 4) & 0xF] + hexChars[b & 0xF];
        }
    }
    return result;
}

// Compute md5 of a Blob asynchronously (returns Promise<string>).
function md5OfBlob(blob)
{
    return blob.arrayBuffer().then((buf) => md5Bytes(new Uint8Array(buf)));
}

// Decode the base64 payload of a data URI into a Uint8Array.
function dataURIToBytes(dataURI)
{
    var byteString = atob(dataURI.split(',')[1]);
    var bytes = new Uint8Array(byteString.length);
    for (var i = 0; i < byteString.length; i++) bytes[i] = byteString.charCodeAt(i);
    return bytes;
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
  var mimeString = dataURI.split(',')[0].split(':')[1].split(';')[0];
  var bytes = dataURIToBytes(dataURI);
  return new Blob([bytes], {type: mimeString});
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
                var payloadBytes = dataURIToBytes(reader.result);
                var md5 = md5Bytes(payloadBytes);
                var size = payloadBytes.length;
                self.routeputConnection._sendBlobWithCheck({
                    channel: this.name,
                    context: null,
                    name: blobName,
                    md5: md5,
                    size: size,
                    chunks: chunks,
                    resolve: resolve,
                    reject: reject
                });
            };
            reader.onerror = () => {
                reject();
            };
            reader.readAsDataURL(blob);
        });
    }

    // Resolve immediately with the cached Blob when available, otherwise coalesce with
    // any in-flight distribution or fire a fresh request for this channel's copy.
    getBlob(blobName)
    {
        return this.routeputConnection.requestBlob("channel." + this.name, blobName);
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
    blobCache;
    pendingBlobRequests;
    requests;
    connectionId;
    serverHostname;

    onmessage;
    onblob;
    onconnect;
    onpropertychange;
    onauthrequired;
    channelPasswords;
    // Set when the server rejects our handshake with authRequired; suppresses the
    // auto-reconnect in onclose so we don't hammer the server until the user retries.
    authBlocked;
    
    constructor(channelName, channelPassword)
    {
        this.host = location.host;
        this.channels = new Map();
        this.requests = new Map();
        this.defaultChannel = new RouteputChannel(channelName, this);
        this.channels.set(channelName, this.defaultChannel);
        // Passwords keyed by channel name; sent with connectionId + subscribe payloads.
        this.channelPasswords = new Map();
        if (channelPassword) this.channelPasswords.set(channelName, channelPassword);
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
        // Cache of blobs we've received in full, keyed by "context:name" → {md5, size, blob}.
        // Used to answer remote "do you have this blob?" queries with state=have and to
        // resolve requestBlob() locally without a round-trip.
        this.blobCache = new Map();
        // Coalesces concurrent requestBlob() callers and in-flight server distributions
        // for the same "context:name" so we never trigger overlapping chunk streams.
        this.pendingBlobRequests = new Map();
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
            this.authBlocked = false;
            if (this.reconnectTimeout) { clearTimeout(this.reconnectTimeout); this.reconnectTimeout = null; }
            this.connection = new WebSocket(this.wsUrl);
            this.connection.onopen = () => {
                console.log("Routeput connected - " + this.wsUrl);
                var meta = {
                    "type": "connectionId",
                    "channel": this.defaultChannel.name,
                    "properties": this.properties,
                    "connectionId": this.connectionId
                };
                var pw = this.channelPasswords.get(this.defaultChannel.name);
                if (pw) meta.password = pw;
                this.transmit({"__routeput": meta});
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
                    if (messageType == "request" && routePutMeta.request == "blobCheck")
                    {
                        // Server is asking whether we already have this blob. Reply based
                        // on our local cache; keeps the server from re-sending files we hold.
                        var qContext = routePutMeta.hasOwnProperty('context') ? routePutMeta.context : '';
                        var cacheKey = qContext + ":" + routePutMeta.name;
                        var cached = this.blobCache.get(cacheKey);
                        var have = !!(cached && cached.md5 && cached.md5.toLowerCase() === String(routePutMeta.md5).toLowerCase() && cached.size == routePutMeta.size);
                        var respMeta = {
                            "type": "response",
                            "response": "blobCheck",
                            "ref": routePutMeta.msgId,
                            "name": routePutMeta.name,
                            "md5": routePutMeta.md5,
                            "size": routePutMeta.size,
                            "state": have ? "have" : "need"
                        };
                        if (routePutMeta.hasOwnProperty('context')) respMeta.context = routePutMeta.context;
                        if (routePutMeta.hasOwnProperty('channel')) respMeta.channel = routePutMeta.channel;
                        this.transmit({ "__routeput": respMeta });
                        // Answering "need" means chunks are on the way — register a
                        // coalescing entry so requestBlob() piggybacks on this stream.
                        if (!have) this._ensureBlobPending(cacheKey);
                    }
                    else if (messageType == "blob" && routePutMeta.hasOwnProperty("exists"))
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
                            var assembled = this.chunkBuffer[chunkBufferKey];
                            var assembledBytes = dataURIToBytes(assembled);
                            var blob = dataURItoBlob(assembled);
                            this.chunkBuffer.delete(chunkBufferKey);
                            // Remember this blob so we can answer future "do you have it?" queries
                            // and serve local requestBlob() calls without a round-trip.
                            this.blobCache.set(chunkBufferKey, { md5: md5Bytes(assembledBytes), size: assembledBytes.length, blob: blob });
                            if (this.onblob != undefined)
                            {
                                this.onblob(context, routePutMeta.name, blob);
                            }
                            // Resolve any coalesced requestBlob() waiters for this blob.
                            var pendingReq = this.pendingBlobRequests.get(chunkBufferKey);
                            if (pendingReq)
                            {
                                this.pendingBlobRequests.delete(chunkBufferKey);
                                pendingReq.resolve(blob);
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
                        this._hideDefaultAuthPrompt();
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
                        if (routePutMeta.response == "subscribe") this._hideDefaultAuthPrompt();
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
                                //This is where things get tricky, we want to merge objects but overwrite everything else.
                                if (typeof newValue === 'object')
                                    updateChannel.properties[key] = {...updateChannel.properties[key], ...newValue};
                                else
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
                                var key = update.key;
                                var newValue = update.new;
                                // if this property update refers to the local session.
                                if (update.id == this.connectionId)
                                {
                                    if (key.startsWith('_')) // make sure its a server key
                                    {
                                        if (this.debug)
                                        {
                                            console.log("setLocalSessionProperty(" + update.id + "): " + key + " = " + newValue);
                                        }
                                        //This is where things get tricky, we want to merge objects but overwrite everything else.
                                        if (typeof newValue === 'object')
                                            this.properties[key] = {...this.properties[key], ...newValue};
                                        else
                                           this.properties[key] = newValue;
                                        if (this.onpropertychange != undefined)
                                        {
                                            this.onpropertychange(key, newValue);
                                        }
                                    }
                                }
                                var members = this.getMembersMatching(update.id);
                                if (this.debug)
                                {
                                    console.log("setSessionProperty(" + update.id + "): " + key + " = " + newValue);
                                }
                                members.forEach((member, channel, map) => {
                                    //This is where things get tricky, we want to merge objects but overwrite everything else.
                                    if (typeof newValue === 'object')
                                        member.properties[key] = {...member.properties[key], ...newValue};
                                    else
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
                            if (routePutMeta.authRequired)
                            {
                                var affectedChannel = routePutMeta.channel || (channel && channel.name);
                                var isDefault = affectedChannel === this.defaultChannel.name;
                                if (isDefault)
                                {
                                    this.authBlocked = true;
                                    if (this.reconnectTimeout) { clearTimeout(this.reconnectTimeout); this.reconnectTimeout = null; }
                                    try { this.connection.close(); } catch (e) {}
                                }
                                if (this.onauthrequired) this.onauthrequired(affectedChannel, jsonObject.text);
                                else this._defaultAuthPrompt(affectedChannel, jsonObject.text);
                            }
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
              if (this.authBlocked) return;
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
                var payloadBytes = dataURIToBytes(reader.result);
                var md5 = md5Bytes(payloadBytes);
                var size = payloadBytes.length;
                this._sendBlobWithCheck({
                    channel: null,
                    context: context,
                    name: name,
                    md5: md5,
                    size: size,
                    chunks: chunks,
                    resolve: resolve,
                    reject: reject
                });
            };
            reader.onerror = () => {
                reject();
            };
            reader.readAsDataURL(blob);
        });
    }

    // Send a blob preceded by a "do you already have it?" blobCheck request to the
    // server. If the server replies state=have we skip chunk transmission; on
    // state=need we send the chunks.
    _sendBlobWithCheck(opts)
    {
        var queryMsgId = randomId();
        var meta = {
            "type": "request",
            "request": "blobCheck",
            "msgId": queryMsgId,
            "name": opts.name,
            "md5": opts.md5,
            "size": opts.size
        };
        if (opts.channel != null) meta.channel = opts.channel;
        if (opts.context != null) meta.context = opts.context;
        var query = { "__routeput": meta };

        var self = this;
        this.requests.set(queryMsgId, {
            "resolve": (respMeta) => {
                if (respMeta && respMeta.state == "have")
                {
                    if (self.debug) console.log("Routeput blob '" + opts.name + "' already on server, skipping chunks.");
                    opts.resolve({ "name": opts.name, "context": opts.context, "cached": true, "exists": true });
                }
                else
                {
                    self._transmitBlobChunks(opts);
                }
            },
            "reject": opts.reject,
            "request": query
        });
        this.transmit(query);
    }

    _transmitBlobChunks(opts)
    {
        var chunks = opts.chunks;
        var sz = chunks.length;
        var outboundMessageQueue = [];
        for (let i = 0; i < sz; i++)
        {
            var ipo = i+1;
            var mm = { "__routeput": { "type": "blob", "name": opts.name, "i": ipo, "of": sz, "data": chunks[i] } };
            if (opts.channel != null) mm.__routeput.channel = opts.channel;
            if (opts.context != null) mm.__routeput.context = opts.context;
            if (ipo == sz)
            {
                var finishMsgId = randomId();
                mm.__routeput['msgId'] = finishMsgId;
                var promHooks = { "resolve": opts.resolve, "reject": opts.reject, "request": mm };
                this.requests.set(finishMsgId, promHooks);
            }
            outboundMessageQueue.push(mm);
        }
        this.noLockTransmit(outboundMessageQueue, 0);
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
        var cacheKey = (context != null ? context : '') + ":" + name;
        var cached = this.blobCache.get(cacheKey);
        if (cached && cached.blob)
        {
            if (this.debug) console.log("Routeput requestBlob '" + name + "' served from local cache.");
            return Promise.resolve(cached.blob);
        }
        // Coalesce: if a fetch or server-initiated distribution for this blob is already
        // in flight, return the existing promise so we don't trigger overlapping chunk
        // streams that would corrupt chunkBuffer.
        var existing = this.pendingBlobRequests.get(cacheKey);
        if (existing) return existing.promise;

        var pending = this._ensureBlobPending(cacheKey);
        var mm = {"__routeput": {"msgId": randomId(), "type": "request", "request": "blob", "name": name, "context": context}};
        var self = this;
        this.makeRequest(mm).then(
            (result) => {
                // Chunk assembly already resolved pending in the Blob case. If the
                // server short-circuited with a metadata ack (cached case), fall back
                // to the cache or reject.
                if (!(result instanceof Blob))
                {
                    var still = self.pendingBlobRequests.get(cacheKey);
                    if (still === pending)
                    {
                        self.pendingBlobRequests.delete(cacheKey);
                        var late = self.blobCache.get(cacheKey);
                        if (late && late.blob) pending.resolve(late.blob);
                        else pending.reject(result);
                    }
                }
            },
            (err) => {
                var still = self.pendingBlobRequests.get(cacheKey);
                if (still === pending)
                {
                    self.pendingBlobRequests.delete(cacheKey);
                    pending.reject(err);
                }
            }
        );
        return pending.promise;
    }

    // Get or create the pending-blob entry for a given "context:name" key.
    _ensureBlobPending(cacheKey)
    {
        var existing = this.pendingBlobRequests.get(cacheKey);
        if (existing) return existing;
        var pending = {};
        pending.promise = new Promise((resolve, reject) => {
            pending.resolve = resolve;
            pending.reject = reject;
        });
        this.pendingBlobRequests.set(cacheKey, pending);
        return pending;
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
    
    subscribe(channel, password)
    {
        var meta = {"msgId": randomId(), "type": "request", "request":"subscribe", "channel": channel};
        if (password)
        {
            this.channelPasswords.set(channel, password);
            meta.password = password;
        }
        else
        {
            var stored = this.channelPasswords.get(channel);
            if (stored) meta.password = stored;
        }
        this.transmit({"__routeput": meta});
    }
    
    unsubscribe(channel)
    {
        this.transmit({"__routeput": {"msgId": randomId(), "type": "request", "request":"unsubscribe", "channel": channel}});
    }

    // Remember a password for a channel so it's applied on the next connect/subscribe.
    setChannelPassword(channelName, password)
    {
        if (password) this.channelPasswords.set(channelName, password);
        else this.channelPasswords.delete(channelName);
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

    // Default password prompt used when no `onauthrequired` handler is registered.
    // Reuses the same DOM node across retries so the user's typing isn't wiped.
    _defaultAuthPrompt(channelName, text)
    {
        if (typeof document === 'undefined' || !document.body) return;
        var modal = document.getElementById('__routeput_auth_modal') || this._buildAuthModal();
        var wasVisible = modal.style.display === 'flex';
        modal.__routeput_channel = channelName;
        var msg = modal.querySelector('.__routeput_auth_msg');
        if (msg) msg.textContent = text || ('Channel "' + channelName + '" requires a password.');
        modal.style.display = 'flex';
        if (!wasVisible)
        {
            var input = modal.querySelector('input');
            if (input) { input.value = ''; input.focus(); }
        }
    }

    _hideDefaultAuthPrompt()
    {
        if (typeof document === 'undefined') return;
        var modal = document.getElementById('__routeput_auth_modal');
        if (modal) modal.style.display = 'none';
    }

    _buildAuthModal()
    {
        var self = this;
        var modal = document.createElement('div');
        modal.id = '__routeput_auth_modal';
        modal.setAttribute('style', 'display:none;position:fixed;inset:0;background:rgba(0,0,0,0.5);z-index:2147483000;align-items:center;justify-content:center;font-family:sans-serif;');
        var box = document.createElement('div');
        box.setAttribute('style', 'background:white;padding:20px 24px;border-radius:6px;min-width:280px;box-shadow:0 4px 20px rgba(0,0,0,0.3);');
        box.innerHTML =
            '<div style="font-size:18px;font-weight:bold;margin-bottom:12px;">Channel password required</div>' +
            '<div class="__routeput_auth_msg" style="font-size:12px;color:#666;margin-bottom:12px;"></div>' +
            '<input type="password" style="width:100%;padding:8px;font-size:14px;box-sizing:border-box;" placeholder="Password" />' +
            '<div style="margin-top:14px;text-align:right;"><button type="button" style="padding:6px 14px;font-size:14px;">Sign in</button></div>';
        modal.appendChild(box);
        document.body.appendChild(modal);
        var input = modal.querySelector('input');
        var btn = modal.querySelector('button');
        var submit = function() {
            var pw = input.value;
            if (!pw) return;
            var ch = modal.__routeput_channel;
            self.setChannelPassword(ch, pw);
            modal.style.display = 'none';
            if (ch === self.defaultChannel.name) self.connect();
            else self.subscribe(ch, pw);
        };
        btn.addEventListener('click', submit);
        input.addEventListener('keydown', function(e) { if (e.key === 'Enter') submit(); });
        return modal;
    }
}
