#!/bin/bash

sbt -Djava.awt.headless=true -jvm-debug 9997 "project membership-attribute-service" "devrun"
