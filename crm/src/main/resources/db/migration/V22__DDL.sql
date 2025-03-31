-- Portal Configuration Table
CREATE TABLE portal_configuration (
    portal_id INT AUTO_INCREMENT PRIMARY KEY,
    portal_name VARCHAR(255) NOT NULL UNIQUE,
    portal_description TEXT NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    user_name VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    business_id INT NOT NULL
);

-- Intent Table
CREATE TABLE intent (
    intent_id INT AUTO_INCREMENT PRIMARY KEY,
    intent_name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    sequence_number INT NOT NULL,
    root_intent_id INT,
    portal_id INT NOT NULL,
    FOREIGN KEY (root_intent_id) REFERENCES intent(intent_id),
    FOREIGN KEY (portal_id) REFERENCES portal_configuration(portal_id)
);