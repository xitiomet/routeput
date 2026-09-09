'use strict';

// Node.js port of src/main/resources/routeput.js. Public surface mirrors the browser
// client: RouteputConnection / RouteputChannel / RouteputRemoteSession, plus the same
// event callbacks (onconnect, onmessage, onauthrequired, onpropertychange, onjoin,
// onleave, onchannelpropertychange, onmemberpropertychange, onblob). Blob transfer
// operates on Node Buffers: senders pass a Buffer (+ optional mime), receivers get a
// Buffer + mime in onblob and requestBlob() resolves to a Buffer.

const WebSocket = require('ws');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// Server chunk size is 4096 bytes of the base64-encoded data URI, per CLAUDE.md.
const BLOB_CHUNK_SIZE = 4096;

function randomId()
{
    const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
    let result = '';
    for (let i = 10; i > 0; --i) result += chars[Math.floor(Math.random() * chars.length)];
    return result;
}

function md5Hex(buffer)
{
    return crypto.createHash('md5').update(buffer).digest('hex');
}

function bufferToDataUri(buffer, mime)
{
    return 'data:' + (mime || 'application/octet-stream') + ';base64,' + buffer.toString('base64');
}

// Parse a full "data:<mime>[;...];base64,<payload>" string into { buffer, mime }.
function parseDataUri(dataUri)
{
    const comma = dataUri.indexOf(',');
    if (comma < 0) return { mime: 'application/octet-stream', buffer: Buffer.alloc(0) };
    const header = dataUri.substring(5, comma);
    const mime = header.split(';')[0] || 'application/octet-stream';
    const buffer = Buffer.from(dataUri.substring(comma + 1), 'base64');
    return { mime, buffer };
}

function chunkString(str, size)
{
    const n = Math.ceil(str.length / size);
    const chunks = new Array(n);
    for (let i = 0, o = 0; i < n; i++, o += size) chunks[i] = str.substr(o, size);
    return chunks;
}

// Minimal filename → mime map. Falls back to application/octet-stream.
const MIME_BY_EXT = {
    '.txt': 'text/plain', '.md': 'text/markdown', '.html': 'text/html',
    '.css': 'text/css', '.js': 'application/javascript', '.mjs': 'application/javascript',
    '.json': 'application/json', '.xml': 'application/xml', '.csv': 'text/csv',
    '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
    '.gif': 'image/gif', '.webp': 'image/webp', '.svg': 'image/svg+xml',
    '.bmp': 'image/bmp', '.ico': 'image/x-icon',
    '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.ogg': 'audio/ogg', '.flac': 'audio/flac',
    '.mp4': 'video/mp4', '.webm': 'video/webm', '.mov': 'video/quicktime',
    '.pdf': 'application/pdf', '.zip': 'application/zip', '.gz': 'application/gzip',
    '.tar': 'application/x-tar'
};

function mimeForFilename(name)
{
    const ext = path.extname(String(name || '')).toLowerCase();
    return MIME_BY_EXT[ext] || 'application/octet-stream';
}

function removeRouteputMeta(obj)
{
    if (obj !== null && obj !== undefined && typeof obj === 'object')
    {
        if (Array.isArray(obj)) return obj.map(removeRouteputMeta);
        const out = {};
        for (const [key, value] of Object.entries(obj))
        {
            if (key !== '__routeput') out[key] = removeRouteputMeta(value);
        }
        return out;
    }
    return obj;
}

function getPathValue(object, path)
{
    let ro;
    let pointer = object;
    if (path !== undefined && path !== '')
    {
        for (const key of String(path).split('.'))
        {
            if (pointer !== undefined && pointer !== null && Object.prototype.hasOwnProperty.call(pointer, key))
            {
                ro = pointer[key];
                pointer = (ro instanceof Object) ? ro : undefined;
            } else {
                ro = undefined;
                pointer = undefined;
                break;
            }
        }
    } else {
        ro = object;
    }
    if (ro instanceof Object) ro = removeRouteputMeta(ro);
    return ro;
}

