package com.pnltracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.pnltracker.config.PortfolioProperties;

@SpringBootApplication
@EnableConfigurationProperties(PortfolioProperties.class)
public class PnlTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PnlTrackerApplication.class, args);
    }
}
