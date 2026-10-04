@echo off
mvn compile exec:java -Dexec.mainClass="org.openstatic.routeput.RoutePutMain" -Dexec.args="-p 6144"