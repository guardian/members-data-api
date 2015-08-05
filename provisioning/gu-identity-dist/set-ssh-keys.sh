#!/usr/bin/env bash

wget -N --directory-prefix=/home/ubuntu/.ssh https://s3-eu-west-1.amazonaws.com/identity-federation-api-dist/identity/authorized_keys

chown ubuntu:ubuntu "/home/ubuntu/.ssh/authorized_keys"
chmod 400 "/home/ubuntu/.ssh/authorized_keys"