class RouteputRemoteSession
{
    constructor(srcId, channelName, properties, routeputConnection)
    {
        this.srcId = srcId;
        this.channelName = channelName;
        this.properties = properties || {};
        this.routeputConnection = routeputConnection;
        this.connected = true;
        this.onpropertychange = undefined;
        this.onmessage = undefined;
    }

    transmit(routeputMessage)
    {
        if (routeputMessage && routeputMessage.__routeput)
        {
            routeputMessage.__routeput.channel = this.channelName;
            routeputMessage.__routeput.dstId = this.srcId;
        } else {
            routeputMessage.__routeput = { channel: this.channelName, dstId: this.srcId };
        }
        this.routeputConnection.transmit(routeputMessage);
    }
}

class RouteputChannel
{
    constructor(name, routeputConnection)
    {
        this.name = name;
        this.members = new Map();
        this.properties = {};
        this.routeputConnection = routeputConnection;
        this.onjoin = undefined;
        this.onleave = undefined;
        this.onchannelpropertychange = undefined;
        this.onmemberpropertychange = undefined;
        this.onmessage = undefined;
        this.onblob = undefined;
    }

    setProperty(k, v)
    {
        const old = Object.prototype.hasOwnProperty.call(this.properties, k) ? this.properties[k] : undefined;
        if (old === v) return;
        this.properties[k] = v;
        this.transmit({
            __routeput: {
                type: 'propertyChange',
                updates: [{ type: 'channel', id: this.name, key: k, old, new: v }]
            }
        });
    }

    setSessionProperty(k, v)
    {
        this.routeputConnection.setProperty(k, v);
    }

    getMembers()
    {
        return [...this.members.values()];
    }

    filterMembers(fn)
    {
        return this.getMembers().filter(fn);
    }

    transmit(routeputMessage)
    {
        if (routeputMessage && routeputMessage.__routeput)
        {
            routeputMessage.__routeput.channel = this.name;
            routeputMessage.__routeput.srcId = this.routeputConnection.connectionId;
        } else {
            routeputMessage.__routeput = { channel: this.name, srcId: this.routeputConnection.connectionId };
        }
        this.routeputConnection.transmit(routeputMessage);
    }

    // Broadcast a blob to the channel. `buffer` is a Node Buffer. Returns a Promise
    // that resolves once the server has ack'd receipt (or immediately if the server
    // already had this blob and the chunk stream was skipped).
    transmitBlob(name, buffer, mime)
    {
        return this.routeputConnection._transmitBlob({
            channel: this.name,
            name,
            buffer,
            mime
        });
    }

    // Read a file from disk and broadcast it to the channel. `options` may override
    // the transmitted name or mime type.
    transmitFile(filePath, options)
    {
        options = options || {};
        return fs.promises.readFile(filePath).then((buffer) => {
            const name = options.name || path.basename(filePath);
            const mime = options.mime || mimeForFilename(name);
            return this.transmitBlob(name, buffer, mime);
        });
    }

    // Fetch a blob stored under this channel's blob folder. Resolves to a Buffer.
    getBlob(name)
    {
        return this.routeputConnection._requestBlob(this.name, name);
    }

    // Ask the server for metadata (md5, size, etc.) for a blob in this channel.
    requestBlobInfo(name)
    {
        return this.routeputConnection._requestBlobInfo(this.name, name);
    }
}

