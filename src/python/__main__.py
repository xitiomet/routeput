import time
from routeput import *


con = RouteputConnection('wss://openstatic.org/channel/', 'sensorData')

con.properties = {"init": "property"}
con.debug = False

def onmessage(channel, message):
    print("Recieved on channel %s" % (channel.name))
    if ('Temperature' in message):
        print("Temperature: %s" % (message['Temperature']))

con.default_channel.on('message', onmessage)

def onjoin(channel, member):
    print("Member Joined %s %s" % (channel.name, member.connection_id))

con.default_channel.on('join', onjoin)

con.start()

while True:
    #con.default_channel.transmit({"__routeput": {"echo": True, "setChannelProperty": {"answer":"hello.world"}}, "hello": { "world": 55}})
    time.sleep(5)