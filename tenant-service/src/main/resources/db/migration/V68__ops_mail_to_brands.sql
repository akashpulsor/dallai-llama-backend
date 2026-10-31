-- Ops mail reaches brands too: a template is for creators or for brands, and a brand mail is queued
-- with the brand's name and industry ({company}, and inspiration videos from its own industry).
ALTER TABLE ops_creator_mail_template ADD COLUMN mail_for VARCHAR(10) NOT NULL DEFAULT 'CREATORS';
ALTER TABLE ops_creator_mail_template ADD CONSTRAINT ops_creator_mail_template_mail_for_ck CHECK (mail_for IN ('CREATORS', 'BRANDS'));

ALTER TABLE ops_creator_mail_delivery ADD COLUMN company VARCHAR(200);
ALTER TABLE ops_creator_mail_delivery ADD COLUMN industry VARCHAR(32);

-- A starter brand template (plain letter; {company} is the brand's name).
INSERT INTO ops_creator_mail_template (id, name, subject, body, cta_label, cta_url, idea_videos, style, mail_for) VALUES
('00000000-0000-4000-8000-0000000000b1', 'Brand intro: video ideas',
 '{name}, a few video ideas for {company}',
 'Hi {name},

I''m Akash from Dalai Llama. We connect brands with creators who make short product videos, ads and reels, and we keep a board of videos brands in your space have liked lately:',
 'Get a video made for {company}', 'https://dalaillama.in', 3, 'PLAIN', 'BRANDS');
