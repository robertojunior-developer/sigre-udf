alter table usuarios add column if not exists senha_hash varchar(120);

insert into perfis(nome) values ('ADMIN') on conflict (nome) do nothing;

insert into usuarios(nome, email, senha_hash, ativo)
values ('Administrador SIGRE', 'admin@sigre.local', crypt('Admin@123', gen_salt('bf')), true)
on conflict (email) do update set senha_hash = coalesce(usuarios.senha_hash, excluded.senha_hash), ativo = true;

insert into usuario_perfis(usuario_id, perfil_id)
select u.id, p.id
from usuarios u cross join perfis p
where u.email = 'admin@sigre.local' and p.nome = 'ADMIN'
on conflict do nothing;