class RouteputConnection
{
    // Options: { url, password, properties, connectionId, debug, autoReconnect, reconnectDelay }
    // `url` should be a ws:// or wss:// base URL (e.g. 'ws://localhost:6144'); the
    // client appends '/channel/'.
    constructor(channelName, options)
    {
        if (typeof options === 'string') options = { password: options };
        options = options || {};

        this.url = options.url || 'ws://localhost:6144';
        this.debug = !!options.debug;
        this.autoReconnect = options.autoReconnect !== false;
        this.reconnectDelay = options.reconnectDelay || 3000;

        this.properties = options.properties ? { ...options.properties } : {};
        this.channels = new Map();
        this.requests = new Map();
        this.channelPasswords = new Map();
        this.defaultChannel = new RouteputChannel(channelName, this);
        this.channels.set(channelName, this.defaultChannel);
        if (options.password) this.channelPasswords.set(channelName, options.password);

        // Blob state. chunkBuffer accumulates in-flight base64 data URIs keyed by
        // "channel:name"; blobCache holds fully assembled blobs so we can answer
        // "do you have this?" queries and satisfy requestBlob() without a round-trip;
        // pendingBlobRequests coalesces concurrent readers of the same blob.
        this.chunkBuffer = new Map();
        this.blobCache = new Map();
        this.pendingBlobRequests = new Map();
        this.receiveBlobs = options.receiveBlobs !== false;

        this.connectionId = options.connectionId;
        this.serverHostname = undefined;
        this.connection = null;
        this.reconnectTimeout = null;
        this._closed = false;

        this.onmessage = undefined;
        this.onblob = undefined;
        this.onconnect = undefined;
        this.onpropertychange = undefined;
        this.onauthrequired = undefined;
    }

    _wsUrl()
    {
        const base = String(this.url).replace(/\/+$/, '');
        return base + '/channel/';
    }

    getMembersMatching(connectionId)
    {
        const rv = new Map();
        this.channels.forEach((ch) => {
            if (ch.members.has(connectionId)) rv.set(ch, ch.members.get(connectionId));
        });
        return rv;
    }

    getChannel(channelName)
    {
        if (this.channels.has(channelName)) return this.channels.get(channelName);
        const ch = new RouteputChannel(channelName, this);
        this.channels.set(channelName, ch);
        return ch;
    }

    connect()
    {
        this._closed = false;
        try
        {
            if (this.reconnectTimeout) { clearTimeout(this.reconnectTimeout); this.reconnectTimeout = null; }
            this.connection = new WebSocket(this._wsUrl());
            this.connection.on('open', () => {
                if (this.debug) console.log('Routeput connected - ' + this._wsUrl());
                const meta = {
                    type: 'connectionId',
                    channel: this.defaultChannel.name,
                    properties: this.properties,
                    connectionId: this.connectionId
                };
                const pw = this.channelPasswords.get(this.defaultChannel.name);
                if (pw) meta.password = pw;
                this.transmit({ __routeput: meta });
            });
            this.connection.on('error', (err) => {
                if (this.debug) console.log('Routeput error - ' + (err && err.message ? err.message : err));
                try { this.connection.close(); } catch (_) {}
            });
            this.connection.on('close', () => {
                if (this._closed || !this.autoReconnect) return;
                this.reconnectTimeout = setTimeout(() => this.connect(), this.reconnectDelay);
            });
            this.connection.on('message', (data) => this._onMessage(data.toString()));
        } catch (err) {
            if (this.debug) console.log(err);
            if (!this._closed && this.autoReconnect)
            {
                this.reconnectTimeout = setTimeout(() => this.connect(), this.reconnectDelay);
            }
        }
    }

    close()
    {
        this._closed = true;
        if (this.reconnectTimeout) { clearTimeout(this.reconnectTimeout); this.reconnectTimeout = null; }
        if (this.connection)
        {
            try { this.connection.close(); } catch (_) {}
        }
    }

