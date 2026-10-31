-- Maker names stored HTML-encoded ("dreamy rafeia &#127872;") showed the code in mail. Clearing their
-- video lets InspirationMediaJob fetch them again, now decoding the name (InspirationPosters.maker).
UPDATE inspiration_item SET video_url = NULL WHERE maker_name LIKE '%&#%' OR maker_name LIKE '%&amp;%';
