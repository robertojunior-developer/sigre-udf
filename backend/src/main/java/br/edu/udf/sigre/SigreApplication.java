package br.edu.udf.sigre;

import br.edu.udf.sigre.service.SigreService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SigreApplication {
  public static void main(String[] args) {
    SpringApplication.run(SigreApplication.class, args);
  }

  @Bean
  CommandLineRunner adminBootstrap(SigreService service,
      @Value("${ADMIN_BOOTSTRAP_NAME:}") String nome,
      @Value("${ADMIN_BOOTSTRAP_EMAIL:}") String email,
      @Value("${ADMIN_BOOTSTRAP_PASSWORD:}") String senha) {
    return args -> {
      if (!email.isBlank() && !senha.isBlank()) {
        service.criarAdministradorBootstrap(nome.isBlank() ? "Administrador" : nome, email, senha);
      }
    };
  }
}
