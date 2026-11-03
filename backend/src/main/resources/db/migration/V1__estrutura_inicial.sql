create extension if not exists pgcrypto;

create table perfis (id bigserial primary key, nome varchar(80) not null unique);
create table usuarios (id uuid primary key default gen_random_uuid(), nome varchar(180) not null, email varchar(180) not null unique, entra_id varchar(120) unique, ativo boolean not null default true, criado_em timestamptz not null default now());
create table usuario_perfis (usuario_id uuid references usuarios(id), perfil_id bigint references perfis(id), primary key (usuario_id, perfil_id));
create table cursos (id bigserial primary key, nome varchar(180) not null unique, ativo boolean not null default true);
create table turmas (id bigserial primary key, curso_id bigint references cursos(id), nome varchar(80) not null, turno varchar(20));
create table professores (id bigserial primary key, nome varchar(180) not null, email varchar(180));
create table alunos (id uuid primary key default gen_random_uuid(), nome varchar(180) not null, cpf_hash varchar(128), email varchar(180) not null, matricula varchar(60) not null unique, curso_id bigint references cursos(id), turma_id bigint references turmas(id), criado_em timestamptz not null default now());
create table vagas_reposicao (id uuid primary key default gen_random_uuid(), data date not null, turno varchar(20) not null, horario time not null, local varchar(180) not null, curso_id bigint references cursos(id), capacidade_maxima int not null check (capacidade_maxima >= 0), situacao varchar(30) not null default 'ABERTA', criado_em timestamptz not null default now());
create table solicitacoes (id uuid primary key default gen_random_uuid(), protocolo varchar(40) not null unique, aluno_id uuid not null references alunos(id), origem varchar(40) not null, idempotency_key varchar(160) not null unique, status varchar(40) not null, justificativa text, criado_em timestamptz not null default now(), atualizado_em timestamptz not null default now());
create table solicitacao_datas (id uuid primary key default gen_random_uuid(), solicitacao_id uuid not null references solicitacoes(id) on delete cascade, data date not null, turno varchar(20) not null, status varchar(40) not null default 'PENDENTE_ANALISE', vaga_reposicao_id uuid references vagas_reposicao(id));
create table documentos (id uuid primary key default gen_random_uuid(), solicitacao_id uuid not null references solicitacoes(id), nome_arquivo varchar(220) not null, content_type varchar(120) not null, storage_key varchar(260) not null, enviado_por uuid references usuarios(id), criado_em timestamptz not null default now());
create table reservas (id uuid primary key default gen_random_uuid(), solicitacao_data_id uuid not null unique references solicitacao_datas(id), vaga_reposicao_id uuid not null references vagas_reposicao(id), aluno_id uuid not null references alunos(id), aprovada_por uuid references usuarios(id), criada_em timestamptz not null default now(), unique(vaga_reposicao_id, aluno_id));
create table notificacoes (id uuid primary key default gen_random_uuid(), solicitacao_id uuid references solicitacoes(id), destinatario varchar(180) not null, status varchar(30) not null, provider_id varchar(160), tentativas int not null default 0, erro text, enviada_em timestamptz, criado_em timestamptz not null default now());
create table eventos_outbox (id uuid primary key default gen_random_uuid(), tipo varchar(80) not null, aggregate_id uuid not null, dedup_key varchar(160) not null unique, payload jsonb not null, status varchar(30) not null default 'PENDENTE', tentativas int not null default 0, criado_em timestamptz not null default now(), processado_em timestamptz);
create table auditoria (id bigserial primary key, usuario_id uuid, acao varchar(120) not null, entidade varchar(120) not null, entidade_id varchar(80), dados jsonb, criado_em timestamptz not null default now());
create table configuracoes (chave varchar(120) primary key, valor text not null, atualizado_em timestamptz not null default now());

create unique index uk_vagas_data_turno_curso on vagas_reposicao(data, turno, coalesce(curso_id, 0));
create index idx_vagas_filtro on vagas_reposicao(data, turno, situacao, curso_id);
create index idx_solicitacoes_status on solicitacoes(status, criado_em);
create index idx_solicitacao_datas_data_turno on solicitacao_datas(data, turno, status);
create index idx_reservas_vaga on reservas(vaga_reposicao_id);
create index idx_notificacoes_status on notificacoes(status, criado_em);
create index idx_outbox_status on eventos_outbox(status, criado_em);

insert into perfis(nome) values ('ADMIN'), ('COORDENACAO'), ('ATENDIMENTO'), ('ALUNO') on conflict do nothing;
insert into cursos(nome) values ('Enfermagem'), ('Fisioterapia'), ('Psicologia'), ('Nutrição') on conflict do nothing;
