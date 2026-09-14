@echo off
echo Nuking old version!
del /S /Q %HOMEDRIVE%%HOMEPATH%\.m2\repository\org\openstatic\routeput\
mvn clean install -U
