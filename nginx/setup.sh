#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NGINX_HOME=$(nginx -V 2>&1 | grep 'configure arguments:' | sed 's#.*conf-path=\([^ ]*\)/nginx\.conf.*#\1#g')

printf "\nUsing NGINX_HOME=$NGINX_HOME\n\n"
printf "Note that you need to have already completed the Identity Platform setup:\n"
printf "https://github.com/guardian/identity-platform#setup-nginx-for-local-development\n\n"

sudo ln -fs $DIR/members-data-api.conf $NGINX_HOME/sites-enabled/members-data-api.conf

printf "\n\nNow restart Nginx!\n\n"
