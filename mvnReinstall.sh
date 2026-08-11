#!/bin/bash
echo Nuking old version!
rm -rf ~/.m2/repository/org/openstatic/routeput/
mvn install
