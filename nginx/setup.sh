#!/bin/bash

GU_KEYS="${HOME}/.gu/keys"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NGINX_HOME=$(nginx -V 2>&1 | grep 'configure arguments:' | sed 's#.*conf-path=\([^ ]*\)/nginx\.conf.*#\1#g')

sudo mkdir -p $NGINX_HOME/sites-enabled
sudo ln -fs $DIR/members-data-api.conf $NGINX_HOME/sites-enabled/members-data-api.conf

cd  ${GU_KEYS}
openssl genrsa -out "members-data-api.key" 2048
openssl req -new -key "members-data-api.key" -out "members-data-api.csr"
openssl x509 -req -in "members-data-api.csr" -signkey "members-data-api.key" -out "members-data-api.crt"


sudo ln -fs ${GU_KEYS}/ $NGINX_HOME/keys

sudo nginx -s stop
sudo nginx

