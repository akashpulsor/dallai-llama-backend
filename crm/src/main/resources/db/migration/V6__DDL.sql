CREATE TABLE agent_data
(
    agent_id    INT AUTO_INCREMENT NOT NULL,
    business_id INT                NULL,
    agent_name  VARCHAR(255)       NULL,
    persona     VARCHAR(255)       NULL,
    `role`      VARCHAR(255)       NULL,
    voice       VARCHAR(255)       NULL,
    active      BIT(1)             NULL,
    created_at  datetime           NULL,
    updated_at  datetime           NULL,
    CONSTRAINT pk_agent_data PRIMARY KEY (agent_id)
);