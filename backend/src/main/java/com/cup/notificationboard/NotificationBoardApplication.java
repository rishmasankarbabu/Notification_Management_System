package com.cup.notificationboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;

@SpringBootApplication
@EnableJms
public class NotificationBoardApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationBoardApplication.class, args);
    }
}
