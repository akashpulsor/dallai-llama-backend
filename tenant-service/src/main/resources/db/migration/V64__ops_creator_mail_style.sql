-- Ops choose per template how its mail looks: PLAIN (a personal letter from a person, best for the
-- main inbox) or STYLED (the designed platform mail with picture cards and a button).
ALTER TABLE ops_creator_mail_template ADD COLUMN style VARCHAR(10) NOT NULL DEFAULT 'PLAIN';
ALTER TABLE ops_creator_mail_template ADD CONSTRAINT ops_creator_mail_template_style_ck CHECK (style IN ('PLAIN', 'STYLED'));

-- A styled copy of "New video ideas", to test the two looks side by side.
INSERT INTO ops_creator_mail_template (id, name, subject, body, cta_label, cta_url, idea_videos, style) VALUES
('00000000-0000-4000-8000-0000000000c4', 'New video ideas (styled)',
 '{name}, a few video ideas for you',
 'Hi {name},

A few videos brands have liked lately. Each one is an idea you could make for a brand of your own, and put on your profile so brands looking for exactly that can find you:',
 'Make your version on Dalai Llama', 'https://creator.dalaillama.in', 3, 'STYLED');
