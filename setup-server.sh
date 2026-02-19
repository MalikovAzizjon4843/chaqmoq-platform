#!/bin/bash
set -e

echo "=== Chaqmoq Platform Server Setup ==="

# yt-dlp va ffmpeg
sudo apt update
sudo apt install -y python3 python3-pip ffmpeg curl
sudo pip3 install yt-dlp --break-system-packages

# Java 17
sudo apt install -y openjdk-17-jre-headless

# Temp papka
sudo mkdir -p /tmp/chaqmoq && sudo chmod 777 /tmp/chaqmoq

# App papkalari
sudo mkdir -p /opt/chaqmoq
sudo chown $USER:$USER /opt/chaqmoq

# Versiyalarni tekshirish
echo "yt-dlp: $(yt-dlp --version)"
echo "ffmpeg: $(ffmpeg -version | head -1)"
echo "java: $(java -version 2>&1 | head -1)"

echo "=== Setup tugadi! ==="
