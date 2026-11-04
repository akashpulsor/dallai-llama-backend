-- The mail as it went (subject and page), so ops can read exactly what a recipient got, however the
-- template changes later. Without the open pixel, so reading it isn't counted as the recipient opening
-- it. Null for mail sent before this.
ALTER TABLE ops_creator_mail_delivery ADD COLUMN sent_subject TEXT;
ALTER TABLE ops_creator_mail_delivery ADD COLUMN sent_html TEXT;
