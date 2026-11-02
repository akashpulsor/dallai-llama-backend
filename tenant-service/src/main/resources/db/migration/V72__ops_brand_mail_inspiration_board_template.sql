-- A styled brand template in Akash's words: who we are, and the inspiration board (3 videos that fit
-- the brand, as picture cards, with a button).
INSERT INTO ops_creator_mail_template (id, name, subject, body, cta_label, cta_url, idea_videos, style, mail_for) VALUES
('00000000-0000-4000-8000-0000000000b3', 'Brand intro: inspiration board (styled)',
 '{name}, fresh video ideas for {company}',
 'Hi {name},

I''m Akash from Dalai Llama. We connect brands with creative talent to produce engaging product videos, ads, and reels.

We also curate an inspiration board featuring trending content and creative campaigns from brands in your industry, helping you discover fresh ideas and understand what''s capturing attention. Here are a few picked for {company}:',
 'Get a video made for {company}', 'https://dalaillama.in', 3, 'STYLED', 'BRANDS');
