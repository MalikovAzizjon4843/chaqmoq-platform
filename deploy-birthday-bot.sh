#!/bin/bash
set -e

mvn clean package -DskipTests -pl chaqmoq-birthday-bot -am
scp chaqmoq-birthday-bot/target/chaqmoq-birthday-bot-1.0.0.jar ${SERVER_USER}@${SERVER_HOST}:/opt/chaqmoq/birthday-bot.jar
ssh ${SERVER_USER}@${SERVER_HOST} "sudo systemctl restart chaqmoq-birthday-bot && sudo systemctl status chaqmoq-birthday-bot"

echo "Birthday bot deploy qilindi!"
