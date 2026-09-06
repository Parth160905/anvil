package com.anvilweb.config;

import com.anvilweb.engine.Anvil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class AnvilConfig {

    @Bean
    public Anvil anvil() throws IOException {
        String base = System.getProperty("java.io.tmpdir") + "/anvil-web";
        return new Anvil(base + "/anvil.wal", base + "/data");
    }
}
