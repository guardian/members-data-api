#!/bin/bash
# Setup Nginx proxies for local development with valid SSL

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

SITE_CONF=${SCRIPT_DIR}/members-data-api.conf

dev-nginx setup-cert members-data-api.thegulocal.com

dev-nginx link-config ${SITE_CONF}
dev-nginx restart-nginx