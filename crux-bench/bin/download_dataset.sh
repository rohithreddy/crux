#!/usr/bin/env bash
cd $(dirname "$0")/..
mkdir data
curl -L https://timescaledata.blob.core.windows.net/datasets/devices_small.tar.gz | tar xz -C data
curl -L https://timescaledata.blob.core.windows.net/datasets/weather_small.tar.gz | tar xz -C data
cd -
