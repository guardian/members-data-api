#!/bin/bash

source set-env.sh

cat > awslogs.conf <<__END__
[general]
state_file = /var/awslogs/state/agent-state
[$apptag-$stagetag]
datetime_format = %Y-%m-%d %H:%M:%S
file = /$apptag-1.0-SNAPSHOT/logs/$apptag.log
buffer_duration = 5000
log_stream_name = $apptag-$stagetag-$instanceid
initial_position = start_of_file
log_group_name = identity-$apptag-$stagetag
__END__

wget https://s3.amazonaws.com/aws-cloudwatch/downloads/latest/awslogs-agent-setup.py
python ./awslogs-agent-setup.py -n -r $region -c awslogs.conf
