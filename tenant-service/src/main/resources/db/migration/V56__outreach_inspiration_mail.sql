-- A creator with no film of their own yet can still write to brands: the mail carries inspiration
-- videos for the brand's industry (credited to whoever made them on Pinterest) and asks the brand
-- to send the creator a request.

-- The pin's picture and maker, for mail (an embed can't play in an inbox). Filled from Pinterest
-- when the pin is saved, or the first time a mail needs it.
ALTER TABLE inspiration_item ADD COLUMN poster_url VARCHAR(500);
ALTER TABLE inspiration_item ADD COLUMN poster_width INT;
ALTER TABLE inspiration_item ADD COLUMN poster_height INT;
ALTER TABLE inspiration_item ADD COLUMN maker_name VARCHAR(120);

ALTER TABLE lead_outreach_intent DROP CONSTRAINT lead_outreach_intent_template_ck;
ALTER TABLE lead_outreach_intent ADD CONSTRAINT lead_outreach_intent_template_ck CHECK (template IN
    ('SHOWCASE_WORK', 'SIMILAR_BRAND_WORK', 'CREATOR_PORTFOLIO', 'NEW_FILM', 'INSPIRATION'));

ALTER TABLE lead_email_template DROP CONSTRAINT lead_email_template_layout_ck;
ALTER TABLE lead_email_template ADD CONSTRAINT lead_email_template_layout_ck CHECK (layout IN
    ('SHOWCASE_WORK', 'SIMILAR_BRAND_WORK', 'CREATOR_PORTFOLIO', 'INSPIRATION'));

INSERT INTO lead_email_template (id, tenant_id, name, layout, subject, intro) VALUES
    ('00000000-0000-4000-8000-0000000000a4', NULL, 'Video ideas for your brand', 'INSPIRATION',
     'Video ideas for your brand',
     'I came across a few videos {industry} brands are running and thought you might want one like them. I make videos like these for brands on Dalai Llama.');