    _onMessage(rawData)
    {
        let jsonObject;
        try { jsonObject = JSON.parse(rawData); }
        catch (e) { if (this.debug) console.log('Routeput parse error: ' + e); return; }
        if (this.debug) console.log('Routeput Receive: ' + rawData);
        if (!jsonObject || !jsonObject.__routeput) return;
        const meta = jsonObject.__routeput;
        const srcId = meta.srcId;
        const messageType = meta.type;

        if (messageType === 'request' && meta.request === 'blobCheck')
        {
            const qChannel = Object.prototype.hasOwnProperty.call(meta, 'channel') ? meta.channel : '';
            const cacheKey = (qChannel == null ? '' : qChannel) + ':' + meta.name;
            const cached = this.blobCache.get(cacheKey);
            const have = !!(this.receiveBlobs === false
                || (cached && cached.md5 && String(cached.md5).toLowerCase() === String(meta.md5).toLowerCase() && cached.size === meta.size));
            const respMeta = {
                type: 'response',
                response: 'blobCheck',
                ref: meta.msgId,
                name: meta.name,
                md5: meta.md5,
                size: meta.size,
                state: have ? 'have' : 'need'
            };
            if (Object.prototype.hasOwnProperty.call(meta, 'channel')) respMeta.channel = meta.channel;
            this.transmit({ __routeput: respMeta });
            // Answering "need" means chunks are on the way — register a coalescing
            // entry so requestBlob() piggybacks on this stream.
            if (!have) this._ensureBlobPending(cacheKey);
            return;
        }
        if (messageType === 'blob' && Object.prototype.hasOwnProperty.call(meta, 'exists'))
        {
            if (Object.prototype.hasOwnProperty.call(meta, 'ref') && this.requests.has(meta.ref))
            {
                const hooks = this.requests.get(meta.ref);
                this.requests.delete(meta.ref);
                if (meta.exists) hooks.resolve(meta);
                else hooks.reject(meta);
            }
            return;
        }
        if (messageType === 'blob' && Object.prototype.hasOwnProperty.call(meta, 'i'))
        {
            if (this.receiveBlobs === false) return;
            const channelName = Object.prototype.hasOwnProperty.call(meta, 'channel') ? meta.channel : '';
            const cacheKey = (channelName == null ? '' : channelName) + ':' + meta.name;
            if (meta.i === 1)
            {
                this.chunkBuffer.set(cacheKey, meta.data);
            } else if (meta.i === meta.of) {
                const assembled = (this.chunkBuffer.get(cacheKey) || '') + meta.data;
                this.chunkBuffer.delete(cacheKey);
                const parsed = parseDataUri(assembled);
                this.blobCache.set(cacheKey, {
                    md5: md5Hex(parsed.buffer),
                    size: parsed.buffer.length,
                    buffer: parsed.buffer,
                    mime: parsed.mime
                });
                const chanObj = (typeof channelName === 'string' && channelName.length > 0) ? this.getChannel(channelName) : null;
                if (chanObj && typeof chanObj.onblob === 'function')
                {
                    try { chanObj.onblob(meta.name, parsed.buffer, parsed.mime); }
                    catch (e) { if (this.debug) console.log(e); }
                }
                if (typeof this.onblob === 'function')
                {
                    try { this.onblob(chanObj, meta.name, parsed.buffer, parsed.mime); }
                    catch (e) { if (this.debug) console.log(e); }
                }
                const pending = this.pendingBlobRequests.get(cacheKey);
                if (pending)
                {
                    this.pendingBlobRequests.delete(cacheKey);
                    pending.resolve(parsed.buffer);
                }
                if (Object.prototype.hasOwnProperty.call(meta, 'ref') && this.requests.has(meta.ref))
                {
                    const hooks = this.requests.get(meta.ref);
                    this.requests.delete(meta.ref);
                    hooks.resolve(parsed.buffer);
                }
            } else {
                this.chunkBuffer.set(cacheKey, (this.chunkBuffer.get(cacheKey) || '') + meta.data);
            }
            return;
        }
        if (messageType === 'connectionId')
        {
            const ch = this.getChannel(meta.channel);
            this.connectionId = meta.connectionId;
            this.properties = meta.properties || {};
            ch.properties = meta.channelProperties || {};
            this.serverHostname = meta.serverHostname;
            if (typeof this.onconnect === 'function')
            {
                try { this.onconnect(); }
                catch (e) { if (this.debug) console.log(e); }
            }
            for (const [key, value] of Object.entries(ch.properties))
            {
                if (this.debug) console.log('setChannelProperty at connect(' + ch.name + '): ' + key + ' = ' + JSON.stringify(value));
                if (typeof ch.onchannelpropertychange === 'function') ch.onchannelpropertychange(key, value);
            }
            return;
        }
        if (messageType === 'ping')
        {
            this.transmit({ __routeput: { type: 'pong', pingTimestamp: meta.timestamp } });
            return;
        }
        if (messageType === 'ConnectionStatus')
        {
            const ch = this.getChannel(meta.channel);
            if (meta.connected)
            {
                const member = new RouteputRemoteSession(srcId, meta.channel, meta.properties || {}, this);
                ch.members.set(srcId, member);
                if (typeof ch.onjoin === 'function') ch.onjoin(member);
                for (const [key, value] of Object.entries(member.properties))
                {
                    if (typeof member.onpropertychange === 'function') member.onpropertychange(key, value);
                    if (typeof ch.onmemberpropertychange === 'function') ch.onmemberpropertychange(member, key, value);
                }
            } else {
                const member = ch.members.get(srcId);
                if (member)
                {
                    member.connected = false;
                    ch.members.delete(srcId);
                    if (typeof ch.onleave === 'function') ch.onleave(member);
                }
            }
            return;
        }
        if (messageType === 'response')
        {
            if (Object.prototype.hasOwnProperty.call(meta, 'ref') && this.requests.has(meta.ref))
            {
                const hooks = this.requests.get(meta.ref);
                this.requests.delete(meta.ref);
                hooks.resolve(meta);
            }
            return;
        }
        if (messageType === 'propertyChange')
        {
            const updates = meta.updates || [];
            for (const update of updates)
            {
                if (update.type === 'channel')
                {
                    const ch = this.getChannel(update.id);
                    const key = update.key;
                    const newValue = update.new;
                    if (newValue !== null && typeof newValue === 'object' && !Array.isArray(newValue))
                        ch.properties[key] = { ...(ch.properties[key] || {}), ...newValue };
                    else
                        ch.properties[key] = newValue;
                    if (this.debug) console.log('setChannelProperty(' + ch.name + '): ' + key + ' = ' + JSON.stringify(newValue));
                    if (typeof ch.onchannelpropertychange === 'function') ch.onchannelpropertychange(key, newValue);
                }
                else if (update.type === 'session')
                {
                    const key = update.key;
                    const newValue = update.new;
                    if (update.id === this.connectionId && key.startsWith('_'))
                    {
                        if (newValue !== null && typeof newValue === 'object' && !Array.isArray(newValue))
                            this.properties[key] = { ...(this.properties[key] || {}), ...newValue };
                        else
                            this.properties[key] = newValue;
                        if (typeof this.onpropertychange === 'function') this.onpropertychange(key, newValue);
                    }
                    const members = this.getMembersMatching(update.id);
                    members.forEach((member, ch) => {
                        if (newValue !== null && typeof newValue === 'object' && !Array.isArray(newValue))
                            member.properties[key] = { ...(member.properties[key] || {}), ...newValue };
                        else
                            member.properties[key] = newValue;
                        if (typeof member.onpropertychange === 'function') member.onpropertychange(key, newValue);
                        if (typeof ch.onmemberpropertychange === 'function') ch.onmemberpropertychange(member, key, newValue);
                    });
                }
            }
            return;
        }

        // Application-level message
        const ch = this.getChannel(meta.channel);
        const member = ch.members.get(srcId);
        if (messageType === 'error')
        {
            if (meta.authRequired)
            {
                const affected = meta.channel || (ch && ch.name);
                if (typeof this.onauthrequired === 'function') this.onauthrequired(affected, jsonObject.text);
                else if (this.debug) console.log('Routeput auth required for channel ' + affected + ': ' + jsonObject.text);
            }
            if (Object.prototype.hasOwnProperty.call(meta, 'ref') && this.requests.has(meta.ref))
            {
                const hooks = this.requests.get(meta.ref);
                this.requests.delete(meta.ref);
                hooks.reject(meta);
            }
        }
        if (typeof this.onmessage === 'function') this.onmessage(member, messageType, jsonObject);
        if (typeof ch.onmessage === 'function') ch.onmessage(member, messageType, jsonObject);
        if (member && typeof member.onmessage === 'function') member.onmessage(member, messageType, jsonObject);
    }

