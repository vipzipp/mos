alter table mos_user add last_login_date timestamp;
alter table mos_user add login_times bigint default 0;