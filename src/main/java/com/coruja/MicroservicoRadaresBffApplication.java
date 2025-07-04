package com.coruja;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class MicroservicoRadaresBffApplication {

	public static void main(String[] args) {
		SpringApplication.run(MicroservicoRadaresBffApplication.class, args);
	}

}
