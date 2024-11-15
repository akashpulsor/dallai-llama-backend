create table refresh_token (
                               business_id integer,
                               expiry_date datetime(6) not null,
                               id bigint not null,
                               token varchar(255) not null,
                               primary key (id)
) engine=InnoDB;


create table refresh_token_seq (
                                   next_val bigint
) engine=InnoDB;


insert into refresh_token_seq values ( 1 );

create table roles (
                       id integer not null auto_increment,
                       name enum ('ROLE_USER','ROLE_CREATOR','ROLE_ADMIN'),
                       primary key (id)
) engine=InnoDB;



create table user_roles (
                            role_id integer not null,
                            business_id integer not null,
                            primary key (role_id, business_id)
) engine=InnoDB;


alter table refresh_token
    add constraint UK_1 unique (business_id);
alter table refresh_token
    add constraint UK_2 unique (token);




alter table business_data
    add constraint UK_3 unique (email);

alter table business_data
    add constraint UK_4 unique (mobile);


alter table refresh_token
    add constraint FK_1
        foreign key (business_id)
            references business_data (business_id);


alter table user_roles
    add constraint FK_2
        foreign key (role_id)
            references roles (id);


alter table user_roles
    add constraint FK_3
        foreign key (business_id)
            references business_data (business_id);