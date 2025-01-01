CREATE TABLE call_status
(
    status_id   INT AUTO_INCREMENT NOT NULL,
    call_log_id INT                NOT NULL,
    status      VARCHAR(255)       NOT NULL,
    timestamp   datetime           NOT NULL,
    CONSTRAINT pk_call_status PRIMARY KEY (status_id)
);

ALTER TABLE call_status
    ADD CONSTRAINT FK_CALL_STATUS_ON_CALL_LOG FOREIGN KEY (call_log_id) REFERENCES call_log (call_log_id);