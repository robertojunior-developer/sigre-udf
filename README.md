# SIGRE — Sistema Integrado de Gestão de Reposição de Estágios

Aplicação web para substituir a operação baseada em planilhas no gerenciamento de reposições de estágios do Centro Universitário UDF. O aluno registra a solicitação diretamente na área do aluno do SIGRE, e a persistência, consulta, aprovação, agenda e notificações ficam centralizadas no PostgreSQL.

## Arquitetura

```text
Aluno -> Portal Angular -> Área do Aluno -> API /api/aluno/solicitacoes -> PostgreSQL
Funcionário -> Painel Angular -> API Admin -> PostgreSQL
Microsoft Forms legado/opcional -> Power Automate -> API /api/forms/solicitacoes
API Admin -> eventos_outbox -> Worker Microsoft Graph -> notificacoes
Microsoft Entra ID -> Frontend/API -> perfis e autorização
```

## Stack

- Frontend: Angular 21, TypeScript, Tailwind CSS, Signals, layout responsivo.
- Backend: Java 21, Spring Boot 3, Spring Security, Spring JDBC/JPA, Flyway.
- Banco: PostgreSQL 16.
- Infra: Docker Compose, variáveis de ambiente, healthcheck.
- Testes: JUnit 5 e Spring Boot Test.

## Como executar

```bash
docker compose up --build
```

Acesse:

- Portal do aluno: http://localhost:4200
- Painel administrativo: http://localhost:4200/admin
- Nova solicitação do aluno: http://localhost:4200/aluno/nova-solicitacao
- API via proxy do frontend: http://localhost:4200/api
- Healthcheck: http://localhost:4200/api/actuator/health

## Solicitação interna do aluno

O fluxo principal não depende mais do Microsoft Forms:

1. O aluno acessa o portal.
2. Clica em `Solicitar reposição`.
3. Preenche a solicitação dentro do SIGRE em `/aluno/nova-solicitacao`.
4. A API `POST /api/aluno/solicitacoes` registra aluno, datas solicitadas e protocolo.
5. A solicitação entra no painel administrativo com status `PENDENTE_ANALISE`.

Body da API interna:

```json
{
  "nome": "Nome do aluno",
  "email": "aluno@udf.edu.br",
  "matricula": "2026001",
  "cpf": "00000000000",
  "curso": "Enfermagem",
  "turma": "ENF-7A",
  "professor": "Professor Responsável",
  "motivo": "Atestado médico no dia do estágio",
  "datas": [
    { "data": "2026-11-10", "turno": "MANHA" }
  ]
}
```

A submissão interna não reserva vaga automaticamente; ela cria uma solicitação com status `PENDENTE_ANALISE`. O endpoint legado `/api/forms/solicitacoes` continua disponível apenas como contingência de integração, usando `responseId` como idempotência.

## E-mails institucionais

O backend já registra `eventos_outbox` do tipo `EMAIL_APROVACAO` após aprovação. A próxima etapa é adicionar um worker autenticado no Microsoft Graph para:

- Buscar eventos pendentes.
- Montar e-mail consolidado por aprovação.
- Enviar via Graph usando credenciais institucionais.
- Registrar sucesso somente após confirmação do provedor.
- Atualizar `notificacoes` e marcar outbox como processado.

## Segurança e LGPD

- CPF não é exibido no portal público e é armazenado apenas como hash.
- Dados pessoais de outros alunos não aparecem na agenda pública.
- O endpoint legado do Forms exige token dedicado.
- A integração final com Microsoft Entra ID deve substituir o modo permissivo de desenvolvimento em `SecurityConfig` antes de produção.
- Credenciais devem ser fornecidas por variáveis de ambiente, nunca por arquivos locais versionados.

## Migrations principais

O Flyway cria as tabelas solicitadas: `usuarios`, `perfis`, `alunos`, `cursos`, `turmas`, `professores`, `solicitacoes`, `solicitacao_datas`, `documentos`, `vagas_reposicao`, `reservas`, `notificacoes`, `eventos_outbox`, `auditoria` e `configuracoes`.

## Produção / publicação

A aplicação está preparada para publicar o frontend e o backend no mesmo domínio. O Angular usa a API relativa `/api`, e o Nginx do frontend encaminha `/api/**` para o backend Spring Boot dentro da rede Docker.

Exemplo de acesso em produção:

- Portal público dos alunos: `https://seu-dominio`
- Nova solicitação: `https://seu-dominio/aluno/nova-solicitacao`
- Painel administrativo: `https://seu-dominio/admin`
- API: `https://seu-dominio/api`

Variáveis recomendadas para produção:

```bash
POSTGRES_DB=sigre
POSTGRES_USER=sigre
POSTGRES_PASSWORD=troque-por-senha-forte
FORMS_API_TOKEN=troque-por-token-forte
CORS_ORIGINS=https://seu-dominio
FRONTEND_PORT=80
```

Em produção, exponha publicamente apenas o frontend/Nginx. O PostgreSQL e o backend ficam acessíveis apenas na rede interna Docker. Use HTTPS no proxy externo, balanceador ou serviço de hospedagem.

O usuário padrão `admin@sigre.local` é removido pela migration `V7__remove_admin_padrao.sql`. Antes de aplicar em produção, garanta que já existe outro administrador cadastrado.

## Verificação local

```bash
cd backend
mvn test

cd ../frontend
npm install
npm run build
```
