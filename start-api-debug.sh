#!/bin/bash

cd $(dirname $0)
sbt -mem 4096 -Djava.awt.headless=true -jvm-debug 9997 "project membership-attribute-service" "devrun"
