package uz.chaqmoq.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"uz.chaqmoq.media", "uz.chaqmoq.common"})
@EntityScan("uz.chaqmoq.media.model")
@EnableJpaRepositories("uz.chaqmoq.media.model")
@EnableScheduling
@EnableAsync
public class MediaBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaBotApplication.class, args);
    }
}
