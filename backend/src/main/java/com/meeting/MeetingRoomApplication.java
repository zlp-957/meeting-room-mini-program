package com.meeting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MeetingRoomApplication {
    public static void main(String[] args) {
        SpringApplication.run(MeetingRoomApplication.class, args);
        System.out.println("会议室预约系统后端启动成功");
    }
}
