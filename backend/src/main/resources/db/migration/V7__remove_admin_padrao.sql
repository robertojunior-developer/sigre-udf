delete from usuario_perfis
where usuario_id in (select id from usuarios where email = 'admin@sigre.local');

delete from usuarios
where email = 'admin@sigre.local';
