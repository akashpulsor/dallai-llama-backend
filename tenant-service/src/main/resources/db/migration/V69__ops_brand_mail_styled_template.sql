-- A styled copy of the starter brand template (picture cards and a button), to compare with the plain letter.
INSERT INTO ops_creator_mail_template (id, name, subject, body, cta_label, cta_url, idea_videos, style, mail_for) VALUES
('00000000-0000-4000-8000-0000000000b2', 'Brand intro: video ideas (styled)',
 '{name}, a few video ideas for {company}',
 'Hi {name},

I''m Akash from Dalai Llama. We connect brands with creators who make short product videos, ads and reels, and we keep a board of videos brands in your space have liked lately:',
 'Get a video made for {company}', 'https://dalaillama.in', 3, 'STYLED', 'BRANDS');
