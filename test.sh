#!/bin/sh

lein run test \
  --nodes-file ~/nodes \
  --username admin \
  --time-limit 60
