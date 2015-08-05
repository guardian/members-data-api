#!/bin/bash

source set-env.sh

adduser --home /$apptag-1.0-SNAPSHOT --disabled-password --gecos \"\" $apptag

aws s3 cp s3://gu-membership-attribute-service-dist/upstart.conf /etc/init/$apptag.conf
aws s3 cp s3://gu-membership-attribute-service-dist/$stacktag/$stagetag/$apptag/$apptag-1.0-SNAPSHOT.tgz /$apptag-1.0-SNAPSHOT/$apptag-1.0-SNAPSHOT.tgz
tar -xvzf /$apptag-1.0-SNAPSHOT/$apptag-1.0-SNAPSHOT.tgz
chown -R $apptag /$apptag-1.0-SNAPSHOT
sed -i "s/<APP>/$apptag/g" /etc/init/$apptag.conf
