insert into vagas_reposicao(data, turno, horario, local, curso_id, capacidade_maxima, situacao)
select current_date + offs, turno, horario::time, 'Laboratório Escola UDF', null, 12, 'ABERTA'
from generate_series(3, 30, 7) offs
cross join (values ('MANHA','08:00'), ('TARDE','14:00'), ('NOITE','18:00')) t(turno, horario)
on conflict do nothing;
