import socket
import ssl
import json
import websocket
from threading import Thread
import random, string

class RouteputChannel():
    def __init__(self, name, connection):
        self.name = name
        self.connection = connection
        self.members = {}
    
    def transmit(self, message):
        if ('__routeput' in message):
            routeput_meta = message['__routeput']
            if not 'channel' in routeput_meta:
                routeput_meta['channel'] = self.name
        else:
            message['__routeput'] = {'channel': self.name}
        self.connection.transmit(message)

class RouteputRemoteSession():
    def __init__(self, connection_id):
        self.connection_id = connection_id

class RouteputConnection(Thread):
    def getChannel(self, name):
        if name in self.channels:
            return self.channels[name]
        else:
            self.channels[name] = RouteputChannel(name, self)
            return self.channels[name]

    def __init__(self, url, channel_name):
        super(RouteputConnection, self).__init__()
        self.callbacks = {}
        self.properties = {}
        self.channels = {}
        self.connected = False
        self.connection_id = ''.join(random.choice(string.ascii_uppercase + string.ascii_lowercase) for _ in range(10))
        self.default_channel = self.getChannel(channel_name)
        self.daemon = True
        self.cancelled = False
        self.url = url
        self.ws = websocket.WebSocketApp(self.url,
                    on_message = lambda ws,msg: self.on_ws_message(ws, msg),
                    on_error   = lambda ws,msg: self.on_ws_error(ws, msg),
                    on_close   = lambda ws:     self.on_ws_close(ws),
                    on_open    = lambda ws:     self.on_ws_open(ws))
        websocket.enableTrace(True)

    def trigger(self, event_name, *args, **kwargs):
        if self.callbacks is not None and event_name in self.callbacks:
            for callback in self.callbacks[event_name]:
                callback(self, *args, **kwargs)

    def on(self, event_name, callback):
        if self.callbacks is None:
            self.callbacks = {}
        if event_name not in self.callbacks:
            self.callbacks[event_name] = [callback]
        else:
            self.callbacks[event_name].append(callback)

    def run(self):
        while self.ws.run_forever(sslopt={"cert_reqs": ssl.CERT_NONE}):
            pass

    def on_ws_message(self, ws, message):
        msg = json.loads(message)
        routeput_meta = msg['__routeput']
        msg_type = None
        if 'type' in routeput_meta:
            msg_type = routeput_meta['type']

        print("Message Type: %s" % msg_type)
        if (msg_type == 'connectionId'):
            self.connection_id = routeput_meta['connectionId']
            self.properties = routeput_meta['properties']
            self.default_channel = self.getChannel(routeput_meta['channel'])
        elif (msg_type == 'ping'):
            self.transmit({'__routeput': {'type': 'pong', 'pingTimestamp': routeput_meta['timestamp']}})
        elif (msg_type == 'ConnectionStatus'):
            pass
        else:
            self.trigger("message", msg)

    def on_ws_error(self, ws, error):
        self.connected = False
        print(error)

    def on_ws_close(self, ws):
        self.connected = False
        print("### closed ###")

    def on_ws_open(self, ws):
        self.connected = True
        mm = {"__routeput": {
                                "type": "connectionId",
                                "channel": self.default_channel.name,
                                "properties": self.properties,
                                "connectionId": self.connection_id
                            }
             }
        self.transmit(mm)
        print("### opened ###")

    def transmit(self, message):
        if self.connected:
            if ('__routeput' in message):
                routeput_meta = message['__routeput']
                if not 'srcId' in routeput_meta:
                    routeput_meta['srcId'] = self.connection_id
            else:
                message['__routeput'] = {'srcId': self.connection_id}
            out_string = json.dumps(message)
            print("Transmit: %s" % (out_string))
            self.ws.send("%s" % (out_string))