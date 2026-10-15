-- The schedules of the JobRunr PoC (source of truth; synced to JobRunr's recurring jobs). Run by spring.sql.init.
create table if not exists batch_schedule (
  id varchar(100) primary key,
  job_name varchar(100) not null,
  cron_expression varchar(200) not null,
  time_zone varchar(100) not null,
  paused boolean not null
);

-- Every change gets a new JobRunr recurring job id (<id>.v<version>), see JobRunrSchedules
alter table batch_schedule add column if not exists version int default 0 not null;
