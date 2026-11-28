#!/bin/bash
# Runs inside LocalStack once S3 is ready: creates the documents bucket, then signals readiness.
set -euo pipefail
awslocal s3 mb s3://documents
touch /tmp/init-done
