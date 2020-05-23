#!/bin/bash
### BEGIN INIT INFO
# Provides:          routeput
# Required-Start:    $local_fs $remote_fs $network
# Required-Stop:     $local_fs $remote_fs $network
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: Route.put Websocket Server
# Description:       Start the Route.put Websocket server
#  This script will start the Route.put web server.
### END INIT INFO

PIDFILE=/var/run/routeput.pid
USER=www-data
GROUP=www-data
CWD=/usr/share/routeput
PROGRAM=/usr/bin/routeput
PROGRAM_ARGS=""

start() {
    echo -n "Starting Route.put Server...."
    start-stop-daemon --start --make-pidfile --pidfile $PIDFILE --chuid $USER --user $USER --group $GROUP --chdir $CWD --umask 0 --exec $PROGRAM --background -- $PROGRAM_ARGS
    echo DONE
}

stop() {
    echo -n "Stopping Route.put Server...."
    start-stop-daemon --stop --pidfile $PIDFILE --user $USER --exec $PROGRAM --retry=TERM/30/KILL/5
    echo DONE
}

status() {
    start-stop-daemon --start --test --oknodo --pidfile $PIDFILE --user $USER --exec $PROGRAM
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
       status
       ;;
    *)
       echo "Usage: $0 {start|stop|status|restart}"
esac

exit 0 
