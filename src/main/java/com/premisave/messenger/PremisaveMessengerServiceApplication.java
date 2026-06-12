package com.premisave.messenger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

import io.github.cdimascio.dotenv.Dotenv;


@SpringBootApplication
@EnableFeignClients
@EnableAsync
public class PremisaveMessengerServiceApplication {

	public static void main(String[] args) {
		Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        dotenv.entries().forEach(entry ->
                System.setProperty(entry.getKey(), entry.getValue()));
		SpringApplication.run(PremisaveMessengerServiceApplication.class, args);
		System.out.println("Premisave Messenger Service started successfully!");
	}

}
