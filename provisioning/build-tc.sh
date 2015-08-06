#!/bin/bash

set -ex

rm -Rf target

mkdir target

cp provisioning/deploy.json target/

cp -R provisioning target/packages

cd target

zip -r artifacts.zip *
