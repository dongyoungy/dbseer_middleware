#!/bin/bash
BIN_PATH=bin:lib/*
java -cp "$BIN_PATH" dbseer.middleware.server.MiddlewareServer $@
