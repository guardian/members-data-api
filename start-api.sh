#!/bin/bash

cd $(dirname $0)
sbt -mem 2048 -Djava.awt.headless=true "project membership-attribute-service" "devrun"
