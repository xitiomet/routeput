#!/bin/bash
# chkconfig: 2345 20 80
# description: Route.put Websocket Server

start() {
    echo -n "Starting Route.put Server...."
    /usr/bin/routeput 2>&1 > /dev/null &
    echo DONE
}

stop() {
    echo -n "Stopping Route.put Server...."
    kill -9 $(ps aux | grep routeput | grep java | awk '{print $2}')
    echo DONE
}

case "$1" in 
    start)
       start
       ;;
    stop)
       stop
       ;;
    restart)
       stop
       start
       ;;
    status)
       # code to check status of app comes here 
       # example: status program_name
       ;;
    *)
       echo "Usage: $0 {start|stop|status|restart}"
esac

exit 0 
