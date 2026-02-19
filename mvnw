#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

if [ -z "$MAVEN_BASEDIR" ]; then
  MAVEN_BASEDIR=$(cd "$(dirname "$0")" && pwd)
fi

WRAPPER_JAR="$MAVEN_BASEDIR/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_PROPERTIES="$MAVEN_BASEDIR/.mvn/wrapper/maven-wrapper.properties"
WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain

if [ -r "$MAVEN_BASEDIR/.mvn/jvm.config" ]; then
  JVM_CONFIG=$(cat "$MAVEN_BASEDIR/.mvn/jvm.config")
fi

if [ ! -f "$WRAPPER_JAR" ]; then
  if [ -r "$WRAPPER_PROPERTIES" ]; then
    WRAPPER_URL=$(grep "wrapperUrl" "$WRAPPER_PROPERTIES" | cut -d'=' -f2-)
  fi
  if [ -z "$WRAPPER_URL" ]; then
    WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
  fi
  echo "Downloading Maven wrapper from $WRAPPER_URL"
  if command -v wget > /dev/null; then
    wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL"
  elif command -v curl > /dev/null; then
    curl -sL -o "$WRAPPER_JAR" "$WRAPPER_URL"
  else
    echo "Error: Cannot download maven-wrapper.jar. Please install wget or curl."
    exit 1
  fi
fi

if [ -z "$JAVA_HOME" ] ; then
  JAVACMD=$(command -v java)
else
  JAVACMD="$JAVA_HOME/bin/java"
fi

if [ ! -x "$JAVACMD" ] ; then
  echo "Error: JAVA_HOME is not defined correctly or java is not in PATH." >&2
  exit 1
fi

exec "$JAVACMD" \
  $JVM_CONFIG \
  $MAVEN_OPTS \
  -classpath "$WRAPPER_JAR" \
  "-Dmaven.multiModuleProjectDirectory=$MAVEN_BASEDIR" \
  $WRAPPER_LAUNCHER $MAVEN_CONFIG "$@"
