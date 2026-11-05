package br.edu.udf.sigre.service;

import br.edu.udf.sigre.dto.Dtos.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SigreService {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;
  private final String formsToken;

  public SigreService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, @Value("${security.forms-api-token}") String formsToken) {
    this.jdbc = jdbc;
    this.passwordEncoder = passwordEncoder;
    this.formsToken = formsToken;
  }

  public void validarTokenForms(String token) {
    if (!formsToken.equals(token)) throw new IllegalArgumentException("Token inválido");
  }

  @Transactional
  public UUID criarAdministrador(NovoAdminRequest req) {
    return criarAdministrador(req.nome(), req.email(), req.senha(), true);
  }

  @Transactional
  public UUID criarAdministradorBootstrap(String nome, String email, String senha) {
    return criarAdministrador(nome, email, senha, false);
  }

  private UUID criarAdministrador(String nome, String email, String senha, boolean auditarCriacao) {
    jdbc.update("insert into perfis(nome) values ('ADMIN') on conflict (nome) do nothing");
    UUID id = jdbc.queryForObject("""
        insert into usuarios(nome, email, senha_hash, ativo)
        values (?, ?, ?, true)
        on conflict (email) do update set nome=excluded.nome, senha_hash=excluded.senha_hash, ativo=true
        returning id
        """, UUID.class, nome, email, passwordEncoder.encode(senha));
    jdbc.update("""
        insert into usuario_perfis(usuario_id, perfil_id)
        select ?, id from perfis where nome='ADMIN'
        on conflict do nothing
        """, id);
    if (auditarCriacao) auditar("ADMIN_CRIADO", "usuarios", id.toString());
    return id;
  }

  public List<AgendaItem> agenda(LocalDate inicio, LocalDate fim, String turno, Long cursoId) {
    String filtroTurno = turno == null || turno.isBlank() ? null : turno;
    String sql = """
        with dias as (
          select d::date data from generate_series(?::date, ?::date, interval '1 day') d
          where extract(isodow from d) between 1 and 5
        ), turnos as (
          select * from (values ('MANHA','08:00'::time), ('TARDE','14:00'::time), ('NOITE','19:00'::time)) t(turno, horario)
        ), ocupacao as (
          select sd.data, sd.turno, count(r.id)::int ocupadas
          from reservas r join solicitacao_datas sd on sd.id=r.solicitacao_data_id
          where sd.status='APROVADA' and sd.data between ? and ?
          group by sd.data, sd.turno
        )
        select gen_random_uuid() id, d.data, t.turno, t.horario, 'Laboratório SR2' local, null::varchar curso, 15 capacidade_maxima,
          coalesce(o.ocupadas,0) ocupadas, greatest(15 - coalesce(o.ocupadas,0), 0) disponiveis,
          case when 15 - coalesce(o.ocupadas,0) <= 0 then 'ESGOTADA' else 'ABERTA' end situacao
        from dias d cross join turnos t
        left join ocupacao o on o.data=d.data and o.turno=t.turno
        """ + (filtroTurno == null ? "" : "where t.turno = ?\n") + "order by d.data, t.horario";
    var mapper = (org.springframework.jdbc.core.RowMapper<AgendaItem>) (rs, row) -> new AgendaItem(UUID.fromString(rs.getString("id")), rs.getDate("data").toLocalDate(), rs.getString("turno"), rs.getTime("horario").toLocalTime(), rs.getString("local"), rs.getString("curso"), rs.getInt("capacidade_maxima"), rs.getInt("ocupadas"), rs.getInt("disponiveis"), rs.getString("situacao"));
    return filtroTurno == null ? jdbc.query(sql, mapper, inicio, fim, inicio, fim) : jdbc.query(sql, mapper, inicio, fim, inicio, fim, filtroTurno);
  }

  @Transactional
  public SolicitacaoCriadaResponse registrarForms(FormsSolicitacaoRequest req) {
    return registrarSolicitacao(req.nome(), req.email(), req.matricula(), req.cpf(), req.curso(), null, req.responseId(), "MICROSOFT_FORMS", req.datas(), "FORMS_RECEBIDO");
  }

  @Transactional
  public SolicitacaoCriadaResponse registrarSolicitacaoAluno(AlunoSolicitacaoRequest req) {
    String idempotencyKey = "PORTAL_ALUNO:" + req.matricula() + ":" + req.datas().hashCode() + ":" + LocalDate.now();
    return registrarSolicitacao(req.nome(), req.email(), req.matricula(), req.cpf(), req.curso(), req.motivo(), idempotencyKey, "PORTAL_ALUNO", req.datas(), "SOLICITACAO_ALUNO_CRIADA");
  }

  private void validarDatasUnicasPorCpf(String cpf, List<SolicitacaoDataRequest> datas) {
    java.util.HashSet<String> chaves = new java.util.HashSet<>();
    for (SolicitacaoDataRequest data : datas) {
      String chave = data.data() + "|" + data.turno();
      if (!chaves.add(chave)) throw new IllegalArgumentException("O mesmo CPF não pode solicitar a mesma data e turno mais de uma vez");
      Integer existente = jdbc.queryForObject("""
          select count(*) from alunos a
          join solicitacoes s on s.aluno_id=a.id
          join solicitacao_datas sd on sd.solicitacao_id=s.id
          where a.cpf_hash=? and sd.data=? and sd.turno=? and s.status <> 'EXCLUIDA'
          """, Integer.class, hash(cpf), data.data(), data.turno());
      if (existente != null && existente > 0) throw new IllegalArgumentException("Já existe solicitação para este CPF, data e turno");
    }
  }

  private SolicitacaoCriadaResponse registrarSolicitacao(String nome, String email, String matricula, String cpf, String curso, String motivoAluno, String idempotencyKey, String origem, List<SolicitacaoDataRequest> datas, String auditoria) {
    validarDatasUnicasPorCpf(cpf, datas);
    Long cursoId = obterOuCriarCurso(curso);
    UUID alunoId = jdbc.queryForObject("""
        insert into alunos(nome, cpf_hash, email, matricula, curso_id) values (?, ?, ?, ?, ?)
        on conflict (matricula) do update set nome = excluded.nome, email = excluded.email, curso_id = excluded.curso_id
        returning id
        """, UUID.class, nome, hash(cpf), email, matricula, cursoId);
    String protocolo = "SIGRE-" + System.currentTimeMillis();
    UUID solicitacaoId;
    try {
      solicitacaoId = jdbc.queryForObject("insert into solicitacoes(protocolo, aluno_id, origem, idempotency_key, status, motivo_aluno) values (?, ?, ?, ?, 'PENDENTE_ANALISE', ?) returning id", UUID.class, protocolo, alunoId, origem, idempotencyKey, motivoAluno);
    } catch (DuplicateKeyException ex) {
      return jdbc.queryForObject("select id, protocolo, status from solicitacoes where idempotency_key = ?", (rs, row) -> new SolicitacaoCriadaResponse(UUID.fromString(rs.getString("id")), rs.getString("protocolo"), rs.getString("status")), idempotencyKey);
    }
    for (SolicitacaoDataRequest data : datas) {
      if (data.data().getDayOfWeek() == DayOfWeek.SATURDAY || data.data().getDayOfWeek() == DayOfWeek.SUNDAY) {
        throw new IllegalArgumentException("Sábados e domingos não podem ser solicitados");
      }
      if (data.data().isBefore(LocalDate.now().plusDays(3))) {
        throw new IllegalArgumentException("A data da reposição deve ter antecedência mínima de 3 dias");
      }
      jdbc.update("insert into solicitacao_datas(solicitacao_id, data, turno) values (?, ?, ?)", solicitacaoId, data.data(), data.turno());
    }
    auditar(auditoria, "solicitacoes", solicitacaoId.toString());
    return new SolicitacaoCriadaResponse(solicitacaoId, protocolo, "PENDENTE_ANALISE");
  }

  public Dashboard dashboard() {
    return jdbc.queryForObject("""
        select count(s.*) total,
        count(*) filter (where s.status='PENDENTE_ANALISE') pendentes,
        count(*) filter (where s.status='AGUARDANDO_DOCUMENTACAO') aguardando,
        count(*) filter (where s.status='APROVADA') aprovadas,
        count(*) filter (where s.status='REJEITADA') rejeitadas,
        coalesce((select sum(greatest(v.capacidade_maxima - coalesce(x.ocupadas,0),0)) from vagas_reposicao v left join (select vaga_reposicao_id, count(*) ocupadas from reservas group by vaga_reposicao_id) x on x.vaga_reposicao_id=v.id),0) vagas_disponiveis,
        (select count(*) from reservas) vagas_ocupadas,
        (select count(*) from notificacoes where status='ENVIADA') emails_enviados,
        (select count(*) from notificacoes where status='FALHA') emails_falha
        from solicitacoes s
        """, (rs, row) -> new Dashboard(rs.getLong("total"), rs.getLong("pendentes"), rs.getLong("aguardando"), rs.getLong("aprovadas"), rs.getLong("rejeitadas"), rs.getLong("vagas_disponiveis"), rs.getLong("vagas_ocupadas"), rs.getLong("emails_enviados"), rs.getLong("emails_falha")));
  }

  public List<SolicitacaoResumo> solicitacoes(String status, LocalDate inicio, LocalDate fim, String ordenacao, String busca) {
    StringBuilder sql = new StringBuilder("""
        select distinct s.id, s.protocolo, a.nome aluno, a.matricula, c.nome curso, s.status, s.criado_em::date criado
        from solicitacoes s join alunos a on a.id=s.aluno_id left join cursos c on c.id=a.curso_id left join solicitacao_datas sd on sd.solicitacao_id=s.id
        where 1=1
        """);
    java.util.ArrayList<Object> args = new java.util.ArrayList<>();
    if (status != null && !status.isBlank()) { sql.append(" and s.status = ?"); args.add(status); }
    if (inicio != null) { sql.append(" and coalesce(sd.data, s.criado_em::date) >= ?"); args.add(inicio); }
    if (fim != null) { sql.append(" and coalesce(sd.data, s.criado_em::date) <= ?"); args.add(fim); }
    if (busca != null && !busca.isBlank()) { sql.append(" and (lower(a.nome) like lower(?) or lower(a.matricula) like lower(?) or lower(s.protocolo) like lower(?))"); String termo = "%" + busca.trim() + "%"; args.add(termo); args.add(termo); args.add(termo); }
    sql.append(" order by ").append(switch (ordenacao == null ? "" : ordenacao) {
      case "MATRICULA_ASC" -> "a.matricula asc";
      case "MATRICULA_DESC" -> "a.matricula desc";
      case "ALUNO_DESC" -> "a.nome desc";
      default -> "a.nome asc";
    }).append(" limit 300");
    return jdbc.query(sql.toString(), (rs, row) -> new SolicitacaoResumo(UUID.fromString(rs.getString("id")), rs.getString("protocolo"), rs.getString("aluno"), rs.getString("matricula"), rs.getString("curso"), rs.getString("status"), rs.getDate("criado").toLocalDate()), args.toArray());
  }

  public List<ConfirmadoItem> confirmados(LocalDate inicio, LocalDate fim) {
    return jdbc.query("""
        select sd.data, sd.turno, a.nome aluno, a.matricula, c.nome curso, t.nome turma
        from reservas r
        join solicitacao_datas sd on sd.id=r.solicitacao_data_id
        join solicitacoes s on s.id=sd.solicitacao_id
        join alunos a on a.id=s.aluno_id
        left join cursos c on c.id=a.curso_id
        left join turmas t on t.curso_id=c.id and t.turno=sd.turno
        where sd.status='APROVADA' and sd.data between ? and ?
        order by sd.data, sd.turno, a.nome
        """, (rs, row) -> new ConfirmadoItem(rs.getDate("data").toLocalDate(), rs.getString("turno"), rs.getString("aluno"), rs.getString("matricula"), rs.getString("curso"), rs.getString("turma")), inicio, fim);
  }

  public List<RelatorioReposicaoItem> relatorioReposicao(LocalDate inicio, LocalDate fim, String status, String turno) {
    StringBuilder sql = new StringBuilder("""
        select a.nome aluno, a.matricula, c.nome curso, sd.turno, sd.data, sd.status, s.protocolo
        from solicitacao_datas sd
        join solicitacoes s on s.id=sd.solicitacao_id
        join alunos a on a.id=s.aluno_id
        left join cursos c on c.id=a.curso_id
        where 1=1
        """);
    List<Object> args = new java.util.ArrayList<>();
    if (inicio != null) { sql.append(" and sd.data>=?"); args.add(inicio); }
    if (fim != null) { sql.append(" and sd.data<=?"); args.add(fim); }
    if (status != null && !status.isBlank()) { sql.append(" and sd.status=?"); args.add(status.trim()); }
    if (turno != null && !turno.isBlank()) { sql.append(" and sd.turno=?"); args.add(turno.trim()); }
    sql.append(" order by sd.data, sd.turno, a.nome, a.matricula");
    return jdbc.query(sql.toString(), (rs, row) -> new RelatorioReposicaoItem(rs.getString("aluno"), rs.getString("matricula"), rs.getString("curso"), rs.getString("turno"), rs.getDate("data").toLocalDate(), rs.getString("status"), rs.getString("protocolo")), args.toArray());
  }

  public List<AlunoConsultaItem> consultarAlunoPorMatricula(String matricula) {
    return jdbc.query("""
        select s.protocolo, sd.data, sd.turno, sd.status, coalesce(sd.justificativa, s.justificativa) justificativa, c.nome curso
        from alunos a
        join solicitacoes s on s.aluno_id=a.id
        join solicitacao_datas sd on sd.solicitacao_id=s.id
        left join cursos c on c.id=a.curso_id
        where lower(a.matricula)=lower(?)
        order by sd.data desc, sd.turno
        """, (rs, row) -> new AlunoConsultaItem(rs.getString("protocolo"), rs.getDate("data").toLocalDate(), rs.getString("turno"), rs.getString("status"), rs.getString("justificativa"), rs.getString("curso")), matricula.trim());
  }

  public SolicitacaoDetalhe solicitacaoDetalhe(UUID id) {
    SolicitacaoDetalhe base = jdbc.queryForObject("""
        select s.id, s.protocolo, a.nome aluno, a.email, a.matricula, c.nome curso, s.status, s.justificativa, s.motivo_aluno, s.criado_em::text criado_em
        from solicitacoes s join alunos a on a.id=s.aluno_id left join cursos c on c.id=a.curso_id where s.id=?
        """, (rs, row) -> new SolicitacaoDetalhe(UUID.fromString(rs.getString("id")), rs.getString("protocolo"), rs.getString("aluno"), rs.getString("email"), rs.getString("matricula"), rs.getString("curso"), rs.getString("status"), rs.getString("justificativa"), rs.getString("motivo_aluno"), rs.getString("criado_em"), List.of(), List.of()), id);
    List<SolicitacaoDataResumo> datas = jdbc.query("select id, data, turno, status, vaga_reposicao_id, justificativa from solicitacao_datas where solicitacao_id=? order by data, turno", (rs, row) -> new SolicitacaoDataResumo(UUID.fromString(rs.getString("id")), rs.getDate("data").toLocalDate(), rs.getString("turno"), rs.getString("status"), rs.getString("vaga_reposicao_id") == null ? null : UUID.fromString(rs.getString("vaga_reposicao_id")), rs.getString("justificativa")), id);
    List<DocumentoResumo> documentos = documentos(id);
    return new SolicitacaoDetalhe(base.id(), base.protocolo(), base.aluno(), base.email(), base.matricula(), base.curso(), base.status(), base.justificativa(), base.motivoAluno(), base.criadoEm(), datas, documentos);
  }

  public List<DocumentoResumo> documentos(UUID solicitacaoId) {
    return jdbc.query("select id, nome_arquivo, content_type, storage_key, criado_em::text criado_em from documentos where solicitacao_id=? order by criado_em desc", (rs, row) -> new DocumentoResumo(UUID.fromString(rs.getString("id")), rs.getString("nome_arquivo"), rs.getString("content_type"), rs.getString("storage_key"), rs.getString("criado_em")), solicitacaoId);
  }

  @Transactional
  public UUID registrarDocumento(UUID solicitacaoId, MultipartFile file) {
    try {
      String storageKey = "db/" + solicitacaoId + "/" + UUID.randomUUID() + "-" + file.getOriginalFilename();
      UUID id = jdbc.queryForObject("insert into documentos(solicitacao_id, nome_arquivo, content_type, storage_key, conteudo) values (?, ?, ?, ?, ?) returning id", UUID.class, solicitacaoId, file.getOriginalFilename(), file.getContentType() == null ? "application/octet-stream" : file.getContentType(), storageKey, file.getBytes());
      auditar("DOCUMENTO_ANEXADO", "solicitacoes", solicitacaoId.toString());
      return id;
    } catch (Exception ex) {
      throw new IllegalStateException("Não foi possível armazenar o documento", ex);
    }
  }

  public Map<String, Object> documentoConteudo(UUID documentoId) {
    return jdbc.queryForObject("select nome_arquivo, content_type, conteudo from documentos where id=?", (rs, row) -> Map.of("nome", rs.getString("nome_arquivo"), "contentType", rs.getString("content_type"), "conteudo", rs.getBytes("conteudo") == null ? new byte[0] : rs.getBytes("conteudo")), documentoId);
  }

  @Transactional
  public void atualizarSolicitacaoAdmin(UUID solicitacaoId, AdminSolicitacaoUpdateRequest req) {
    jdbc.update("update solicitacoes set status=?, justificativa=?, atualizado_em=now() where id=?", req.status(), req.justificativa(), solicitacaoId);
    jdbc.update("delete from reservas where solicitacao_data_id in (select id from solicitacao_datas where solicitacao_id=?)", solicitacaoId);
    jdbc.update("delete from solicitacao_datas where solicitacao_id=?", solicitacaoId);
    for (SolicitacaoDataRequest data : req.datas()) {
      if (data.data().getDayOfWeek() == DayOfWeek.SATURDAY || data.data().getDayOfWeek() == DayOfWeek.SUNDAY) throw new IllegalArgumentException("Sábados e domingos não podem ser solicitados");
      jdbc.update("insert into solicitacao_datas(solicitacao_id, data, turno, status) values (?, ?, ?, ?)", solicitacaoId, data.data(), data.turno(), req.status() == null || req.status().isBlank() ? "PENDENTE_ANALISE" : req.status());
    }
    auditar("SOLICITACAO_ATUALIZADA_ADMIN", "solicitacoes", solicitacaoId.toString());
  }

  @Transactional
  public void excluirSolicitacaoAdmin(UUID solicitacaoId) {
    jdbc.update("delete from reservas where solicitacao_data_id in (select id from solicitacao_datas where solicitacao_id=?)", solicitacaoId);
    jdbc.update("delete from documentos where solicitacao_id=?", solicitacaoId);
    jdbc.update("delete from notificacoes where solicitacao_id=?", solicitacaoId);
    jdbc.update("delete from eventos_outbox where aggregate_id=?", solicitacaoId);
    jdbc.update("delete from solicitacao_datas where solicitacao_id=?", solicitacaoId);
    jdbc.update("delete from solicitacoes where id=?", solicitacaoId);
    auditar("SOLICITACAO_EXCLUIDA_ADMIN", "solicitacoes", solicitacaoId.toString());
  }

  @Transactional
  public void solicitarDocumentacao(UUID solicitacaoId, DecisaoRequest req) {
    jdbc.queryForObject("select id from solicitacoes where id = ? for update", UUID.class, solicitacaoId);
    for (UUID sdId : req.solicitacaoDataIds()) {
      jdbc.update("update solicitacao_datas set status='AGUARDANDO_DOCUMENTACAO', justificativa=? where id=? and solicitacao_id=?", req.justificativa(), sdId, solicitacaoId);
    }
    atualizarStatusSolicitacao(solicitacaoId);
    auditar("DOCUMENTACAO_SOLICITADA", "solicitacoes", solicitacaoId.toString());
  }

  public List<CadastroItem> cursos() {
    return jdbc.query("select id, nome, null::varchar email, null::bigint curso_id, null::varchar curso, null::varchar turno from cursos where ativo=true order by nome", (rs, row) -> cadastroItem(rs));
  }

  public List<CadastroItem> turmas() {
    return jdbc.query("select t.id, t.nome, null::varchar email, t.curso_id, c.nome curso, t.turno from turmas t left join cursos c on c.id=t.curso_id order by c.nome, t.nome", (rs, row) -> cadastroItem(rs));
  }

  public List<CadastroItem> professores() {
    return jdbc.query("select id, nome, email, null::bigint curso_id, null::varchar curso, null::varchar turno from professores order by nome", (rs, row) -> cadastroItem(rs));
  }

  @Transactional
  public Long criarCurso(CadastroRequest req) {
    Long id = obterOuCriarCurso(req.nome());
    auditar("CURSO_SALVO", "cursos", id.toString());
    return id;
  }

  @Transactional
  public Long criarTurma(CadastroRequest req) {
    Long id = jdbc.queryForObject("insert into turmas(curso_id, nome, turno) values (?, ?, ?) returning id", Long.class, req.cursoId(), req.nome(), req.turno());
    auditar("TURMA_CRIADA", "turmas", id.toString());
    return id;
  }

  @Transactional
  public Long criarProfessor(CadastroRequest req) {
    Long id = jdbc.queryForObject("insert into professores(nome, email) values (?, ?) returning id", Long.class, req.nome(), req.email());
    auditar("PROFESSOR_CRIADO", "professores", id.toString());
    return id;
  }

  @Transactional
  public void atualizarCurso(Long id, CadastroRequest req) {
    jdbc.update("update cursos set nome=? where id=?", req.nome(), id);
    auditar("CURSO_ATUALIZADO", "cursos", id.toString());
  }

  @Transactional
  public void excluirCurso(Long id) {
    jdbc.update("update cursos set ativo=false where id=?", id);
    auditar("CURSO_EXCLUIDO", "cursos", id.toString());
  }

  @Transactional
  public void atualizarTurma(Long id, CadastroRequest req) {
    jdbc.update("update turmas set nome=?, curso_id=?, turno=? where id=?", req.nome(), req.cursoId(), req.turno(), id);
    auditar("TURMA_ATUALIZADA", "turmas", id.toString());
  }

  @Transactional
  public void excluirTurma(Long id) {
    jdbc.update("delete from turmas where id=?", id);
    auditar("TURMA_EXCLUIDA", "turmas", id.toString());
  }

  @Transactional
  public void atualizarProfessor(Long id, CadastroRequest req) {
    jdbc.update("update professores set nome=?, email=? where id=?", req.nome(), req.email(), id);
    auditar("PROFESSOR_ATUALIZADO", "professores", id.toString());
  }

  @Transactional
  public void excluirProfessor(Long id) {
    jdbc.update("delete from professores where id=?", id);
    auditar("PROFESSOR_EXCLUIDO", "professores", id.toString());
  }

  private CadastroItem cadastroItem(java.sql.ResultSet rs) throws java.sql.SQLException {
    Long cursoId = rs.getObject("curso_id") == null ? null : rs.getLong("curso_id");
    return new CadastroItem(rs.getLong("id"), rs.getString("nome"), rs.getString("email"), cursoId, rs.getString("curso"), rs.getString("turno"));
  }

  public List<VagaResumo> vagas() {
    return jdbc.query("""
        select v.id, v.data, v.turno, v.horario, v.local, c.nome curso, v.capacidade_maxima,
          count(r.id)::int ocupadas, greatest(v.capacidade_maxima - count(r.id)::int, 0) disponiveis, v.situacao
        from vagas_reposicao v
        left join cursos c on c.id = v.curso_id
        left join reservas r on r.vaga_reposicao_id = v.id
        group by v.id, c.nome
        order by v.data, v.horario
        limit 300
        """, (rs, row) -> new VagaResumo(UUID.fromString(rs.getString("id")), rs.getDate("data").toLocalDate(), rs.getString("turno"), rs.getTime("horario").toLocalTime(), rs.getString("local"), rs.getString("curso"), rs.getInt("capacidade_maxima"), rs.getInt("ocupadas"), rs.getInt("disponiveis"), rs.getString("situacao")));
  }

  public List<AuditoriaItem> auditoria() {
    return jdbc.query("select id, acao, entidade, entidade_id, criado_em::text criado_em from auditoria order by criado_em desc limit 300", (rs, row) -> new AuditoriaItem(rs.getLong("id"), rs.getString("acao"), rs.getString("entidade"), rs.getString("entidade_id"), rs.getString("criado_em")));
  }

  @Transactional
  public UUID criarVaga(VagaRequest req) {
    java.time.LocalTime horario = horarioPadrao(req.turno());
    UUID id = jdbc.queryForObject("insert into vagas_reposicao(data, turno, horario, local, curso_id, capacidade_maxima, situacao) values (?, ?, ?, ?, ?, ?, ?) returning id", UUID.class, req.data(), req.turno(), horario, "Laboratório SR2", req.cursoId(), req.capacidadeMaxima(), req.situacao());
    auditar("VAGA_CRIADA", "vagas_reposicao", id.toString());
    return id;
  }

  private java.time.LocalTime horarioPadrao(String turno) {
    return switch (turno) {
      case "MANHA" -> java.time.LocalTime.of(8, 0);
      case "TARDE" -> java.time.LocalTime.of(14, 0);
      default -> java.time.LocalTime.of(19, 0);
    };
  }

  @Transactional
  public void aprovar(UUID solicitacaoId, DecisaoRequest req) {
    UUID alunoId = jdbc.queryForObject("select aluno_id from solicitacoes where id = ? for update", UUID.class, solicitacaoId);
    for (UUID sdId : req.solicitacaoDataIds()) {
      List<UUID> vagas = jdbc.query("""
          select v.id from solicitacao_datas sd join vagas_reposicao v on v.data=sd.data and v.turno=sd.turno
          where sd.id=? and sd.solicitacao_id=? and v.situacao='ABERTA'
          order by case when v.curso_id is null then 1 else 0 end limit 1 for update of v
          """, (rs, row) -> UUID.fromString(rs.getString("id")), sdId, solicitacaoId);
      UUID vagaId = vagas.isEmpty() ? criarVagaAutomatica(sdId) : vagas.get(0);
      Integer livres = jdbc.queryForObject("select capacidade_maxima - (select count(*) from reservas where vaga_reposicao_id=? and solicitacao_data_id<>?) from vagas_reposicao where id=?", Integer.class, vagaId, sdId, vagaId);
      if (livres == null || livres <= 0) throw new IllegalStateException("Vaga esgotada");
      jdbc.update("delete from reservas where solicitacao_data_id=?", sdId);
      jdbc.update("insert into reservas(solicitacao_data_id, vaga_reposicao_id, aluno_id) values (?, ?, ?)", sdId, vagaId, alunoId);
      jdbc.update("update solicitacao_datas set status='APROVADA', vaga_reposicao_id=?, justificativa=null where id=? and solicitacao_id=?", vagaId, sdId, solicitacaoId);
    }
    atualizarStatusSolicitacao(solicitacaoId);
    jdbc.update("insert into eventos_outbox(tipo, aggregate_id, dedup_key, payload) values ('EMAIL_APROVACAO', ?, ?, jsonb_build_object('solicitacaoId', ?::text)) on conflict do nothing", solicitacaoId, "EMAIL_APROVACAO:" + solicitacaoId, solicitacaoId.toString());
    auditar("SOLICITACAO_APROVADA", "solicitacoes", solicitacaoId.toString());
  }

  private void atualizarStatusSolicitacao(UUID solicitacaoId) {
    Map<String, Object> contagens = jdbc.queryForMap("""
        select count(*) total,
          count(*) filter (where status='APROVADA') aprovadas,
          count(*) filter (where status='REJEITADA') rejeitadas,
          count(*) filter (where status='AGUARDANDO_DOCUMENTACAO') documentacao
        from solicitacao_datas where solicitacao_id=?
        """, solicitacaoId);
    long total = ((Number) contagens.get("total")).longValue();
    long aprovadas = ((Number) contagens.get("aprovadas")).longValue();
    long rejeitadas = ((Number) contagens.get("rejeitadas")).longValue();
    long documentacao = ((Number) contagens.get("documentacao")).longValue();
    String status = total > 0 && aprovadas == total ? "APROVADA" : total > 0 && rejeitadas == total ? "REJEITADA" : documentacao > 0 ? "AGUARDANDO_DOCUMENTACAO" : aprovadas > 0 ? "PARCIALMENTE_APROVADA" : "PENDENTE_ANALISE";
    jdbc.update("update solicitacoes set status=?, atualizado_em=now() where id=?", status, solicitacaoId);
  }

  private UUID criarVagaAutomatica(UUID solicitacaoDataId) {
    List<UUID> existentes = jdbc.query("""
        select v.id
        from solicitacao_datas sd
        join vagas_reposicao v on v.data=sd.data and v.turno=sd.turno and v.situacao='ABERTA'
        where sd.id=?
        order by case when v.curso_id is null then 1 else 0 end
        limit 1 for update of v
        """, (rs, row) -> UUID.fromString(rs.getString("id")), solicitacaoDataId);
    if (!existentes.isEmpty()) return existentes.get(0);
    return jdbc.queryForObject("""
        insert into vagas_reposicao(data, turno, horario, local, curso_id, capacidade_maxima, situacao)
        select data, turno, case turno when 'MANHA' then '08:00'::time when 'TARDE' then '14:00'::time else '19:00'::time end,
          'Laboratório SR2', null, 15, 'ABERTA'
        from solicitacao_datas where id=? returning id
        """, UUID.class, solicitacaoDataId);
  }

  @Transactional
  public void rejeitar(UUID solicitacaoId, DecisaoRequest req) {
    jdbc.queryForObject("select id from solicitacoes where id = ? for update", UUID.class, solicitacaoId);
    for (UUID sdId : req.solicitacaoDataIds()) {
      jdbc.update("delete from reservas where solicitacao_data_id=?", sdId);
      jdbc.update("update solicitacao_datas set status='REJEITADA', justificativa=?, vaga_reposicao_id=null where id=? and solicitacao_id=?", req.justificativa(), sdId, solicitacaoId);
    }
    atualizarStatusSolicitacao(solicitacaoId);
    auditar("SOLICITACAO_REJEITADA", "solicitacoes", solicitacaoId.toString());
  }

  private Long obterOuCriarCurso(String nome) {
    return jdbc.queryForObject("insert into cursos(nome, ativo) values (?, true) on conflict (nome) do update set nome=excluded.nome, ativo=true returning id", Long.class, nome);
  }

  private String hash(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private void auditar(String acao, String entidade, String id) {
    jdbc.update("insert into auditoria(acao, entidade, entidade_id) values (?, ?, ?)", acao, entidade, id);
  }
}
