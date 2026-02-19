#!/bin/bash
set -e

mvn clean package -DskipTests -pl chaqmoq-media-bot -am
scp chaqmoq-media-bot/target/chaqmoq-media-bot-1.0.0.jar ${SERVER_USER}@${SERVER_HOST}:/opt/chaqmoq/media-bot.jar
ssh ${SERVER_USER}@${SERVER_HOST} "sudo systemctl restart chaqmoq-media-bot && sudo systemctl status chaqmoq-media-bot"

echo "Media bot deploy qilindi!"
