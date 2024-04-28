 create table llm (
        llm_id integer not null auto_increment,
        active bit,
        creator_id integer,
        create_date datetime(6),
        update_date datetime(6),
        label varchar(255),
        llm_token varchar(255),
        value varchar(255),
        primary key (llm_id)
    ) engine=InnoDB;


 create table article (
         article_id int not null auto_increment,
         generation_id integer,
         is_published bit,
         structure_id integer,
         user_id integer,
         body varchar(255),
         publish_id varchar(255),
         title varchar(255),
         search_tags varbinary(255),
         primary key (article_id)
     ) engine=InnoDB;


 create table publish_destination (
     destination_id int NOT NULL AUTO_INCREMENT,
     destination_name varchar(255),
     logo_url varchar(255),
     password varchar(255),
     user_name varchar(255),
     primary key (destination_id)
 ) engine=InnoDB;



create table refresh_token (
    user_id integer,
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


create table search_data (
    llm_id_date integer,
    create_date datetime(6),
    modified_date datetime(6),
    search_id varchar(255) not null,
    search_query varchar(255),
    user_id varchar(255),
    primary key (search_id)
) engine=InnoDB;


create table structure_data (
    structure_id integer not null auto_increment,
    user_id integer,
    structure varchar(255),
    primary key (structure_id)
) engine=InnoDB;


create table tools (
    active bit,
    creator_id integer,
    tool_id integer not null auto_increment,
    create_date datetime(6),
    update_date datetime(6),
    creator_name varchar(255),
    name varchar(255),
    title varchar(255),
    primary key (tool_id)
) engine=InnoDB;


create table user (
    account_non_expired bit,
    account_non_locked bit,
    credentials_non_expired bit,
    id integer not null auto_increment,
    follower_count bigint,
    bio varchar(255),
    company_name varchar(255),
    email varchar(255),
    github_url varchar(255),
    linked_in_url varchar(255),
    password varchar(255),
    phone_number varchar(255),
    profile_image varchar(255),
    user_name varchar(255),
    gender enum ('MALE','FEMALE','OTHER'),
    primary key (id)
) engine=InnoDB;


create table user_roles (
    role_id integer not null,
    user_id integer not null,
    primary key (role_id, user_id)
) engine=InnoDB;


alter table refresh_token
   add constraint UK_f95ixxe7pa48ryn1awmh2evt7 unique (user_id);
alter table refresh_token
  add constraint UK_r4k4edos30bx9neoq81mdvwph unique (token);


alter table user
 add constraint UKlqjrcobrh9jc8wpcar64q1bfh unique (user_name);


alter table user
add constraint UKob8kqyqqgmefl0aco34akdtpe unique (email);

alter table user
   add constraint UK_4bgmpi98dylab6qdvf9xyaxu4 unique (phone_number);


alter table refresh_token
  add constraint FKfgk1klcib7i15utalmcqo7krt
  foreign key (user_id)
  references user (id);


alter table user_roles
 add constraint FKh8ciramu9cc9q3qcqiv4ue8a6
 foreign key (role_id)
 references roles (id);


alter table user_roles
add constraint FK55itppkw3i07do3h7qoclqd4k
foreign key (user_id)
references user (id);