#!/bin/bash
# chkconfig: 2345 20 80
# description: Route.put Websocket Server

PIDFILE=/var/run/routeput.pid
USER=www-data
GROUP=www-data
CWD=/var/www
JVM_ARGS=
JAR_PATH=/usr/share/java/routeput.jar
PROGRAM=/usr/bin/java
PROGRAM_ARGS="$JVM_ARGS -jar $JAR_PATH"

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
