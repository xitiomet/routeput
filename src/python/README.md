# Routeput Client library for python3


## Simple Example
```python
from routeput import *

# Initialize the connection object
con = RouteputConnection('wss://openstatic.org/channel/', 'sensorData')

# set your session properties
con.properties = {"init": "property"}

# Turn on or off debugging
con.debug = False

# create a handler for when a message is received on this channel
def onmessage(channel, message):
    print("Recieved on channel %s" % (channel.name))
    if ('Temperature' in message):
        print("Temperature: %s" % (message['Temperature']))
# assign callback for message event on default channel
con.default_channel.on('message', onmessage)

# Create a handler for when a user joins the channel
def onjoin(channel, member):
    print("Member Joined %s %s" % (channel.name, member.connection_id))
# assign callback for join event on default channel
con.default_channel.on('join', onjoin)

# Start the connection
con.start()

# Just keep running
while True:
    #con.default_channel.transmit({"__routeput": {"echo": True, "setChannelProperty": {"answer":"hello.world"}}, "hello": { "world": 55}})
    time.sleep(5)
```