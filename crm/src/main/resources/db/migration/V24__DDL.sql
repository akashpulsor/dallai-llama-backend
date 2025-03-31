CREATE TABLE session (
    id VARCHAR(255) PRIMARY KEY,
    user_id INT NOT NULL,
    session_type VARCHAR(255) NOT NULL,
    created_at DATETIME,
    updated_at DATETIME,
    start_time DATETIME,
    end_time DATETIME,
    status VARCHAR(255) NOT NULL,
    expiry_time DATETIME
);

CREATE TABLE browser_session (
    session_id VARCHAR(255) PRIMARY KEY,
    browser_run_id VARCHAR(255),
    portal_id INT NOT NULL,
    current_url VARCHAR(1024),
    status VARCHAR(255) NOT NULL,
    last_action_timestamp DATETIME,
    campaign_id INT,
    --  No explicit foreign key constraint needed here because of @MapsId
    --  session_id is both PK and FK to Session
    FOREIGN KEY (session_id) REFERENCES Session(id)
);
