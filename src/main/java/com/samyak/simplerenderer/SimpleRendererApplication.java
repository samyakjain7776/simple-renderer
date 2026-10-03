package com.samyak.simplerenderer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.samyak.simplerenderer")
public class SimpleRendererApplication {
  public static void main(String[] args) {
    SpringApplication.run(SimpleRendererApplication.class, args);
  }
}
