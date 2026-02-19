package uz.chaqmoq.media.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "media_bot_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotUser {

    @Id
    private Long telegramId;

    private String username;

    private String firstName;

    private String lastName;

    @Builder.Default
    private String language = "uz";

    @Builder.Default
    private boolean banned = false;

    @Builder.Default
    private int downloadCount = 0;

    private LocalDateTime createdAt;

    private LocalDateTime lastActive;

    @Builder.Default
    private String state = "IDLE";

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastActive = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        lastActive = LocalDateTime.now();
    }
}