    setProperty(k, v)
    {
        const old = Object.prototype.hasOwnProperty.call(this.properties, k) ? this.properties[k] : undefined;
        if (old === v) return;
        if (this.connectionId === undefined) return;
        this.properties[k] = v;
        this.transmit({
            __routeput: {
                type: 'propertyChange',
                updates: [{ type: 'session', id: this.connectionId, key: k, old, new: v }]
            }
        });
    }

    makeRequest(routeputMessage)
    {
        return new Promise((resolve, reject) => {
            if (!routeputMessage || !routeputMessage.__routeput) return reject('No routeput META');
            const meta = routeputMessage.__routeput;
            if (meta.msgId) this.requests.set(meta.msgId, { resolve, reject, request: routeputMessage });
            this.transmit(routeputMessage);
        });
    }

    transmit(routeputMessage)
    {
        if (routeputMessage.__routeput)
        {
            if (!routeputMessage.__routeput.srcId) routeputMessage.__routeput.srcId = this.connectionId;
        } else {
            routeputMessage.__routeput = { srcId: this.connectionId };
        }
        const out = JSON.stringify(routeputMessage);
        if (this.debug) console.log('Routeput Transmit: ' + out);
        if (!this.connection || this.connection.readyState !== WebSocket.OPEN) return;
        try { this.connection.send(out); }
        catch (err) { if (this.debug) console.log(err); }
    }

