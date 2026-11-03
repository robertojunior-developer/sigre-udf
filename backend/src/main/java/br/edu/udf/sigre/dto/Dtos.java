package br.edu.udf.sigre.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class Dtos {
  private Dtos() {}
  public record AgendaItem(UUID id, LocalDate data, String turno, LocalTime horario, String local, String curso, int capacidadeMaxima, int vagasOcupadas, int vagasDisponiveis, String situacao) {}
  public record SolicitacaoDataRequest(@NotNull LocalDate data, @NotBlank String turno) {}
  public record FormsSolicitacaoRequest(@NotBlank String responseId, @NotBlank String nome, @Email String email, @NotBlank String matricula, String cpf, @NotBlank String curso, String turma, String professor, @NotEmpty List<SolicitacaoDataRequest> datas) {}
  public record AlunoSolicitacaoRequest(@NotBlank String nome, @Email String email, @NotBlank String matricula, String cpf, @NotBlank String curso, String turma, String professor, @NotBlank String motivo, @AssertTrue Boolean aceiteTermo, @NotEmpty List<SolicitacaoDataRequest> datas) {}
  public record SolicitacaoCriadaResponse(UUID id, String protocolo, String status) {}
  public record Dashboard(long total, long pendentes, long aguardandoDocumentacao, long aprovadas, long rejeitadas, long vagasDisponiveis, long vagasOcupadas, long emailsEnviados, long emailsFalha) {}
  public record SolicitacaoResumo(UUID id, String protocolo, String aluno, String matricula, String curso, String status, LocalDate criadoEm) {}
  public record ConfirmadoItem(LocalDate data, String turno, String aluno, String matricula, String curso, String turma) {}
  public record RelatorioReposicaoItem(String aluno, String matricula, String curso, String turno, LocalDate data, String status, String protocolo) {}
  public record AlunoConsultaItem(String protocolo, LocalDate data, String turno, String status, String justificativa, String curso) {}
  public record SolicitacaoDataResumo(UUID id, LocalDate data, String turno, String status, UUID vagaReposicaoId) {}
  public record DocumentoResumo(UUID id, String nomeArquivo, String contentType, String storageKey, String criadoEm) {}
  public record SolicitacaoDetalhe(UUID id, String protocolo, String aluno, String email, String matricula, String curso, String status, String justificativa, String criadoEm, List<SolicitacaoDataResumo> datas, List<DocumentoResumo> documentos) {}
  public record CadastroRequest(@NotBlank String nome, String email, Long cursoId, String turno) {}
  public record CadastroItem(Long id, String nome, String email, Long cursoId, String curso, String turno) {}
  public record VagaRequest(@NotNull LocalDate data, @NotBlank String turno, @NotNull LocalTime horario, @NotBlank String local, Long cursoId, @PositiveOrZero int capacidadeMaxima, @NotBlank String situacao) {}
  public record VagaResumo(UUID id, LocalDate data, String turno, LocalTime horario, String local, String curso, int capacidadeMaxima, int ocupadas, int disponiveis, String situacao) {}
  public record AuditoriaItem(Long id, String acao, String entidade, String entidadeId, String criadoEm) {}
  public record DecisaoRequest(@NotEmpty List<UUID> solicitacaoDataIds, String justificativa) {}
  public record AdminSolicitacaoUpdateRequest(String status, String justificativa, @NotEmpty List<SolicitacaoDataRequest> datas) {}
  public record NovoAdminRequest(@NotBlank String nome, @Email String email, @NotBlank String senha) {}
}
