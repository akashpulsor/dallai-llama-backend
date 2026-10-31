-- Which industry a creator mail's inspiration videos come from; NULL picks a random industry for
-- every mail (the default, so the starter templates now vary instead of always showing one industry).
ALTER TABLE ops_creator_mail_template ADD COLUMN idea_industry VARCHAR(32);
