package org.birthdaybot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"org.birthdaybot", "uz.chaqmoq.common"})
public class BirthdayBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(BirthdayBotApplication.class, args);
    }
}
