package uz.chaqmoq.media.util;

import lombok.Getter;
import org.springframework.stereotype.Component;

@Component
public class PlatformDetector {

    @Getter
    public enum Platform {
        YOUTUBE("YouTube", "🎬"),
        TIKTOK("TikTok", "🎵"),
        INSTAGRAM("Instagram", "📸"),
        FACEBOOK("Facebook", "📘"),
        TWITTER("Twitter/X", "🐦"),
        PINTEREST("Pinterest", "📌"),
        UNSUPPORTED("Hozircha qo'llab-quvvatlanmaydi", "🚫"),
        UNKNOWN("Noma'lum", "❓");

        private final String name;
        private final String emoji;

        Platform(String name, String emoji) {
            this.name = name;
            this.emoji = emoji;
        }
    }

    public Platform detect(String url) {
        if (url == null || url.isBlank()) return Platform.UNKNOWN;
        String lower = url.toLowerCase();

        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return Platform.YOUTUBE;
        if (lower.contains("tiktok.com")) return Platform.TIKTOK;
        if (lower.contains("instagram.com")) return Platform.INSTAGRAM;
        if (lower.contains("facebook.com") || lower.contains("fb.watch")) return Platform.FACEBOOK;
        if (lower.contains("twitter.com") || lower.contains("x.com")) return Platform.TWITTER;
        if (lower.contains("pinterest.com") || lower.contains("pin.it")) return Platform.PINTEREST;
        if (lower.contains("snapchat.com")) return Platform.UNSUPPORTED;
        if (lower.contains("threads.net") || lower.contains("threads.com")) return Platform.UNSUPPORTED;
        if (lower.contains("likee.video") || lower.contains("l.likee.video") || lower.contains("likee.com")) return Platform.UNSUPPORTED;

        return Platform.UNKNOWN;
    }

    public boolean isValidUrl(String text) {
        return text != null && (text.startsWith("http://") || text.startsWith("https://"));
    }

    public boolean isSupported(String url) {
        Platform p = detect(url);
        return p != Platform.UNKNOWN && p != Platform.UNSUPPORTED;
    }
}
