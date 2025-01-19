CREATE TABLE payment_data (
    payment_id INT PRIMARY KEY AUTO_INCREMENT,
    call_id INT NOT NULL,
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    end_time DATETIME DEFAULT NULL,
    input_token INT DEFAULT 0,
    output_token INT DEFAULT 0,
    total_token INT DEFAULT 0,
    call_time INT DEFAULT 0,
    input_text_token INT DEFAULT 0,
    input_audio_token INT DEFAULT 0,
    input_cached_token INT DEFAULT 0,
    input_cached_text_token INT DEFAULT 0,
    input_cached_audio_token INT DEFAULT 0,
    output_text_token INT DEFAULT 0,
    output_audio_token INT DEFAULT 0,
    INDEX idx_call_id (call_id),
    INDEX idx_start_time (start_time)
);