    subscribe(channel, password)
    {
        const meta = { msgId: randomId(), type: 'request', request: 'subscribe', channel };
        if (password)
        {
            this.channelPasswords.set(channel, password);
            meta.password = password;
        } else {
            const stored = this.channelPasswords.get(channel);
            if (stored) meta.password = stored;
        }
        this.transmit({ __routeput: meta });
    }

    unsubscribe(channel)
    {
        this.transmit({ __routeput: { msgId: randomId(), type: 'request', request: 'unsubscribe', channel } });
    }

    retryHandshake()
    {
        if (this.connection && this.connection.readyState === WebSocket.OPEN)
        {
            const meta = {
                type: 'connectionId',
                channel: this.defaultChannel.name,
                properties: this.properties,
                connectionId: this.connectionId
            };
            const pw = this.channelPasswords.get(this.defaultChannel.name);
            if (pw) meta.password = pw;
            this.transmit({ __routeput: meta });
        } else {
            this.connect();
        }
    }

    setChannelPassword(channelName, password)
    {
        if (password) this.channelPasswords.set(channelName, password);
        else this.channelPasswords.delete(channelName);
    }

    logError(text)   { this.transmit({ __routeput: { type: 'error' },   text }); }
    logInfo(text)    { this.transmit({ __routeput: { type: 'info' },    text }); }
    logWarning(text) { this.transmit({ __routeput: { type: 'warning' }, text }); }

    // Shared entry point for channel-scoped blob sends. Callers use
    // RouteputChannel.transmitBlob / transmitFile instead.
    _transmitBlob(opts)
    {
        if (!Buffer.isBuffer(opts.buffer)) return Promise.reject(new Error('transmitBlob requires a Buffer'));
        const buffer = opts.buffer;
        const mime = opts.mime || mimeForFilename(opts.name);
        const dataUri = bufferToDataUri(buffer, mime);
        const chunks = chunkString(dataUri, BLOB_CHUNK_SIZE);
        const md5 = md5Hex(buffer);
        const size = buffer.length;
        const cacheKey = (opts.channel == null ? '' : opts.channel) + ':' + opts.name;
        this.blobCache.set(cacheKey, { md5, size, buffer, mime });
        return new Promise((resolve, reject) => {
            this._sendBlobWithCheck({
                channel: opts.channel,
                name: opts.name,
                md5,
                size,
                chunks,
                resolve,
                reject
            });
        });
    }

