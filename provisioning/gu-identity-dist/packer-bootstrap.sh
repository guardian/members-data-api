#!/bin/bash

PACKER_VERSION=packer_0.8.1_linux_amd64.zip
LOGGER=/tmp/packer.log
TOPIC=arn:aws:sns:eu-west-1:942464564246:Packer-PackerLogsTopic-1FY28BPRYC1Q7
SNS_SUBJECT="Subject: Packer.IO AMI Build Summary `date +%d-%m-%Y`"

REGION=`curl -s http://169.254.169.254/latest/meta-data/placement/availability-zone |sed 's/.$//'`
ASGNAME=`aws autoscaling describe-auto-scaling-groups --region $REGION | grep "AutoScalingGroupName.*Packer" |sed -e 's/^.*: *"\([^"]*\)".*$/\1/'`

#Install Packer.io
apt-get -y install unzip
wget https://dl.bintray.com/mitchellh/packer/$PACKER_VERSION
unzip $PACKER_VERSION

#Build the AMI
aws s3 cp s3://gu-identity-packer-dist/packer-ami-java8-hvm.json .
time ./packer build packer-ami-java8-hvm.json >> $LOGGER 2>&1

#Send the build log via email
SNS_MESSAGE=$(cat $LOGGER)
aws sns publish --topic-arn "$TOPIC" --message "$SNS_MESSAGE" --subject "$SNS_SUBJECT" --region $REGION

#Terminate this instance
aws autoscaling update-auto-scaling-group --auto-scaling-group-name $ASGNAME --desired-capacity 0 --region $REGION
