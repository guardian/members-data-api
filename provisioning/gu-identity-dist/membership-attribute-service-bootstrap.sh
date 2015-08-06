#!/bin/bash

source set-env.sh

adduser --home /$apptag --disabled-password --gecos \"\" $apptag

aws s3 cp s3://gu-membership-attribute-service-dist/upstart.conf /etc/init/$apptag.conf
aws s3 cp s3://gu-membership-attribute-service-dist/$stacktag/$stagetag/$apptag/app.zip /$apptag/$apptag.zip

unzip /$apptag/$apptag.zip -d /$apptag
chown -R $apptag /$apptag
sed -i "s/<APP>/$apptag/g" /etc/init/$apptag.conf
