#!/bin/bash

source set-env.sh

adduser --home /$apptag --disabled-password --gecos \"\" $apptag

secrettag=$(aws ec2 describe-tags --filters "Name=resource-id,Values=$instanceid" "Name=resource-type,Values=instance" "Name=key,Values=PlayAppSecret" --region $region | grep -oP "(?<=\"Value\": \")[^\"]+")


aws s3 cp s3://gu-membership-attribute-service-dist/upstart.conf /etc/init/$apptag.conf
aws s3 cp s3://gu-membership-attribute-service-dist/$stacktag/$stagetag/$apptag/app.zip /$apptag/$apptag.zip

unzip /$apptag/$apptag.zip -d /$apptag
chown -R $apptag /$apptag
sed -i "s/<APP>/$apptag/g" /etc/init/$apptag.conf
sed -i "s/<Stage>/$stagetag/g" /etc/init/$apptag.conf
sed -i "s/<PLAY_APP_SECRET>/$secrettag/g" /etc/init/$apptag.conf
