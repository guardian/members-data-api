#!/bin/bash
#Clean up legacy config
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NGINX_HOME=$(nginx -V 2>&1 | grep 'configure arguments:' | sed 's#.*conf-path=\([^ ]*\)/nginx\.conf.*#\1#g')
sudo rm -f $NGINX_HOME/sites-enabled/members-data-api.conf

# Setup Nginx proxies for local development with valid SSL
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

SITE_CONF=${SCRIPT_DIR}/members-data-api.conf

dev-nginx setup-cert members-data-api.thegulocal.com

dev-nginx link-config ${SITE_CONF}
dev-nginx restart-nginx