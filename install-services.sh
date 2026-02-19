#!/bin/bash

sudo cp systemd/chaqmoq-media-bot.service /etc/systemd/system/
sudo cp systemd/chaqmoq-birthday-bot.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable chaqmoq-media-bot chaqmoq-birthday-bot

echo "Servicelar o'rnatildi! Endi /etc/environment ga tokenlarni qo'yib:"
echo "sudo systemctl start chaqmoq-media-bot"
echo "sudo systemctl start chaqmoq-birthday-bot"
