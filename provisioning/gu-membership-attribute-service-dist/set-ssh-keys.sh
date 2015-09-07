#!/bin/bash -ev

aws s3 cp s3://membership-dist/membership/authorized_keys /home/ubuntu/aws_authorized_keys
cat /home/ubuntu/aws_authorized_keys >> /home/ubuntu/.ssh/authorized_keys

chown ubuntu:ubuntu "/home/ubuntu/.ssh/authorized_keys"
chmod 400 "/home/ubuntu/.ssh/authorized_keys"
