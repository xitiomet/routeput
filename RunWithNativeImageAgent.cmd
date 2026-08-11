@echo off
java -agentlib:native-image-agent=config-merge-dir=src\main\resources\META-INF\native-image\ -jar target\routeput-2.0.jar %*
    