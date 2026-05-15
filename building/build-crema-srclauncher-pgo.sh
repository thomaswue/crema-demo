#!/bin/bash
rm -f donut.iprof rainbow.iprof bf.iprof
./build-crema-srclauncher.sh --pgo-instrument -o crema-srclauncher.pgo-instrument
echo "Collecting profiles:"
echo "Donut..."
timeout 2s ./crema-srclauncher.pgo-instrument -XX:ProfilesDumpFile=donut.iprof -Djava.home=$JAVA_HOME Donut.java > /dev/null
echo "Rainbow..."
timeout 2s ./crema-srclauncher.pgo-instrument -XX:ProfilesDumpFile=rainbow.iprof -Djava.home=$JAVA_HOME Rainbow.java > /dev/null
echo "BF..."
./crema-srclauncher.pgo-instrument -XX:ProfilesDumpFile=bf.iprof -Djava.home=$JAVA_HOME  BF.java
echo "Building pgo-optimized image:"
./build-crema-srclauncher.sh --pgo=donut.iprof,rainbow.iprof,bf.iprof -o crema-srclauncher.pgo
