'use strict';

// Simple demo: connect to a routeput server, subscribe to a channel, log every
// inbound message, and broadcast a "tick" message every few seconds. Also
// demonstrates the blob API — pass a file path as the second argument to send it,
// and any blob received from other members is written to ./received/.
//
// Usage:
//   node example.js [channel] [fileToSend]
//   ROUTEPUT_URL=ws://localhost:6144 node example.js lobby ./picture.png

const fs = require('fs');
const path = require('path');
const { RouteputConnection } = require('./routeput-node');

const channelName = process.argv[2] || 'lobby';
const fileToSend = process.argv[3];
const url = process.env.ROUTEPUT_URL || 'ws://localhost:6144';
const password = process.env.ROUTEPUT_PASSWORD || undefined;
const interval = Number(process.env.ROUTEPUT_INTERVAL || 3000);
const receivedDir = path.resolve(__dirname, 'received');

const conn = new RouteputConnection(channelName, {
    url,
    password,
    debug: process.env.ROUTEPUT_DEBUG === '1',
    properties: {
        clientType: 'node-example',
        pid: process.pid,
        nodeVersion: process.version
    }
});

conn.onconnect = () => {
    console.log(`[connected] id=${conn.connectionId} channel="${channelName}" url=${url}`);
    if (fileToSend) sendFile(fileToSend);
};

conn.onauthrequired = (ch, reason) => {
    console.error(`[auth-required] channel="${ch}" reason="${reason}"`);
    console.error('Set ROUTEPUT_PASSWORD=<password> and try again.');
    conn.close();
    process.exit(1);
};

conn.onmessage = (member, type, msg) => {
    const from = (member && member.srcId) || (msg.__routeput && msg.__routeput.srcId) || '?';
    const payload = { ...msg };
    delete payload.__routeput;
    console.log(`[recv] type=${type} from=${from} :: ${JSON.stringify(payload)}`);
};

conn.onblob = (channel, name, buffer, mime) => {
    const chName = channel ? channel.name : '(no channel)';
    console.log(`[blob] channel="${chName}" name="${name}" size=${buffer.length} mime=${mime}`);
    try {
        fs.mkdirSync(receivedDir, { recursive: true });
        const outPath = path.join(receivedDir, name);
        fs.writeFileSync(outPath, buffer);
        console.log(`[blob] saved to ${outPath}`);
    } catch (e) {
        console.error('[blob] save failed:', e.message);
    }
};

conn.defaultChannel.onjoin = (member) => {
    console.log(`[join]  ${member.srcId} joined "${member.channelName}"`);
};
conn.defaultChannel.onleave = (member) => {
    console.log(`[leave] ${member.srcId} left "${member.channelName}"`);
};
conn.defaultChannel.onchannelpropertychange = (key, value) => {
    console.log(`[chprop] ${key} = ${JSON.stringify(value)}`);
};

conn.connect();

let counter = 0;
const timer = setInterval(() => {
    if (!conn.connectionId) return;
    counter++;
    const message = {
        text: `Hello from node example #${counter}`,
        counter,
        ts: Date.now(),
        __routeput: { type: 'chat' }
    };
    console.log(`[send]  ${message.text}`);
    conn.defaultChannel.transmit(message);
}, interval);

function sendFile(filePath)
{
    console.log(`[blob-send] uploading ${filePath} to channel "${channelName}"...`);
    conn.defaultChannel.transmitFile(filePath).then((result) => {
        console.log(`[blob-send] done`, result.cached ? '(already cached on server)' : '');
    }, (err) => {
        console.error('[blob-send] failed:', err && err.message ? err.message : err);
    });
}

function shutdown()
{
    console.log('\n[shutdown] closing connection');
    clearInterval(timer);
    conn.close();
    setTimeout(() => process.exit(0), 100);
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
