package br.edu.udf.sigre;

import static org.assertj.core.api.Assertions.assertThat;
import br.edu.udf.sigre.dto.Dtos.FormsSolicitacaoRequest;
import br.edu.udf.sigre.dto.Dtos.SolicitacaoDataRequest;
import br.edu.udf.sigre.service.SigreService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sigre;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
class SigreApplicationTests {
  @Autowired SigreService service;

  @Test
  void contextLoads() {
    assertThat(service).isNotNull();
  }

  @Test
  void requestDtoSupportsMultipleDates() {
    var request = new FormsSolicitacaoRequest("resp-1", "Aluno Teste", "aluno@udf.edu.br", "2026001", null, "Enfermagem", null, null, List.of(new SolicitacaoDataRequest(LocalDate.now(), "MANHA")));
    assertThat(request.datas()).hasSize(1);
  }
}
