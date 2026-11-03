package br.edu.udf.sigre.controller;

import br.edu.udf.sigre.dto.Dtos.*;
import br.edu.udf.sigre.service.SigreService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiControllers {
  private final SigreService service;

  public ApiControllers(SigreService service) {
    this.service = service;
  }

  @GetMapping("/public/agenda")
  List<AgendaItem> agenda(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
      @RequestParam(required = false) String turno,
      @RequestParam(required = false) Long cursoId) {
    return service.agenda(inicio, fim, turno, cursoId);
  }

  @GetMapping("/public/cursos")
  List<CadastroItem> cursosPublicos() {
    return service.cursos();
  }

  @PostMapping("/forms/solicitacoes")
  SolicitacaoCriadaResponse forms(@RequestHeader("X-Forms-Token") String token, @RequestBody @Valid FormsSolicitacaoRequest request) {
    service.validarTokenForms(token);
    return service.registrarForms(request);
  }

  @PostMapping("/aluno/solicitacoes")
  SolicitacaoCriadaResponse criarSolicitacaoAluno(@RequestBody @Valid AlunoSolicitacaoRequest request) {
    return service.registrarSolicitacaoAluno(request);
  }

  @GetMapping("/aluno/solicitacoes")
  List<AlunoConsultaItem> consultarSolicitacoesAluno(@RequestParam String matricula) {
    return service.consultarAlunoPorMatricula(matricula);
  }

  @PostMapping("/aluno/solicitacoes/{id}/documentos")
  Map<String, UUID> uploadDocumentoAluno(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
    return Map.of("id", service.registrarDocumento(id, file));
  }

  @PostMapping("/admin/usuarios")
  Map<String, UUID> criarAdministrador(@RequestBody @Valid NovoAdminRequest request) {
    return Map.of("id", service.criarAdministrador(request));
  }

  @GetMapping("/admin/dashboard")
  Dashboard dashboard() {
    return service.dashboard();
  }

  @GetMapping("/admin/solicitacoes")
  List<SolicitacaoResumo> solicitacoes(@RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
      @RequestParam(required = false) String ordenacao,
      @RequestParam(required = false) String busca) {
    return service.solicitacoes(status, inicio, fim, ordenacao, busca);
  }

  @GetMapping("/admin/confirmados")
  List<ConfirmadoItem> confirmados(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
    return service.confirmados(inicio, fim);
  }

  @GetMapping("/admin/relatorio-reposicao")
  List<RelatorioReposicaoItem> relatorioReposicao(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String turno) {
    return service.relatorioReposicao(inicio, fim, status, turno);
  }

  @GetMapping("/admin/solicitacoes/{id}")
  SolicitacaoDetalhe solicitacaoDetalhe(@PathVariable UUID id) {
    return service.solicitacaoDetalhe(id);
  }

  @PostMapping("/admin/solicitacoes/{id}/documentos")
  Map<String, UUID> uploadDocumento(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
    return Map.of("id", service.registrarDocumento(id, file));
  }

  @GetMapping("/admin/documentos/{id}/conteudo")
  ResponseEntity<byte[]> documentoConteudo(@PathVariable UUID id) {
    Map<String, Object> doc = service.documentoConteudo(id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType((String) doc.get("contentType")))
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.get("nome") + "\"")
        .body((byte[]) doc.get("conteudo"));
  }

  @PutMapping("/admin/solicitacoes/{id}")
  ResponseEntity<Void> atualizarSolicitacaoAdmin(@PathVariable UUID id, @RequestBody @Valid AdminSolicitacaoUpdateRequest request) {
    service.atualizarSolicitacaoAdmin(id, request);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/admin/solicitacoes/{id}")
  ResponseEntity<Void> excluirSolicitacaoAdmin(@PathVariable UUID id) {
    service.excluirSolicitacaoAdmin(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/solicitacoes/{id}/solicitar-documentacao")
  ResponseEntity<Void> solicitarDocumentacao(@PathVariable UUID id, @RequestBody DecisaoRequest request) {
    service.solicitarDocumentacao(id, request);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/admin/vagas")
  List<VagaResumo> vagas() {
    return service.vagas();
  }

  @PostMapping("/admin/vagas")
  Map<String, UUID> criarVaga(@RequestBody @Valid VagaRequest request) {
    return Map.of("id", service.criarVaga(request));
  }

  @GetMapping("/admin/auditoria")
  List<AuditoriaItem> auditoria() {
    return service.auditoria();
  }

  @GetMapping("/admin/cursos")
  List<CadastroItem> cursos() { return service.cursos(); }

  @PostMapping("/admin/cursos")
  Map<String, Long> criarCurso(@RequestBody @Valid CadastroRequest request) { return Map.of("id", service.criarCurso(request)); }

  @PutMapping("/admin/cursos/{id}")
  ResponseEntity<Void> atualizarCurso(@PathVariable Long id, @RequestBody @Valid CadastroRequest request) { service.atualizarCurso(id, request); return ResponseEntity.noContent().build(); }

  @DeleteMapping("/admin/cursos/{id}")
  ResponseEntity<Void> excluirCurso(@PathVariable Long id) { service.excluirCurso(id); return ResponseEntity.noContent().build(); }

  @GetMapping("/admin/turmas")
  List<CadastroItem> turmas() { return service.turmas(); }

  @PostMapping("/admin/turmas")
  Map<String, Long> criarTurma(@RequestBody @Valid CadastroRequest request) { return Map.of("id", service.criarTurma(request)); }

  @PutMapping("/admin/turmas/{id}")
  ResponseEntity<Void> atualizarTurma(@PathVariable Long id, @RequestBody @Valid CadastroRequest request) { service.atualizarTurma(id, request); return ResponseEntity.noContent().build(); }

  @DeleteMapping("/admin/turmas/{id}")
  ResponseEntity<Void> excluirTurma(@PathVariable Long id) { service.excluirTurma(id); return ResponseEntity.noContent().build(); }

  @GetMapping("/admin/professores")
  List<CadastroItem> professores() { return service.professores(); }

  @PostMapping("/admin/professores")
  Map<String, Long> criarProfessor(@RequestBody @Valid CadastroRequest request) { return Map.of("id", service.criarProfessor(request)); }

  @PutMapping("/admin/professores/{id}")
  ResponseEntity<Void> atualizarProfessor(@PathVariable Long id, @RequestBody @Valid CadastroRequest request) { service.atualizarProfessor(id, request); return ResponseEntity.noContent().build(); }

  @DeleteMapping("/admin/professores/{id}")
  ResponseEntity<Void> excluirProfessor(@PathVariable Long id) { service.excluirProfessor(id); return ResponseEntity.noContent().build(); }

  @PostMapping("/admin/solicitacoes/{id}/aprovar")
  ResponseEntity<Void> aprovar(@PathVariable UUID id, @RequestBody @Valid DecisaoRequest request) {
    service.aprovar(id, request);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/solicitacoes/{id}/rejeitar")
  ResponseEntity<Void> rejeitar(@PathVariable UUID id, @RequestBody DecisaoRequest request) {
    service.rejeitar(id, request);
    return ResponseEntity.noContent().build();
  }
}
