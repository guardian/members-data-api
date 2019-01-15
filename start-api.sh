#!/bin/bash

cd $(dirname $0)
sbt -Djava.awt.headless=true "project membership-attribute-service" "devrun"
