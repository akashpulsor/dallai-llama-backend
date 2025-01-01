CREATE TABLE call_log
(
    call_log_id     INT AUTO_INCREMENT NOT NULL,
    call_type       VARCHAR(255)       NULL,
    campaign_run_id INT                NULL,
    lead_id         INT                NULL,
    call_sid        VARCHAR(255)       NULL,
    from_number     VARCHAR(255)       NULL,
    to_number       VARCHAR(255)       NULL,
    start_time      datetime           NULL,
    end_time        datetime           NULL,
    CONSTRAINT pk_call_log PRIMARY KEY (call_log_id)
);