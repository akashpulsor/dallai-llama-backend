-- The three built-in creator templates (V34) read as a creator pitching their own work to a brand.
-- Only the global rows; templates creators saved for themselves are theirs.
UPDATE lead_email_template SET subject = 'A video I made recently: {film}',
    intro = 'I make videos for brands on Dalai Llama, and wanted to share one I made recently. I think something like it could work well for you.'
WHERE id = '00000000-0000-4000-8000-0000000000a1' AND tenant_id IS NULL;
UPDATE lead_email_template SET subject = 'A video I made for a brand like yours',
    intro = 'I recently made this video for a brand in {industry}, and thought a film like it could work for you too.'
WHERE id = '00000000-0000-4000-8000-0000000000a2' AND tenant_id IS NULL;
UPDATE lead_email_template SET subject = 'A few videos I''ve made for brands · {creator}',
    intro = 'Here are a few videos I''ve made for brands recently, each delivered to a paying client.'
WHERE id = '00000000-0000-4000-8000-0000000000a3' AND tenant_id IS NULL;
