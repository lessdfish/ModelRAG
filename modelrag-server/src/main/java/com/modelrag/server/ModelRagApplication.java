package com.modelrag.server;
import org.springframework.boot.SpringApplication; import org.springframework.boot.autoconfigure.SpringBootApplication; import org.springframework.scheduling.annotation.EnableAsync; import org.springframework.scheduling.annotation.EnableScheduling;
@EnableAsync @EnableScheduling @SpringBootApplication(scanBasePackages="com.modelrag") public class ModelRagApplication {public static void main(String[] args){SpringApplication.run(ModelRagApplication.class,args);}}
