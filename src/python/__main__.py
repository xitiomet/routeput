import time
from routeput import *

print('start program')

con = RouteputConnection('wss://openstatic.org/channel/', 'test')

con.properties = {"init": "property"}

def onmessage(connection, message):
    print("Event triggered")
    print(message)

con.on('message', onmessage)

con.start()

print('got here')

while True:
    con.default_channel.transmit({'hello': 'world'})
    time.sleep(5)