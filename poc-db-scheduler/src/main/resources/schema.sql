-- db-scheduler's table (from db-scheduler's hsql_tables.sql, adapted for H2). Run by spring.sql.init on every start.
create table if not exists scheduled_tasks (
  task_name varchar(100) not null,
  task_instance varchar(100) not null,
  task_data blob,
  execution_time timestamp with time zone not null,
  picked boolean not null,
  picked_by varchar(50),
  last_success timestamp with time zone,
  last_failure timestamp with time zone,
  consecutive_failures int,
  last_heartbeat timestamp with time zone,
  version bigint not null,
  priority smallint,
  primary key (task_name, task_instance)
);

create index if not exists execution_time_idx on scheduled_tasks (execution_time);
create index if not exists last_heartbeat_idx on scheduled_tasks (last_heartbeat);
