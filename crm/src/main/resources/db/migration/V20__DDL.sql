CREATE TABLE conversation_data (
    conversation_id INT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    call_id INT NOT NULL,
    conversation_data TEXT,
    INDEX idx_call_id (call_id)
);