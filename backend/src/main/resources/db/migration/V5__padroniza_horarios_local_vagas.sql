update vagas_reposicao
set local = 'Laboratório SR2',
    horario = case turno
      when 'MANHA' then '08:00'::time
      when 'TARDE' then '14:00'::time
      when 'NOITE' then '19:00'::time
      else horario
    end;