    _sendBlobWithCheck(opts)
    {
        const queryMsgId = randomId();
        const meta = {
            type: 'request',
            request: 'blobCheck',
            msgId: queryMsgId,
            name: opts.name,
            md5: opts.md5,
            size: opts.size
        };
        if (opts.channel != null) meta.channel = opts.channel;
        const query = { __routeput: meta };
        const self = this;
        this.requests.set(queryMsgId, {
            resolve: (respMeta) => {
                if (respMeta && respMeta.state === 'have')
                {
                    if (self.debug) console.log("Routeput blob '" + opts.name + "' already on server, skipping chunks.");
                    opts.resolve({ name: opts.name, cached: true, exists: true });
                }
                else
                {
                    self._transmitBlobChunks(opts);
                }
            },
            reject: opts.reject,
            request: query
        });
        this.transmit(query);
    }

    _transmitBlobChunks(opts)
    {
        const chunks = opts.chunks;
        const total = chunks.length;
        let idx = 0;
        const sendOne = () => {
            if (idx >= total) return;
            const i = idx++;
            const ipo = i + 1;
            const mm = { __routeput: { type: 'blob', name: opts.name, i: ipo, of: total, data: chunks[i] } };
            if (opts.channel != null) mm.__routeput.channel = opts.channel;
            if (ipo === total)
            {
                const finishMsgId = randomId();
                mm.__routeput.msgId = finishMsgId;
                this.requests.set(finishMsgId, { resolve: opts.resolve, reject: opts.reject, request: mm });
            }
            this.transmit(mm);
            if (idx < total) setImmediate(sendOne);
        };
        sendOne();
    }

    // Internal: resolve to a Buffer for the blob stored under `channel:name`.
    // Callers use RouteputChannel.getBlob so they never touch raw channel keys.
    _requestBlob(channelName, name)
    {
        const cacheKey = (channelName == null ? '' : channelName) + ':' + name;
        const cached = this.blobCache.get(cacheKey);
        if (cached && cached.buffer)
        {
            if (this.debug) console.log("Routeput requestBlob '" + name + "' served from local cache.");
            return Promise.resolve(cached.buffer);
        }
        const existing = this.pendingBlobRequests.get(cacheKey);
        if (existing) return existing.promise;

        const pending = this._ensureBlobPending(cacheKey);
        const reqMeta = { msgId: randomId(), type: 'request', request: 'blob', name };
        if (channelName != null) reqMeta.channel = channelName;
        const mm = { __routeput: reqMeta };
        const self = this;
        this.makeRequest(mm).then(
            (result) => {
                if (!Buffer.isBuffer(result))
                {
                    const still = self.pendingBlobRequests.get(cacheKey);
                    if (still === pending)
                    {
                        self.pendingBlobRequests.delete(cacheKey);
                        const late = self.blobCache.get(cacheKey);
                        if (late && late.buffer) pending.resolve(late.buffer);
                        else pending.reject(result);
                    }
                }
            },
            (err) => {
                const still = self.pendingBlobRequests.get(cacheKey);
                if (still === pending)
                {
                    self.pendingBlobRequests.delete(cacheKey);
                    pending.reject(err);
                }
            }
        );
        return pending.promise;
    }

    _ensureBlobPending(cacheKey)
    {
        const existing = this.pendingBlobRequests.get(cacheKey);
        if (existing) return existing;
        const pending = {};
        pending.promise = new Promise((resolve, reject) => {
            pending.resolve = resolve;
            pending.reject = reject;
        });
        this.pendingBlobRequests.set(cacheKey, pending);
        return pending;
    }

    _requestBlobInfo(channelName, name)
    {
        const reqMeta = { msgId: randomId(), type: 'request', request: 'blobInfo', name };
        if (channelName != null) reqMeta.channel = channelName;
        const mm = { __routeput: reqMeta };
        return this.makeRequest(mm);
    }
}

module.exports = {
    RouteputConnection,
    RouteputChannel,
    RouteputRemoteSession,
    randomId,
    removeRouteputMeta,
    getPathValue,
    md5Hex,
    bufferToDataUri,
    parseDataUri,
    mimeForFilename,
    BLOB_CHUNK_SIZE
};
